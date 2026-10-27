package com.example.p2pchat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object Identity {
    fun securePrefs(ctx: Context): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Random 128-bit ID (32 hex chars), generated once per install. */
    fun deviceId(ctx: Context): String {
        val p = securePrefs(ctx)
        return p.getString("device_id", null) ?: UUID.randomUUID().toString().replace("-", "")
            .also { p.edit().putString("device_id", it).apply() }
    }
}
