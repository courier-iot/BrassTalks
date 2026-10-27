package com.example.p2pchat

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class Msg(
    val id: String, val senderId: String, val senderName: String,
    val text: String, val ts: Long, val system: Boolean = false,
    val image: String? = null, val imgW: Int = 0, val imgH: Int = 0
)

/** created = true when this phone built the carriage (the creator is its host). */
data class RoomInfo(val code: String, val password: String, val name: String, val created: Boolean = false)

data class Member(val id: String, val name: String, val isHost: Boolean, val isMe: Boolean)

data class VoteInfo(val target: String, val yes: Set<String>, val no: Set<String>, val startedAt: Long)

object RoomCodes {
    private val words = listOf(
        "COG", "GEAR", "PISTON", "VALVE", "BOILER", "STEAM", "BRASS", "RIVET", "SPROCKET", "ZEPPELIN",
        "AIRSHIP", "COPPER", "FURNACE", "GAUGE", "LEVER", "TURBINE", "CLOCKWORK", "ANVIL",
        "FLYWHEEL", "DIRIGIBLE", "PROPELLER", "WHISTLE", "CRANK", "PENDULUM", "TELEGRAPH", "LOCOMOTIVE"
    )
    private val rng = SecureRandom()

    /** A word plus 7 random digits, e.g. CLOCKWORK-0482917 */
    fun generate(): String =
        "${words[rng.nextInt(words.size)]}-${rng.nextInt(10_000_000).toString().padStart(7, '0')}"

    /** Random passphrase with 80 bits of entropy, e.g. K7QM-2XWD-9PLR-HT4B. Used after a removal. */
    fun newPassphrase(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (0 until 4).joinToString("-") {
            (0 until 4).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}

/**
 * One carriage (chat). Owns its own connections, keys, members, votes and history.
 * The phone can be aboard several carriages at once; ChatViewModel manages the list.
 */
class CarriageSession(
    private val app: Application,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val store: HistoryStore,
    private val vault: PhotoVault,
    val myId: String,
    private val myPub: ByteArray,
    private val ecdh: Pair<PrivateKey, ByteArray>,
    val settings: StateFlow<AppSettings>,
    initial: RoomInfo,
    private val onChanged: () -> Unit,              // password or member count changed: manager saves / refreshes
    private val onNotice: (String) -> Unit,
    private val onLeft: (CarriageSession) -> Unit,
    private val viewing: (String) -> Boolean        // is this carriage the one on screen?
) {
    val tag: String = Crypto.roomTag(initial.code)
    private val myEk: ByteArray = ecdh.second

    val room = MutableStateFlow(initial)
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val peers = MutableStateFlow(0)
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val known = MutableStateFlow<Map<String, String>>(emptyMap())   // id -> display name
    val hostId = MutableStateFlow<String?>(null)
    val votes = MutableStateFlow<Map<String, VoteInfo>>(emptyMap())
    val unread = MutableStateFlow(0)
    /** Your own private name for this carriage. Blank means "show the code". Others never see it. */
    val nickname = MutableStateFlow(prefs.getString("nick_$tag", "") ?: "")

    private var transport: Transport? = null
    private val seen = HashSet<String>()
    private val banned = HashSet<String>()
    private var saveJob: Job? = null
    private var bgJob: Job? = null
    @Volatile private var closed = false

    // ---- key rotation state ----
    private val eks = ConcurrentHashMap<String, ByteArray>()                           // member id -> key-exchange public key
    private val seenAt = ConcurrentHashMap<String, Long>()                             // member id -> first seen (ms)
    private val evidence = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()   // target -> signed kick/vote envelopes
    private val bundles = CopyOnWriteArrayList<JSONObject>()                           // key-change bundles we have applied
    private val excluded = ConcurrentHashMap.newKeySet<String>()                       // removed members the current key already locks out
    private val prevPasses = ArrayList<String>()                                       // recent older passphrases
    private val rekeyLock = Mutex()
    @Volatile private var curPass = ""
    @Volatile private var curKid = ""

    // ---------- Start / leave ----------

    suspend fun start() {
        val r = room.value
        val key = withContext(Dispatchers.Default) { Crypto.deriveKey(r.password, r.code) }
        curPass = r.password
        curKid = Crypto.kid(key)

        synchronized(banned) {
            banned.clear()
            banned.addAll(prefs.getStringSet("bans_$tag", emptySet()) ?: emptySet())
        }
        hostId.value = if (r.created) myId else prefs.getString("host_$tag", null)
        if (r.created) prefs.edit().putString("host_$tag", myId).apply()

        eks.clear(); seenAt.clear(); evidence.clear(); bundles.clear(); excluded.clear(); prevPasses.clear()
        excluded.addAll(prefs.getStringSet("excl_$tag", emptySet()) ?: emptySet())
        readJsonStrings(prefs.getString("bundles_$tag", null)).forEach { s ->
            runCatching { bundles.add(JSONObject(s)) }
        }
        prevPasses.addAll(readJsonStrings(prefs.getString("prev_$tag", null)))

        val s = settings.value
        val history = if (s.keepHistory) withContext(Dispatchers.IO) { store.load(tag, s.retentionDays) } else emptyList()
        synchronized(seen) { seen.clear(); history.forEach { seen.add(it.id) } }
        messages.value = history
        // Photos: honour the "keep photos" and history settings
        withContext(Dispatchers.IO) {
            if (!s.keepImages || !s.keepHistory) vault.delete(tag)
            else vault.prune(tag, history.mapNotNull { it.image }.toSet())
        }

        val t = LanTransport(app)
        synchronized(banned) { banned.forEach { t.ban(it) } }
        transport = t
        t.start(myId, tag, curKid, key, ::onFrame, ::onPeers, ::rescueFor)

        // Older keys let us help members who missed a key change (derived in the background)
        val old = prevPasses.toList()
        scope.launch(Dispatchers.Default) {
            old.forEach { p ->
                val k = Crypto.deriveKey(p, r.code)
                transport?.addLegacyKey(Crypto.kid(k), k)
            }
        }

        bgJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(30_000)
                announce()
                purgeVotes()
                val pending = synchronized(banned) {
                    banned.any { it !in excluded && evidence[it]?.isNotEmpty() == true }
                }
                if (pending) scheduleRekey()
            }
        }
    }

