package com.example.p2pchat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val rng = SecureRandom()

    /** Password -> 256-bit AES key. Room code acts as salt. Slow on purpose; call off the main thread. */
    fun deriveKey(password: String, roomCode: String): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), "p2pchat:$roomCode".toByteArray(), 100_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    /** Public, non-reversible room label used for discovery (the real room code is never broadcast). */
    fun roomTag(roomCode: String): String =
        MessageDigest.getInstance("SHA-256").digest(roomCode.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    /** Returns null if the data was tampered with or the key (password) is wrong. */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray? = try {
        if (data.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12)))
            c.doFinal(data, 12, data.size - 12)
        }
    } catch (e: Exception) { null }
}
