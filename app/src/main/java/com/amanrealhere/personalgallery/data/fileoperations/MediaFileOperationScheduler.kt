package com.amanrealhere.personalgallery.data.fileoperations

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID

object MediaFileOperationScheduler {
    fun enqueue(context: Context, type: String, sources: List<String>, destination: String): String {
        require(type == MediaFileOperationWorker.TYPE_COPY || type == MediaFileOperationWorker.TYPE_MOVE)
        val target = File(destination).canonicalFile
        require(target.isDirectory && target.canWrite())
        val id = UUID.randomUUID().toString()
        val requestFile = File(context.filesDir, "media-operation-$id.json")
        val request = JSONObject().put("type", type).put("destination", target.path)
            .put("sources", JSONArray(sources.distinct()))
        val temporary = File(context.filesDir, requestFile.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(request.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), requestFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), requestFile.toPath())
        }

        val work = OneTimeWorkRequestBuilder<MediaFileOperationWorker>()
            .setId(UUID.fromString(id))
            .setInputData(workDataOf(MediaFileOperationWorker.KEY_OPERATION_ID to id))
            .addTag(MediaFileOperationWorker.TAG)
            .build()
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "personal-gallery-file-operation",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work
            )
        } catch (e: Exception) {
            requestFile.delete()
            throw e
        }
        return id
    }
}
