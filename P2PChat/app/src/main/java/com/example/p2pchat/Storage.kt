package com.example.p2pchat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Your privacy choices. Stored on this phone only.
 * Voice memos have no setting on purpose: they are never saved and vanish when you leave a room.
 */
data class AppSettings(
    val keepHistory: Boolean = true,
    val retentionDays: Int = 0,      // 0 = keep forever
    val keepImages: Boolean = true,  // used once photo sharing is added
    val keepVideos: Boolean = true,  // used once video sharing is added
    val keepAlive: Boolean = true    // foreground service: stay connected with the screen off
)

object SettingsStore {
    fun load(p: SharedPreferences) = AppSettings(
        keepHistory = p.getBoolean("s_hist", true),
        retentionDays = p.getInt("s_days", 0),
        keepImages = p.getBoolean("s_img", true),
        keepVideos = p.getBoolean("s_vid", true),
        keepAlive = p.getBoolean("s_keep", true)
    )

    fun save(p: SharedPreferences, s: AppSettings) {
        p.edit().putBoolean("s_hist", s.keepHistory).putInt("s_days", s.retentionDays)
            .putBoolean("s_img", s.keepImages).putBoolean("s_vid", s.keepVideos).putBoolean("s_keep", s.keepAlive).apply()
    }
}

/** Encrypted-at-rest chat history, one file per room. */
class HistoryStore(context: Context) {
    private val ctx = context.applicationContext
    private val master by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }

    private fun file(tag: String) = File(ctx.filesDir, "history_$tag.bin")
    private fun enc(f: File) = EncryptedFile.Builder(
        ctx, f, master, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
    ).build()

    private fun cutoff(days: Int) = if (days > 0) System.currentTimeMillis() - days * 86_400_000L else 0L

    @Synchronized
    fun save(tag: String, msgs: List<Msg>, retentionDays: Int) {
        if (tag.isEmpty()) return
        val limit = cutoff(retentionDays)
        val arr = JSONArray()
        msgs.filter { it.ts >= limit }.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("sid", m.senderId).put("n", m.senderName)
                .put("text", m.text).put("ts", m.ts).put("sys", m.system)
                .put("img", m.image ?: "").put("w", m.imgW).put("h", m.imgH))
        }
        val f = file(tag)
        f.delete() // EncryptedFile refuses to overwrite
        enc(f).openFileOutput().use { it.write(arr.toString().toByteArray()) }
    }

    @Synchronized
    fun load(tag: String, retentionDays: Int): List<Msg> {
        val f = file(tag)
        if (!f.exists()) return emptyList()
        return try {
            val txt = enc(f).openFileInput().use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONArray(txt)
            val limit = cutoff(retentionDays)
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Msg(it.getString("id"), it.getString("sid"), it.getString("n"),
                    it.getString("text"), it.getLong("ts"), it.optBoolean("sys", false),
                    it.optString("img", "").ifEmpty { null }, it.optInt("w", 0), it.optInt("h", 0))
            }.filter { it.ts >= limit }
        } catch (e: Exception) { emptyList() }
    }

    @Synchronized fun delete(tag: String) { file(tag).delete() }

    @Synchronized
    fun deleteAll() {
        ctx.filesDir.listFiles { _, name -> name.startsWith("history_") }?.forEach { it.delete() }
    }
}
