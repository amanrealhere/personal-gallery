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
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailWorkScheduler
import com.amanrealhere.personalgallery.domain.model.Album
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
                        pageSize = 60,
                        initialLoadSize = 90,
                        prefetchDistance = 30,
                        enablePlaceholders = false,
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
        if (indexingAlbumId == album.id && indexingJob?.isActive == true) return
        selectedAlbumId.value = album.id
        indexingJob?.cancel()
        indexingAlbumId = album.id
        _isIndexingAlbum.value = true
        indexingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                mediaIndexRepository.reconcileAlbum(
                    albumId = album.id,
                    folder = File(album.canonicalPath)
                )
                if (selectedAlbumId.value == album.id) {
                    ensureAlbumThumbnailsNow(album.id)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Keep the last successfully indexed catalogue available.
            } finally {
                if (selectedAlbumId.value == album.id) {
                    _isIndexingAlbum.value = false
                    indexingAlbumId = null
                }
            }
        }
    }

    fun clearAlbumImages() {
        indexingJob?.cancel()
        indexingJob = null
        indexingAlbumId = null
        _isIndexingAlbum.value = false
        selectedAlbumId.value = null
    }

    fun ensureAlbumThumbnails(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ensureAlbumThumbnailsNow(albumId)
        }
    }

    private suspend fun ensureAlbumThumbnailsNow(albumId: String) {
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

            val album = database.albumDao().findById(albumId) ?: return
            val folder = File(album.canonicalPath)
            if (!folder.isDirectory || !folder.canRead()) return
            val files = database.mediaItemDao().itemsForAlbum(albumId)
                .map { File(it.sourcePath) }

            val state = database.thumbnailDao().albumState(albumId)
            val activeGenerationId = state?.activeGenerationId
            var cacheIsCurrent = state != null && activeGenerationId != null &&
                state.imageCount == files.size &&
                state.latestImageModifiedAtEpochMillis == files.firstOrNull()?.lastModified()

            if (cacheIsCurrent && activeGenerationId != null) {
                val records = database.thumbnailDao().records(activeGenerationId, albumId)
                val regularByPath = records
                    .filter { it.kind == ThumbnailGenerationWorker.KIND_REGULAR }
                    .associateBy { it.sourcePath }

                cacheIsCurrent = files.all { file ->
                    val record = regularByPath[file.absolutePath]
                    record != null &&
                        record.status in setOf(
                            ThumbnailGenerationWorker.STATUS_SUCCESS,
                            ThumbnailGenerationWorker.STATUS_REUSED
                        ) &&
                        record.sourceCanonicalPath == runCatching { file.canonicalPath }.getOrDefault(file.absolutePath) &&
                        record.sourceName == file.name &&
                        record.sourceSizeBytes == file.length() &&
                        record.sourceModifiedAtEpochMillis == file.lastModified() &&
                        isValidCachedThumbnail(File(record.outputPath), ThumbnailGenerationWorker.REGULAR_SIZE)
                }

                val latest = files.firstOrNull()
                if (cacheIsCurrent && latest != null) {
                    val cover = records.firstOrNull {
                        it.kind == ThumbnailGenerationWorker.KIND_COVER &&
                            it.sourcePath == latest.absolutePath
                    }
                    cacheIsCurrent = cover != null &&
                        cover.status in setOf(
                            ThumbnailGenerationWorker.STATUS_SUCCESS,
                            ThumbnailGenerationWorker.STATUS_REUSED
                        ) &&
                        state?.coverThumbnailPath == cover.outputPath &&
                        isValidCachedThumbnail(File(cover.outputPath), ThumbnailGenerationWorker.COVER_SIZE)
                } else if (cacheIsCurrent && latest == null) {
                    cacheIsCurrent = state?.coverThumbnailPath == null
                }
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
