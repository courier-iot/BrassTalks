package com.example.p2pchat

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.abs

data class Msg(
    val id: String, val senderId: String, val senderName: String,
    val text: String, val ts: Long, val system: Boolean = false
)

/** created = true when this phone built the room (the creator is the room's host). */
data class RoomInfo(val code: String, val password: String, val name: String, val created: Boolean = false)

data class Member(val id: String, val name: String, val isHost: Boolean, val isMe: Boolean)

data class VoteInfo(val target: String, val yes: Set<String>, val no: Set<String>, val startedAt: Long)

object RoomCodes {
    private val words = listOf("COG", "GEAR", "PISTON", "VALVE", "BOILER", "STEAM", "BRASS", "RIVET", "SPROCKET", "ZEPPELIN")
    private val rng = SecureRandom()
    fun generate(): String = "${words[rng.nextInt(words.size)]}-${1000 + rng.nextInt(9000)}"
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Identity.securePrefs(app)
    private val store = HistoryStore(app)
    val myId: String = Identity.deviceId()
    private val myPub: ByteArray = Identity.publicKeyBytes()

    val room = MutableStateFlow<RoomInfo?>(null)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val peers = MutableStateFlow(0)
    val joining = MutableStateFlow(false)
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val known = MutableStateFlow<Map<String, String>>(emptyMap())   // id -> display name
    val hostId = MutableStateFlow<String?>(null)
    val votes = MutableStateFlow<Map<String, VoteInfo>>(emptyMap())
    val notice = MutableStateFlow<String?>(null)
    val settings = MutableStateFlow(SettingsStore.load(prefs))

    private var transport: Transport? = null
    private var tag = ""
    private val seen = HashSet<String>()
    private val banned = HashSet<String>()
    private var saveJob: Job? = null
    private var bgJob: Job? = null

    init {
        // You stay in the room until you tap Leave: restore the saved session on launch.
        val code = prefs.getString("room_code", null)
        val pw = prefs.getString("room_pw", null)
        val name = prefs.getString("name", null)
        if (code != null && pw != null && name != null) {
            join(RoomInfo(code, pw, name, prefs.getBoolean("room_created", false)))
        }
    }

    // ---------- Joining / leaving ----------

