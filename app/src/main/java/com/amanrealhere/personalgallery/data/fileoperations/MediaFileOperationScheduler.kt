package com.amanrealhere.personalgallery.data.fileoperations

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object MediaFileOperationScheduler {
    fun enqueue(context: Context, type: String, sources: List<String>, destination: String): String {
        require(type == MediaFileOperationWorker.TYPE_COPY || type == MediaFileOperationWorker.TYPE_MOVE)
        require(File(destination).isDirectory)
        val id = UUID.randomUUID().toString()
        val request = JSONObject().put("type", type).put("destination", destination)
            .put("sources", JSONArray(sources.distinct()))
        File(context.filesDir, "media-operation-$id.json").writeText(request.toString())
        val work = OneTimeWorkRequestBuilder<MediaFileOperationWorker>()
            .setId(UUID.fromString(id))
            .setInputData(workDataOf(MediaFileOperationWorker.KEY_OPERATION_ID to id))
            .addTag(MediaFileOperationWorker.TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "personal-gallery-file-operation",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            work
        )
        return id
    }
}
