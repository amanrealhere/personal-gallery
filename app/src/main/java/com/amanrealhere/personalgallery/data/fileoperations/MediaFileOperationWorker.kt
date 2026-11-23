package com.amanrealhere.personalgallery.data.fileoperations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.app.PendingIntent
import android.os.Environment
import com.amanrealhere.personalgallery.PersonalGalleryApplication
import com.amanrealhere.personalgallery.data.repository.MediaIndexRepository
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailWorkScheduler
import kotlinx.coroutines.flow.first
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
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
            val message = "Operation failed: request file is missing; no files were processed"
            log("ERROR", message)
            postCompletionNotification(currentType, message)
            return@withContext Result.failure(workDataOf(KEY_ERROR to message))
        }

        val request = try { JSONObject(requestFile.readText()) } catch (e: Exception) {
            val message = "Operation failed: request file could not be read; no files were processed"
            log("ERROR", message + ": " + e.message)
            requestFile.delete()
            postCompletionNotification(currentType, message)
            return@withContext Result.failure(workDataOf(KEY_ERROR to message))
        }
        currentType = request.optString("type", TYPE_COPY)
        val type = currentType
        val destinationDirectory = File(request.optString("destination"))
        val sourcePaths = request.optJSONArray("sources") ?: JSONArray()
        if (!destinationDirectory.isDirectory || !destinationDirectory.canWrite()) {
            val message = "Operation failed: destination album is unavailable"
            log("ERROR", message + ": " + destinationDirectory.absolutePath)
            requestFile.delete()
            postCompletionNotification(type, message)
            return@withContext Result.failure(workDataOf(KEY_ERROR to message))
        }
        val galleryOwnedRoot = runCatching {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ".personal-gallery"
            ).canonicalPath
        }.getOrNull()
        val destinationCanonical = runCatching { destinationDirectory.canonicalPath }.getOrNull()
        val galleryOwnedPrefix = galleryOwnedRoot?.let { it.trimEnd(File.separatorChar) + File.separator } ?: ""
        if (galleryOwnedRoot != null && destinationCanonical != null &&
            (destinationCanonical == galleryOwnedRoot || destinationCanonical.startsWith(galleryOwnedPrefix))) {
            val message = "Operation failed: Personal Gallery's own storage cannot be a file-operation destination"
            log("ERROR", message + ": " + destinationCanonical)
            requestFile.delete()
            postCompletionNotification(type, message)
            return@withContext Result.failure(workDataOf(KEY_ERROR to message))
        }

        operationState = if (stateFile.isFile) {
            try {
                JSONObject(stateFile.readText())
            } catch (exception: Exception) {
                val message = "Operation failed: persistent state is unreadable; no files were processed in this attempt"
                log("ERROR", message + ": " + exception.message)
                postCompletionNotification(type, message)
                return@withContext Result.failure(workDataOf(KEY_ERROR to message))
            }
        } else JSONObject()
        plans = operationState.optJSONObject("plans") ?: JSONObject()
        completed = operationState.optJSONObject("completed") ?: JSONObject()
        val persistedElapsedStart = operationState.optLong("startedElapsedRealtime", 0L)
        operationStartedAtMillis = persistedElapsedStart.takeIf { it > 0L && SystemClock.elapsedRealtime() >= it } ?: SystemClock.elapsedRealtime()
        operationState.put("startedElapsedRealtime", operationStartedAtMillis)
        operationState.put("startedAtMillis", System.currentTimeMillis())
        persistState()

        val total = sourcePaths.length()
        var processed = 0
        var succeeded = 0
        var failed = 0
        log("START", "$type · $total files · Destination: ${destinationDirectory.absolutePath}")
        cleanupAbandonedTemporaryFiles(destinationDirectory)
        setForeground(createForegroundInfo(type, 0, total, 0, "Preparing $total items", "Getting things ready"))

        for (index in 0 until total) {
            if (isStopped) break
            val sourcePath = sourcePaths.optString(index)
            val source = File(sourcePath)
            val action = if (type == TYPE_MOVE) "Move" else "Copy"
            publishProgress(processed, total, succeeded, failed, source.name, "Preparing $action", 0, "Checking source and destination")
            log("FILE", "$action started: ${source.absolutePath}")
            if (galleryOwnedRoot != null) {
                val sourceCanonical = runCatching { source.canonicalPath }.getOrNull()
                if (sourceCanonical == galleryOwnedRoot ||
                    (sourceCanonical != null && sourceCanonical.startsWith(galleryOwnedPrefix))) {
                    failed++
                    processed++
                    log("FAIL", "App-owned storage is not a valid source: ${source.absolutePath}")
                    publishProgress(processed, total, succeeded, failed, source.name, "Rejected app-owned source")
                    continue
                }
            }
            try {
                var plan = plans.optJSONObject(sourcePath)
                if (plan == null) {
                    val expectedSize = source.takeIf { it.isFile }?.length() ?: 0L
                    val expectedMtimeSeconds = source.takeIf { it.isFile }?.lastModified()?.div(1000L) ?: 0L
                    plan = JSONObject()
                        .put("destination", uniqueDestination(destinationDirectory, source.name).absolutePath)
                        .put("size", expectedSize)
                        .put("mtimeSeconds", expectedMtimeSeconds)
                        .put("started", false)
                        .put("sourcePresent", source.isFile)
                        .put("phase", "planned")
                    plans.put(sourcePath, plan)
                    persistState()
                    log("PLAN", "Reserved destination ${plan.getString("destination")} · size=$expectedSize · mtime(s)=$expectedMtimeSeconds")
                }
                if (!plan.optBoolean("sourcePresent", true) && source.isFile && source.canRead()) {
                    plan.put("destination", uniqueDestination(destinationDirectory, source.name).absolutePath)
                    plan.put("size", source.length())
                    plan.put("mtimeSeconds", source.lastModified() / 1000L)
                    plan.put("sourcePresent", true)
                    plan.put("started", false)
                    plan.put("phase", "planned")
                    plans.put(sourcePath, plan)
                    persistState()
                    log("RECOVERY", "Source reappeared after an earlier unavailable attempt; its metadata and destination plan were refreshed")
                }
                val destination = File(plan.getString("destination"))
                val expectedSize = plan.optLong("size", -1L)
                val expectedMtimeSeconds = plan.optLong("mtimeSeconds", -1L)

                val completedRecord = completed.optJSONObject(sourcePath)
                if (completedRecord != null) {
                    val recordedDestination = File(completedRecord.optString("destination", destination.absolutePath))
                    val recordedSize = completedRecord.optLong("size", expectedSize)
                    val recordedMtime = completedRecord.optLong("mtimeSeconds", expectedMtimeSeconds)
                    val validExisting = recordedDestination.absolutePath == destination.absolutePath &&
                        verifyDestination(recordedDestination, destination.name, recordedSize, recordedMtime)
                    if (validExisting) {
                        succeeded++
                        log("RESUME", "Previously completed destination verified and skipped: ${recordedDestination.name}; source is not touched")
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
                val recoverablePublishedFile = plan.optString("phase") in setOf("publishing", "published") ||
                    (type == TYPE_MOVE && !source.exists())
                if (plan.optBoolean("started", false) && plan.optBoolean("sourcePresent", true) && destination.exists() &&
                    verifyDestination(destination, destination.name, expectedSize, expectedMtimeSeconds) &&
                    recoverablePublishedFile) {
                    if (type == TYPE_MOVE) {
                        if (source.exists()) {
                            if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
                                throw IllegalStateException("Source changed since the operation was planned; source retained during recovery")
                            }
                            log("RECOVERY", "Verified destination from an interrupted Move; source still matches planned name/size/mtime-to-seconds, removing source")
                            if (!source.delete() || source.exists()) throw IllegalStateException("Verified destination but could not delete source during recovery")
                        } else {
                            log("RECOVERY", "Verified destination from an interrupted atomic Move; source is already absent")
                        }
                    } else {
                        log("RECOVERY", "Verified published destination from an interrupted Copy; source remains untouched")
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
                    plan.put("started", false)
                    plan.put("phase", "planned")
                    plans.put(sourcePath, plan)
                    persistState()
                }

                plan.put("started", true)
                plan.put("phase", "started")
                plans.put(sourcePath, plan)
                persistState()
                log("PLAN", "Execution marked started and plan persisted for ${source.name}")
                val result = if (type == TYPE_MOVE) {
                    moveOne(source, destinationDirectory, plan.getString("destination"), expectedSize, expectedMtimeSeconds, sourcePath, plan, index, total, succeeded, failed)
                } else {
                    copyOne(source, destinationDirectory, plan.getString("destination"), expectedSize, expectedMtimeSeconds, sourcePath, plan, processed, total, succeeded, failed)
                    true
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
                log("FAIL", detail)
            }
            processed++
            publishProgress(processed, total, succeeded, failed, source.name, "$action processed")
        }

        if (processed < total) {
            log("INTERRUPTED", "Only $processed of $total files processed; retrying with persisted state")
            return@withContext Result.retry()
        }

        var reconciliationFailures = 0
        val sourcePathList = (0 until sourcePaths.length()).map { sourcePaths.optString(it) }
        try {
            val app = applicationContext as PersonalGalleryApplication
            val registeredAlbums = app.database.albumDao().observeAll().first()
            val sourceAlbums = registeredAlbums.filter { album ->
                sourcePathList.any { path ->
                    path.startsWith(album.canonicalPath.trimEnd(File.separatorChar) + File.separator)
                }
            }
            val destinationAlbum = registeredAlbums.firstOrNull {
                runCatching { File(it.canonicalPath).canonicalPath == destinationDirectory.canonicalPath }.getOrDefault(false)
            }
            if (sourceAlbums.isEmpty()) {
                reconciliationFailures++
                log("RECONCILE_FAIL", "No registered source album matched the affected source paths")
            }
            if (destinationAlbum == null) {
                reconciliationFailures++
                log("RECONCILE_FAIL", "Destination album could not be matched to a registered album")
            }
            val affectedAlbums = (sourceAlbums + listOfNotNull(destinationAlbum)).distinctBy { it.id }
            log("RECONCILE", "Refreshing index and thumbnail work for ${affectedAlbums.size} affected album(s)")
            publishProgress(processed, total, succeeded, failed, "Album index", "Refreshing affected album indexes and thumbnails")
            for (album in affectedAlbums) {
                try {
                    log("RECONCILE", "Album refresh started: ${album.name}")
                    publishProgress(processed, total, succeeded, failed, album.name, "Refreshing album index and thumbnails")
                    val scanResult = MediaIndexRepository(app.database.mediaItemDao())
                        .reconcileAlbum(album.id, File(album.canonicalPath))
                    when (scanResult) {
                        MediaIndexRepository.ScanResult.Unavailable ->
                            throw IllegalStateException("Album scan unavailable; index refresh was not completed")
                        is MediaIndexRepository.ScanResult.Completed -> {
                            if (!scanResult.complete) {
                                throw IllegalStateException("Album scan was incomplete (${scanResult.indexedCount} discovered); stale rows were retained")
                            }
                            log("RECONCILE", "Album index refresh completed: ${scanResult.indexedCount} image(s)")
                        }
                    }
                    ThumbnailWorkScheduler.enqueueAlbum(applicationContext, album.id, force = false)
                    log("RECONCILE", "Album refresh and thumbnail scheduling succeeded: ${album.name}")
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    reconciliationFailures++
                    log("RECONCILE_FAIL", "${album.name}: ${exception.message ?: "refresh failed"}")
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reconciliationFailures++
            log("RECONCILE_FAIL", "Could not discover affected albums: ${exception.message ?: "refresh failed"}")
        }

        val reconciliationSuffix = if (reconciliationFailures == 0) "" else " · $reconciliationFailures album refresh failure(s)"
        val verificationSummary = "$succeeded file(s) passed filename, size, and mtime-to-seconds verification"
        val summary = "$succeeded succeeded, $failed failed, $processed of $total processed · $verificationSummary$reconciliationSuffix"
        log("SUMMARY", summary)
        if (reconciliationFailures > 0) {
            val reconciliationAttempt = operationState.optInt("reconciliationAttempts", 0) + 1
            operationState.put("reconciliationAttempts", reconciliationAttempt)
            persistState()
            if (reconciliationAttempt < 3) {
                log("RETRY", "Retrying durable album reconciliation (attempt ${reconciliationAttempt} of 3; independent of interrupted file-operation retries)")
                return@withContext Result.retry()
            }
            log("RECONCILE_FAIL", "Reconciliation retry limit reached; automatic retries are no longer scheduled and the refresh failure remains in the log")
        }
        operationState.put("result", JSONObject()
            .put("type", type)
            .put("summary", summary)
            .put("processed", processed)
            .put("total", total)
            .put("succeeded", succeeded)
            .put("failed", failed)
            .put("status", "COMPLETED")
            .put("finishedAtMillis", System.currentTimeMillis())
            .put("durationMs", (SystemClock.elapsedRealtime() - operationStartedAtMillis).coerceAtLeast(0L)))
        persistState()
        requestFile.delete()
        postCompletionNotification(type, summary)
        // WorkManager Data remains bounded. Full details are retained in the
        // operation log and persistent per-file state JSON, outside its 10 KB cap.
        Result.success(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_SUMMARY to summary, KEY_TYPE to type, KEY_PROGRESS_UNITS to total * 1000
        ))
    }

    private suspend fun copyOne(
        source: File,
        destinationDirectory: File,
        destinationPath: String,
        expectedSize: Long,
        expectedMtimeSeconds: Long,
        sourcePath: String,
        plan: JSONObject,
        processed: Int,
        total: Int,
        succeeded: Int,
        failed: Int
    ): File {
        if (!source.isFile || !source.canRead()) throw IllegalStateException("Source is unavailable")
        val destination = File(destinationPath)
        val temporary = File(destinationDirectory, ".personal-gallery-$currentOperationId-${UUID.randomUUID()}.tmp")
        log("COPY", "Copying to temporary file: ${temporary.name}; expected name=${destination.name}; size=$expectedSize; mtime(s)=$expectedMtimeSeconds")
        publishProgress(processed, total, succeeded, failed, source.name, "Copying", 0, if (currentType == TYPE_MOVE) "Fallback · writing copy before deleting source" else "Writing temporary copy")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    var lastProgressAt = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped) throw CancellationException("File copy stopped")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 1_000L || copied == expectedSize) {
                            log("PROGRESS", "Copied " + copied + " / " + expectedSize + " bytes for " + source.name)
                            val filePercent = if (expectedSize > 0L) ((copied * 100L) / expectedSize).coerceIn(0L, 100L).toInt() else 0
                            val fileProgress = if (expectedSize > 0L) ((copied * 1000L) / expectedSize).coerceIn(0L, 999L).toInt() else 0
                            publishProgress(processed, total, succeeded, failed, source.name, "Copying", fileProgress, "$filePercent% · " + if (currentType == TYPE_MOVE) "copying before deleting source" else "writing temporary copy")
                            lastProgressAt = now
                        }
                    }
                    output.fd.sync()
                }
            }
            publishProgress(processed, total, succeeded, failed, source.name, "Verifying", 0, if (currentType == TYPE_MOVE) "Checking fallback copy before source deletion" else "Checking size and source metadata")
            if (temporary.length() != expectedSize) throw IllegalStateException("Temporary size verification failed (expected $expectedSize, got ${temporary.length()})")
            log("VERIFY", "Temporary size verified: ${temporary.length()} bytes")
            if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
                throw IllegalStateException("Source changed while copying (size/mtime-to-seconds)")
            }
            publishProgress(processed, total, succeeded, failed, source.name, "Restoring timestamp", 0, "Applying original modified time")
            if (!temporary.setLastModified(expectedMtimeSeconds * 1000L)) throw IllegalStateException("Could not set destination mtime")
            if (!verifyDestination(temporary, temporary.name, expectedSize, expectedMtimeSeconds)) {
                throw IllegalStateException("Temporary file verification failed (size or mtime-to-seconds)")
            }
            log("VERIFY", "Temporary mtime verified to seconds: ${temporary.lastModified()/1000L}")
            val expectedDestinationName = File(destinationPath).name
            plan.put("phase", "publishing")
            plans.put(sourcePath, plan)
            persistState()
            publishProgress(processed, total, succeeded, failed, source.name, "Finishing", 0, "Publishing verified copy")
            log("STATE", "Publishing phase persisted before destination rename")
            Files.move(temporary.toPath(), destination.toPath())
            log("PUBLISH", "Temporary file published using same-directory filesystem move")
            if (!verifyDestination(destination, expectedDestinationName, expectedSize, expectedMtimeSeconds)) {
                throw IllegalStateException("Published destination verification failed")
            }
            log("VERIFY", "Published destination verified: name=${destination.name}; size=${destination.length()}; mtime(s)= ${destination.lastModified()/1000L}")
            plan.put("phase", "published")
            plans.put(sourcePath, plan)
            persistState()
            log("STATE", "Published destination phase persisted for recovery")
            return destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private suspend fun moveOne(
        source: File,
        destinationDirectory: File,
        destinationPath: String,
        expectedSize: Long,
        expectedMtimeSeconds: Long,
        sourcePath: String,
        plan: JSONObject,
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
        publishProgress(processed, total, succeeded, failed, source.name, "Moving", 0, "Trying atomic rename")
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            publishProgress(processed, total, succeeded, failed, source.name, "Verifying", 0, "Checking destination name, size and mtime")
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
        } catch (e: IOException) {
            if (!differentStorageVolumes(source, destinationDirectory)) throw e
            log("FALLBACK", "Atomic rename failed across storage volumes; using copy-and-delete: ${e.message}")
        }

        publishProgress(processed, total, succeeded, failed, source.name, "Copying", 0, "Fallback · copy and verify before deleting")
        val copied = copyOne(source, destinationDirectory, destinationPath, expectedSize, expectedMtimeSeconds, sourcePath, plan, processed, total, succeeded, failed)
        if (!verifyDestination(copied, expectedDestinationName, expectedSize, expectedMtimeSeconds)) {
            throw IllegalStateException("Destination verification failed; original retained")
        }
        if (source.length() != expectedSize || source.lastModified() / 1000L != expectedMtimeSeconds) {
            throw IllegalStateException("Source changed before deletion; original retained")
        }
        publishProgress(processed, total, succeeded, failed, source.name, "Deleting original", 0, "Destination verified · removing source")
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
     * copy-and-delete is an appropriate fallback.
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

    private suspend fun publishProgress(
        processed: Int, total: Int, succeeded: Int, failed: Int, current: String, status: String,
        currentFileProgressPermille: Int = 0, detail: String = status
    ) {
        val progressUnits = processed * 1000 + currentFileProgressPermille.coerceIn(0, 999)
        setProgress(workDataOf(
            KEY_PROCESSED to processed, KEY_TOTAL to total,
            KEY_SUCCEEDED to succeeded, KEY_FAILED to failed,
            KEY_CURRENT to current.take(160), KEY_STATUS to status.take(120),
            KEY_DETAIL to detail.take(120), KEY_PROGRESS_UNITS to progressUnits,
            KEY_LOG_PATH to operationLog.absolutePath
        ))
        val mainLine = when {
            status.equals("Copying", ignoreCase = true) -> "Copying " + current.take(90)
            status.equals("Moving", ignoreCase = true) -> "Moving " + current.take(90)
            status.contains("fallback", ignoreCase = true) -> "Moving " + current.take(90)
            status.startsWith("Preparing", ignoreCase = true) -> status.take(90)
            status.contains("index", ignoreCase = true) || status.contains("album", ignoreCase = true) -> status.take(90)
            else -> status.take(90)
        }
        setForeground(createForegroundInfo(currentType, processed, total, progressUnits, mainLine.take(100), detail.take(90)))
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

    private fun createForegroundInfo(type: String, processed: Int, total: Int, progressUnits: Int, mainLine: String, detailLine: String): ForegroundInfo {
        createChannel()
        val title = if (type == TYPE_MOVE) "Moving $total images" else "Copying $total images"
        val maximum = (total.coerceAtLeast(1) * 1000)
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(applicationContext, operationNotificationId(false), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(mainLine.take(100))
            .setSubText(detailLine.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText("$mainLine\n$detailLine\n$processed of $total items"))
            .setContentIntent(contentIntent)
            .setProgress(maximum, progressUnits.coerceIn(0, maximum), total == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        return ForegroundInfo(operationNotificationId(false), notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun postCompletionNotification(type: String, summary: String) {
        createChannel()
        val hasIssues = Regex("[1-9][0-9]* failed").containsMatchIn(summary) || summary.contains("album refresh failure", ignoreCase = true)
        val title = if (summary.startsWith("Operation failed")) "File operation failed" else if (hasIssues) (if (type == TYPE_MOVE) "Move finished with issues" else "Copy finished with issues") else if (type == TYPE_MOVE) "Move completed" else "Copy completed"
        val succeeded = Regex("(\\d+) succeeded").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val failed = Regex("(\\d+) failed").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val compactText = if (summary.startsWith("Operation failed")) "Operation could not start" else if (summary.contains("album refresh failure", ignoreCase = true)) "$succeeded succeeded · album refresh failed" else "$succeeded succeeded · $failed failed"
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(applicationContext, operationNotificationId(true), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(title)
            .setContentText(compactText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentIntent)
            .setProgress(0, 0, false)
            .setAutoCancel(false)
            .setOngoing(false)
            .build()
        applicationContext.getSystemService(NotificationManager::class.java).notify(operationNotificationId(true), notification)
    }

    private fun operationNotificationId(completion: Boolean): Int {
        val base = currentOperationId.takeLast(8).toLongOrNull(16)?.toInt()
            ?: currentOperationId.hashCode()
        val salt = if (completion) 0x2468ACE0 else 0x13579BDF
        val id = (base xor salt) and 0x7fffffff
        return if (id == 0) 1 else id
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "File operations", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress and results for media copy and move operations"
                }
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
        const val KEY_ERROR = "error"
        const val KEY_STATUS = "status"
        const val KEY_LOG_PATH = "log_path"
        const val KEY_DETAIL = "detail"
        const val KEY_PROGRESS_UNITS = "progress_units"
        const val KEY_TYPE = "type"
        const val TYPE_COPY = "COPY"
        const val TYPE_MOVE = "MOVE"
        const val TAG = "personal-gallery-file-operation"
        private const val CHANNEL_ID = "personal-gallery-file-operations"
    }
}