    fun leave(eraseHistory: Boolean = false) {
        if (closed) return
        closed = true
        val t = tag
        val snapshot = messages.value
        val s = settings.value
        bgJob?.cancel(); saveJob?.cancel()
        transport?.stop(); transport = null
        prefs.edit().remove("excl_$t").remove("bundles_$t").remove("prev_$t").remove("nick_$t").apply()
        scope.launch(Dispatchers.IO) {
            if (eraseHistory || !s.keepHistory) store.delete(t) else store.save(t, snapshot, s.retentionDays)
            if (eraseHistory || !s.keepHistory || !s.keepImages) vault.delete(t)
        }
        onLeft(this)
    }

    /** Stop without forgetting anything (app is going away). */
    fun close() {
        bgJob?.cancel(); saveJob?.cancel()
        if (settings.value.keepHistory) runCatching { store.save(tag, messages.value, settings.value.retentionDays) }
        transport?.stop(); transport = null
    }

    // ---------- Sending ----------

    fun send(text: String) {
        val r = room.value
        val t = text.trim().ifEmpty { return }
        val id = UUID.randomUUID().toString()
        val ts = System.currentTimeMillis()
        add(Msg(id, myId, r.name, t, ts))
        scope.launch(Dispatchers.Default) {
            val sig = Identity.sign("msg|$tag|$id|$myId|$ts|$t".toByteArray())
            transport?.send(JSONObject().put("type", "msg").put("id", id).put("sid", myId)
                .put("n", r.name).put("text", t).put("ts", ts)
                .put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
        }
    }

    private fun announce() {
        val r = room.value
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val host = hostId.value == myId
            val ek = b64(myEk)
            val sig = Identity.sign("hi|$tag|$myId|${r.name}|$host|$ts|$ek".toByteArray())
            transport?.send(JSONObject().put("type", "hi").put("sid", myId).put("n", r.name)
                .put("h", host).put("ts", ts).put("ek", ek).put("pk", b64(myPub)).put("sig", b64(sig))
                .toString().toByteArray())
        }
    }

    // ---------- Receiving ----------

