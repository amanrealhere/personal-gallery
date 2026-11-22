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
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * Reconciles a registered album with the Room media catalogue.
 *
 * The filesystem walk streams directory entries instead of materializing a File[]
 * for each directory. Metadata is read only for supported image candidates.
 * All discovered items are staged in batches; catalogue reconciliation is done
 * once after the scan, avoiding repeated SQL comparison work per batch.
 * Missing catalogue rows are removed only after a complete scan.
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
                val children = try {
                    Files.newDirectoryStream(directory.toPath())
                } catch (_: Exception) {
                    complete = false
                    continue
                }

                children.use { entries ->
                    try {
                        for (entry in entries) {
                            currentCoroutineContext().ensureActive()
                            val child = entry.toFile()
                            val path = entry
                            // Avoid metadata reads for ordinary non-image files. Directories
                            // still need to be identified so recursive traversal stays complete.
                            if (!isSupportedImageName(child.name)) {
                                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                                    child.name != ".personal-gallery"
                                ) {
                                    pendingDirectories.add(child)
                                }
                                continue
                            }

                            val attributes = try {
                                Files.readAttributes(
                                    path,
                                    BasicFileAttributes::class.java,
                                    LinkOption.NOFOLLOW_LINKS
                                )
                            } catch (_: Exception) {
                                complete = false
                                continue
                            }
                            if (attributes.isSymbolicLink) continue
                            if (attributes.isDirectory) {
                                if (child.name != ".personal-gallery") pendingDirectories.add(child)
                            } else if (attributes.isRegularFile) {
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
                                if (batch.size >= BATCH_SIZE) {
                                    mediaItemDao.insertStaging(batch)
                                    batch.clear()
                                }
                            }
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        complete = false
                    }
                }
            }
            if (batch.isNotEmpty()) mediaItemDao.insertStaging(batch)
            onProgress(indexed)
            // Apply all staged rows in one set-based reconciliation, not once per
            // batch. This avoids repeating the existing-row comparisons hundreds of times.
            mediaItemDao.finishStagedScan(albumId, scanToken, complete)
            finalized = true
            return ScanResult.Completed(indexed, complete)
        } catch (exception: CancellationException) {
            throw exception
        } finally {
            if (!finalized) withContext(NonCancellable) { mediaItemDao.clearStaging(scanToken) }
        }
    }

    private fun isSupportedImageName(name: String): Boolean =
        name.endsWith(".jpg", ignoreCase = true) ||
            name.endsWith(".jpeg", ignoreCase = true) ||
            name.endsWith(".png", ignoreCase = true) ||
            name.endsWith(".webp", ignoreCase = true)

    sealed interface ScanResult {
        data object Unavailable : ScanResult
        data class Completed(val indexedCount: Int, val complete: Boolean) : ScanResult
    }

    companion object {
        private const val BATCH_SIZE = 512
        // Keep UI/StateFlow progress traffic low while indexing large albums.
        private const val PROGRESS_INTERVAL = 256
    }
}
