package com.amanrealhere.personalgallery.data.thumbnail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.amanrealhere.personalgallery.R
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.data.local.GalleryDatabase
import com.amanrealhere.personalgallery.data.local.ThumbnailGenerationEntity
import com.amanrealhere.personalgallery.data.local.ThumbnailRecordEntity
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ThumbnailGenerationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val database: GalleryDatabase by lazy {
        GalleryDatabase.getInstance(applicationContext)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val requestedAlbumId = inputData.getString(KEY_ALBUM_ID)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val albums = if (requestedAlbumId == null) {
            database.albumDao().observeAll().let { flow ->
                kotlinx.coroutines.flow.first(flow)
            }
        } else {
            listOfNotNull(database.albumDao().findById(requestedAlbumId))
        }

        val generationId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val dao = database.thumbnailDao()
        dao.insertGeneration(
            ThumbnailGenerationEntity(
                id = generationId,
                albumId = requestedAlbumId,
                createdAtEpochMillis = now,
                completedAtEpochMillis = null,
                status = STATUS_RUNNING,
                totalCount = 0,
                processedCount = 0,
                successCount = 0,
                failureCount = 0
            )
        )

        var processed = 0
        var successes = 0
        var failures = 0
        val albumFiles = albums.map { album ->
            album to discoverImages(File(album.canonicalPath))
        }
        val total = albumFiles.sumOf { (_, images) -> images.size + if (images.isNotEmpty()) 1 else 0 }
        dao.setTotal(generationId, total)
        setProgress(workDataOfProgress(0, total, 0))

        try {
            setForeground(createForegroundInfo("Preparing thumbnails", 0, total))
            for ((album, images) in albumFiles) {
                if (isStopped) throw CancellationException("Generation stopped")
                val albumRoot = File(album.canonicalPath)
                if (!albumRoot.isDirectory || !albumRoot.canRead()) {
                    failures++
                    processed++
                    dao.updateProgress(generationId, processed, successes, failures)
                    continue
                }

                val albumOutput = GalleryStorage.getInstance(applicationContext)
                    .albumThumbnailDirectory(generationId, album.id)
                val regularDirectory = File(albumOutput, "regular")
                val coverDirectory = File(albumOutput, "cover")
                regularDirectory.mkdirs()
                coverDirectory.mkdirs()

                val oldState = dao.albumState(album.id)
                val oldGeneration = oldState?.activeGenerationId
                var latestCoverPath: String? = null

                for ((index, source) in images.withIndex()) {
                    if (isStopped) throw CancellationException("Generation stopped")
                    val output = File(regularDirectory, source.name + ".jpg")
                    val record = createThumbnailRecord(
                        source = source,
                        destination = output,
                        albumId = album.id,
                        generationId = generationId,
                        kind = KIND_REGULAR,
                        size = REGULAR_SIZE,
                        quality = REGULAR_QUALITY,
                        oldGenerationId = oldGeneration,
                        force = force
                    )
                    dao.insertRecords(listOf(record))
                    if (record.status == STATUS_SUCCESS || record.status == STATUS_REUSED) successes++ else failures++
                    processed++
                    if (index == 0) {
                        val coverOutput = File(coverDirectory, source.name + ".jpg")
                        val coverRecord = createThumbnailRecord(
                            source = source,
                            destination = coverOutput,
                            albumId = album.id,
                            generationId = generationId,
                            kind = KIND_COVER,
                            size = COVER_SIZE,
                            quality = COVER_QUALITY,
                            oldGenerationId = oldGeneration,
                            force = force
                        )
                        dao.insertRecords(listOf(coverRecord))
                        latestCoverPath = if (coverRecord.status == STATUS_SUCCESS ||
                            coverRecord.status == STATUS_REUSED
                        ) coverRecord.outputPath else null
                        if (coverRecord.status == STATUS_SUCCESS ||
                            coverRecord.status == STATUS_REUSED
                        ) successes++ else failures++
                        processed++
                    }
                    if (processed % 8 == 0 || processed == total) {
                        dao.updateProgress(generationId, processed, successes, failures)
                        setProgress(workDataOfProgress(processed, total, failures))
                        setForeground(
                            createForegroundInfo(
                                "Creating thumbnails",
                                processed,
                                total,
                                failures
                            )
                        )
                    }
                }

                dao.setAlbumState(
                    AlbumThumbnailStateEntity(
                        albumId = album.id,
                        activeGenerationId = generationId,
                        coverThumbnailPath = latestCoverPath,
                        imageCount = images.size,
                        latestImageModifiedAtEpochMillis = images.firstOrNull()?.lastModified()
                    )
                )
            }

            dao.updateProgress(generationId, processed, successes, failures)
            dao.finishGeneration(
                generationId,
                if (failures == 0) STATUS_COMPLETE else STATUS_COMPLETE_WITH_ERRORS,
                System.currentTimeMillis()
            )
            setProgress(workDataOfProgress(processed, total, failures))
            Result.success()
        } catch (cancelled: CancellationException) {
            dao.finishGeneration(generationId, STATUS_INTERRUPTED, System.currentTimeMillis())
            throw cancelled
        } catch (exception: Exception) {
            dao.finishGeneration(generationId, STATUS_FAILED, System.currentTimeMillis())
            Result.failure(
                androidx.work.workDataOf("error" to (exception.message ?: "Thumbnail generation failed"))
            )
        }
    }

    private suspend fun createThumbnailRecord(
        source: File,
        destination: File,
        albumId: String,
        generationId: String,
        kind: String,
        size: Int,
        quality: Int,
        oldGenerationId: String?,
        force: Boolean
    ): ThumbnailRecordEntity {
        val canonical = try {
            source.canonicalPath
        } catch (_: Exception) {
            source.absolutePath
        }
        val metadata = SourceMetadata(
            canonicalPath = canonical,
            name = source.name,
            size = source.length(),
            modified = source.lastModified()
        )

        if (!force && oldGenerationId != null) {
            val old = database.thumbnailDao().findRecord(
                oldGenerationId,
                albumId,
                source.absolutePath,
                kind
            )
            if (old != null &&
                old.status in setOf(STATUS_SUCCESS, STATUS_REUSED) &&
                old.sourceCanonicalPath == metadata.canonicalPath &&
                old.sourceName == metadata.name &&
                old.sourceSizeBytes == metadata.size &&
                old.sourceModifiedAtEpochMillis == metadata.modified &&
                File(old.outputPath).isFile && File(old.outputPath).length() > 0L
            ) {
                return try {
                    destination.parentFile?.mkdirs()
                    val temporary = File(destination.parentFile, destination.name + ".tmp")
                    File(old.outputPath).inputStream().use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(destination)) {
                        temporary.delete()
                        throw IllegalStateException("Could not atomically place cached thumbnail")
                    }
                    metadata.toRecord(generationId, albumId, source, kind, destination, STATUS_REUSED)
                } catch (exception: Exception) {
                    renderRecord(source, destination, albumId, generationId, kind, size, quality, metadata, exception)
                }
            }
        }

        return renderRecord(source, destination, albumId, generationId, kind, size, quality, metadata, null)
    }

    private fun renderRecord(
        source: File,
        destination: File,
        albumId: String,
        generationId: String,
        kind: String,
        size: Int,
        quality: Int,
        metadata: SourceMetadata,
        previousError: Exception?
    ): ThumbnailRecordEntity {
        return try {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            temporary.delete()
            val bitmap = decodeThumbnail(source, size)
                ?: throw IllegalArgumentException("Unsupported or unreadable image")
            try {
                FileOutputStream(temporary).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                        "JPEG encoding failed"
                    }
                    stream.fd.sync()
                }
            } finally {
                bitmap.recycle()
            }
            check(temporary.renameTo(destination)) {
                temporary.delete()
                "Could not atomically place thumbnail"
            }
            metadata.toRecord(generationId, albumId, source, kind, destination, STATUS_SUCCESS)
        } catch (exception: Exception) {
            destination.delete()
            metadata.toRecord(
                generationId, albumId, source, kind, destination,
                STATUS_FAILED,
                previousError?.message?.let { "$it; " } .orEmpty() + (exception.message ?: "Image processing failed")
            )
        }
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

        val side = minOf(oriented.width, oriented.height)
        val left = (oriented.width - side) / 2
        val top = (oriented.height - side) / 2
        val square = Bitmap.createBitmap(oriented, left, top, side, side)
        if (square !== oriented) oriented.recycle()

        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.BLACK)
            val scale = targetSize.toFloat() / side.toFloat()
            val matrix = Matrix().apply {
                setScale(scale, scale)
            }
            drawBitmap(square, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
        square.recycle()
        return output
    }

    private fun applyExifOrientation(source: File, bitmap: Bitmap): Bitmap {
        val orientation = try {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
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

    private fun discoverImages(folder: File): List<File> =
        folder.listFiles()?.asSequence()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedWith(
                compareByDescending<File> { it.lastModified() }
                    .thenBy { it.absolutePath }
            )
            ?.toList()
            .orEmpty()

    private fun createForegroundInfo(
        message: String,
        processed: Int,
        total: Int,
        failures: Int = 0
    ): ForegroundInfo {
        createNotificationChannel()
        val percent = if (total == 0) 100 else (processed * 100 / total).coerceIn(0, 100)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Personal Gallery")
            .setContentText("$message: $processed / $total ($percent%)")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$message\n$processed of $total thumbnails ($percent%)\nFailures: $failures"
            ))
            .setProgress(total.coerceAtLeast(1), processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Thumbnail generation",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun SourceMetadata.toRecord(
        generationId: String,
        albumId: String,
        source: File,
        kind: String,
        destination: File,
        status: String,
        error: String? = null
    ) = ThumbnailRecordEntity(
        generationId = generationId,
        albumId = albumId,
        sourcePath = source.absolutePath,
        sourceName = name,
        sourceSizeBytes = size,
        sourceModifiedAtEpochMillis = modified,
        sourceCanonicalPath = canonicalPath,
        kind = kind,
        outputPath = destination.absolutePath,
        status = status,
        errorMessage = error
    )

    private data class SourceMetadata(
        val canonicalPath: String,
        val name: String,
        val size: Long,
        val modified: Long
    )

    private fun workDataOfProgress(processed: Int, total: Int, failures: Int) =
        androidx.work.workDataOf(
            KEY_PROGRESS_PROCESSED to processed,
            KEY_PROGRESS_TOTAL to total,
            KEY_PROGRESS_FAILURES to failures
        )

    companion object {
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_FORCE = "force"
        const val KEY_PROGRESS_PROCESSED = "progress_processed"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_PROGRESS_FAILURES = "progress_failures"

        const val KIND_REGULAR = "regular"
        const val KIND_COVER = "cover"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_REUSED = "REUSED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_COMPLETE = "COMPLETED"
        const val STATUS_COMPLETE_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
        const val STATUS_INTERRUPTED = "INTERRUPTED"
        const val STATUS_FAILED_GENERATION = "FAILED"
        const val REGULAR_SIZE = 256
        const val REGULAR_QUALITY = 85
        const val COVER_SIZE = 512
        const val COVER_QUALITY = 90

        private const val CHANNEL_ID = "thumbnail-generation"
        private const val NOTIFICATION_ID = 4107
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
