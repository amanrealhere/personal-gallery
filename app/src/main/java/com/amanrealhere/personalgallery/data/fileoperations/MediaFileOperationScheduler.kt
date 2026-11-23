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
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

object MediaFileOperationScheduler {
    fun enqueue(context: Context, type: String, sources: List<String>, destination: String): String {
        require(type == MediaFileOperationWorker.TYPE_COPY || type == MediaFileOperationWorker.TYPE_MOVE)
        require(File(destination).isDirectory)
        val id = UUID.randomUUID().toString()
        val request = JSONObject().put("type", type).put("destination", destination)
            .put("sources", JSONArray(sources.distinct()))
        val requestFile = File(context.filesDir, "media-operation-$id.json")
        val temporaryRequest = File(context.filesDir, "media-operation-$id.json.tmp")
        try {
            FileOutputStream(temporaryRequest).use { output ->
                output.write(request.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            try {
                Files.move(temporaryRequest.toPath(), requestFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryRequest.toPath(), requestFile.toPath())
            }
        } catch (exception: Exception) {
            temporaryRequest.delete()
            requestFile.delete()
            throw exception
        }

        val work = OneTimeWorkRequestBuilder<MediaFileOperationWorker>()
            .setId(UUID.fromString(id))
            .setInputData(workDataOf(
                MediaFileOperationWorker.KEY_OPERATION_ID to id,
                MediaFileOperationWorker.KEY_TYPE to type
            ))
            .addTag(MediaFileOperationWorker.TAG)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "personal-gallery-file-operation",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work
            )
        } catch (exception: Exception) {
            requestFile.delete()
            throw exception
        }
        return id
    }
}
