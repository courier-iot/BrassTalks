package com.example.p2pchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos received or sent, stored encrypted on this phone, one folder per carriage. */
class PhotoVault(context: Context) {
    private val ctx = context.applicationContext
    private val master by lazy { MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }

    private fun dir(tag: String) = File(ctx.filesDir, "media_$tag").apply { mkdirs() }
    private fun file(tag: String, id: String) = File(dir(tag), "$id.bin")
    private fun enc(f: File) = EncryptedFile.Builder(
        ctx, f, master, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
    ).build()

    @Synchronized
    fun save(tag: String, id: String, bytes: ByteArray) {
        val f = file(tag, id)
        f.delete() // EncryptedFile refuses to overwrite
        enc(f).openFileOutput().use { it.write(bytes) }
    }

    @Synchronized
    fun read(tag: String, id: String): ByteArray? {
        val f = file(tag, id)
        if (!f.exists()) return null
        return try { enc(f).openFileInput().use { it.readBytes() } } catch (e: Exception) { null }
    }

    @Synchronized
    fun exists(tag: String, id: String): Boolean = file(tag, id).exists()

    @Synchronized
    fun delete(tag: String) { File(ctx.filesDir, "media_$tag").deleteRecursively() }

    @Synchronized
    fun deleteAll() {
        ctx.filesDir.listFiles { _, name -> name.startsWith("media_") }?.forEach { it.deleteRecursively() }
    }

    /** Delete every stored photo of this carriage that no remaining message refers to. */
    @Synchronized
    fun prune(tag: String, keepIds: Set<String>) {
        dir(tag).listFiles()?.forEach { f -> if (f.nameWithoutExtension !in keepIds) f.delete() }
    }
}

object ImageUtil {
    class Result(val bytes: ByteArray, val w: Int, val h: Int)

    private const val MAX_BYTES = 3_000_000
    private const val TARGET_BYTES = 1_200_000

    /**
     * Shrinks a picked photo to at most [maxSide] pixels on its longest side and re-encodes it as JPEG.
     * Re-encoding also strips hidden data such as GPS location. Returns null if it can't be read.
     */
    fun compress(ctx: Context, uri: Uri, maxSide: Int = 1280): Result? {
        return try {
            val cr = ctx.contentResolver
            val orientation = runCatching {
                cr.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
            }

            var q = 80
            var out: ByteArray
            while (true) {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                out = bos.toByteArray()
                if (out.size <= TARGET_BYTES || q <= 35) break
                q -= 15
            }
            if (out.size > MAX_BYTES) return null
            Result(out, bmp.width, bmp.height)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Decode for on-screen display, no larger than [maxSide] pixels. */
    fun decodeSampled(bytes: ByteArray, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide || bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
