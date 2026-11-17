package com.amanrealhere.personalgallery.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.amanrealhere.personalgallery.data.local.AlbumDao
import com.amanrealhere.personalgallery.data.local.AlbumEntity
import com.amanrealhere.personalgallery.data.storage.AlbumFolderValidator
import com.amanrealhere.personalgallery.data.storage.FolderValidationResult
import com.amanrealhere.personalgallery.domain.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class AlbumRepository(
    private val albumDao: AlbumDao,
    private val folderValidator: AlbumFolderValidator =
        AlbumFolderValidator()
) {

    fun observeAlbums(): Flow<List<Album>> {
        return albumDao.observeAll().map { entities ->
            entities.map { it.toAlbum() }
        }
    }

    suspend fun registerAlbum(
        rawPath: String
    ): AlbumRegistrationResult = withContext(Dispatchers.IO) {

        when (val validation = folderValidator.validate(rawPath)) {

            is FolderValidationResult.Valid -> {
                val folder = validation.canonicalFolder
                val canonicalPath = folder.path

                val existing = albumDao.findByCanonicalPath(canonicalPath)

                if (existing != null) {
                    return@withContext AlbumRegistrationResult.AlreadyRegistered(
                        existing.toAlbum()
                    )
                }

                val entity = AlbumEntity(
                    name = folder.name.ifBlank { canonicalPath },
                    canonicalPath = canonicalPath,
                    registeredAtEpochMillis = System.currentTimeMillis()
                )

                try {
                    albumDao.insert(entity)

                    AlbumRegistrationResult.Registered(
                        entity.toAlbum()
                    )
                } catch (exception: SQLiteConstraintException) {
                    // Another registration may have inserted this path
                    // between our lookup and insertion.
                    val conflicting = albumDao.findByCanonicalPath(
                        canonicalPath
                    ) ?: throw exception

                    AlbumRegistrationResult.AlreadyRegistered(
                        conflicting.toAlbum()
                    )
                }
            }

            else -> AlbumRegistrationResult.InvalidFolder(validation)
        }
    }

    private fun AlbumEntity.toAlbum(): Album {
        return Album(
            id = id,
            name = name,
            canonicalPath = canonicalPath,
            registeredAtEpochMillis = registeredAtEpochMillis
        )
    }
}
