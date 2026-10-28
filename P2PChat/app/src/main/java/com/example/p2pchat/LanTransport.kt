package com.example.p2pchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.SecretKey

/**
 * Serverless full mesh over the local network.
 * Every phone listens on a port, advertises itself via mDNS (NSD), and connects to the others.
 * To avoid duplicate links, the peer with the smaller ID dials the larger one.
 * Rooms are capped at Limits.MAX_ROOM members; extra joiners are told the room is full.
 *
 * Keys: ring[0] is the current room key. Older keys are kept so that a member who missed a key change
 * can reconnect, be handed the key-change bundle (never chat content), and catch up automatically.
 */
class LanTransport(context: Context) : Transport {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = ConcurrentHashMap<String, Conn>()
    private val known = ConcurrentHashMap<String, Pair<String, Int>>() // peerId -> host,port
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val banned = ConcurrentHashMap.newKeySet<String>()
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
    private val tries = ConcurrentHashMap<String, Int>()
    private val ring = CopyOnWriteArrayList<Pair<String, SecretKey>>()   // (kid, key), newest first
    @Volatile private var graceUntil = 0L

    private lateinit var selfId: String
    private lateinit var prefix: String
    private lateinit var onFrame: (ByteArray) -> Unit
    private lateinit var onPeers: (Set<String>) -> Unit
    private lateinit var rescue: (String, String) -> ByteArray?

    private var lock: WifiManager.MulticastLock? = null
    private var server: ServerSocket? = null
    private var regL: NsdManager.RegistrationListener? = null
    private var discL: NsdManager.DiscoveryListener? = null

    private class Conn(val socket: Socket, val out: DataOutputStream) {
        fun send(b: ByteArray) = synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
    }

    private class Match(val kid: String, val key: SecretKey, val plain: ByteArray)

    override fun start(
        selfId: String, roomTag: String, kid: String, key: SecretKey,
        onFrame: (ByteArray) -> Unit, onPeers: (Set<String>) -> Unit,
        rescue: (String, String) -> ByteArray?
    ) {
        this.selfId = selfId; this.prefix = "rm$roomTag-"
        this.onFrame = onFrame; this.onPeers = onPeers; this.rescue = rescue
        ring.add(kid to key)

        lock = wifi.createMulticastLock("p2pchat").apply { setReferenceCounted(false); acquire() }
        val ss = ServerSocket(0).also { server = it }

        // Accept incoming connections
        scope.launch {
            while (isActive) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                launch { handshake(s) }
            }
        }

