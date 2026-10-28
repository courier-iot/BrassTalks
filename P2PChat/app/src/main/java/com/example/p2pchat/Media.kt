package com.example.p2pchat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

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

    /** Encrypt a (possibly large) file into the vault without loading it all into memory. */
    @Synchronized
    fun saveFrom(tag: String, id: String, src: File) {
        val f = file(tag, id)
        f.delete()
        enc(f).openFileOutput().use { out -> src.inputStream().use { it.copyTo(out, 64 * 1024) } }
    }

    /** Stream a stored item out (decrypted). Returns false if it is missing or unreadable. */
    @Synchronized
    fun copyTo(tag: String, id: String, out: OutputStream): Boolean {
        val f = file(tag, id)
        if (!f.exists()) return false
        return try {
            enc(f).openFileInput().use { it.copyTo(out, 64 * 1024) }
            true
        } catch (e: Exception) { false }
    }

    fun decryptTo(tag: String, id: String, dest: File): Boolean {
        val ok = try { FileOutputStream(dest).use { copyTo(tag, id, it) } } catch (e: Exception) { false }
        if (!ok) dest.delete()
        return ok
    }

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

/** Sending quality for photos and videos. Original applies to photos only (videos: tap-to-download, later). */
enum class Quality(
    val key: String, val label: String,
    val photoSide: Int, val photoTarget: Int, val photoEstimate: Long,
    val videoShort: Int, val videoBitrate: Int,
    val photoDesc: String, val videoDesc: String
) {
    SAVER("saver", "Data saver", 960, 400_000, 200_000, 360, 700_000, "Smallest and fastest", "360p, smallest"),
    STANDARD("standard", "Standard", 1280, 1_200_000, 400_000, 480, 1_200_000, "Good balance", "480p, good balance"),
    HIGH("high", "High", 1920, 2_500_000, 900_000, 720, 2_500_000, "Sharper, bigger", "720p, sharper, bigger"),
    ORIGINAL("original", "Original", 0, 0, 0, 0, 0, "Full size, as taken", "");

    companion object {
        val presets = listOf(SAVER, STANDARD, HIGH)
        fun of(key: String?): Quality = presets.firstOrNull { it.key == key } ?: STANDARD
    }
}

/** Rough size of a shrunk video: picture plus about 128 kbps of sound. */
fun videoEstimateBytes(q: Quality, durMs: Long): Long = (q.videoBitrate + 128_000L) * durMs / 8000

fun fmtSize(bytes: Long): String =
    if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1_000_000.0) else "${(bytes / 1000).coerceAtLeast(1)} KB"

object ImageUtil {
    class Result(val bytes: ByteArray, val w: Int, val h: Int)

    private const val MAX_BYTES = 3_000_000
    const val MAX_ORIGINAL = 25_000_000

    /** Hidden details removed from original photos: location, and serial numbers that identify the camera. */
    private val PRIVATE_TAGS = listOf(
        "GPSVersionID", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
        "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod",
        "GPSSpeed", "GPSSpeedRef", "GPSTrack", "GPSTrackRef", "GPSImgDirection", "GPSImgDirectionRef",
        "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef",
        "GPSDestBearing", "GPSDestBearingRef", "GPSDestDistance", "GPSDestDistanceRef",
        "GPSAreaInformation", "GPSHPositioningError", "GPSMapDatum", "GPSSatellites", "GPSStatus",
        "GPSMeasureMode", "GPSDOP", "GPSDifferential",
        "BodySerialNumber", "LensSerialNumber", "CameraOwnerName", "ImageUniqueID"
    )

    private fun matrixFor(orientation: Int): Matrix {
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
        return m
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Original quality. JPEGs are sent byte-for-byte except for the private tags above.
     * Other formats (HEIC, PNG, WebP...) become a full-size JPEG, which removes all hidden data and opens on
     * every phone. Returns null if unreadable or over 25 MB.
     */
    fun original(ctx: Context, uri: Uri): Result? {
        val cr = ctx.contentResolver
        val type = (cr.getType(uri) ?: "").lowercase()
        if (type != "image/jpeg" && type != "image/jpg") {
            return compress(ctx, uri, maxSide = 4096, target = MAX_ORIGINAL, startQuality = 92, maxBytes = MAX_ORIGINAL)
        }
        val tmp = File.createTempFile("orig", ".jpg", ctx.cacheDir)
        return try {
            cr.openInputStream(uri)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } } ?: return null
            if (tmp.length() > MAX_ORIGINAL) return null
            val stripped = runCatching {
                val ex = ExifInterface(tmp.absolutePath)
                PRIVATE_TAGS.forEach { ex.setAttribute(it, null) }
                ex.saveAttributes()
            }.isSuccess
            // If the hidden data can't be removed safely, fall back to a clean full-size copy
            if (!stripped) {
                return compress(ctx, uri, maxSide = 4096, target = MAX_ORIGINAL, startQuality = 92, maxBytes = MAX_ORIGINAL)
            }
            val bytes = tmp.readBytes()
            if (bytes.size > MAX_ORIGINAL) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var w = bounds.outWidth; var h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            when (orientationOf(bytes)) {
                ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> { val t = w; w = h; h = t }
            }
            Result(bytes, w, h)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * Shrinks a picked photo to at most [maxSide] pixels on its longest side and re-encodes it as JPEG.
     * Re-encoding also strips hidden data such as GPS location. Returns null if it can't be read.
     */
    fun compress(
        ctx: Context, uri: Uri, maxSide: Int = 1280, target: Int = 1_200_000,
        startQuality: Int = 80, maxBytes: Int = MAX_BYTES
    ): Result? {
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

            val m = matrixFor(orientation)
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)

            val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) {
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true
                )
            }

            var q = startQuality
            var out: ByteArray
            while (true) {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
                out = bos.toByteArray()
                if (out.size <= target || q <= 35) break
                q -= 15
            }
            if (out.size > maxBytes) return null
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
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            // Original photos keep their "which way up" tag, so apply it when showing them
            val m = matrixFor(orientationOf(bytes))
            if (m.isIdentity) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}


