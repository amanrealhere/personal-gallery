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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Performs durable, sequential file operations.
 *
 * Move prefers ATOMIC_MOVE. Copy-and-delete is used only when atomic rename is
 * unsupported or the filesystem reports a cross-device move.
 * Verification deliberately uses filename, byte length, and mtime to seconds;
 * no checksums are calculated.
 */
class MediaFileOperationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private lateinit var operationLog: File
    private var currentType = TYPE_COPY
    private var currentOperationId = ""

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return@withContext Result.failure()
        currentOperationId = operationId
        operationLog = File(applicationContext.filesDir, "media-operation-$operationId.log")
        val requestFile = File(applicationContext.filesDir, "media-operation-$operationId.json")
        if (!requestFile.isFile) return@withContext Result.failure()

        val request = try { JSONObject(requestFile.readText()) } catch (e: Exception) {
            log("ERROR", "Request file could not be read: ${e.message}")
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Request file could not be read"))
        }
        currentType = request.optString("type", TYPE_COPY)
        val type = currentType
        val destinationDirectory = File(request.optString("destination"))
        val sourcePaths = request.optJSONArray("sources") ?: JSONArray()
        if (!destinationDirectory.isDirectory || !destinationDirectory.canWrite()) {
            log("ERROR", "Destination is unavailable: ${destinationDirectory.absolutePath}")
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Destination album is unavailable"))
        }

        val total = sourcePaths.length()
        var processed = 0
        var succeeded = 0
        var failed = 0
        val briefErrors = mutableListOf<String>()
        log("START", "$type · $total files · Destination: ${destinationDirectory.absolutePath}")
        setForeground(createForegroundInfo(type, 0, total, "Preparing · $total files"))

        for (index in 0 until total) {
            if (isStopped) break
            val source = File(sourcePaths.optString(index))
            val action = if (type == TYPE_MOVE) "Move" else "Copy"
            publishProgress(processed, total, succeeded, failed, source.name, "Preparing $action")
            log("FILE", "$action started: ${source.absolutePath}")
            try {
                val result = if (type == TYPE_MOVE) moveOne(source, destinationDirectory, index, total, succeeded, failed)
                    else copyOne(source, destinationDirectory)
                if (result) {
                    succeeded++
                    log("SUCCESS", "$action completed: ${source.name}")
                } else {
                    failed++
                    log("FAIL", "$action returned false: ${source.name}")
                }
            } catch (exception: CancellationException) {
                log("INTERRUPTED", "Worker cancelled while processing ${source.name}")
                throw exception
            } catch (exception: Exception) {
                failed++
                val detail = "${source.name}: ${exception.message ?: exception.javaClass.simpleName}"
                briefErrors += detail
                log("FAIL", detail)
            }
            processed++
            publishProgress(processed, total, succeeded, failed, source.name, "$action processed")
        }

        val summary = "$succeeded succeeded, $failed failed, $processed of $total processed"
        log("SUMMARY", summary)
        // Keep WorkManager Data small. The full, potentially large operation log
        // remains in the app-private log file and is not limited to 10 KB.
        requestFile.delete()
        postCompletionNotification(type, summary)
        Result.success(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_SUMMARY to summary, KEY_LOG_PATH to operationLog.absolutePath,
            KEY_ERRORS to briefErrors.take(12).joinToString("\n").take(6000)
        ))
    }

    private fun copyOne(source: File, destinationDirectory: File, plannedDestination: File? = null): File {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val originalSize = source.length()
        val originalModifiedSeconds = source.lastModified() / 1000L
        val destination = plannedDestination ?: uniqueDestination(destinationDirectory, source.name)
        val expectedDestinationName = destination.name
        val temporary = File(destinationDirectory, ".personal-gallery-$currentOperationId-${UUID.randomUUID()}.tmp")
        log("COPY", "Copying to temporary file: ${temporary.name}; expected size=$originalSize; mtime(s)=$originalModifiedSeconds")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            if (temporary.length() != originalSize) throw IllegalStateException("Temporary size verification failed (expected $originalSize, got ${temporary.length()})")
            log("VERIFY", "Temporary size verified: ${temporary.length()} bytes")
            if (source.length() != originalSize || source.lastModified() / 1000L != originalModifiedSeconds) {
                throw IllegalStateException("Source changed while copying (size/mtime-to-seconds)")
            }
            val roundedMillis = originalModifiedSeconds * 1000L
            if (!temporary.setLastModified(roundedMillis)) throw IllegalStateException("Could not set destination mtime")
            if (temporary.length() != originalSize || temporary.lastModified() / 1000L != originalModifiedSeconds) {
                throw IllegalStateException("Temporary verification failed (size or mtime-to-seconds)")
            }
            log("VERIFY", "Temporary mtime verified to seconds: ${temporary.lastModified()/1000L}")
            Files.move(temporary.toPath(), destination.toPath())
            if (expectedDestinationName != source.name) {
                log("VERIFY", "Collision suffix accepted: $expectedDestinationName")
            }
            if (!destination.isFile || destination.length() != originalSize ||
                destination.lastModified() / 1000L != originalModifiedSeconds ||
                destination.name != expectedDestinationName
            ) {
                destination.delete()
                throw IllegalStateException("Published destination verification failed")
            }
            log("VERIFY", "Published file verified: name=${destination.name}; size=${destination.length()}; mtime(s)= ${destination.lastModified()/1000L}")
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveOne(source: File, destinationDirectory: File, processed: Int, total: Int, succeeded: Int, failed: Int): Boolean {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val originalSize = source.length()
        val originalModifiedSeconds = source.lastModified() / 1000L
        if (source.parentFile?.canonicalPath == destinationDirectory.canonicalPath) {
            log("SKIP", "Already in destination album; no move performed: ${source.name}")
            return false
        }
        val destination = uniqueDestination(destinationDirectory, source.name)
        val expectedDestinationName = destination.name
        log("MOVE", "Attempting atomic rename: ${source.name} → ${expectedDestinationName}")
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val valid = destination.isFile && destination.name == expectedDestinationName &&
                destination.length() == originalSize &&
                destination.lastModified() / 1000L == originalModifiedSeconds && !source.exists()
            log("VERIFY", "Atomic rename checks: name=${destination.name}; size=${destination.length() == originalSize}; mtime-seconds=${destination.lastModified()/1000L == originalModifiedSeconds}; source-absent=${!source.exists()}")
            if (valid) {
                log("METHOD", "Completed by atomic rename")
                return true
            }
            // Do not attempt copy/delete after a rename that succeeded but failed
            // post-verification: the source may already be gone.
            throw IllegalStateException("Atomic rename completed but verification failed; source/destination retained for inspection")
        } catch (e: AtomicMoveNotSupportedException) {
            log("FALLBACK", "Atomic rename unsupported; using copy-and-delete: ${e.message}")
        } catch (e: FileSystemException) {
            if (!isCrossDeviceOrAtomicUnsupported(e)) throw e
            log("FALLBACK", "Atomic rename unavailable for these filesystems; using copy-and-delete: ${e.message}")
        }

        publishProgress(processed, total, succeeded, failed, source.name, "Copying before deleting source")
        setForeground(createForegroundInfo(TYPE_MOVE, processed, total, "Fallback copy · ${source.name}"))
        val copied = copyOne(source, destinationDirectory, destination)
        val copiedValid = copied.name == expectedDestinationName && copied.length() == originalSize &&
            copied.lastModified() / 1000L == originalModifiedSeconds
        log("VERIFY", "Fallback destination checks: name=${copied.name == destination.name}; size=${copied.length() == originalSize}; mtime-seconds=${copied.lastModified()/1000L == originalModifiedSeconds}")
        if (!copiedValid) throw IllegalStateException("Destination verification failed; original retained")
        if (source.length() != originalSize || source.lastModified() / 1000L != originalModifiedSeconds) {
            throw IllegalStateException("Source changed before deletion; original retained")
        }
        log("DELETE", "Verified destination; deleting original source")
        if (!source.delete()) throw IllegalStateException("Destination verified; original could not be deleted")
        if (source.exists()) throw IllegalStateException("Original still exists after delete attempt")
        log("METHOD", "Completed by copy, verification, and source deletion")
        return true
    }

    private fun isCrossDeviceOrAtomicUnsupported(e: FileSystemException): Boolean {
        val reason = (e.reason ?: e.message ?: "").lowercase()
        return listOf("cross-device", "cross device", "exdev", "not same device", "atomic move not supported", "operation not supported").any { it in reason }
    }

    private fun publishProgress(processed: Int, total: Int, succeeded: Int, failed: Int, current: String, status: String) {
        val safeCurrent = current.take(160)
        setProgress(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_CURRENT to safeCurrent, KEY_STATUS to status.take(240),
            KEY_LOG_PATH to operationLog.absolutePath
        ))
        setForeground(createForegroundInfo(currentType, processed, total, "$status · $safeCurrent"))
    }

    private fun log(tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        operationLog.appendText("[$timestamp] [$tag] $message\n")
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
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$processed of $total items ($percent%)\nLog: ${operationLog.name}"))
            .setProgress(total.coerceAtLeast(1), processed, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun postCompletionNotification(type: String, summary: String) {
        createChannel()
        val title = if (type == TYPE_MOVE) "Move finished" else "Copy finished"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\nDetailed log: ${operationLog.name}"))
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
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
        const val KEY_LOG_PATH = "log_path"
        const val TYPE_COPY = "COPY"
        const val TYPE_MOVE = "MOVE"
        const val TAG = "personal-gallery-file-operation"
        private const val CHANNEL_ID = "personal-gallery-file-operations"
        private const val NOTIFICATION_ID = 4117
    }
}
