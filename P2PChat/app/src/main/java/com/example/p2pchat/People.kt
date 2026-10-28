package com.example.p2pchat

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Everyone you've met, by their permanent unique ID (the same across every carriage).
 *
 * - Aliases are permanent: the first alias seen for an ID is the one shown from then on, so nobody can
 *   pass themselves off as someone else by changing the name their app sends.
 * - Nicknames are private: a name YOU give someone. Only your phone ever sees it.
 */
class PeopleBook(private val prefs: SharedPreferences) {
    val nicknames = MutableStateFlow(load(KEY_NICK))
    val aliases = MutableStateFlow(load(KEY_ALIAS))

    private fun load(key: String): Map<String, String> = try {
        val o = JSONObject(prefs.getString(key, "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }

    private fun save(key: String, map: Map<String, String>) {
        prefs.edit().putString(key, JSONObject(map).toString()).apply()
    }

    /** Record a verified alias. Only the first one ever seen for this ID sticks. */
    fun seen(id: String, alias: String) {
        val a = alias.trim().take(24)
        if (a.isEmpty() || aliases.value.containsKey(id)) return
        if (aliases.value.size >= MAX_PEOPLE) return
        aliases.update { it + (id to a) }
        save(KEY_ALIAS, aliases.value)
    }

    fun alias(id: String, fallback: String): String = aliases.value[id] ?: fallback

    fun nickname(id: String): String? = nicknames.value[id]?.takeIf { it.isNotBlank() }

    /** What to show for a person: your nickname if you set one, otherwise their permanent alias. */
    fun label(id: String, fallback: String): String = nickname(id) ?: alias(id, fallback)

    fun setNickname(id: String, nick: String) {
        val n = nick.trim().take(24)
        nicknames.update { if (n.isEmpty()) it - id else it + (id to n) }
        save(KEY_NICK, nicknames.value)
    }

    companion object {
        private const val KEY_NICK = "people_nick"
        private const val KEY_ALIAS = "people_alias"
        private const val MAX_PEOPLE = 5000
    }
}
