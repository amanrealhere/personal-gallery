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
import java.util.UUID

/**
 * Reconciles the filesystem with the Room media catalogue.
 *
 * The scan is staged outside the live media_items table so Paging isn't invalidated
 * for every batch. Only changed rows are applied in one final transaction. Missing
 * rows are deleted only after a complete scan.
 */
class MediaIndexRepository(private val mediaItemDao: MediaItemDao) {
    suspend fun reconcileAlbum(albumId: String, folder: File): ScanResult {
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
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        if (child.name != ".personal-gallery") pendingDirectories.add(child)
                    } else if (child.isFile && child.extension.lowercase() in SUPPORTED_EXTENSIONS) {
                        val canonicalPath = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
                        batch += MediaItemStagingEntity(
                            scanToken = scanToken,
                            id = "$albumId:$canonicalPath",
                            albumId = albumId,
                            sourcePath = child.absolutePath,
                            sourceCanonicalPath = canonicalPath,
                            displayName = child.name,
                            fileSizeBytes = child.length(),
                            modifiedAtEpochMillis = child.lastModified()
                        )
                        indexed++
                        if (batch.size >= BATCH_SIZE) {
                            mediaItemDao.insertStaging(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
            if (batch.isNotEmpty()) mediaItemDao.insertStaging(batch.toList())
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
        private const val BATCH_SIZE = 256
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
