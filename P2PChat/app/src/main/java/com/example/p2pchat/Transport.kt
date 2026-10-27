package com.example.p2pchat

import javax.crypto.SecretKey

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
        onPeers: (Int) -> Unit
    )
    fun send(frame: ByteArray)
    fun stop()
}
