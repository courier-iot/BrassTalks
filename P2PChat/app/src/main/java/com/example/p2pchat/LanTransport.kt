package com.example.p2pchat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * Serverless full mesh over the local network.
 * Every phone listens on a port, advertises itself via mDNS (NSD), and connects to the others.
 * To avoid duplicate links, the peer with the smaller ID dials the larger one.
 * Rooms are capped at Limits.MAX_ROOM members; extra joiners are told the room is full.
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

    private lateinit var selfId: String
    private lateinit var prefix: String
    private lateinit var key: SecretKey
    private lateinit var onFrame: (ByteArray) -> Unit
    private lateinit var onPeers: (Set<String>) -> Unit

    private var lock: WifiManager.MulticastLock? = null
    private var server: ServerSocket? = null
    private var regL: NsdManager.RegistrationListener? = null
    private var discL: NsdManager.DiscoveryListener? = null

    private class Conn(val socket: Socket, val out: DataOutputStream) {
        fun send(b: ByteArray) = synchronized(out) { out.writeInt(b.size); out.write(b); out.flush() }
    }

    override fun start(
        selfId: String, roomTag: String, key: SecretKey,
        onFrame: (ByteArray) -> Unit, onPeers: (Set<String>) -> Unit
    ) {
        this.selfId = selfId; this.prefix = "rm$roomTag-"; this.key = key
        this.onFrame = onFrame; this.onPeers = onPeers

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
                val done = CompletableDeferred<NsdServiceInfo?>()
                @Suppress("DEPRECATION")
                nsd.resolveService(found, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { done.complete(null) }
                    override fun onServiceResolved(i: NsdServiceInfo) { done.complete(i) }
                })
                val r = withTimeoutOrNull(5000) { done.await() } ?: continue
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
            while (isActive) { delay(10_000); broadcast(PING) }
        }
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

    /** Both sides send an encrypted HELLO. A wrong password fails to decrypt and the link is dropped. */
    private fun handshake(s: Socket) {
        var id: String? = null
        var conn: Conn? = null
        try {
            s.keepAlive = true
            s.soTimeout = 5000
            val input = DataInputStream(s.getInputStream().buffered())
            val c = Conn(s, DataOutputStream(s.getOutputStream().buffered()))
            conn = c
            c.send(Crypto.encrypt(key, "HELLO:$selfId".toByteArray()))

            val hello = Crypto.decrypt(key, readFrame(input))?.toString(Charsets.UTF_8) ?: return
            if (!hello.startsWith("HELLO:")) return
            val peer = hello.removePrefix("HELLO:")
            if (peer == selfId || banned.contains(peer)) return

            // Room full: tell the newcomer politely, then close gracefully
            if (!conns.containsKey(peer) && conns.size >= Limits.MAX_PEERS) {
                runCatching {
                    c.send(Crypto.encrypt(key, FULL))
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

            while (true) {
                val plain = Crypto.decrypt(key, readFrame(input)) ?: continue
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

    private fun broadcast(plain: ByteArray) {
        val enc = Crypto.encrypt(key, plain)
        conns.values.forEach { c -> runCatching { c.send(enc) }.onFailure { runCatching { c.socket.close() } } }
    }

    override fun send(frame: ByteArray) { scope.launch { broadcast(frame) } }

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
        private const val TYPE = "_p2pchat._tcp."
        private const val MAX_FRAME = 25 * 1024 * 1024 // room to grow for media chunks
        private val PING = "PING".toByteArray()
        private val FULL = """{"type":"full"}""".toByteArray()
    }
}
