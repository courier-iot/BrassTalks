package com.example.p2pchat

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * Each install owns an ECDSA keypair that lives in the Android Keystore (the private key never leaves the chip).
 * The device ID is a hash of the public key, so an ID cannot be faked: only the phone holding the
 * private key can sign as that ID. Reinstalling creates a new key and therefore a new ID.
 */
object Identity {
    private const val ALIAS = "brasstalks_identity"

    fun securePrefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore().containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
        )
        gen.generateKeyPair()
    }

    fun publicKeyBytes(): ByteArray {
        ensureKey()
        return keyStore().getCertificate(ALIAS).publicKey.encoded
    }

    fun idFromPublicKey(pub: ByteArray): String = Crypto.sha256Hex(pub).take(32)

    fun deviceId(): String = idFromPublicKey(publicKeyBytes())

    fun sign(data: ByteArray): ByteArray {
        ensureKey()
        val pk = keyStore().getKey(ALIAS, null) as PrivateKey
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pk)
        s.update(data)
        return s.sign()
    }

    fun verify(pub: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val pk = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pub))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pk)
        s.update(data)
        s.verify(sig)
    } catch (e: Exception) { false }
}
