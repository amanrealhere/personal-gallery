package com.amanrealhere.personalgallery.data.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Persistent, on-demand cache for grid-sized JPEGs. Only composed cells trigger
 * generation, avoiding whole-album thumbnail jobs before the first scroll.
 */
object MediaThumbnailCache {
    const val DEFAULT_THUMBNAIL_SIZE = 256
    private const val JPEG_QUALITY = 85
    private const val CACHE_VERSION = 2
    private const val MAX_CACHE_BYTES = 350L * 1024L * 1024L
    private const val TRIM_TARGET_BYTES = 250L * 1024L * 1024L
    private const val CLEANUP_INTERVAL = 100
    private const val LOCK_COUNT = 64

    private val permits = Semaphore(2)
    private val locks = Array(LOCK_COUNT) { Mutex() }
    private val generatedCount = AtomicInteger()

    suspend fun getOrCreate(
        context: Context,
        sourcePath: String,
        sourceSizeBytes: Long,
        sourceModifiedAtEpochMillis: Long,
        targetSize: Int
    ): File = withContext(Dispatchers.IO) {
        val size = targetSize.coerceIn(128, 512)
        val key = cacheKey(sourcePath, sourceSizeBytes, sourceModifiedAtEpochMillis, size)
        val directory = File(context.cacheDir, "media-grid-thumbnails-v$CACHE_VERSION")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create media thumbnail cache")
        }
        val destination = File(directory, "$key.jpg")
        val lock = locks[Math.floorMod(key.hashCode(), LOCK_COUNT)]

        lock.withLock {
            if (!isValid(destination, size)) {
                permits.withPermit {
                    coroutineContext.ensureActive()
                    val source = File(sourcePath)
                    check(source.isFile) { "Source image is unavailable" }
                    check(source.length() == sourceSizeBytes &&
                        source.lastModified() == sourceModifiedAtEpochMillis) {
                        "Source image changed since indexing"
                    }

                    val temporary = File(directory, key + "." + System.nanoTime() + ".tmp")
                    try {
                        val bitmap = decodeThumbnail(source, size)
                            ?: throw IllegalArgumentException("Unsupported or unreadable image")
                        try {
                            coroutineContext.ensureActive()
                            FileOutputStream(temporary).use { stream ->
                                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                                    "JPEG encoding failed"
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                        coroutineContext.ensureActive()
                        check(source.isFile && source.length() == sourceSizeBytes &&
                            source.lastModified() == sourceModifiedAtEpochMillis) {
                            "Source image changed during thumbnail generation"
                        }
                        destination.delete()
                        check(temporary.renameTo(destination)) {
                            "Could not atomically place thumbnail in cache"
                        }
                    } finally {
                        temporary.delete()
                    }

                    if (generatedCount.incrementAndGet() % CLEANUP_INTERVAL == 0) {
                        trimCache(directory)
                    }
                }
            } else {
                destination.setLastModified(System.currentTimeMillis())
            }
        }
        destination
    }

    private fun cacheKey(path: String, size: Long, modified: Long, targetSize: Int): String {
        val identity = "$path|$size|$modified|$targetSize|center-crop-v$CACHE_VERSION"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun isValid(file: File, targetSize: Int): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth == targetSize && options.outHeight == targetSize
    }

    private fun decodeThumbnail(source: File, targetSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val shortest = minOf(bounds.outWidth, bounds.outHeight)
        while (shortest / (sample * 2) >= targetSize) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return null

        val oriented = applyExifOrientation(source, decoded)
        if (oriented !== decoded) decoded.recycle()

        try {
            val side = minOf(oriented.width, oriented.height)
            val square = Bitmap.createBitmap(
                oriented,
                (oriented.width - side) / 2,
                (oriented.height - side) / 2,
                side,
                side
            )
            if (square !== oriented) oriented.recycle()
            try {
                val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
                val scale = targetSize.toFloat() / square.width.toFloat()
                val matrix = Matrix().apply { setScale(scale, scale) }
                Canvas(output).apply {
                    drawColor(Color.BLACK)
                    drawBitmap(square, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                return output
            } finally {
                if (square !== oriented) square.recycle()
                if (!oriented.isRecycled) oriented.recycle()
            }
        } catch (exception: Exception) {
            if (!oriented.isRecycled) oriented.recycle()
            throw exception
        }
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun trimCache(directory: File) {
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "jpg" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= TRIM_TARGET_BYTES) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
