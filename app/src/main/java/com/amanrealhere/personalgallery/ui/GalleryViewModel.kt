package com.amanrealhere.personalgallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amanrealhere.personalgallery.PersonalGalleryApplication
import com.amanrealhere.personalgallery.data.repository.AlbumRegistrationResult
import com.amanrealhere.personalgallery.data.repository.AlbumRepository
import com.amanrealhere.personalgallery.domain.model.Album
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GalleryViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val galleryApplication =
        application as PersonalGalleryApplication

    private val repository = AlbumRepository(
        galleryApplication.database.albumDao()
    )

    val albums: StateFlow<List<Album>> = repository
        .observeAlbums()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _registrationResult =
        MutableStateFlow<AlbumRegistrationResult?>(null)

    val registrationResult: StateFlow<AlbumRegistrationResult?> =
        _registrationResult.asStateFlow()

    private val _isRegistering = MutableStateFlow(false)

    val isRegistering: StateFlow<Boolean> =
        _isRegistering.asStateFlow()

    fun registerAlbum(path: String) {
        if (_isRegistering.value) return

        viewModelScope.launch {
            _isRegistering.value = true

            try {
                _registrationResult.value =
                    repository.registerAlbum(path)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                _registrationResult.value =
                    AlbumRegistrationResult.RegistrationFailed
            } finally {
                _isRegistering.value = false
            }
        }
    }

    fun clearRegistrationResult() {
        _registrationResult.value = null
    }
}
