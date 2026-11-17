package com.amanrealhere.personalgallery.data.storage

import android.content.Context
import android.os.Environment
import java.io.File

class GalleryStorage(context: Context) {
    val root: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        ".personal-gallery"
    )

    val databaseDirectory: File = File(root, "database")
    val thumbnailsDirectory: File = File(root, ".thumbnails")
    val generationsDirectory: File = File(thumbnailsDirectory, "generations")

    init {
        check(Environment.isExternalStorageManager()) {
            "Personal Gallery requires all-files access to use Pictures/.personal-gallery."
        }
        listOf(root, databaseDirectory, thumbnailsDirectory, generationsDirectory)
            .forEach { directory ->
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IllegalStateException("Cannot create app storage directory: $directory")
                }
                check(directory.isDirectory && directory.canWrite()) {
                    "App storage directory is not writable: $directory"
                }
            }
    }

    fun databaseFile(): File = File(databaseDirectory, "personal-gallery.db")

    fun generationDirectory(generationId: String): File =
        File(generationsDirectory, generationId)

    fun albumThumbnailDirectory(generationId: String, albumId: String): File =
        File(generationDirectory(generationId), albumId)

    companion object {
        @Volatile private var instance: GalleryStorage? = null

        fun getInstance(context: Context): GalleryStorage =
            instance ?: synchronized(this) {
                instance ?: GalleryStorage(context.applicationContext).also {
                    instance = it
                }
            }
    }
}
