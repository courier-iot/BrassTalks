package com.example.p2pchat

import javax.crypto.SecretKey

object Limits {
    const val MAX_ROOM = 25          // total members including yourself
    const val MAX_PEERS = MAX_ROOM - 1
}

/**
 * The swappable layer. LanTransport (NSD + direct sockets) today;
 * a WebRTC + internet-discovery transport later. Chat/crypto code never changes.
 * Frames in/out are plaintext; the transport encrypts them on the wire.
 */
interface Transport {
    fun start(
        selfId: String,
        roomTag: String,
        key: SecretKey,
        onFrame: (ByteArray) -> Unit,
        onPeers: (Set<String>) -> Unit   // IDs of everyone currently connected
    )
    fun send(frame: ByteArray)
    /** Drop this peer now and refuse any future connection from them. */
    fun ban(id: String)
    fun stop()
}
