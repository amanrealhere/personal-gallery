package com.amanrealhere.personalgallery.data.repository

import com.amanrealhere.personalgallery.data.storage.FolderValidationResult
import com.amanrealhere.personalgallery.domain.model.Album

sealed interface AlbumRegistrationResult {

    data class Registered(
        val album: Album
    ) : AlbumRegistrationResult

    data class AlreadyRegistered(
        val album: Album
    ) : AlbumRegistrationResult

    data class InvalidFolder(
        val reason: FolderValidationResult
    ) : AlbumRegistrationResult

    data object RegistrationFailed : AlbumRegistrationResult
}