// ---------- Voice notes ----------

const val MAX_VOICE_MS = 120_000L   // 2 minutes

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Records one voice note (AAC, mono, about 4 KB per second). The temporary file is always deleted. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        cancel()
        return try {
            val f = File.createTempFile("vn", ".m4a", ctx.cacheDir)
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
            file = f
            rec = r
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            cancel()
            false
        }
    }

    fun elapsedMs(): Long = if (rec == null) 0L else SystemClock.elapsedRealtime() - startedAt

    /** Stop and return (audio, duration in ms), or null if it was too short or failed. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = rec ?: return null
        val dur = elapsedMs()
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        rec = null
        val f = file
        file = null
        val bytes = if (ok && dur >= 700) runCatching { f?.readBytes() }.getOrNull() else null
        f?.delete()
        return bytes?.let { it to dur }
    }

    fun cancel() {
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        rec = null
        file?.delete()
        file = null
    }
}

/** Plays voice notes straight from memory (nothing is written to disk). One at a time. */
object VoicePlayer {
    val playing = MutableStateFlow<String?>(null)
    val position = MutableStateFlow(0L)
    private var mp: MediaPlayer? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun toggle(id: String, bytes: ByteArray) {
        if (playing.value == id) { stop(); return }
        stop()
        try {
            val p = MediaPlayer()
            mp = p
            p.setDataSource(BytesSource(bytes))
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            playing.value = id
            job = scope.launch {
                while (isActive) {
                    position.value = runCatching { p.currentPosition.toLong() }.getOrDefault(0L)
                    delay(200)
                }
            }
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        playing.value = null
        position.value = 0L
    }

    private class BytesSource(private val data: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1
            val n = minOf(size.toLong(), data.size - position).toInt()
            System.arraycopy(data, position.toInt(), buffer, offset, n)
            return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }
}


// ---------- Videos ----------

const val MAX_VIDEO_MS = 5 * 60_000L          // 5 minutes
const val MAX_VIDEO_BYTES = 150 * 1024 * 1024  // safety cap after shrinking (High, 5 min is about 100 MB)
fun videoKey(id: String) = "$id-v"
fun thumbKey(id: String) = "$id-t"

object VideoUtil {
    class Info(val durMs: Long, val w: Int, val h: Int, val thumb: ByteArray?)


    /** Length, on-screen size and a small preview picture. Null if it isn't a readable video. */
    fun probe(ctx: Context, uri: Uri): Info? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            if (w <= 0 || h <= 0 || dur <= 0) return null
            val frame = mmr.getFrameAtTime(minOf(1_000_000L, dur * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumb = frame?.let { f ->
                val scale = 320f / maxOf(f.width, f.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(
                    f, (f.width * scale).toInt().coerceAtLeast(1), (f.height * scale).toInt().coerceAtLeast(1), true
                ) else f
                ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
            }
            Info(dur, w, h, thumb)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** Just the length, for the quality chooser. */
    fun durationMs(ctx: Context, uri: Uri): Long? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Shrinks a video to the chosen quality (360p, 480p or 720p) as H.264 + AAC, which also drops hidden metadata such as location.
     * Media3 marks these APIs "unstable"; that only matters for lint, not for building.
     */
    suspend fun transcode(
        ctx: Context, uri: Uri, out: File, info: Info, q: Quality, onProgress: (Float) -> Unit
    ): Boolean =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Boolean>()
            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(q.videoBitrate).build())
                .setEnableFallback(true)
                .build()
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(true)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.complete(false)
                    }
                })
                .build()

            // Short side becomes 360/480/720 pixels (portrait or landscape); smaller videos keep their size
            val short = q.videoShort
            val effects = if (minOf(info.w, info.h) > short) {
                val height = if (info.h >= info.w) (short.toFloat() * info.h / info.w).toInt() / 2 * 2 else short
                Effects(listOf(), listOf(Presentation.createForHeight(height)))
            } else Effects.EMPTY
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setEffects(effects).build()

            out.delete()
            try {
                transformer.start(item, out.absolutePath)
            } catch (e: Exception) {
                return@withContext false
            }
            val holder = ProgressHolder()
            val poll = launch {
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(500)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            } finally {
                poll.cancel()
            }
        }
}
