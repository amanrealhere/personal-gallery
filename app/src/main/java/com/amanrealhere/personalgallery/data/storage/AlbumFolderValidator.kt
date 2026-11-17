package com.amanrealhere.personalgallery.data.storage

import android.os.Environment
import java.io.File
import java.io.IOException

sealed interface FolderValidationResult {

    data class Valid(
        val canonicalFolder: File
    ) : FolderValidationResult

    data object EmptyPath : FolderValidationResult

    data object NotAbsolutePath : FolderValidationResult

    data object PermissionRequired : FolderValidationResult

    data object DoesNotExist : FolderValidationResult

    data object NotDirectory : FolderValidationResult

    data object NotReadable : FolderValidationResult

    data object InvalidPath : FolderValidationResult
}

class AlbumFolderValidator {

    fun validate(rawPath: String): FolderValidationResult {
        val path = rawPath.trim()

        if (path.isEmpty()) {
            return FolderValidationResult.EmptyPath
        }

        val requestedFolder = File(path)

        if (!requestedFolder.isAbsolute) {
            return FolderValidationResult.NotAbsolutePath
        }

        if (!Environment.isExternalStorageManager(requestedFolder)) {
            return FolderValidationResult.PermissionRequired
        }

        return try {
            val canonicalFolder = requestedFolder.canonicalFile

            when {
                !canonicalFolder.exists() ->
                    FolderValidationResult.DoesNotExist

                !canonicalFolder.isDirectory ->
                    FolderValidationResult.NotDirectory

                !canonicalFolder.canRead() ->
                    FolderValidationResult.NotReadable

                else ->
                    FolderValidationResult.Valid(canonicalFolder)
            }
        } catch (_: IOException) {
            FolderValidationResult.InvalidPath
        } catch (_: SecurityException) {
            FolderValidationResult.NotReadable
        }
    }
}
