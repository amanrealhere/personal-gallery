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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.FileSystemException
import java.util.UUID

class MediaFileOperationWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString(KEY_OPERATION_ID) ?: return@withContext Result.failure()
        val requestFile = File(applicationContext.filesDir, "media-operation-$operationId.json")
        if (!requestFile.isFile) return@withContext Result.failure()
        val request = try { JSONObject(requestFile.readText()) } catch (e: Exception) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Invalid operation request: ${e.message}"))
        }
        val type = request.optString("type")
        val destinationDirectory = File(request.optString("destination"))
        val sources = request.optJSONArray("sources") ?: JSONArray()
        if (type !in setOf(TYPE_COPY, TYPE_MOVE) || !destinationDirectory.isDirectory || !destinationDirectory.canWrite()) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "Destination or operation is invalid"))
        }

        val logDirectory = File(applicationContext.filesDir, "media-operation-logs").apply { mkdirs() }
        val logFile = File(logDirectory, "$operationId.log")
        val journalFile = File(applicationContext.filesDir, "media-operation-$operationId-journal.json")
        val journal = readJournal(journalFile)
        val completed = journal.optJSONObject("completed") ?: JSONObject().also { journal.put("completed", it) }
        val total = sources.length()
        var processed = 0
        var succeeded = 0
        var failed = 0
        val errorSummary = mutableListOf<String>()
        appendLog(logFile, "=== $type operation $operationId ===\nDestination: ${destinationDirectory.canonicalPath}\nTotal sources: $total\n")

        setForeground(createForegroundInfo(type, operationId, 0, total, "Preparing", logFile))
        for (index in 0 until total) {
            if (isStopped) break
            val sourcePath = sources.optString(index)
            val source = File(sourcePath)
            val actionLabel = if (type == TYPE_MOVE) "Moving" else "Copying"
            setProgress(progressData(processed, total, succeeded, failed, source.name, actionLabel, logFile))
            setForeground(createForegroundInfo(type, operationId, processed, total, "$actionLabel · ${source.name}", logFile))
            try {
                val priorEntry = completed.optJSONObject(sourcePath)
                val priorDestination = priorEntry?.optString("destination")?.takeIf { it.isNotBlank() }?.let(::File)
                if (priorEntry != null && priorDestination != null && isVerifiedDestination(source, priorDestination, priorEntry)) {
                    if (type == TYPE_MOVE && source.exists() &&
                        source.canonicalPath != priorDestination.canonicalPath) {
                        check(source.delete()) { "Previously verified Move destination exists, but source could not be deleted during recovery" }
                        appendLog(logFile, "[RESUME-MOVE-DELETE] Removed source after re-verifying journaled destination: ${source.name}\n")
                    }
                    succeeded++
                    appendLog(logFile, "[RESUME-SKIP] ${source.name}: prior destination verified at ${priorDestination.name}\n")
                } else {
                    if (priorEntry != null) {
                        completed.remove(sourcePath)
                        appendLog(logFile, "[RESUME-RECHECK] ${source.name}: recorded destination no longer verifies; retrying\n")
                    }
                    val outcome = if (type == TYPE_MOVE) moveOne(source, destinationDirectory, logFile) else copyOne(source, destinationDirectory, logFile)
                    completed.put(sourcePath, JSONObject()
                        .put("destination", outcome.destination.absolutePath)
                        .put("name", outcome.destination.name)
                        .put("size", outcome.destination.length())
                        .put("mtimeSecond", outcome.destination.lastModified() / 1000L))
                    writeJournal(journalFile, journal)
                    succeeded++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                val message = "${source.name}: ${e.message ?: "operation failed"}"
                errorSummary += message
                appendLog(logFile, "[FAILED] $message\n")
            }
            processed++
            setProgress(progressData(processed, total, succeeded, failed, source.name, actionLabel, logFile))
            setForeground(createForegroundInfo(type, operationId, processed, total, "$actionLabel · ${source.name}", logFile))
        }
        val summary = "$succeeded succeeded, $failed failed"
        appendLog(logFile, "\n=== FINISHED: $summary ===\n")
        journal.put("summary", summary).put("finishedAt", System.currentTimeMillis())
        writeJournal(journalFile, journal)
        postCompletionNotification(type, operationId, summary, logFile)
        Result.success(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total, KEY_SUCCEEDED to succeeded,
            KEY_FAILED to failed, KEY_SUMMARY to summary,
            KEY_ERRORS to errorSummary.take(20).joinToString("\n").take(3000),
            KEY_LOG_PATH to logFile.absolutePath
        ))
    }

    private data class OperationOutcome(val destination: File)

    private fun copyOne(source: File, destinationDirectory: File, logFile: File): OperationOutcome {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source unavailable or unreadable")
        val originalSize = source.length()
        val originalModified = source.lastModified()
        val destination = uniqueDestination(destinationDirectory, source.name)
        val temporary = File(destinationDirectory, ".personal-gallery-${UUID.randomUUID()}.tmp")
        appendLog(logFile, "[COPY] ${source.name} -> ${destination.name}\n")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1024 * 1024)
                    output.fd.sync()
                }
            }
            check(temporary.length() == originalSize) { "Size verification failed on temporary copy" }
            check(source.length() == originalSize && source.lastModified() / 1000L == originalModified / 1000L) {
                "Source changed while copying"
            }
            check(temporary.setLastModified(originalModified)) { "Could not preserve source modification time" }
            val tempNamePass = temporary.name.endsWith(".tmp")
            val tempSizePass = temporary.length() == originalSize
            val tempMtimePass = temporary.lastModified() / 1000L == originalModified / 1000L
            appendLog(logFile, "[VERIFY-TEMP] name=$tempNamePass size=$tempSizePass mtime(second)=$tempMtimePass\n")
            check(tempSizePass && tempMtimePass) { "Temporary destination verification failed" }

            publishNoReplace(temporary, destination)
            val namePass = destination.name.isNotBlank() && destination.parentFile?.canonicalPath == destinationDirectory.canonicalPath
            val sizePass = destination.isFile && destination.length() == originalSize
            val mtimePass = destination.lastModified() / 1000L == originalModified / 1000L
            appendLog(logFile, "[VERIFY-PUBLISHED] filename=${destination.name} pass=$namePass size=$sizePass mtime(second)=$mtimePass\n")
            check(namePass && sizePass && mtimePass) { "Published destination verification failed" }
            return OperationOutcome(destination)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun moveOne(source: File, destinationDirectory: File, logFile: File): OperationOutcome {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source unavailable or unreadable")
        val originalSize = source.length()
        val originalModified = source.lastModified()
        if (source.parentFile?.canonicalPath == destinationDirectory.canonicalPath) {
            appendLog(logFile, "[MOVE-NOOP] Source already in destination album: ${source.name}\n")
            return OperationOutcome(source)
        }
        var destination = uniqueDestination(destinationDirectory, source.name)
        appendLog(logFile, "[MOVE-ATOMIC-TRY] ${source.name} -> ${destination.name}\n")
        try {
            // Atomic rename first, as requested. Never use Files.getFileStore().
            if (destination.exists()) destination = uniqueDestination(destinationDirectory, source.name)
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val namePass = destination.name.isNotBlank() && destination.parentFile?.canonicalPath == destinationDirectory.canonicalPath
            val sizePass = destination.isFile && destination.length() == originalSize
            val mtimePass = destination.lastModified() / 1000L == originalModified / 1000L
            val sourceGonePass = !source.exists()
            appendLog(logFile, "[MOVE-ATOMIC-RESULT] success filename=${destination.name} pass=$namePass size=$sizePass mtime(second)=$mtimePass sourceRemoved=$sourceGonePass\n")
            check(namePass && sizePass && mtimePass && sourceGonePass) { "Atomic move verification failed; source/destination state requires review" }
            return OperationOutcome(destination)
        } catch (e: AtomicMoveNotSupportedException) {
            appendLog(logFile, "[MOVE-FALLBACK] Atomic move unsupported: ${e.message}; copying then verifying before source deletion\n")
        } catch (e: FileSystemException) {
            val detail = (e.reason ?: e.message ?: "").lowercase()
            if (!(detail.contains("cross-device") || detail.contains("exdev") || detail.contains("not supported") || detail.contains("unsupported"))) throw e
            appendLog(logFile, "[MOVE-FALLBACK] Filesystem reports cross-device/unsupported atomic move: ${e.message}\n")
        }

        destination = uniqueDestination(destinationDirectory, source.name)
        val outcome = copyOne(source, destinationDirectory, logFile)
        val copied = outcome.destination
        val namePass = copied.name == destination.name && copied.parentFile?.canonicalPath == destinationDirectory.canonicalPath
        val sizePass = copied.isFile && copied.length() == originalSize
        val mtimePass = copied.lastModified() / 1000L == originalModified / 1000L
        appendLog(logFile, "[MOVE-FALLBACK-VERIFY] filename=${copied.name} pass=$namePass size=$sizePass mtime(second)=$mtimePass\n")
        check(namePass && sizePass && mtimePass) { "Copy fallback destination failed verification; source retained" }
        check(source.length() == originalSize && source.lastModified() / 1000L == originalModified / 1000L) {
            "Source changed before deletion; source retained"
        }
        if (!source.delete()) throw IllegalStateException("Destination verified, but source deletion failed; both files retained")
        check(!source.exists()) { "Source still exists after deletion attempt" }
        appendLog(logFile, "[MOVE-FALLBACK-DELETE] source removed after verification: ${source.name}\n")
        return OperationOutcome(copied)
    }

    private fun isVerifiedDestination(source: File, destination: File, entry: JSONObject): Boolean {
        if (!destination.isFile) return false
        val recordedName = entry.optString("name")
        val recordedSize = entry.optLong("size", -1L)
        val recordedMtimeSecond = entry.optLong("mtimeSecond", -1L)
        if (recordedName.isBlank() || recordedSize < 0L || recordedMtimeSecond < 0L) return false
        val destinationPass = destination.name == recordedName &&
            destination.length() == recordedSize &&
            destination.lastModified() / 1000L == recordedMtimeSecond
        if (!destinationPass) return false
        return !source.exists() || (source.isFile && source.length() == recordedSize &&
            source.lastModified() / 1000L == recordedMtimeSecond)
    }

    private fun publishNoReplace(temp: File, destination: File) {
        // Unique names are selected before publishing. Recheck to avoid a normal-path collision.
        check(!destination.exists()) { "Destination collision before publish" }
        try {
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            check(!destination.exists()) { "Destination collision before fallback publish" }
            Files.move(temp.toPath(), destination.toPath())
        }
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

    private fun readJournal(file: File): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (_: Exception) { JSONObject() }

    private fun writeJournal(file: File, journal: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(journal.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun appendLog(file: File, text: String) {
        FileOutputStream(file, true).use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun progressData(processed: Int, total: Int, succeeded: Int, failed: Int, current: String, status: String, logFile: File) =
        workDataOf(KEY_PROCESSED to processed, KEY_TOTAL to total, KEY_SUCCEEDED to succeeded,
            KEY_FAILED to failed, KEY_CURRENT to current, KEY_STATUS to status,
            KEY_LOG_PATH to logFile.absolutePath)

    private fun createForegroundInfo(type: String, operationId: String, processed: Int, total: Int, status: String, logFile: File): ForegroundInfo {
        createChannel()
        val title = if (type == TYPE_MOVE) "Moving images" else "Copying images"
        val percent = if (total == 0) 0 else processed * 100 / total
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$status\n$processed of $total items ($percent%)\nDetailed log: ${logFile.name}"))
            .setProgress(total.coerceAtLeast(1), processed, total == 0)
            .setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
            .build()
        return ForegroundInfo(notificationId(operationId), notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun postCompletionNotification(type: String, operationId: String, summary: String, logFile: File) {
        createChannel()
        val title = if (type == TYPE_MOVE) "Move finished" else "Copy finished"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\nDetailed log stored at: ${logFile.absolutePath}"))
            .setAutoCancel(false).setOngoing(true).setOnlyAlertOnce(true).build()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(notificationId(operationId), notification)
    }

    private fun readLogTail(file: File, maxBytes: Int): String = try {
        if (!file.isFile) return "Log is not available yet"
        RandomAccessFile(file, "r").use { reader ->
            val start = (reader.length() - maxBytes.toLong()).coerceAtLeast(0L)
            reader.seek(start)
            if (start > 0L) reader.readLine()
            val buffer = ByteArray((reader.length() - reader.filePointer).toInt())
            reader.readFully(buffer)
            String(buffer, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        "Could not read log excerpt: ${e.message}"
    }

    private fun notificationId(operationId: String): Int = (operationId.hashCode() and 0x7fffffff).coerceAtLeast(1)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL_ID, "File operations", NotificationManager.IMPORTANCE_LOW))
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
    }
}
