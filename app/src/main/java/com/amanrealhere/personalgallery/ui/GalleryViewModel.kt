package com.amanrealhere.personalgallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.getWorkInfosForUniqueWorkFlow
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
            val files = folder.listFiles()?.asSequence()
                ?.filter {
                    it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS
                }
                ?.sortedWith(
                    compareByDescending<File> { it.lastModified() }
                        .thenBy { it.absolutePath }
                )
                ?.toList()
                .orEmpty()
            _albumImages.value = files
        }
    }

    fun clearAlbumImages() {
        _albumImages.value = emptyList()
    }

    fun ensureAlbumThumbnails(albumId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = database.thumbnailDao().albumState(albumId)
            if (state == null || state.activeGenerationId == null) {
                ThumbnailWorkScheduler.enqueueAlbum(getApplication(), albumId, force = false)
            }
        }
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