    private fun onPeers(ids: Set<String>) {
        if (closed) return
        val before = connected.value
        val now = System.currentTimeMillis()
        ids.forEach { seenAt.putIfAbsent(it, now) }
        connected.value = ids
        peers.value = ids.size
        if (ids.any { it !in before }) announce()
        onChanged()
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed) return
        try {
            val j = JSONObject(bytes.toString(Charsets.UTF_8))
            when (j.getString("type")) {
                "full" -> if (connected.value.isEmpty()) {
                    onNotice("That carriage is full (${Limits.MAX_ROOM} members max).")
                    leave(false)
                }
                "msg" -> handleMsg(j)
                "img" -> handleImgHeader(j)
                "imgc" -> handleImgChunk(j)
                "hi" -> handleHi(j)
                "kick" -> handleKick(j)
                "kv" -> handleVote(j)
                "rekey" -> scope.launch(Dispatchers.Default) { acceptBundle(j) }
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
        if (sid != myId) {
            if (!(AppState.foreground && viewing(tag))) unread.update { it + 1 }
            if (!AppState.foreground) Notifier.message(app, displayName(), n, text)
        }
    }

    private fun handleHi(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid)) return
        val n = j.getString("n"); val h = j.getBoolean("h"); val ts = j.getLong("ts")
        val ek = j.optString("ek")
        if (!verified(j, "hi|$tag|$sid|$n|$h|$ts|$ek")) return
        known.update { it + (sid to n) }
        if (ek.isNotEmpty()) eks[sid] = Base64.decode(ek, Base64.NO_WRAP)
        // Host claim: trust the first one we see (see TODO: verify via invite).
        if (h && hostId.value == null && sid != myId) {
            hostId.value = sid
            prefs.edit().putString("host_$tag", sid).apply()
        }
    }

    // ---------- Naming ----------

    fun displayName(): String = nickname.value.ifBlank { room.value.code }

    fun rename(name: String) {
        val n = name.trim().take(30)
        if (n.isEmpty()) prefs.edit().remove("nick_$tag").apply()
        else prefs.edit().putString("nick_$tag", n).apply()
        nickname.value = n
        onChanged()
    }

    // ---------- Photos ----------
    //
    // A photo is shrunk (about 1280px, JPEG), then sent as one signed header (carries a fingerprint of the
    // whole picture) plus small encrypted chunks. Receivers rebuild it, check the fingerprint matches the
    // signed header, and only then show it. Chunks may arrive in any order.

    private class Assembly {
        @Volatile var header: JSONObject? = null
        val chunks = ConcurrentHashMap<Int, ByteArray>()
        val started = System.currentTimeMillis()
    }

    private val assemblies = ConcurrentHashMap<String, Assembly>()

    fun readImage(m: Msg): ByteArray? = m.image?.let { vault.read(tag, it) }

    fun sendPhoto(uri: Uri) {
        val r = room.value
        scope.launch(Dispatchers.IO) {
            val res = ImageUtil.compress(app, uri)
            if (res == null) {
                onNotice("Couldn't read that photo.")
                return@launch
            }
            val id = UUID.randomUUID().toString()
            val ts = System.currentTimeMillis()
            val hash = Crypto.sha256Hex(res.bytes)
            val chunks = (res.bytes.size + CHUNK - 1) / CHUNK
            vault.save(tag, id, res.bytes)
            add(Msg(id, myId, r.name, "", ts, image = id, imgW = res.w, imgH = res.h))

            val sig = Identity.sign("img|$tag|$id|$myId|$ts|${res.bytes.size}|$chunks|$hash".toByteArray())
            transport?.send(JSONObject().put("type", "img").put("id", id).put("sid", myId).put("n", r.name)
                .put("ts", ts).put("size", res.bytes.size).put("chunks", chunks).put("w", res.w).put("h", res.h)
                .put("hash", hash).put("pk", b64(myPub)).put("sig", b64(sig)).toString().toByteArray())
            for (i in 0 until chunks) {
                val part = res.bytes.copyOfRange(i * CHUNK, minOf((i + 1) * CHUNK, res.bytes.size))
                transport?.send(JSONObject().put("type", "imgc").put("id", id).put("sid", myId)
                    .put("i", i).put("d", b64(part)).toString().toByteArray())
                if (i % 4 == 3) delay(20)   // let chat messages and heartbeats slip in between
            }
        }
    }

    private fun assemblyFor(id: String): Assembly? {
        assemblies[id]?.let { return it }
        val cutoff = System.currentTimeMillis() - 120_000
        assemblies.entries.removeIf { it.value.started < cutoff }
        if (assemblies.size >= MAX_ASSEMBLIES) return null
        return assemblies.getOrPut(id) { Assembly() }
    }

    private fun handleImgHeader(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid) || sid == myId) return
        val id = j.getString("id"); val ts = j.getLong("ts")
        val size = j.getInt("size"); val chunks = j.getInt("chunks"); val hash = j.getString("hash")
        if (size !in 1..MAX_IMG || chunks !in 1..MAX_CHUNKS) return
        if (!verified(j, "img|$tag|$id|$sid|$ts|$size|$chunks|$hash")) return
        if (synchronized(seen) { seen.contains(id) }) return
        val a = assemblyFor(id) ?: return
        a.header = j
        tryComplete(id)
    }

    private fun handleImgChunk(j: JSONObject) {
        val sid = j.getString("sid")
        if (isBanned(sid) || sid == myId) return
        val id = j.getString("id"); val i = j.getInt("i"); val d = j.getString("d")
        if (i !in 0 until MAX_CHUNKS || d.length > 90_000) return
        val a = assemblyFor(id) ?: return
        a.chunks.putIfAbsent(i, Base64.decode(d, Base64.NO_WRAP))
        tryComplete(id)
    }

    private fun tryComplete(id: String) {
        val a = assemblies[id] ?: return
        val hd = a.header ?: return
        val total = hd.getInt("chunks")
        if (a.chunks.size < total) return
        if (!assemblies.remove(id, a)) return
        val out = ByteArrayOutputStream()
        for (i in 0 until total) out.write(a.chunks[i] ?: return)
        val data = out.toByteArray()
        if (data.size != hd.getInt("size") || Crypto.sha256Hex(data) != hd.getString("hash")) return

        val sid = hd.getString("sid"); val n = hd.getString("n"); val ts = hd.getLong("ts")
        val w = hd.optInt("w", 0); val ht = hd.optInt("h", 0)
        scope.launch(Dispatchers.IO) {
            vault.save(tag, id, data)
            known.update { it + (sid to n) }
            add(Msg(id, sid, n, "", ts, image = id, imgW = w, imgH = ht))
            if (!(AppState.foreground && viewing(tag))) unread.update { it + 1 }
            if (!AppState.foreground) Notifier.message(app, displayName(), n, "\uD83D\uDCF7 Photo")
        }
    }

    // ---------- Kick: host (instant) and vote ----------

    private fun keepEvidence(target: String, envelope: JSONObject) {
        evidence.getOrPut(target) { CopyOnWriteArrayList() }.add(envelope.toString())
    }

    fun hostKick(target: String) {
        if (hostId.value != myId || target == myId) return
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kick|$tag|$target|$ts".toByteArray())
            val env = JSONObject().put("type", "kick").put("sid", myId).put("target", target)
                .put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
            keepEvidence(target, env)
            transport?.send(env.toString().toByteArray())
            delay(400) // let the notice reach them before we cut the link
            applyBan(target)
        }
    }

    private fun handleKick(j: JSONObject) {
        val sid = j.getString("sid"); val target = j.getString("target"); val ts = j.getLong("ts")
        if (sid != hostId.value || target == sid) return
        if (!verified(j, "kick|$tag|$target|$ts")) return
        keepEvidence(target, j)
        applyBan(target)
    }

    /** Vote yes/no on removing a member. The first yes starts the vote. */
    fun castVote(target: String, yes: Boolean) {
        if (target == myId || target == hostId.value) return
        scope.launch(Dispatchers.Default) {
            val ts = System.currentTimeMillis()
            val sig = Identity.sign("kv|$tag|$target|$yes|$ts".toByteArray())
            val env = JSONObject().put("type", "kv").put("sid", myId).put("target", target)
                .put("yes", yes).put("ts", ts).put("pk", b64(myPub)).put("sig", b64(sig))
            if (yes) keepEvidence(target, env)
            transport?.send(env.toString().toByteArray())
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
        if (yes) keepEvidence(target, j)
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

    private fun applyBan(target: String, rekey: Boolean = true) {
        if (target == myId) {
            onNotice("You were removed from carriage ${room.value.code}.")
            leave(false)
            return
        }
        val added = synchronized(banned) { banned.add(target) }
        if (!added) return
        prefs.edit().putStringSet("bans_$tag", synchronized(banned) { HashSet(banned) }).apply()
        transport?.ban(target)
        votes.update { it - target }
        val name = known.value[target] ?: target.take(6)
        add(Msg("sys-ban-$target", "system", "", "$name was removed from the carriage.",
            System.currentTimeMillis(), system = true))
        if (rekey) scheduleRekey()
    }

    // ---------- Key change after a removal ----------
    //
    // Goal: the removed person's passphrase stops working, and everyone else switches automatically.
    // 1. One member (the "leader": the host if present, otherwise the lowest ID; others take over after a delay
    //    if the leader doesn't act) makes a new random passphrase.
    // 2. The leader seals it separately to every remaining member's key-exchange key, so the removed person
    //    (and anyone else) cannot read it. The bundle also carries the signed kick/votes as proof.
    // 3. Each member verifies the proof, opens their sealed copy, and switches keys. No one disconnects.
    // 4. Anyone who was offline is handed the bundle automatically when they reconnect (see rescueFor).

    private fun leaderOrder(): List<String> {
        val banSnap = synchronized(banned) { HashSet(banned) }
        val members = (connected.value + myId) - banSnap
        val host = hostId.value
        return members.sortedWith(compareBy<String>({ if (it == host) 0 else 1 }, { it }))
    }

    private fun scheduleRekey() {
        scope.launch(Dispatchers.Default) {
            val rank = leaderOrder().indexOf(myId)
            if (rank < 0) return@launch
            delay(500L + rank * 6000L)
            issueRekeyIfNeeded()
        }
    }

    private suspend fun issueRekeyIfNeeded() {
        if (closed) return
        val r = room.value

        // Wait (briefly) until we hold every remaining member's key-exchange key
        var members: List<String> = emptyList()
        var banSnap: Set<String> = emptySet()
        var ready = false
        for (attempt in 0 until 10) {
            banSnap = synchronized(banned) { HashSet(banned) }
            members = ((connected.value + myId) - banSnap).toList()
            if (members.all { it == myId || eks.containsKey(it) }) { ready = true; break }
            delay(500)
        }
        if (!ready) return   // try again on the next 30s tick

        val targets = (banSnap - excluded).filter { evidence[it]?.isNotEmpty() == true }.sorted()
        if (targets.isEmpty()) return

        val prev = curKid
        val newPass = RoomCodes.newPassphrase()
        val newKey = Crypto.deriveKey(newPass, r.code)
        val next = Crypto.kid(newKey)

        val w = JSONObject()
        for (m in members) {
            val pub = if (m == myId) myEk else (eks[m] ?: continue)
            val aad = "rekey|$tag|$prev|$next|$m".toByteArray()
            val (eph, ct) = Crypto.wrapFor(pub, m, newPass.toByteArray(), aad)
            w.put(m, JSONObject().put("e", b64(eph)).put("c", b64(ct)))
        }
        val wStr = w.toString()
        val ev = JSONArray()
        targets.forEach { t -> evidence[t]?.forEach { ev.put(it) } }
        val ts = System.currentTimeMillis()
        val sig = Identity.sign(
            "rekey|$tag|$prev|$next|$myId|${targets.joinToString(",")}|$ts|${Crypto.sha256Hex(wStr.toByteArray())}".toByteArray()
        )
        val bundle = JSONObject().put("type", "rekey").put("sid", myId).put("pk", b64(myPub))
            .put("prev", prev).put("next", next).put("targets", JSONArray(targets))
            .put("w", wStr).put("ev", ev).put("ts", ts).put("sig", b64(sig))

        if (curKid != prev) return            // someone else already changed the key while we were working
        transport?.send(bundle.toString().toByteArray())   // sealed with the OLD key so everyone can read it
        acceptBundle(bundle)                               // then we switch ourselves
    }

    /** Is there signed proof that [target] was legitimately removed? Self-contained: needs no live connections. */
    private fun evidenceOk(target: String, ev: List<JSONObject>, recipients: Set<String>): Boolean {
        val yes = HashSet<String>()
        for (j in ev) {
            if (j.optString("target") != target) continue
            val sid = j.optString("sid"); val ts = j.optLong("ts")
            when (j.optString("type")) {
                "kick" -> if (sid == hostId.value && sid != target && verified(j, "kick|$tag|$target|$ts")) return true
                "kv" -> if (j.optBoolean("yes") && sid != target && sid in recipients &&
                    verified(j, "kv|$tag|$target|true|$ts")) yes.add(sid)
            }
        }
        return yes.size >= 2 && yes.size * 2 > recipients.size
    }

    /** Validate a key-change bundle and, if it is genuine, switch to the new key. Used live and for catching up. */
    private suspend fun acceptBundle(b: JSONObject) {
        rekeyLock.withLock {
            try {
                if (closed) return
                val r = room.value
                val prev = b.getString("prev"); val next = b.getString("next")
                if (prev != curKid) return                      // stale, duplicate, or a different chain

                val leader = b.getString("sid"); val ts = b.getLong("ts")
                val tArr = b.getJSONArray("targets")
                val targets = (0 until tArr.length()).map { tArr.getString(it) }
                val wStr = b.getString("w")
                val signed = "rekey|$tag|$prev|$next|$leader|${targets.joinToString(",")}|$ts|${Crypto.sha256Hex(wStr.toByteArray())}"
                if (!verified(b, signed)) return
                if (isBanned(leader)) return

                val w = JSONObject(wStr)
                val recipients = w.keys().asSequence().toSet()
                if (leader !in recipients || myId !in recipients) return
                if (targets.isEmpty() || targets.all { it in excluded }) return
                if (targets.any { it in recipients }) return    // the removed person must not get the key

                val evArr = b.getJSONArray("ev")
                val ev = (0 until evArr.length()).mapNotNull { runCatching { JSONObject(evArr.getString(it)) }.getOrNull() }
                if (!targets.all { evidenceOk(it, ev, recipients) }) return

                // Nobody legitimate may be left out (guards against a member sneaking a rival out).
                // Members who connected in the last minute may not have been known to the leader yet.
                val now = System.currentTimeMillis()
                val view = (connected.value + myId) - synchronized(banned) { HashSet(banned) } - targets.toSet()
                if (view.any { it !in recipients && now - (seenAt[it] ?: 0L) > 60_000L }) return

                val mine = w.getJSONObject(myId)
                val aad = "rekey|$tag|$prev|$next|$myId".toByteArray()
                val passBytes = Crypto.unwrap(
                    ecdh.first, Base64.decode(mine.getString("e"), Base64.NO_WRAP), myId,
                    Base64.decode(mine.getString("c"), Base64.NO_WRAP), aad
                ) ?: return
                val newPass = passBytes.toString(Charsets.UTF_8)
                val newKey = Crypto.deriveKey(newPass, r.code)
                if (Crypto.kid(newKey) != next) return

                // ---- commit: switch to the new key ----
                transport?.rekey(next, newKey)
                val oldPass = curPass
                curPass = newPass; curKid = next
                excluded.addAll(targets)
                bundles.add(b)
                while (bundles.size > MAX_BUNDLES) bundles.removeAt(0)
                prevPasses.add(oldPass)
                while (prevPasses.size > MAX_BUNDLES) prevPasses.removeAt(0)
                prefs.edit()
                    .putStringSet("excl_$tag", HashSet(excluded))
                    .putString("bundles_$tag", JSONArray(bundles.map { it.toString() }).toString())
                    .putString("prev_$tag", JSONArray(prevPasses.toList()).toString())
                    .apply()
                room.update { it.copy(password = newPass) }
                onChanged()   // manager saves the new passphrase for auto-rejoin
                targets.forEach { applyBan(it, rekey = false) }
                add(Msg("sys-rekey-$next", "system", "",
                    "The carriage passphrase was changed after a removal. Invite now shares the new one.", now, system = true))
                announce()
            } catch (_: Exception) {}
        }
    }

    /** Called by the transport when someone connects with an older key: hand them the bundle that moves them on. */
    private fun rescueFor(peerId: String, theirKid: String): ByteArray? {
        val b = bundles.firstOrNull { it.optString("prev") == theirKid } ?: return null
        val w = runCatching { JSONObject(b.getString("w")) }.getOrNull() ?: return null
        return if (w.has(peerId)) b.toString().toByteArray() else null
    }

    private fun readJsonStrings(s: String?): List<String> {
        if (s == null) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    // ---------- History ----------

    private fun add(m: Msg) {
        synchronized(seen) { if (!seen.add(m.id)) return }
        messages.update { (it + m).sortedBy { x -> x.ts } }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!settings.value.keepHistory) return
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(1500)
            store.save(tag, messages.value, settings.value.retentionDays)
        }
    }

    private fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    companion object {
        private const val VOTE_EXPIRY_MS = 5 * 60_000L
        private const val VOTE_FRESH_MS = 10 * 60_000L
        private const val MAX_BUNDLES = 10
        private const val CHUNK = 48 * 1024
        private const val MAX_IMG = 3_000_000
        private const val MAX_CHUNKS = 100
        private const val MAX_ASSEMBLIES = 8
    }
}