    fun join(r: RoomInfo) {
        if (joining.value || room.value != null) return
        viewModelScope.launch {
            joining.value = true
            val key = withContext(Dispatchers.Default) { Crypto.deriveKey(r.password, r.code) }
            tag = Crypto.roomTag(r.code)

            synchronized(banned) {
                banned.clear()
                banned.addAll(prefs.getStringSet("bans_$tag", emptySet()) ?: emptySet())
            }
            hostId.value = if (r.created) myId else prefs.getString("host_$tag", null)
            if (r.created) prefs.edit().putString("host_$tag", myId).apply()

            val s = settings.value
            val history = if (s.keepHistory) withContext(Dispatchers.IO) { store.load(tag, s.retentionDays) } else emptyList()
            synchronized(seen) { seen.clear(); history.forEach { seen.add(it.id) } }
            messages.value = history

            prefs.edit().putString("room_code", r.code).putString("room_pw", r.password)
                .putString("name", r.name).putBoolean("room_created", r.created).apply()
            room.value = r

            val t = LanTransport(getApplication())
            synchronized(banned) { banned.forEach { t.ban(it) } }
            transport = t
            t.start(myId, tag, key, ::onFrame, ::onPeers)

            bgJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) { delay(30_000); announce(); purgeVotes() }
            }
            joining.value = false
        }
    }

    fun leave(eraseHistory: Boolean = false) {
        if (room.value == null) return
        val t = tag
        val snapshot = messages.value
        val s = settings.value
        bgJob?.cancel(); saveJob?.cancel()
        transport?.stop(); transport = null
        prefs.edit().remove("room_code").remove("room_pw").remove("room_created").apply()
        room.value = null; messages.value = emptyList(); peers.value = 0
        connected.value = emptySet(); known.value = emptyMap(); votes.value = emptyMap(); hostId.value = null
        synchronized(seen) { seen.clear() }
        viewModelScope.launch(Dispatchers.IO) {
            if (eraseHistory || !s.keepHistory) store.delete(t) else store.save(t, snapshot, s.retentionDays)
        }
    }

    // ---------- Sending ----------

    fun send(text: String) {
        val r = room.value ?: return
        val t = text.trim().ifEmpty { return }
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        add(Msg(id, myId, r.name, t, ts))
        viewModelScope.launch(Dispatchers.Default) {
            val sig = Identity.sign("msg|$tag|$id|$myId|$ts|$t".toByteArray())
            transport?.send(JSONObject().put("type", "msg").put("id", id).put("sid", myId)
                .put("n", r.name).put("text", t).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun announce() {
        val r = room.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val host = hostId.value == myId
            val sig = Identity.sign("hi|$tag|$myId|${r.name}|$host|$ts".toByteArray())
            transport?.send(JSONObject().put("type", "hi").put("sid", myId).put("n", r.name)
                .put("h", host).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
                .toString().toByteArray())
        }
    }

    // ---------- Receiving ----------

    private fun onPeers(ids: Set<String>) {
        val before = connected.value
        connected.value = ids
        peers.value = ids.size
        if (ids.any { it !in before }) announce()
    }

    private fun onFrame(bytes: ByteArray) {
        try {
            val j = JSONObject(bytes.toString(Charsets.UTF_8))
            when (j.getString("type")) {
                "full" -> if (connected.value.isEmpty()) {
                    notice.value = "That room is full (${Limits.MAX_ROOM} members max)."
                    leave(false)
                }
                "msg" -> handleMsg(j)
                "hi" -> handleHi(j)
                "kick" -> handleKick(j)
                "kv" -> handleVote(j)
            }
        } catch (_: Exception) {}
    }

    private fun verified(j: JSONObject, data: String): Boolean = try {
        val pk = Base64.decode(j.getString("pk"), Base64.NO_WRAP)
        val sig = Base64.decode(j.getString("sig"), Base64.NO_WRAP)
        j.getString("sid") == Identity.idFromPublicKey(pk) && Identity.verify(pk, data.toByteArray(), sig)
    } catch (e: Exception) { false }

    private fun isBanned(id: String) = synchronized(banned) { banned.contains(id) }

    private fun handleMsg(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val id = j.getString("id"); val n = j.getString("n")
        val text = j.getString("text"); val ts = j.getLong("ts")
        if (text.length > 4000) return
        if (!verified(j, "msg|$tag|$id|$sid|$ts|$text")) return
        known.update { it + (sid to n) }
        add(Msg(id, sid, n, text, ts))
    }

    private fun handleHi(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val n = j.getString("n"); val h = j.getBoolean("h"); val ts = j.getLong("ts")
        if (!verified(j, "hi|$tag|$sid|$n|$h|$ts")) return
        known.update { it + (sid to n) }
        // Host claim: trust the first one we see (see TODO: verify via invite).
        if (h && hostId.value == null && sid != myId) {
            hostId.value = sid
            prefs.edit().putString("host_$tag", sid).apply()
        }
    }

    // ---------- Kick: host (instant) and vote ----------

    fun hostKick(target: String) {
        if (hostId.value != myId || target == myId) return
        viewModelScope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kick|$tag|$target|$ts".toByteArray())
            transport?.send(JSONObject().put("type", "kick").put("sid", myId).put("target", target)
                .put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
            delay(400) // let the notice reach them before we cut the link
            applyBan(target)
        }
    }

    private fun handleKick(j: JSONObject) {
        val sid = j.getString("sid"); val target = j.getString("target"); val ts = j.getLong("ts")
        if (sid != hostId.value || target == sid) return
        if (!verified(j, "kick|$tag|$target|$ts")) return
        applyBan(target)
    }

    /** Vote yes/no on removing a member. The first yes starts the vote. */
    fun castVote(target: String, yes: Boolean) {
        if (target == myId || target == hostId.value) return
        viewModelScope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kv|$tag|$target|$yes|$ts".toByteArray())
            transport?.send(JSONObject().put("type", "kv").put("sid", myId).put("target", target)
                .put("yes", yes).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
                .toString().toByteArray())
            recordVote(myId, target, yes)
        }
    }

    private fun handleVote(j: JSONObject) {
        val voter = j.getString("sid"); val target = j.getString("target")
        val yes = j.getBoolean("yes"); val ts = j.getLong("ts")
        if (isBanned(voter) || voter == target || target == hostId.value) return
        if (abs(System.currentTimeMillis() - ts) > VOTE_FRESH_MS) return
        if (voter !in connected.value) return
        if (!verified(j, "kv|$tag|$target|$yes|$ts")) return
        recordVote(voter, target, yes)
    }

    private fun recordVote(voter: String, target: String, yes: Boolean) {
        votes.update { map ->
            val v = map[target] ?: VoteInfo(target, emptySet(), emptySet(), System.currentTimeMillis())
            val nv = if (yes) v.copy(yes = v.yes + voter, no = v.no - voter)
                     else v.copy(no = v.no + voter, yes = v.yes - voter)
            map + (target to nv)
        }
        evaluate(target)
    }

    /** Passes when at least 2 members vote yes AND yes is a strict majority of everyone except the target. */
    private fun evaluate(target: String) {
        val v = votes.value[target] ?: return
        val eligible = (connected.value + myId) - target
        val yes = v.yes.count { it in eligible }
        if (yes >= 2 && yes * 2 > eligible.size) applyBan(target)
    }

    private fun purgeVotes() {
        val cutoff = System.currentTimeMillis() - VOTE_EXPIRY_MS
        votes.update { m -> m.filterValues { it.startedAt >= cutoff } }
    }

    private fun applyBan(target: String) {
        if (target == myId) {
            notice.value = "You were removed from the room."
            leave(false)
            return
        }
        val added = synchronized(banned) { banned.add(target) }
        if (!added) return
        prefs.edit().putStringSet("bans_$tag", synchronized(banned) { HashSet(banned) }).apply()
        transport?.ban(target)
        votes.update { it - target }
        val name = known.value[target] ?: target.take(6)
        add(Msg("sys-ban-$target", "system", "", "$name was removed from the room.",
            System.currentTimeMillis(), system = true))
    }

    // ---------- History / settings ----------

    private fun add(m: Msg) {
        synchronized(seen) { if (!seen.add(m.id)) return }
        messages.update { (it + m).sortedBy { x -> x.ts } }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!settings.value.keepHistory) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1500)
            store.save(tag, messages.value, settings.value.retentionDays)
        }
    }

    fun updateSettings(s: AppSettings) {
        settings.value = s
        SettingsStore.save(prefs, s)
        if (!s.keepHistory) viewModelScope.launch(Dispatchers.IO) { store.deleteAll() }
    }

    fun eraseAllHistory() { viewModelScope.launch(Dispatchers.IO) { store.deleteAll() } }

    fun clearNotice() { notice.value = null }

    override fun onCleared() {
        if (room.value != null && settings.value.keepHistory) {
            runCatching { store.save(tag, messages.value, settings.value.retentionDays) }
        }
        transport?.stop()
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        private const val VOTE_EXPIRY_MS = 5 * 60_000L
        private const val VOTE_FRESH_MS = 10 * 60_000L
    }
}
