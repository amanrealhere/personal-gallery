package com.amanrealhere.personalgallery.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.amanrealhere.personalgallery.PersonalGalleryApplication
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.data.repository.AlbumRegistrationResult
import com.amanrealhere.personalgallery.data.repository.AlbumRepository
import com.amanrealhere.personalgallery.data.repository.MediaIndexRepository
import com.amanrealhere.personalgallery.data.fileoperations.MediaFileOperationScheduler
import com.amanrealhere.personalgallery.data.fileoperations.MediaFileOperationWorker
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailWorkScheduler
import com.amanrealhere.personalgallery.domain.model.Album
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val galleryApplication = application as PersonalGalleryApplication
    private val database = galleryApplication.database
    private val repository = AlbumRepository(database.albumDao())
    private val mediaIndexRepository = MediaIndexRepository(database.mediaItemDao())

    private val _fileOperationMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val fileOperationMessages = _fileOperationMessages.asSharedFlow()

    fun startFileOperation(type: String, sourcePaths: List<String>, destinationAlbum: Album) {
        val uniqueSources = sourcePaths.distinct()
        if (uniqueSources.isEmpty()) return
        viewModelScope.launch {
            try {
                val operationId = MediaFileOperationScheduler.enqueue(
                    getApplication(),
                    type,
                    uniqueSources,
                    destinationAlbum.canonicalPath
                )
                val workManager = WorkManager.getInstance(getApplication<Application>())
                val finishedWork = workManager.getWorkInfosByTagFlow(MediaFileOperationWorker.TAG)
                    .first { infos -> infos.any { it.id.toString() == operationId && it.state.isFinished } }
                    .first { it.id.toString() == operationId }

                val output = finishedWork.outputData
                val succeeded = output.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, 0)
                val failed = output.getInt(MediaFileOperationWorker.KEY_FAILED, uniqueSources.size)
                val errors = output.getString(MediaFileOperationWorker.KEY_ERRORS).orEmpty()
                val errorMessage = output.getString(MediaFileOperationWorker.KEY_ERROR)
                val firstItemError = errors.lineSequence().firstOrNull()?.trim()

                // Refresh the registered albums affected by these paths, then update their covers.
                val sourceAlbums = albums.value.filter { album ->
                    uniqueSources.any { path ->
                        path.startsWith(album.canonicalPath.trimEnd(File.separator) + File.separator)
                    }
                }
                val affectedAlbums = (sourceAlbums + destinationAlbum).distinctBy { it.id }
                for (album in affectedAlbums) {
                    mediaIndexRepository.reconcileAlbum(album.id, File(album.canonicalPath))
                    ThumbnailWorkScheduler.enqueueAlbum(getApplication(), album.id, force = false)
                }

                val verb = if (type == MediaFileOperationWorker.TYPE_MOVE) "Moved" else "Copied"
                val message = if (failed == 0 && succeeded > 0) {
                    "$verb ${succeeded} item${if (succeeded == 1) "" else "s"} to ${destinationAlbum.name}"
                } else if (succeeded == 0 && errorMessage != null) {
                    "$verb failed: $errorMessage"
                } else if (succeeded == 0 && firstItemError != null) {
                    "$verb failed: $firstItemError"
                } else {
                    "$verb ${succeeded} of ${uniqueSources.size}; ${failed} failed"
                }
                _fileOperationMessages.emit(message)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _fileOperationMessages.emit("File operation failed: ${exception.message ?: "unknown error"}")
            }
        }
    }

    val albums: StateFlow<List<Album>> = repository.observeAlbums().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val thumbnailStates: StateFlow<List<AlbumThumbnailStateEntity>> =
        database.thumbnailDao().observeAlbumStates().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    val thumbnailWorkInfos: Flow<List<WorkInfo>> =
        WorkManager.getInstance(application)
            .getWorkInfosForUniqueWorkFlow(ThumbnailWorkScheduler.UNIQUE_WORK_NAME)

    private val selectedAlbumId = MutableStateFlow<String?>(null)

    val albumImages: Flow<PagingData<MediaItemEntity>> = selectedAlbumId
        .flatMapLatest { albumId ->
            if (albumId == null) {
                flowOf(PagingData.empty<MediaItemEntity>())
            } else {
                Pager(
                    config = PagingConfig(
                        pageSize = 48,
                        initialLoadSize = 48,
                        prefetchDistance = 24,
                        enablePlaceholders = true,
                        maxSize = 240
                    ),
                    pagingSourceFactory = {
                        database.mediaItemDao().pagingSource(albumId)
                    }
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    val albumImageCount: StateFlow<Int> = selectedAlbumId
        .flatMapLatest { albumId ->
            if (albumId == null) flowOf(0)
            else database.mediaItemDao().observeCountForAlbum(albumId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )

    private val _isIndexingAlbum = MutableStateFlow(false)
    val isIndexingAlbum: StateFlow<Boolean> = _isIndexingAlbum.asStateFlow()

    private val _liveScanImageCount = MutableStateFlow(0)
    val liveScanImageCount: StateFlow<Int> = _liveScanImageCount.asStateFlow()

    // Distinguish exact completed scans from partial scans and stale catalogue counts.
    private val _scanFinished = MutableStateFlow(false)
    val scanFinished: StateFlow<Boolean> = _scanFinished.asStateFlow()
    private val _scanComplete = MutableStateFlow(false)
    val scanComplete: StateFlow<Boolean> = _scanComplete.asStateFlow()

    private var indexingJob: Job? = null
    private var indexingAlbumId: String? = null

    private val _registrationResult = MutableStateFlow<AlbumRegistrationResult?>(null)
    val registrationResult: StateFlow<AlbumRegistrationResult?> =
        _registrationResult.asStateFlow()

    private val _isRegistering = MutableStateFlow(false)
    val isRegistering: StateFlow<Boolean> = _isRegistering.asStateFlow()

    fun registerAlbum(path: String) {
        if (_isRegistering.value) return
        viewModelScope.launch {
            _isRegistering.value = true
            try {
                _registrationResult.value = repository.registerAlbum(path)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _registrationResult.value = AlbumRegistrationResult.RegistrationFailed
            } finally {
                _isRegistering.value = false
            }
        }
    }

    fun loadAlbumImages(album: Album) {
        if (selectedAlbumId.value == album.id) return
        if (indexingAlbumId == album.id && indexingJob?.isActive == true) return

        // Open Paging immediately from the existing catalogue so the album appears
        // without waiting for a filesystem reconciliation scan to finish.
        // Reconciliation still runs in the background and updates the catalogue.
        indexingJob?.cancel()
        selectedAlbumId.value = album.id
        indexingAlbumId = album.id
        _liveScanImageCount.value = 0
        _scanFinished.value = false
        _scanComplete.value = false
        _isIndexingAlbum.value = true
        indexingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = mediaIndexRepository.reconcileAlbum(
                    albumId = album.id,
                    folder = File(album.canonicalPath),
                    onProgress = { count ->
                        if (indexingAlbumId == album.id) _liveScanImageCount.value = count
                    }
                )
                if (indexingAlbumId == album.id) {
                    _scanFinished.value = true
                    _scanComplete.value = result is MediaIndexRepository.ScanResult.Completed && result.complete
                    if (result is MediaIndexRepository.ScanResult.Unavailable) _liveScanImageCount.value = 0
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Failed scans fall back to the last successfully indexed catalogue count.
                if (indexingAlbumId == album.id) {
                    _liveScanImageCount.value = 0
                    _scanFinished.value = true
                    _scanComplete.value = false
                }
            } finally {
                if (indexingAlbumId == album.id && indexingJob?.isActive == true) {
                    _isIndexingAlbum.value = false
                    indexingAlbumId = null
                }
            }

            if (selectedAlbumId.value == album.id) {
                ensureAlbumCoverNow(album.id)
            }
        }
    }

    fun clearAlbumImages() {
        indexingJob?.cancel()
        indexingJob = null
        indexingAlbumId = null
        _isIndexingAlbum.value = false
        _liveScanImageCount.value = 0
        _scanFinished.value = false
        _scanComplete.value = false
        selectedAlbumId.value = null
    }

    private suspend fun ensureAlbumCoverNow(albumId: String) {
        val workManager = WorkManager.getInstance(getApplication<Application>())
        val pendingWorks = workManager
            .getWorkInfosForUniqueWorkFlow(ThumbnailWorkScheduler.UNIQUE_WORK_NAME)
            .first()
        val alreadyCovered = pendingWorks.any { work ->
            !work.state.isFinished && (
                ThumbnailWorkScheduler.TAG_ALL in work.tags ||
                    ThumbnailWorkScheduler.TAG_ALBUM_PREFIX + albumId in work.tags
            )
        }
        if (alreadyCovered) return

        val imageCount = database.mediaItemDao().countForAlbum(albumId)
        val latest = database.mediaItemDao().latestItemForAlbum(albumId)
        val state = database.thumbnailDao().albumState(albumId)
        var cacheIsCurrent = state != null &&
            state.imageCount == imageCount &&
            state.latestImageModifiedAtEpochMillis == latest?.modifiedAtEpochMillis

        if (cacheIsCurrent && latest == null) {
            cacheIsCurrent = state?.coverThumbnailPath == null
        } else if (cacheIsCurrent && latest != null) {
            val activeGenerationId = state?.activeGenerationId
            val coverPath = state?.coverThumbnailPath
            val coverRecord = activeGenerationId?.let { generationId ->
                database.thumbnailDao().records(generationId, albumId).firstOrNull { record ->
                    record.kind == ThumbnailGenerationWorker.KIND_COVER &&
                        record.sourcePath == latest.sourcePath
                }
            }
            cacheIsCurrent = coverPath != null && coverRecord != null &&
                coverRecord.status in setOf(
                    ThumbnailGenerationWorker.STATUS_SUCCESS,
                    ThumbnailGenerationWorker.STATUS_REUSED
                ) &&
                coverPath == coverRecord.outputPath &&
                isValidCachedThumbnail(File(coverPath), ThumbnailGenerationWorker.COVER_SIZE)
        }

        if (!cacheIsCurrent) {
            ThumbnailWorkScheduler.enqueueAlbum(getApplication(), albumId, force = false)
        }
    }

    private fun isValidCachedThumbnail(file: File, expectedSize: Int): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth == expectedSize && options.outHeight == expectedSize
    }

    fun regenerateAllThumbnails() {
        ThumbnailWorkScheduler.enqueueAll(getApplication(), force = true)
    }

    fun regenerateAlbumThumbnails(albumId: String) {
        ThumbnailWorkScheduler.enqueueAlbum(getApplication(), albumId, force = true)
    }

    fun clearRegistrationResult() {
        _registrationResult.value = null
    }

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    }
}
