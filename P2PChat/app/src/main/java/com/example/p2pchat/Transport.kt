package com.example.p2pchat

import javax.crypto.SecretKey

object Limits {
    const val MAX_ROOM = 25          // total members including yourself
    const val MAX_PEERS = MAX_ROOM - 1
}

/**
 * The swappable layer. LanTransport (NSD + direct sockets) today;
 * a WebRTC + internet-discovery transport later. Chat/crypto code never changes.
 * Frames in/out are plaintext; the transport encrypts them on the wire with the current room key.
 */
interface Transport {
    fun start(
        selfId: String,
        roomTag: String,
        kid: String,                       // fingerprint of [key]
        key: SecretKey,
        onFrame: (ByteArray) -> Unit,
        onPeers: (Set<String>) -> Unit,    // IDs of everyone currently connected
        /** Someone connecting with an older key: return the key-change bundle that moves them forward, or null. */
        rescue: (peerId: String, theirKid: String) -> ByteArray?
    )
    fun send(frame: ByteArray)
    /** Send right now on the calling thread (use off the main thread). Keeps order on each link. */
    fun sendBlocking(frame: ByteArray)
    /** Switch to a new room key. Existing links stay up; the previous key is honoured briefly. */
    fun rekey(kid: String, key: SecretKey)
    /** Remember an older key so members who missed a key change can be brought up to date. */
    fun addLegacyKey(kid: String, key: SecretKey)
    /** Drop this peer now and refuse any future connection from them. */
    fun ban(id: String)
    fun stop()
}