        // Advertise ourselves
        val info = NsdServiceInfo().apply {
            serviceName = prefix + selfId
            serviceType = TYPE
            port = ss.localPort
        }
        regL = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, regL)

        // Discover others in the same room
        discL = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                val id = peerIdOf(i.serviceName) ?: return
                if (id != selfId && !banned.contains(id)) resolveQueue.trySend(i)
            }
            override fun onServiceLost(i: NsdServiceInfo) {
                peerIdOf(i.serviceName)?.let { known.remove(it) }
            }
        }
        nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discL)

        // NSD resolves one service at a time
        scope.launch {
            for (found in resolveQueue) {
                val r = resolveOne(found)
                if (r == null) {
                    // Another carriage may have been resolving at the same moment: try again shortly
                    val n = (tries[found.serviceName] ?: 0) + 1
                    tries[found.serviceName] = n
                    if (n < 4) launch { delay(1500); resolveQueue.trySend(found) }
                    continue
                }
                tries.remove(found.serviceName)
                val id = peerIdOf(r.serviceName) ?: continue
                if (banned.contains(id)) continue
                @Suppress("DEPRECATION")
                val host = r.host?.hostAddress ?: continue
                known[id] = host to r.port
                if (selfId < id && dialing.add(id)) launch { dialLoop(id) }
            }
        }

        // Heartbeat so dead links are noticed quickly
        scope.launch {
            while (isActive) { delay(10_000); writeAll(Crypto.encrypt(ring[0].second, PING)) }
        }
    }

    /** Android allows one lookup at a time for the whole app, so all carriages share one lock. */
    private suspend fun resolveOne(found: NsdServiceInfo): NsdServiceInfo? = resolveLock.withLock {
        val done = CompletableDeferred<NsdServiceInfo?>()
        @Suppress("DEPRECATION")
        nsd.resolveService(found, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done.complete(null) }
            override fun onServiceResolved(i: NsdServiceInfo) { done.complete(i) }
        })
        withTimeoutOrNull(5000) { done.await() }
    }

    /** Keeps (re)connecting to a peer for as long as it stays advertised. */
    private suspend fun dialLoop(peerId: String) {
        while (scope.isActive) {
            val addr = known[peerId] ?: break
            if (!conns.containsKey(peerId)) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(addr.first, addr.second), 4000)
                    handshake(s) // blocks until the link closes
                } catch (_: Exception) {}
            }
            delay(3000)
        }
        dialing.remove(peerId)
    }

    private fun decryptWithRing(data: ByteArray): Match? {
        for ((kid, k) in ring) {
            val p = Crypto.decrypt(k, data)
            if (p != null) return Match(kid, k, p)
        }
        return null
    }

    /** Both sides send an encrypted HELLO. A wrong key fails to decrypt and the link is dropped. */
    private fun handshake(s: Socket) {
        var id: String? = null
        var conn: Conn? = null
        try {
            s.keepAlive = true
            s.soTimeout = 5000
            val input = DataInputStream(s.getInputStream().buffered())
            val c = Conn(s, DataOutputStream(s.getOutputStream().buffered()))
            conn = c
            val cur = ring[0]
            c.send(Crypto.encrypt(cur.second, "HELLO:$selfId".toByteArray()))

            val first = readFrame(input)
            val m = decryptWithRing(first)
            if (m == null) {
                // We might be the one who is behind: the peer can hand us a key-change bundle sealed with OUR key.
                val second = readFrame(input)
                val p = Crypto.decrypt(cur.second, second)
                if (p != null) onFrame(p)
                return
            }
            val hello = m.plain.toString(Charsets.UTF_8)
            if (!hello.startsWith("HELLO:")) return
            val peer = hello.removePrefix("HELLO:")
            if (peer == selfId || banned.contains(peer)) return

            if (m.kid != ring[0].first) {
                // Peer is still on an older key. They don't get into the chat, but if they were a member
                // we give them the bundle that moves them to the new key.
                val bundle = rescue(peer, m.kid)
                if (bundle != null) {
                    runCatching {
                        c.send(Crypto.encrypt(m.key, bundle))
                        s.shutdownOutput()
                        s.soTimeout = 1500
                        while (input.read() >= 0) { }
                    }
                }
                return
            }

            // Room full: tell the newcomer politely, then close gracefully
            if (!conns.containsKey(peer) && conns.size >= Limits.MAX_PEERS) {
                runCatching {
                    c.send(Crypto.encrypt(m.key, FULL))
                    s.shutdownOutput()
                    s.soTimeout = 1500
                    while (input.read() >= 0) { }
                }
                return
            }

            if (conns.putIfAbsent(peer, c) != null) return
            id = peer
            s.soTimeout = 25_000
            onPeers(conns.keys.toSet())

            var badSince = 0L
            while (true) {
                val raw = readFrame(input)
                val snap = ring.toList()
                var plain = Crypto.decrypt(snap[0].second, raw)
                if (plain == null && snap.size > 1 && System.currentTimeMillis() < graceUntil) {
                    plain = Crypto.decrypt(snap[1].second, raw)
                }
                if (plain == null) {
                    // Undecryptable: usually a moment of key-change overlap. If it lasts, drop the link so the
                    // peer reconnects and gets brought up to date.
                    val now = System.currentTimeMillis()
                    if (badSince == 0L) badSince = now
                    if (now - badSince > 45_000) break
                    continue
                }
                badSince = 0L
                if (plain.contentEquals(PING)) continue
                onFrame(plain)
            }
        } catch (_: Exception) {
        } finally {
            if (id != null && conn != null && conns.remove(id, conn)) onPeers(conns.keys.toSet())
            runCatching { s.close() }
        }
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val n = input.readInt()
        require(n in 0..MAX_FRAME)
        return ByteArray(n).also { input.readFully(it) }
    }

    private fun writeAll(enc: ByteArray) {
        conns.values.forEach { c -> runCatching { c.send(enc) }.onFailure { runCatching { c.socket.close() } } }
    }

    /** Encrypts right now with the current key (so a frame sent just before a key change stays on the old key). */
    override fun send(frame: ByteArray) {
        val enc = Crypto.encrypt(ring[0].second, frame)
        scope.launch { writeAll(enc) }
    }

    override fun sendBlocking(frame: ByteArray) {
        writeAll(Crypto.encrypt(ring[0].second, frame))
    }

    override fun rekey(kid: String, key: SecretKey) {
        if (ring.any { it.first == kid }) return
        ring.add(0, kid to key)
        graceUntil = System.currentTimeMillis() + 120_000
    }

    override fun addLegacyKey(kid: String, key: SecretKey) {
        if (ring.none { it.first == kid }) ring.add(kid to key)
    }

    override fun ban(id: String) {
        banned.add(id)
        known.remove(id)
        conns[id]?.let { runCatching { it.socket.close() } }
    }

    override fun stop() {
        scope.cancel()
        resolveQueue.close()
        runCatching { discL?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { regL?.let { nsd.unregisterService(it) } }
        runCatching { server?.close() }
        conns.values.forEach { runCatching { it.socket.close() } }
        conns.clear()
        runCatching { lock?.release() }
    }

    private fun peerIdOf(serviceName: String): String? =
        if (serviceName.startsWith(prefix)) serviceName.removePrefix(prefix).take(32).takeIf { it.length == 32 } else null

    companion object {
        private val resolveLock = Mutex()
        private const val TYPE = "_p2pchat._tcp."
        private const val MAX_FRAME = 25 * 1024 * 1024 // room to grow for media chunks
        private val PING = "PING".toByteArray()
        private val FULL = """{"type":"full"}""".toByteArray()
    }
}
