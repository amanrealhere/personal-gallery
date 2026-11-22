package com.amanrealhere.personalgallery.data.fileoperations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

class MediaFileOperationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return@withContext Result.failure()
        val requestFile = File(applicationContext.filesDir, "media-operation-$operationId.json")
        if (!requestFile.isFile) return@withContext Result.failure()

        val request = try {
            JSONObject(requestFile.readText())
        } catch (_: Exception) {
            requestFile.delete()
            return@withContext Result.failure()
        }
        val type = request.optString("type")
        val destinationDirectory = File(request.optString("destination"))
        val sourcePaths = request.optJSONArray("sources") ?: JSONArray()
        if (!destinationDirectory.isDirectory || !destinationDirectory.canWrite()) {
            requestFile.delete()
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Destination album is unavailable"))
        }

        val total = sourcePaths.length()
        var processed = 0
        var succeeded = 0
        var failed = 0
        val failureMessages = mutableListOf<String>()
        setForeground(createForegroundInfo(type, 0, total, "Preparing"))

        for (index in 0 until total) {
            if (isStopped) break
            val sourcePath = sourcePaths.optString(index)
            val source = File(sourcePath)
            setProgress(workDataOf(
                KEY_PROCESSED to processed,
                KEY_TOTAL to total,
                KEY_SUCCEEDED to succeeded,
                KEY_FAILED to failed,
                KEY_CURRENT to source.name,
                KEY_STATUS to (if (type == TYPE_MOVE) "Moving" else "Copying")
            ))
            setForeground(createForegroundInfo(type, processed, total, "${if (type == TYPE_MOVE) "Moving" else "Copying"} · ${source.name}"))
            try {
                val result = if (type == TYPE_MOVE) moveOne(source, destinationDirectory, processed, total, succeeded, failed)
                    else copyOne(source, destinationDirectory)
                if (result) succeeded++ else failed++
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failed++
                failureMessages += "${source.name}: ${exception.message ?: "Operation failed"}"
            }
            processed++
            val label = if (type == TYPE_MOVE) "Moving" else "Copying"
            setProgress(workDataOf(
                KEY_PROCESSED to processed,
                KEY_TOTAL to total,
                KEY_SUCCEEDED to succeeded,
                KEY_FAILED to failed,
                KEY_CURRENT to source.name,
                KEY_STATUS to (if (type == TYPE_MOVE) "Moving" else "Copying")
            ))
            setForeground(createForegroundInfo(type, processed, total, "$label · ${source.name}"))
        }

        requestFile.delete()
        val summary = "$succeeded succeeded, $failed failed"
        postCompletionNotification(type, summary)
        Result.success(workDataOf(
            KEY_PROCESSED to processed,
            KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded,
            KEY_FAILED to failed,
            KEY_SUMMARY to summary,
            KEY_ERRORS to failureMessages.joinToString("\n")
        ))
    }

    private fun copyOne(source: File, destinationDirectory: File): Boolean {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val originalSize = source.length()
        val originalModified = source.lastModified()
        val destination = uniqueDestination(destinationDirectory, source.name)
        val temporary = File(destinationDirectory, ".personal-gallery-${UUID.randomUUID()}.tmp")

        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            if (temporary.length() != originalSize) throw IllegalStateException("Copied size does not match")
            if (source.length() != originalSize || source.lastModified() != originalModified) {
                throw IllegalStateException("Source changed during copy")
            }
            if (!temporary.setLastModified(originalModified)) {
                throw IllegalStateException("Could not restore modification time")
            }
            if (temporary.length() != originalSize || temporary.lastModified() != originalModified) {
                throw IllegalStateException("Copied file verification failed")
            }
            Files.move(temporary.toPath(), destination.toPath())
            if (destination.length() != originalSize || destination.lastModified() != originalModified) {
                destination.delete()
                throw IllegalStateException("Published file verification failed")
            }
            return true
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun moveOne(source: File, destinationDirectory: File, processed: Int, total: Int, succeeded: Int, failed: Int): Boolean {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val originalSize = source.length()
        val originalModified = source.lastModified()
        if (source.parentFile?.canonicalPath == destinationDirectory.canonicalPath) return true
        val destination = uniqueDestination(destinationDirectory, source.name)

        val sameFileStore =
            Files.getFileStore(source.toPath()) == Files.getFileStore(destinationDirectory.toPath())

        if (sameFileStore) {
            try {
                Files.move(source.toPath(), destination.toPath())
                if (destination.isFile && destination.length() == originalSize &&
                    destination.lastModified() == originalModified && !source.exists()
                ) return true
                throw IllegalStateException("Moved file verification failed")
            } catch (exception: Exception) {
                // Same-filesystem failures are not treated as cross-filesystem moves.
                throw exception
            }
        }

        // Cross-filesystem fallback: copy, verify logical size and mtime, then delete source.
        setProgress(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_CURRENT to source.name, KEY_STATUS to "Different storage · Copying before deletion"
        ))
        setForeground(createForegroundInfo(type = TYPE_MOVE, processed = processed, total = total,
            status = "Different storage · Copying ${source.name} before deletion"))
        copyOne(source, destinationDirectory)
        val copied = File(destinationDirectory, destination.name)
        if (!copied.isFile || copied.length() != originalSize ||
            copied.lastModified() != originalModified
        ) throw IllegalStateException("Copied destination verification failed")
        if (source.length() != originalSize || source.lastModified() != originalModified) {
            throw IllegalStateException("Source changed before deletion")
        }
        if (!source.delete()) throw IllegalStateException("Destination verified; original could not be deleted")
        return !source.exists()
    }

    private fun uniqueDestination(directory: File, originalName: String): File {
        val original = File(directory, originalName)
        if (!original.exists()) return original
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: originalName.length
        val base = originalName.substring(0, dot)
        val extension = originalName.substring(dot)
        var index = 1
        while (true) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun createForegroundInfo(type: String, processed: Int, total: Int, status: String): ForegroundInfo {
        createChannel()
        val title = if (type == TYPE_MOVE) "Moving images" else "Copying images"
        val percent = if (total == 0) 0 else processed * 100 / total
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$processed of $total items ($percent%)"))
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

    private fun postCompletionNotification(type: String, summary: String) {
        createChannel()
        val title = if (type == TYPE_MOVE) "Move finished" else "Copy finished"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(summary)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "File operations", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    companion object {
        const val KEY_OPERATION_ID = "operation_id"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_SUCCEEDED = "succeeded"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT = "current"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERRORS = "errors"
        const val KEY_ERROR = "error"
        const val KEY_STATUS = "status"
        const val TYPE_COPY = "COPY"
        const val TYPE_MOVE = "MOVE"
        const val TAG = "personal-gallery-file-operation"
        private const val CHANNEL_ID = "personal-gallery-file-operations"
        private const val NOTIFICATION_ID = 4117
    }
}
