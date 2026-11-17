package com.amanrealhere.personalgallery.ui

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import com.amanrealhere.personalgallery.PersonalGalleryApplication
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.data.repository.AlbumRegistrationResult
import com.amanrealhere.personalgallery.data.repository.AlbumRepository
import com.amanrealhere.personalgallery.data.thumbnail.ThumbnailWorkScheduler
import com.amanrealhere.personalgallery.domain.model.Album
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class GalleryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val galleryApplication = application as PersonalGalleryApplication
    private val database = galleryApplication.database
    private val repository = AlbumRepository(database.albumDao())

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

    private val _albumImages = MutableStateFlow<List<File>>(emptyList())
    val albumImages: StateFlow<List<File>> = _albumImages.asStateFlow()

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
        viewModelScope.launch(Dispatchers.IO) {
            val folder = File(album.canonicalPath)
            val files = if (!folder.isDirectory || !folder.canRead()) {
                emptyList()
            } else {
                folder.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                    .sortedWith(
                        compareByDescending<File> { it.lastModified() }
                            .thenBy { it.absolutePath }
                    )
                    .toList()
            }
            _albumImages.value = files
        }
    }

    fun clearAlbumImages() {
        _albumImages.value = emptyList()
    }

    fun ensureAlbumThumbnails(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(getApplication<Application>())
            val hasQueuedGeneration = workManager
                .getWorkInfosForUniqueWorkFlow(ThumbnailWorkScheduler.UNIQUE_WORK_NAME)
                .first()
                .any { !it.state.isFinished }
            if (hasQueuedGeneration) return@launch

            val album = database.albumDao().findById(albumId) ?: return@launch
            val folder = File(album.canonicalPath)
            if (!folder.isDirectory || !folder.canRead()) return@launch
            val files = folder.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
                .sortedWith(
                    compareByDescending<File> { it.lastModified() }
                        .thenBy { it.absolutePath }
                )
                .toList()

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
