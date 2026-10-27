package com.example.p2pchat

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Password -> 256-bit AES key. Room code acts as salt. Slow on purpose; call off the main thread. */
    fun deriveKey(password: String, roomCode: String): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), "p2pchat:$roomCode".toByteArray(), 100_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** Short public fingerprint of a room key, used to say which key a message or link is on. */
    fun kid(key: SecretKey): String = sha256Hex(key.encoded).take(8)

    /** Public, non-reversible room label used for discovery (the real room code is never broadcast). */
    fun roomTag(roomCode: String): String = sha256Hex(roomCode.toByteArray()).take(12)

    fun encrypt(key: SecretKey, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Returns null if the data was tampered with or the key (password) is wrong. */
    fun decrypt(key: SecretKey, data: ByteArray, aad: ByteArray? = null): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            if (aad != null) c.updateAAD(aad)
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (e: Exception) { null }

    // ---- Sealing a secret to one specific member (ephemeral-static ECDH + AES-GCM) ----

    private fun wrapKey(priv: PrivateKey, pub: PublicKey, recipientId: String): SecretKey {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        val d = MessageDigest.getInstance("SHA-256")
        d.update(ka.generateSecret())
        d.update("brasstalks-wrap:$recipientId".toByteArray())
        return SecretKeySpec(d.digest(), "AES")
    }

    /** Returns (ephemeral public key, ciphertext). Only the holder of the recipient's private key can open it. */
    fun wrapFor(recipientPub: ByteArray, recipientId: String, secret: ByteArray, aad: ByteArray): Pair<ByteArray, ByteArray> {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val eph = gen.generateKeyPair()
        val rpk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(recipientPub))
        val kek = wrapKey(eph.private, rpk, recipientId)
        return eph.public.encoded to encrypt(kek, secret, aad)
    }

    fun unwrap(myPrivate: PrivateKey, ephPub: ByteArray, myId: String, ct: ByteArray, aad: ByteArray): ByteArray? = try {
        val epk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ephPub))
        decrypt(wrapKey(myPrivate, epk, myId), ct, aad)
    } catch (e: Exception) { null }
}
