package com.example.p2pchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's one long-lived brain. Holds your profile, your settings, and every carriage you are aboard.
 * (Each carriage is a CarriageSession with its own connections and keys.)
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Identity.securePrefs(app)
    private val store = HistoryStore(app)
    private val vault = PhotoVault(app)
    /** Nicknames you give people, and everyone's permanent alias. */
    val people = PeopleBook(prefs)
    val myId: String = Identity.deviceId()
    private val myPub: ByteArray = Identity.publicKeyBytes()
    private val ecdh = Identity.ecdhKeys(prefs)

    val settings = MutableStateFlow(SettingsStore.load(prefs))
    val profileName = MutableStateFlow<String?>(prefs.getString("profile_name", null))
    val carriages = MutableStateFlow<List<CarriageSession>>(emptyList())
    val active = MutableStateFlow<CarriageSession?>(null)
    val joining = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)
    /** (number of carriages, total other people connected) for the notification */
    val status = MutableStateFlow(0 to 0)

    init {
        migrateOldSave()
        restore()
    }

    // ---------- Profile ----------

    /** Your alias is chosen once, at first launch, and is permanent for this install. */
    fun setProfileName(name: String) {
        if (profileName.value != null) return
        val n = name.trim().take(24)
        if (n.isEmpty()) return
        prefs.edit().putString("profile_name", n).apply()
        profileName.value = n
    }

    // ---------- Carriages ----------

    private fun makeSession(r: RoomInfo) = CarriageSession(
        app = getApplication(), scope = viewModelScope, prefs = prefs, store = store,
        vault = vault, people = people, myId = myId, myPub = myPub, ecdh = ecdh, settings = settings, initial = r,
        onChanged = { persist(); refreshStatus() },
        onNotice = { notice.value = it },
        onLeft = { removeSession(it) },
        viewing = { t -> active.value?.tag == t }
    )

    fun join(code: String, password: String, created: Boolean) {
        val name = profileName.value ?: return
        if (joining.value) return
        val existing = carriages.value.firstOrNull { it.tag == Crypto.roomTag(code) }
        if (existing != null) {
            select(existing)
            notice.value = "You're already aboard that carriage."
            return
        }
        viewModelScope.launch {
            joining.value = true
            val s = makeSession(RoomInfo(code, password, name, created))
            carriages.value = carriages.value + s      // show it right away
            select(s)
            persist(); refreshStatus()
            if (settings.value.keepAlive) SessionService.start(getApplication())
            runCatching { s.start() }
            joining.value = false
        }
    }

    fun select(s: CarriageSession?) {
        active.value = s
        if (s != null) {
            s.unread.value = 0
            prefs.edit().putString("active_tag", s.tag).apply()
        }
    }

    fun markActiveRead() { active.value?.unread?.value = 0 }

    private fun removeSession(s: CarriageSession) {
        val rest = carriages.value - s
        carriages.value = rest
        if (active.value === s) select(rest.lastOrNull())
        persist(); refreshStatus()
        if (rest.isEmpty()) SessionService.stop(getApplication())
    }

    private fun entry(r: RoomInfo) = JSONObject().put("code", r.code).put("pw", r.password)
        .put("name", r.name).put("created", r.created)

    private fun persist() {
        val arr = JSONArray()
        carriages.value.forEach { arr.put(entry(it.room.value)) }
        prefs.edit().putString("carriages", arr.toString()).apply()
    }

    private fun refreshStatus() {
        val list = carriages.value
        status.value = list.size to list.sumOf { it.peers.value }
    }

    /** Older versions saved a single room; carry it over as the first carriage. */
    private fun migrateOldSave() {
        if (prefs.getString("carriages", null) != null) return
        val code = prefs.getString("room_code", null)
        val pw = prefs.getString("room_pw", null)
        val name = prefs.getString("name", null)
        if (profileName.value == null && name != null) {
            prefs.edit().putString("profile_name", name).apply()
            profileName.value = name
        }
        if (code != null && pw != null && name != null) {
            val arr = JSONArray().put(entry(RoomInfo(code, pw, name, prefs.getBoolean("room_created", false))))
            prefs.edit().putString("carriages", arr.toString())
                .remove("room_code").remove("room_pw").remove("room_created").apply()
        }
    }

    /** You stay aboard every carriage until you leave it: restore them all on launch. */
    private fun restore() {
        val arr = runCatching { JSONArray(prefs.getString("carriages", "[]")) }.getOrDefault(JSONArray())
        val list = (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                RoomInfo(o.getString("code"), o.getString("pw"), o.getString("name"), o.optBoolean("created", false))
            }.getOrNull()
        }
        if (list.isEmpty()) return
        val sessions = list.map { makeSession(it) }
        carriages.value = sessions
        val lastTag = prefs.getString("active_tag", null)
        active.value = sessions.firstOrNull { it.tag == lastTag } ?: sessions.first()
        refreshStatus()
        sessions.forEach { s -> viewModelScope.launch { runCatching { s.start() } } }
        if (settings.value.keepAlive) SessionService.start(getApplication())
    }

    /** Should the background service be running? */
    fun wantsSession(): Boolean =
        carriages.value.isNotEmpty() || (prefs.getString("carriages", "[]") ?: "[]").length > 2

    // ---------- Settings ----------

    fun updateSettings(s: AppSettings) {
        val keepChanged = s.keepAlive != settings.value.keepAlive
        settings.value = s
        SettingsStore.save(prefs, s)
        if (keepChanged && carriages.value.isNotEmpty()) {
            if (s.keepAlive) SessionService.start(getApplication()) else SessionService.stop(getApplication())
        }
        if (!s.keepHistory) viewModelScope.launch(Dispatchers.IO) { store.deleteAll() }
    }

    fun eraseAllHistory() { viewModelScope.launch(Dispatchers.IO) { store.deleteAll(); vault.deleteAll() } }

    fun clearNotice() { notice.value = null }

    override fun onCleared() {
        carriages.value.forEach { runCatching { it.close() } }
    }
}
