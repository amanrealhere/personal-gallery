package com.amanrealhere.personalgallery.data.repository

import com.amanrealhere.personalgallery.data.local.MediaItemDao
import com.amanrealhere.personalgallery.data.local.MediaItemStagingEntity
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds a Room catalogue for a registered album.
 *
 * Directory discovery and image metadata reads are performed by a small, bounded
 * worker pool. A single collector owns staging writes so Room is not hammered by
 * concurrent transactions. Directory and image channels are streamed: neither
 * directory contents nor the entire album are held in memory.
 *
 * Reconciliation happens once after discovery. Missing catalogue rows are deleted
 * only when every directory and candidate metadata read completed successfully.
 */
class MediaIndexRepository(private val mediaItemDao: MediaItemDao) {
    suspend fun reconcileAlbum(
        albumId: String,
        folder: File,
        onProgress: (Int) -> Unit = {}
    ): ScanResult {
        val scanToken = UUID.randomUUID().toString()
        mediaItemDao.clearStaging(scanToken)
        if (GalleryStorage.isInternalGalleryPath(folder.path)) return ScanResult.Unavailable
        if (!folder.isDirectory || !folder.canRead()) return ScanResult.Unavailable

        val complete = AtomicBoolean(true)
        val indexed = AtomicInteger(0)
        var finalized = false

        try {
            coroutineScope {
                val directories = Channel<File>(DIRECTORY_QUEUE_CAPACITY)
                val discoveredImages = Channel<MediaItemStagingEntity>(IMAGE_CHANNEL_CAPACITY)
                val activeDirectories = AtomicInteger(1)
                directories.trySend(folder)

                val collector = async {
                    val batch = ArrayList<MediaItemStagingEntity>(BATCH_SIZE)
                    for (item in discoveredImages) {
                        currentCoroutineContext().ensureActive()
                        batch += item
                        val count = indexed.incrementAndGet()
                        if (count == 1 || count % PROGRESS_INTERVAL == 0) onProgress(count)
                        if (batch.size == BATCH_SIZE) {
                            mediaItemDao.insertStaging(batch)
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) mediaItemDao.insertStaging(batch)
                    onProgress(indexed.get())
                }

                val workers = List(WORKER_COUNT) {
                    launch {
                        for (directory in directories) {
                            currentCoroutineContext().ensureActive()
                            try {
                                Files.newDirectoryStream(directory.toPath()).use { entries ->
                                    for (entry in entries) {
                                        currentCoroutineContext().ensureActive()
                                        val child = entry.toFile()
                                        if (child.name == ".personal-gallery") continue

                                        // Directories must be discovered regardless of name;
                                        // supported image candidates receive full metadata reads.
                                        if (!isSupportedImageName(child.name)) {
                                            val attributes = try {
                                                Files.readAttributes(
                                                    entry,
                                                    BasicFileAttributes::class.java,
                                                    LinkOption.NOFOLLOW_LINKS
                                                )
                                            } catch (_: Exception) {
                                                complete.set(false)
                                                continue
                                            }
                                            if (!attributes.isSymbolicLink && attributes.isDirectory) {
                                                activeDirectories.incrementAndGet()
                                                if (directories.trySend(child).isFailure) {
                                                    activeDirectories.decrementAndGet()
                                                    complete.set(false)
                                                }
                                            }
                                            continue
                                        }

                                        val attributes = try {
                                            Files.readAttributes(
                                                entry,
                                                BasicFileAttributes::class.java,
                                                LinkOption.NOFOLLOW_LINKS
                                            )
                                        } catch (_: Exception) {
                                            complete.set(false)
                                            continue
                                        }

                                        if (attributes.isSymbolicLink) continue
                                        if (attributes.isDirectory) {
                                            activeDirectories.incrementAndGet()
                                            if (directories.trySend(child).isFailure) {
                                                activeDirectories.decrementAndGet()
                                                complete.set(false)
                                            }
                                        } else if (attributes.isRegularFile) {
                                            val absolutePath = child.absolutePath
                                            discoveredImages.send(
                                                MediaItemStagingEntity(
                                                    scanToken = scanToken,
                                                    id = "$albumId:$absolutePath",
                                                    albumId = albumId,
                                                    sourcePath = absolutePath,
                                                    sourceCanonicalPath = absolutePath,
                                                    displayName = child.name,
                                                    fileSizeBytes = attributes.size(),
                                                    modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis()
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Exception) {
                                complete.set(false)
                            } finally {
                                if (activeDirectories.decrementAndGet() == 0) {
                                    directories.close()
                                }
                            }
                        }
                    }
                }

                workers.joinAll()
                discoveredImages.close()
                collector.await()
            }

            mediaItemDao.finishStagedScan(albumId, scanToken, complete.get())
            finalized = true
            return ScanResult.Completed(indexed.get(), complete.get())
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
        private const val WORKER_COUNT = 4
        private const val DIRECTORY_QUEUE_CAPACITY = 256
        private const val IMAGE_CHANNEL_CAPACITY = 1024
        private const val PROGRESS_INTERVAL = 256
    }
}
