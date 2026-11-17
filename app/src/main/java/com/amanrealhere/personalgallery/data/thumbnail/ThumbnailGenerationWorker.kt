package com.amanrealhere.personalgallery.data.thumbnail

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
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.data.local.GalleryDatabase
import com.amanrealhere.personalgallery.data.local.ThumbnailGenerationEntity
import com.amanrealhere.personalgallery.data.local.ThumbnailRecordEntity
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            database.albumDao().observeAll().first()
        } else {
            listOfNotNull(database.albumDao().findById(requestedAlbumId))
        }

        val generationId = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date()) + "-" + UUID.randomUUID().toString().take(8)
        val scopeLabel = if (requestedAlbumId == null) {
            "All albums"
        } else {
            "Album: ${albums.firstOrNull()?.name ?: requestedAlbumId}"
        }
        val now = System.currentTimeMillis()
        val dao = database.thumbnailDao()
        dao.markStaleGenerationsInterrupted(now)
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
        var lastProgressUpdateAt = System.currentTimeMillis()
        val albumFiles = albums.map { album ->
            album to discoverImages(File(album.canonicalPath))
        }
        val total = albumFiles.sumOf { (album, images) ->
            images.size + if (images.isNotEmpty()) 1 else if (
                !File(album.canonicalPath).isDirectory || !File(album.canonicalPath).canRead()
            ) 1 else 0
        }
        val pendingStates = mutableListOf<AlbumThumbnailStateEntity>()
        val pendingRecords = ArrayList<ThumbnailRecordEntity>(64)
        dao.setTotal(generationId, total)
        setProgress(workDataOfProgress(0, total, 0))

        try {
            setForeground(createForegroundInfo("$scopeLabel · Preparing thumbnails", 0, total))
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
                val oldRecordsByKey = if (!force && oldGeneration != null) {
                    dao.records(oldGeneration, album.id).associateBy { it.sourcePath to it.kind }
                } else {
                    emptyMap()
                }
                var latestCoverPath: String? = null
                var albumFailures = 0

                for ((index, source) in images.withIndex()) {
                    if (isStopped) throw CancellationException("Generation stopped")
                    val relativeSourcePath = source.relativeTo(albumRoot).path
                    val output = File(regularDirectory, relativeSourcePath + ".jpg")
                    val record = createThumbnailRecord(
                        source = source,
                        destination = output,
                        albumId = album.id,
                        generationId = generationId,
                        kind = KIND_REGULAR,
                        size = REGULAR_SIZE,
                        quality = REGULAR_QUALITY,
                        oldRecord = oldRecordsByKey[source.absolutePath to KIND_REGULAR],
                        force = force
                    )
                    pendingRecords.add(record)
                    if (record.status == STATUS_SUCCESS || record.status == STATUS_REUSED) {
                        successes++
                    } else {
                        failures++
                        albumFailures++
                    }
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
                            oldRecord = oldRecordsByKey[source.absolutePath to KIND_COVER],
                            force = force
                        )
                        pendingRecords.add(coverRecord)
                        latestCoverPath = if (coverRecord.status == STATUS_SUCCESS ||
                            coverRecord.status == STATUS_REUSED
                        ) coverRecord.outputPath else null
                        if (coverRecord.status == STATUS_SUCCESS ||
                            coverRecord.status == STATUS_REUSED
                        ) {
                            successes++
                        } else {
                            failures++
                            albumFailures++
                        }
                        processed++
                    }
                    if (pendingRecords.size >= 64) {
                        dao.insertRecords(pendingRecords.toList())
                        pendingRecords.clear()
                    }
                    val progressTime = System.currentTimeMillis()
                    if (processed % 25 == 0 || processed == total ||
                        progressTime - lastProgressUpdateAt >= 1_000L
                    ) {
                        dao.updateProgress(generationId, processed, successes, failures)
                        setProgress(workDataOfProgress(processed, total, failures))
                        lastProgressUpdateAt = progressTime
                        setForeground(
                            createForegroundInfo(
                                "$scopeLabel · Creating thumbnails",
                                processed,
                                total,
                                failures
                            )
                        )
                    }
                }

                // Keep the previous complete album generation active if any item failed.
                if (albumFailures == 0) {
                    pendingStates.add(
                        AlbumThumbnailStateEntity(
                            albumId = album.id,
                            activeGenerationId = generationId,
                            coverThumbnailPath = latestCoverPath,
                            imageCount = images.size,
                            latestImageModifiedAtEpochMillis = images.firstOrNull()?.lastModified()
                        )
                    )
                }
            }

            if (pendingRecords.isNotEmpty()) {
                dao.insertRecords(pendingRecords)
            }
            dao.updateProgress(generationId, processed, successes, failures)
            setProgress(workDataOfProgress(processed, total, failures))
            // Switch every affected album and mark the generation complete atomically.
            dao.activateGeneration(
                generationId,
                if (failures == 0) STATUS_COMPLETE else STATUS_COMPLETE_WITH_ERRORS,
                System.currentTimeMillis(),
                pendingStates
            )
            runCatching {
                postCompletionNotification(scopeLabel, processed, total, failures)
            }
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

    private fun createThumbnailRecord(
        source: File,
        destination: File,
        albumId: String,
        generationId: String,
        kind: String,
        size: Int,
        quality: Int,
        oldRecord: ThumbnailRecordEntity?,
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

        if (!force && oldRecord != null &&
            oldRecord.status in setOf(STATUS_SUCCESS, STATUS_REUSED) &&
            oldRecord.sourceCanonicalPath == metadata.canonicalPath &&
            oldRecord.sourceName == metadata.name &&
            oldRecord.sourceSizeBytes == metadata.size &&
            oldRecord.sourceModifiedAtEpochMillis == metadata.modified &&
            isValidThumbnail(File(oldRecord.outputPath), size)
        ) {
            val temporary = File(destination.parentFile, destination.name + ".tmp")
            return try {
                destination.parentFile?.mkdirs()
                temporary.delete()
                File(oldRecord.outputPath).inputStream().use { input ->
                        FileOutputStream(temporary).use { output -> input.copyTo(output) }
                    }
                    check(isValidThumbnail(temporary, size)) { "Cached thumbnail copy is invalid" }
                    check(temporary.renameTo(destination)) {
                        "Could not atomically place cached thumbnail"
                    }
                    metadata.toRecord(generationId, albumId, source, kind, destination, STATUS_REUSED)
                } catch (exception: Exception) {
                    temporary.delete()
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
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        return try {
            destination.parentFile?.mkdirs()
            temporary.delete()
            val bitmap = decodeThumbnail(source, size)
                ?: throw IllegalArgumentException("Unsupported or unreadable image")
            try {
                check(source.isFile && source.length() == metadata.size &&
                    source.lastModified() == metadata.modified) {
                    "Source image changed while its thumbnail was being generated"
                }
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
            temporary.delete()
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

    private fun isValidThumbnail(file: File, expectedSize: Int): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth == expectedSize && options.outHeight == expectedSize
    }

    private fun discoverImages(folder: File): List<File> =
        if (!folder.isDirectory || !folder.canRead()) {
            emptyList()
        } else {
            folder.walkTopDown()
                .onEnter { it.name != ".personal-gallery" }
                .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .sortedWith(
                    compareByDescending<File> { it.lastModified() }
                        .thenBy { it.absolutePath }
                )
                .toList()
        }

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

    private fun postCompletionNotification(scopeLabel: String, processed: Int, total: Int, failures: Int) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Personal Gallery")
            .setContentText(
                if (failures == 0) "$scopeLabel · Complete: $processed thumbnails"
                else "$scopeLabel · Finished with $failures failures"
            )
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notification)
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
