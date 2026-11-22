package com.amanrealhere.personalgallery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.amanrealhere.personalgallery.data.fileoperations.MediaFileOperationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

@androidx.compose.runtime.Composable
fun FileOperationDetailsDialog(
    workInfo: WorkInfo,
    onDismiss: () -> Unit
) {
    val progress = workInfo.progress
    val output = workInfo.outputData
    val processed = progress.getInt(MediaFileOperationWorker.KEY_PROCESSED,
        output.getInt(MediaFileOperationWorker.KEY_PROCESSED, 0))
    val total = progress.getInt(MediaFileOperationWorker.KEY_TOTAL,
        output.getInt(MediaFileOperationWorker.KEY_TOTAL, 0))
    val succeeded = progress.getInt(MediaFileOperationWorker.KEY_SUCCEEDED,
        output.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, 0))
    val failed = progress.getInt(MediaFileOperationWorker.KEY_FAILED,
        output.getInt(MediaFileOperationWorker.KEY_FAILED, 0))
    val current = progress.getString(MediaFileOperationWorker.KEY_CURRENT).orEmpty()
    val status = progress.getString(MediaFileOperationWorker.KEY_STATUS).orEmpty()
    val logPath = progress.getString(MediaFileOperationWorker.KEY_LOG_PATH)
        ?: output.getString(MediaFileOperationWorker.KEY_LOG_PATH)
    val summary = output.getString(MediaFileOperationWorker.KEY_SUMMARY)
        ?: if (workInfo.state.isFinished) "${succeeded} succeeded, ${failed} failed" else "In progress"
    var logText by remember(logPath) { mutableStateOf("Loading detailed operation log…") }

    LaunchedEffect(logPath, processed, failed, workInfo.state) {
        logText = if (logPath == null) "Log path not available yet." else withContext(Dispatchers.IO) {
            try {
                val file = File(logPath)
                if (!file.isFile) "Operation log is being created…" else RandomAccessFile(file, "r").use { reader ->
                    val startOffset = (reader.length() - 16_000L).coerceAtLeast(0L)
                    reader.seek(startOffset)
                    if (startOffset > 0L) reader.readLine()
                    val bytes = ByteArray((reader.length() - reader.filePointer).toInt())
                    reader.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                "Could not read operation log: ${e.message}"
            }
        }
    }

    val title = when {
        workInfo.state == WorkInfo.State.RUNNING && status.contains("Moving", true) -> "Moving files"
        workInfo.state == WorkInfo.State.RUNNING -> "Copying files"
        workInfo.state == WorkInfo.State.SUCCEEDED -> "File operation completed"
        workInfo.state == WorkInfo.State.FAILED -> "File operation failed"
        workInfo.state == WorkInfo.State.CANCELLED -> "File operation cancelled"
        else -> "File operation"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(summary, style = MaterialTheme.typography.titleSmall)
                if (!workInfo.state.isFinished && total > 0) {
                    LinearProgressIndicator(
                        progress = { (processed.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                    Text("$processed / $total (${(processed * 100 / total).coerceIn(0, 100)}%)")
                } else if (!workInfo.state.isFinished) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                if (current.isNotBlank()) Text("Current file: $current", style = MaterialTheme.typography.bodySmall)
                Text("Work state: ${workInfo.state}", style = MaterialTheme.typography.bodySmall)
                Text("Succeeded: $succeeded · Failed: $failed", style = MaterialTheme.typography.bodySmall)
                Text("Detailed log (latest entries):", modifier = Modifier.padding(top = 10.dp),
                    style = MaterialTheme.typography.labelLarge)
                Text(
                    logText,
                    modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
