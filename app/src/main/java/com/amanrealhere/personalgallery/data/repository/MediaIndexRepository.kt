package com.amanrealhere.personalgallery.data.repository

import com.amanrealhere.personalgallery.data.local.MediaItemDao
import com.amanrealhere.personalgallery.data.local.MediaItemStagingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Reconciles the filesystem with the Room media catalogue.
 *
 * The scan is staged outside the live media_items table so Paging isn't invalidated
 * for every batch. Only changed rows are applied in one final transaction. Missing
 * rows are deleted only after a complete scan.
 */
class MediaIndexRepository(private val mediaItemDao: MediaItemDao) {
    suspend fun reconcileAlbum(
        albumId: String,
        folder: File,
        onProgress: (Int) -> Unit = {}
    ): ScanResult {
        val scanToken = UUID.randomUUID().toString()
        mediaItemDao.clearStaging(scanToken)
        if (!folder.isDirectory || !folder.canRead()) return ScanResult.Unavailable

        val batch = ArrayList<MediaItemStagingEntity>(BATCH_SIZE)
        var indexed = 0
        var complete = true
        var finalized = false
        val pendingDirectories = ArrayDeque<File>().apply { add(folder) }

        try {
            while (pendingDirectories.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val directory = pendingDirectories.removeLast()
                if (!directory.canRead()) {
                    complete = false
                    continue
                }
                val children = try {
                    directory.listFiles()
                } catch (_: SecurityException) {
                    null
                }
                if (children == null) {
                    complete = false
                    continue
                }
                for (child in children) {
                    currentCoroutineContext().ensureActive()
                    if (child.name == ".personal-gallery") continue
                    val isImageCandidate = child.extension.lowercase() in SUPPORTED_EXTENSIONS
                    if (!isImageCandidate) {
                        // Skip full NIO attribute reads for files outside the supported image types.
                        if (child.isDirectory) {
                            if (!Files.isSymbolicLink(child.toPath())) pendingDirectories.add(child)
                        } else if (!child.canRead()) {
                            // An unreadable entry could be a directory; avoid deleting stale
                            // catalogue rows if this scan cannot confidently traverse it.
                            complete = false
                        }
                        continue
                    }
                    val attributes = try {
                        Files.readAttributes(child.toPath(), BasicFileAttributes::class.java)
                    } catch (_: Exception) {
                        complete = false
                        continue
                    }
                    if (attributes.isSymbolicLink) continue
                    if (attributes.isDirectory) pendingDirectories.add(child)
                    else if (attributes.isRegularFile) {
                        // The registered album root is canonical and symlinks are skipped,
                        // so the absolute child path is a stable identity without realpath().
                        val absolutePath = child.absolutePath
                        batch += MediaItemStagingEntity(
                            scanToken = scanToken,
                            id = "$albumId:$absolutePath",
                            albumId = albumId,
                            sourcePath = absolutePath,
                            sourceCanonicalPath = absolutePath,
                            displayName = child.name,
                            fileSizeBytes = attributes.size(),
                            modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis()
                        )
                        indexed++
                        if (indexed == 1 || indexed % PROGRESS_INTERVAL == 0) onProgress(indexed)
                        val batchLimit = if (indexed <= INITIAL_BATCH_SIZE) INITIAL_BATCH_SIZE else BATCH_SIZE
                        if (batch.size >= batchLimit) {
                            mediaItemDao.insertStaging(batch)
                            mediaItemDao.applyStagedBatch(albumId, scanToken, batch.map { it.id })
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) mediaItemDao.insertStaging(batch)
            onProgress(indexed)
            mediaItemDao.finishStagedScan(albumId, scanToken, complete)
            finalized = true
            return ScanResult.Completed(indexed, complete)
        } catch (exception: CancellationException) {
            throw exception
        } finally {
            if (!finalized) withContext(NonCancellable) { mediaItemDao.clearStaging(scanToken) }
        }
    }

    sealed interface ScanResult {
        data object Unavailable : ScanResult
        data class Completed(val indexedCount: Int, val complete: Boolean) : ScanResult
    }

    companion object {
        private const val INITIAL_BATCH_SIZE = 64
        private const val BATCH_SIZE = 512
        // Keep UI/StateFlow progress traffic low while indexing large albums.
        private const val PROGRESS_INTERVAL = 256
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
