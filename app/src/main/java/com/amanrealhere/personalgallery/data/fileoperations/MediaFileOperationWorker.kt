package com.amanrealhere.personalgallery.data.fileoperations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
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
 * Durable sequential Copy/Move worker.
 *
 * Move first attempts ATOMIC_MOVE. Copy-and-delete is used only when atomic
 * rename is unsupported or the filesystem reports a cross-device move.
 * Verification is limited to destination name, size, and modification time at
 * whole-second precision. No checksums are calculated.
 */
class MediaFileOperationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private lateinit var operationLog: File
    private lateinit var stateFile: File
    private var currentType = TYPE_COPY
    private var currentOperationId = ""
    private var operationState = JSONObject()
    private var plans = JSONObject()
    private var completed = JSONObject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return@withContext Result.failure()
        currentOperationId = operationId
        operationLog = File(applicationContext.filesDir, "media-operation-$operationId.log")
        stateFile = File(applicationContext.filesDir, "media-operation-$operationId.state.json")
        val requestFile = File(applicationContext.filesDir, "media-operation-$operationId.json")
        if (!requestFile.isFile) {
            log("ERROR", "Request file missing. Persistent log: ${operationLog.name}")
            return@withContext Result.failure()
        }

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

        operationState = try { if (stateFile.isFile) JSONObject(stateFile.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        plans = operationState.optJSONObject("plans") ?: JSONObject()
        completed = operationState.optJSONObject("completed") ?: JSONObject()
        persistState()

        val total = sourcePaths.length()
        var processed = 0
        var succeeded = 0
        var failed = 0
        val briefErrors = mutableListOf<String>()
        log("START", "$type · $total files · Destination: ${destinationDirectory.absolutePath}")
        cleanupAbandonedTemporaryFiles(destinationDirectory)
        setForeground(createForegroundInfo(type, 0, total, "Preparing · $total files"))

        for (index in 0 until total) {
            if (isStopped) break
            val sourcePath = sourcePaths.optString(index)
            val source = File(sourcePath)
            val action = if (type == TYPE_MOVE) "Move" else "Copy"
            publishProgress(processed, total, succeeded, failed, source.name, "Preparing $action")
            log("FILE", "$action started: ${source.absolutePath}")
            try {
                var plan = plans.optJSONObject(sourcePath)
                if (plan == null) {
                    val expectedSize = source.takeIf { it.isFile }?.length() ?: 0L
                    val expectedMtimeSeconds = source.takeIf { it.isFile }?.lastModified()?.div(1000L) ?: 0L
                    plan = JSONObject()
                        .put("destination", uniqueDestination(destinationDirectory, source.name).absolutePath)
                        .put("size", expectedSize)
                        .put("mtimeSeconds", expectedMtimeSeconds)
                    plans.put(sourcePath, plan)
                    persistState()
                    log("PLAN", "Reserved destination ${plan.getString("destination")} · size=$expectedSize · mtime(s)=$expectedMtimeSeconds")
                }
                val destination = File(plan.getString("destination"))
                val expectedSize = plan.optLong("size", -1L)
                val expectedMtimeSeconds = plan.optLong("mtimeSeconds", -1L)

                val completedRecord = completed.optJSONObject(sourcePath)
                if (completedRecord != null) {
                    val recordedDestination = File(completedRecord.optString("destination", destination.absolutePath))
                    val recordedSize = completedRecord.optLong("size", expectedSize)
                    val recordedMtime = completedRecord.optLong("mtimeSeconds", expectedMtimeSeconds)
                    val validExisting = verifyDestination(recordedDestination, recordedDestination.name, recordedSize, recordedMtime)
                    val sourceStateValid = type != TYPE_MOVE || !source.exists()
                    if (validExisting && sourceStateValid) {
                        succeeded++
                        log("RESUME", "Previously completed item verified and skipped: ${recordedDestination.name}")
                        processed++
                        publishProgress(processed, total, succeeded, failed, source.name, "Verified completed item")
                        continue
                    }
                    completed.remove(sourcePath)
                    persistState()
                    log("RECOVERY", "Prior completion record invalid; reprocessing ${source.name}")
                }

                // Recover a file published before the worker was interrupted before
                // it could persist its completion record. The destination was
                // planned and persisted before the file operation began.
                if (destination.exists() && verifyDestination(destination, destination.name, expectedSize, expectedMtimeSeconds)) {
                    if (type == TYPE_MOVE && source.exists()) {
                        if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
                            throw IllegalStateException("Source changed since the operation was planned; source retained during recovery")
                        }
                        log("RECOVERY", "Verified planned destination from an interrupted Move; source still matches planned size/mtime, removing source")
                        if (!source.delete() || source.exists()) throw IllegalStateException("Verified destination but could not delete source during recovery")
                    }
                    markCompleted(sourcePath, destination, expectedSize, expectedMtimeSeconds, "recovered")
                    succeeded++
                    processed++
                    publishProgress(processed, total, succeeded, failed, source.name, "Recovered completed file")
                    continue
                }
                if (destination.exists()) {
                    log("PLAN", "Planned destination is occupied by a different file; reserving a new collision-safe name")
                    plan.put("destination", uniqueDestination(destinationDirectory, source.name).absolutePath)
                    plans.put(sourcePath, plan)
                    persistState()
                }

                val result = if (type == TYPE_MOVE) {
                    moveOne(source, destinationDirectory, plan.getString("destination"), expectedSize, expectedMtimeSeconds, index, total, succeeded, failed)
                } else {
                    copyOne(source, destinationDirectory, plan.getString("destination"), expectedSize, expectedMtimeSeconds)
                }
                if (result) {
                    val finalDestination = File(plan.getString("destination"))
                    markCompleted(sourcePath, finalDestination, expectedSize, expectedMtimeSeconds, "verified")
                    succeeded++
                    log("SUCCESS", "$action completed: ${source.name} → ${finalDestination.name}")
                } else {
                    failed++
                    log("FAIL", "$action did not complete: ${source.name}")
                }
            } catch (exception: CancellationException) {
                log("INTERRUPTED", "Worker cancelled while processing ${source.name}; saved operation state remains for recovery")
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
        requestFile.delete()
        postCompletionNotification(type, summary)
        // WorkManager Data remains bounded. Full details are retained in the
        // operation log and persistent per-file state JSON, outside its 10 KB cap.
        Result.success(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_SUMMARY to summary, KEY_LOG_PATH to operationLog.absolutePath
        ))
    }

    private fun copyOne(
        source: File,
        destinationDirectory: File,
        destinationPath: String,
        expectedSize: Long,
        expectedMtimeSeconds: Long
    ): File {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val destination = File(destinationPath)
        val temporary = File(destinationDirectory, ".personal-gallery-$currentOperationId-${UUID.randomUUID()}.tmp")
        log("COPY", "Copying to temporary file: ${temporary.name}; expected name=${destination.name}; size=$expectedSize; mtime(s)=$expectedMtimeSeconds")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            if (temporary.length() != expectedSize) throw IllegalStateException("Temporary size verification failed (expected $expectedSize, got ${temporary.length()})")
            log("VERIFY", "Temporary size verified: ${temporary.length()} bytes")
            if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
                throw IllegalStateException("Source changed while copying (size/mtime-to-seconds)")
            }
            if (!temporary.setLastModified(expectedMtimeSeconds * 1000L)) throw IllegalStateException("Could not set destination mtime")
            if (!verifyDestination(temporary, temporary.name, expectedSize, expectedMtimeSeconds)) {
                throw IllegalStateException("Temporary file verification failed (size or mtime-to-seconds)")
            }
            log("VERIFY", "Temporary mtime verified to seconds: ${temporary.lastModified()/1000L}")
            Files.move(temporary.toPath(), destination.toPath())
            if (!verifyDestination(destination, destination.name, expectedSize, expectedMtimeSeconds) ||
                destination.name != File(destinationPath).name
            ) {
                throw IllegalStateException("Published destination verification failed")
            }
            log("VERIFY", "Published destination verified: name=${destination.name}; size=${destination.length()}; mtime(s)= ${destination.lastModified()/1000L}")
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveOne(
        source: File,
        destinationDirectory: File,
        destinationPath: String,
        expectedSize: Long,
        expectedMtimeSeconds: Long,
        processed: Int,
        total: Int,
        succeeded: Int,
        failed: Int
    ): Boolean {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
            throw IllegalStateException("Source changed since the operation was planned")
        }
        val destination = File(destinationPath)
        val expectedDestinationName = destination.name
        if (source.parentFile?.canonicalPath == destinationDirectory.canonicalPath) {
            log("SKIP", "Already in destination album; no move performed: ${source.name}")
            return false
        }
        log("MOVE", "Attempting atomic rename: ${source.name} → $expectedDestinationName")
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val valid = verifyDestination(destination, expectedDestinationName, expectedSize, expectedMtimeSeconds) && !source.exists()
            log("VERIFY", "Atomic rename checks: name=${destination.name == expectedDestinationName}; size=${destination.length() == expectedSize}; mtime-seconds=${destination.lastModified()/1000L == expectedMtimeSeconds}; source-absent=${!source.exists()}")
            if (valid) {
                log("METHOD", "Completed by atomic rename")
                return true
            }
            throw IllegalStateException("Atomic rename completed but verification failed; no fallback was attempted to avoid risking a second copy")
        } catch (e: AtomicMoveNotSupportedException) {
            log("FALLBACK", "Atomic rename unsupported; using copy-and-delete: ${e.message}")
        } catch (e: FileSystemException) {
            if (!isCrossDeviceOrAtomicUnsupported(e) && !differentStorageVolumes(source, destinationDirectory)) throw e
            log("FALLBACK", "Atomic rename unavailable for these filesystems; using copy-and-delete: ${e.message}")
        }

        publishProgress(processed, total, succeeded, failed, source.name, "Copying before deleting source")
        setForeground(createForegroundInfo(TYPE_MOVE, processed, total, "Fallback copy · ${source.name}"))
        val copied = copyOne(source, destinationDirectory, destinationPath, expectedSize, expectedMtimeSeconds)
        if (!verifyDestination(copied, expectedDestinationName, expectedSize, expectedMtimeSeconds)) {
            throw IllegalStateException("Destination verification failed; original retained")
        }
        if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
            throw IllegalStateException("Source changed before deletion; original retained")
        }
        log("DELETE", "Destination name/size/mtime verified; deleting original source")
        if (!source.delete() || source.exists()) throw IllegalStateException("Destination verified; original could not be deleted")
        log("METHOD", "Completed by copy, verification, and source deletion")
        return true
    }

    private fun verifyDestination(file: File, expectedName: String, expectedSize: Long, expectedMtimeSeconds: Long): Boolean {
        val nameOk = file.name == expectedName
        val sizeOk = file.isFile && file.length() == expectedSize
        val mtimeOk = file.isFile && file.lastModified() / 1000L == expectedMtimeSeconds
        log("VERIFY", "name=${file.name} (ok=$nameOk); size=${file.length()} (ok=$sizeOk); mtime-to-seconds=${file.lastModified()/1000L} (ok=$mtimeOk)")
        return nameOk && sizeOk && mtimeOk
    }

    private fun markCompleted(sourcePath: String, destination: File, size: Long, mtimeSeconds: Long, method: String) {
        completed.put(sourcePath, JSONObject()
            .put("destination", destination.absolutePath)
            .put("size", size)
            .put("mtimeSeconds", mtimeSeconds)
            .put("method", method))
        persistState()
    }

    private fun persistState() {
        operationState.put("plans", plans)
        operationState.put("completed", completed)
        val temporaryState = File(stateFile.parentFile, stateFile.name + ".tmp")
        FileOutputStream(temporaryState).use { output ->
            output.write(operationState.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporaryState.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryState.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isCrossDeviceOrAtomicUnsupported(e: FileSystemException): Boolean {
        val reason = (e.reason ?: e.message ?: "").lowercase()
        return listOf(
            "cross-device", "cross device", "exdev", "not same device",
            "invalid cross-device link", "atomic move not supported", "operation not supported"
        ).any { it in reason }
    }

    /**
     * Android's Files.getFileStore() is restricted and cannot be used as a
     * reliable volume test. Infer common shared-storage volume roots only after
         * ATOMIC_MOVE itself has failed; this is used solely to decide whether
         copy-and-delete is an appropriate fallback.
     */
    private fun differentStorageVolumes(source: File, destination: File): Boolean {
        fun volumeRoot(file: File): String? {
            val path = runCatching { file.canonicalPath }.getOrNull() ?: return null
            val primary = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull()
            if (primary != null && (path == primary || path.startsWith(primary.trimEnd('/') + "/"))) return primary
            if (path.startsWith("/storage/")) {
                val volume = path.removePrefix("/storage/").substringBefore('/')
                if (volume.isNotBlank() && volume != "self") return "/storage/$volume"
            }
            return null
        }
        val sourceRoot = volumeRoot(source)
        val destinationRoot = volumeRoot(destination)
        return sourceRoot != null && destinationRoot != null && sourceRoot != destinationRoot
    }

    private fun publishProgress(processed: Int, total: Int, succeeded: Int, failed: Int, current: String, status: String) {
        setProgress(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_CURRENT to current.take(160), KEY_STATUS to status.take(240),
            KEY_LOG_PATH to operationLog.absolutePath, KEY_LOG_PREVIEW to logPreview()
        ))
        setForeground(createForegroundInfo(currentType, processed, total, "$status · ${current.take(160)}"))
    }

    private fun logPreview(): String = try {
        operationLog.readLines().takeLast(8).joinToString("\n").takeLast(1800)
    } catch (_: Exception) {
        "Detailed operation log is being written."
    }

    private fun log(tag: String, message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        operationLog.appendText("[$timestamp] [$tag] $message\n")
    }

    private fun cleanupAbandonedTemporaryFiles(directory: File) {
        val prefix = ".personal-gallery-"
        val leftovers = directory.listFiles()?.filter {
            it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".tmp")
        }.orEmpty()
        if (leftovers.isEmpty()) return
        var deleted = 0
        for (file in leftovers) {
            if (file.delete() || !file.exists()) deleted++
            else log("CLEANUP", "Could not remove abandoned temporary file: ${file.name}")
        }
        log("CLEANUP", "Removed $deleted abandoned temporary file(s) from destination")
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
        val details = logPreview()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$processed of $total items ($percent%)\n\n$details"))
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
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n\n${logPreview()}\nFull log: ${operationLog.name}"))
            .setAutoCancel(false)
            .setOngoing(false)
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
        const val KEY_LOG_PREVIEW = "log_preview"
        const val TYPE_COPY = "COPY"
        const val TYPE_MOVE = "MOVE"
        const val TAG = "personal-gallery-file-operation"
        private const val CHANNEL_ID = "personal-gallery-file-operations"
        private const val NOTIFICATION_ID = 4117
    }
}
