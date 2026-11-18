package com.amanrealhere.personalgallery.data.repository

import com.amanrealhere.personalgallery.data.local.MediaItemDao
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Reconciles the filesystem with the Room media catalogue.
 *
 * A scan only removes records that were not seen when traversal completed
 * successfully. If any directory cannot be listed, existing records are
 * retained rather than risking deletion of media hidden by a permission or
 * storage error.
 */
class MediaIndexRepository(
    private val mediaItemDao: MediaItemDao
) {
    suspend fun reconcileAlbum(albumId: String, folder: File): ScanResult {
        if (!folder.isDirectory || !folder.canRead()) {
            return ScanResult.Unavailable
        }

        val scanToken = UUID.randomUUID().toString()
        val batch = ArrayList<MediaItemEntity>(BATCH_SIZE)
        var indexed = 0
        var complete = true
        val pendingDirectories = ArrayDeque<File>()
        pendingDirectories.add(folder)

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
                    if (child.name != ".personal-gallery") {
                        pendingDirectories.add(child)
                    }
                } else if (child.isFile && child.extension.lowercase() in SUPPORTED_EXTENSIONS) {
                    val canonicalPath = runCatching { child.canonicalPath }
                        .getOrDefault(child.absolutePath)
                    batch += MediaItemEntity(
                        id = "$albumId:$canonicalPath",
                        albumId = albumId,
                        sourcePath = child.absolutePath,
                        sourceCanonicalPath = canonicalPath,
                        displayName = child.name,
                        fileSizeBytes = child.length(),
                        modifiedAtEpochMillis = child.lastModified(),
                        lastSeenScanToken = scanToken
                    )
                    indexed++

                    if (batch.size >= BATCH_SIZE) {
                        mediaItemDao.insertOrReplace(batch.toList())
                        batch.clear()
                    }
                }
            }
        }

        if (batch.isNotEmpty()) {
            mediaItemDao.insertOrReplace(batch)
        }

        if (complete) {
            mediaItemDao.deleteNotSeenInScan(albumId, scanToken)
        }

        return ScanResult.Completed(indexed, complete)
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
