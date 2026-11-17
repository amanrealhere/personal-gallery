package com.amanrealhere.personalgallery.data.thumbnail

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

object ThumbnailWorkScheduler {
    const val UNIQUE_WORK_NAME = "personal-gallery-thumbnail-generation"
    const val TAG_ALL = "personal-gallery-thumbnail-generation:all"
    const val TAG_ALBUM_PREFIX = "personal-gallery-thumbnail-generation:album:"

    fun enqueueAll(context: Context, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ThumbnailGenerationWorker>()
            .setInputData(workDataOf(ThumbnailGenerationWorker.KEY_FORCE to force))
            .addTag(TAG_ALL)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    fun enqueueAlbum(context: Context, albumId: String, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ThumbnailGenerationWorker>()
            .setInputData(
                workDataOf(
                    ThumbnailGenerationWorker.KEY_ALBUM_ID to albumId,
                    ThumbnailGenerationWorker.KEY_FORCE to force
                )
            )
            .addTag(TAG_ALBUM_PREFIX + albumId)
            .setConstraints(Constraints.Builder().build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }
}
