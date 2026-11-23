package com.amanrealhere.personalgallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import com.amanrealhere.personalgallery.data.fileoperations.MediaFileOperationWorker
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.screens.addalbum.AddAlbumScreen
import com.amanrealhere.personalgallery.ui.screens.album.AlbumContentsScreen
import com.amanrealhere.personalgallery.ui.screens.home.HomeScreen
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class GalleryDestination { Albums, AddAlbum }

@Composable
private fun OperationLogView(text: String) {
    val scheme = MaterialTheme.colorScheme
    val successColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF9AD7A6) else Color(0xFF237A3B)
    val warningColor = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFFD180) else Color(0xFF9A5B00)
    val lines = remember(text) { text.lines() }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        itemsIndexed(lines) { _, line ->
            val tag = Regex("\\[(.*?)\\]").findAll(line).lastOrNull()?.groupValues?.getOrNull(1).orEmpty()
            val lineColor = when (tag) {
                "ERROR", "FAIL", "RECONCILE_FAIL", "INTERRUPTED" -> scheme.error
                "SUCCESS", "SUMMARY" -> successColor
                "FALLBACK", "RETRY" -> warningColor
                "VERIFY", "METHOD", "PUBLISH", "STATE" -> scheme.primary
                else -> scheme.onSurfaceVariant
            }
            Text(line, color = lineColor, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp))
        }
    }
}
@Composable
fun GalleryShell(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    var sidebarExpanded by remember { mutableStateOf(true) }
    var selectedAlbumPath by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf(GalleryDestination.Albums) }
    val pendingGeneration = remember { mutableStateOf<(() -> Unit)?>(null) }
    val pendingFileOperation = remember { mutableStateOf<(() -> Unit)?>(null) }

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val thumbnailStates by viewModel.thumbnailStates.collectAsStateWithLifecycle()
    val thumbnailStatesByAlbumId = remember(thumbnailStates) {
        thumbnailStates.associateBy { it.albumId }
    }
    val images = viewModel.albumImages
    val isIndexingAlbum by viewModel.isIndexingAlbum.collectAsStateWithLifecycle()
    val indexedImageCount by viewModel.albumImageCount.collectAsStateWithLifecycle()
    val liveScanImageCount by viewModel.liveScanImageCount.collectAsStateWithLifecycle()
    val scanFinished by viewModel.scanFinished.collectAsStateWithLifecycle()
    val scanComplete by viewModel.scanComplete.collectAsStateWithLifecycle()
    val workInfos by viewModel.thumbnailWorkInfos.collectAsStateWithLifecycle(initialValue = emptyList())
    val fileOperationWorkInfos by WorkManager.getInstance(context)
        .getWorkInfosByTagFlow(MediaFileOperationWorker.TAG)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val latestFileOperationId by viewModel.latestFileOperationId.collectAsStateWithLifecycle()
    val activeFileOperation = fileOperationWorkInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val displayedFileOperation = activeFileOperation
        ?: latestFileOperationId?.let { id -> fileOperationWorkInfos.firstOrNull { it.id.toString() == id } }
    var savedOperationState by remember { mutableStateOf(JSONObject()) }
    var savedOperationResult by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(latestFileOperationId, displayedFileOperation?.state) {
        val id = latestFileOperationId
        savedOperationState = if (id == null) JSONObject() else withContext(Dispatchers.IO) {
            val stateFile = File(context.filesDir, "media-operation-" + id + ".state.json")
            runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        }
        savedOperationResult = savedOperationState.optJSONObject("result") ?: JSONObject()
    }
    val fileOperationProcessed = displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_PROCESSED, -1)?.takeIf { it >= 0 }
        ?: displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_PROCESSED, 0) ?: savedOperationResult.optInt("processed", 0)
    val fileOperationTotal = displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_TOTAL, -1)?.takeIf { it >= 0 }
        ?: displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_TOTAL, 0) ?: savedOperationResult.optInt("total", 0)
    val fileOperationSucceeded = displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, -1)?.takeIf { it >= 0 }
        ?: displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, 0) ?: savedOperationResult.optInt("succeeded", 0)
    val fileOperationFailed = displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_FAILED, -1)?.takeIf { it >= 0 }
        ?: displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_FAILED, 0) ?: savedOperationResult.optInt("failed", 0)
    val fileOperationCurrent = displayedFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_CURRENT).orEmpty()
    val fileOperationDetail = displayedFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_DETAIL).orEmpty()
    val fileOperationProgressUnits = displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, -1)?.takeIf { it >= 0 }
        ?: displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, fileOperationProcessed * 1000)
        ?: savedOperationResult.optInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, fileOperationProcessed * 1000)
    val operationStartedAtElapsedRealtime = savedOperationState.optLong("startedElapsedRealtime", 0L)
    val operationDurationMs = savedOperationResult.optLong("durationMs", -1L)
    val fileOperationStatus = if (displayedFileOperation?.state?.isFinished == true) {
        displayedFileOperation.outputData.getString(MediaFileOperationWorker.KEY_SUMMARY)
            ?: displayedFileOperation.outputData.getString(MediaFileOperationWorker.KEY_ERROR)
            ?: displayedFileOperation.state.name
    } else {
        displayedFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_STATUS)
            ?: if (displayedFileOperation != null) displayedFileOperation.state.name else savedOperationResult.optString("summary").takeIf { it.isNotBlank() } ?: "Saved operation log; WorkManager record is unavailable"
    }
    var operationDialogVisible by remember { mutableStateOf(false) }
    var advancedLogVisible by remember { mutableStateOf(false) }
    var operationLogText by remember { mutableStateOf("") }
    val operationDialogScrollState = rememberScrollState()
    var operationLaunchError by remember { mutableStateOf<String?>(null) }
    var pendingToastOperationIds by remember { mutableStateOf(setOf<String>())
    LaunchedEffect(viewModel) {
        launch { viewModel.fileOperationStartedIds.collect { pendingToastOperationIds = pendingToastOperationIds + it } }
        launch { viewModel.fileOperationMessages.collect { operationLaunchError = it; Toast.makeText(context, "Could not queue operation", Toast.LENGTH_SHORT).show() } }
    }
    LaunchedEffect(fileOperationWorkInfos, pendingToastOperationIds) {
        for (work in fileOperationWorkInfos.filter { it.state.isFinished }) {
            val id = work.id.toString()
            if (id !in pendingToastOperationIds) continue
            pendingToastOperationIds = pendingToastOperationIds - id
            val type = work.outputData.getString(MediaFileOperationWorker.KEY_TYPE)
                ?: work.inputData.getString(MediaFileOperationWorker.KEY_TYPE)
            val action = if (type == MediaFileOperationWorker.TYPE_MOVE) "Move" else "Copy"
            val failedCount = work.outputData.getInt(MediaFileOperationWorker.KEY_FAILED, 0)
            val summary = work.outputData.getString(MediaFileOperationWorker.KEY_SUMMARY).orEmpty()
            val message = when (work.state) {
                WorkInfo.State.FAILED -> action + " failed"
                WorkInfo.State.CANCELLED -> action + " cancelled"
                WorkInfo.State.SUCCEEDED -> if (failedCount > 0 || summary.contains("album refresh failure", true)) action + " finished with errors" else action + " completed"
                else -> continue
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(latestFileOperationId) {
        if (latestFileOperationId != null) operationDialogVisible = true
    }
    LaunchedEffect(displayedFileOperation?.id, displayedFileOperation?.progress, displayedFileOperation?.outputData, latestFileOperationId, advancedLogVisible) {
        if (!advancedLogVisible) {
            operationLogText = ""
        } else {
            val operationId = displayedFileOperation?.id?.toString() ?: latestFileOperationId
            operationLogText = if (operationId == null) "" else withContext(Dispatchers.IO) {
                val logFile = File(context.filesDir, "media-operation-" + operationId + ".log")
                if (logFile.isFile) runCatching { logFile.readText() }
                    .getOrElse { "Unable to read detailed log: " + it.message }
                else "Operation log has not been created yet."
            }
        }
    }
    val selectedAlbum = remember(albums, selectedAlbumPath) {
        albums.firstOrNull { it.canonicalPath == selectedAlbumPath }
    }
    val selectedState = selectedAlbum?.id?.let(thumbnailStatesByAlbumId::get)
    val activeWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: workInfos.firstOrNull { !it.state.isFinished }
    val isGenerating = activeWork != null
    val progressMessage = activeWork?.let { work ->
        val processed = work.progress.getInt(
            com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker.KEY_PROGRESS_PROCESSED,
            0
        )
        val total = work.progress.getInt(
            com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker.KEY_PROGRESS_TOTAL,
            0
        )
        val failures = work.progress.getInt(
            com.amanrealhere.personalgallery.data.thumbnail.ThumbnailGenerationWorker.KEY_PROGRESS_FAILURES,
            0
        )
        val percent = if (total == 0) 0 else (processed * 100 / total).coerceIn(0, 100)
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                "Cover generation is queued"
            WorkInfo.State.RUNNING ->
                "Generating covers: $processed / $total ($percent%) · $failures failures"
            else -> "Thumbnail generation"
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingFileOperation.value != null && (!granted || !MediaFileOperationWorker.areNotificationsEnabled(context))) {
            Toast.makeText(context, "Notifications disabled", Toast.LENGTH_SHORT).show()
        }
        pendingGeneration.value?.invoke()
        pendingGeneration.value = null
        pendingFileOperation.value?.invoke()
        pendingFileOperation.value = null
    }

    fun startFileOperation(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingFileOperation.value = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!MediaFileOperationWorker.areNotificationsEnabled(context)) {
            Toast.makeText(context, "Notifications disabled", Toast.LENGTH_SHORT).show()
        }
        action()
    }
    fun startGeneration(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val marker = File(
                GalleryStorage.getInstance(context).root,
                ".notification-permission-requested"
            )
            if (!marker.exists()) {
                marker.createNewFile()
                pendingGeneration.value = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        action()
    }

    LaunchedEffect(selectedAlbum?.id) {
        if (selectedAlbum == null) {
            viewModel.clearAlbumImages()
        } else {
            viewModel.loadAlbumImages(selectedAlbum)
        }
    }

    // Reconcile on album selection only; avoid restarting the scan on resume.
    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) 280.dp else 76.dp,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "sidebar width"
    )

    BackHandler(
        enabled = destination == GalleryDestination.AddAlbum || selectedAlbumPath != null
    ) {
        if (destination == GalleryDestination.AddAlbum) {
            destination = GalleryDestination.Albums
        } else {
            selectedAlbumPath = null
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(sidebarWidth).fillMaxHeight()) {
                AlbumsSidebar(
                    sidebarExpanded = sidebarExpanded,
                    albums = albums,
                    thumbnailStates = thumbnailStates,
                    selectedAlbumPath = selectedAlbumPath,
                    isAlbumsOverviewSelected = destination == GalleryDestination.Albums && selectedAlbumPath == null,
                    isAddAlbumSelected = destination == GalleryDestination.AddAlbum,
                    onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
                    onAlbumsClick = {
                        destination = GalleryDestination.Albums
                        selectedAlbumPath = null
                    },
                    onAddAlbumClick = { destination = GalleryDestination.AddAlbum },
                    onAlbumClick = { album: Album ->
                        selectedAlbumPath = album.canonicalPath
                        destination = GalleryDestination.Albums
                    }
                )
            }

            Spacer(Modifier.width(1.dp).fillMaxHeight())

            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (destination) {
                    GalleryDestination.Albums -> {
                        if (selectedAlbum == null) {
                            HomeScreen(
                                albums = albums,
                                thumbnailStates = thumbnailStates,
                                isGenerating = isGenerating,
                                progressMessage = progressMessage,
                                onAlbumClick = { selectedAlbumPath = it.canonicalPath },
                                onAddAlbumClick = { destination = GalleryDestination.AddAlbum },
                                onRegenerateAll = {
                                    startGeneration { viewModel.regenerateAllThumbnails() }
                                }
                            )
                        } else {
                                        AlbumContentsScreen(
                                sidebarExpanded = sidebarExpanded,
                                album = selectedAlbum,
                                albums = albums,
                                onFileOperation = { type, sourcePaths, destinationAlbum ->
                                    startFileOperation {
                                        viewModel.startFileOperation(type, sourcePaths, destinationAlbum)
                                    }
                                },
                                images = images,
                                imageCount = indexedImageCount,
                                liveScanImageCount = liveScanImageCount,
                                isIndexing = isIndexingAlbum,
                                scanFinished = scanFinished,
                                scanComplete = scanComplete,
                                coverPath = selectedState?.coverThumbnailPath,
                                progressMessage = progressMessage,
                                isLoading = isGenerating,
                                onRegenerate = {
                                    startGeneration {
                                        viewModel.regenerateAlbumThumbnails(selectedAlbum.id)
                                    }
                                }
                            )
                        }
                    }

                    GalleryDestination.AddAlbum -> {
                        AddAlbumScreen(
                            viewModel = viewModel,
                            onAlbumSelected = { album ->
                                selectedAlbumPath = album.canonicalPath
                                destination = GalleryDestination.Albums
                            }
                        )
                    }
                }

                if (operationLaunchError != null) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("File operation could not be queued") },
                        text = { Text(operationLaunchError.orEmpty()) },
                        confirmButton = {
                            TextButton(onClick = { operationLaunchError = null }) { Text("Close") }
                        }
                    )
                }

                if (latestFileOperationId != null && operationDialogVisible) {
                    val workState = displayedFileOperation?.state
                    val operationFinished = workState?.isFinished == true || savedOperationResult.optString("status") == "COMPLETED"
                    val operationRunning = workState == WorkInfo.State.RUNNING
                    val operationType = displayedFileOperation?.outputData?.getString(MediaFileOperationWorker.KEY_TYPE)
                        ?: displayedFileOperation?.inputData?.getString(MediaFileOperationWorker.KEY_TYPE)
                        ?: savedOperationResult.optString("type", "COPY")
                    val operationName = if (operationType == MediaFileOperationWorker.TYPE_MOVE) "Move" else "Copy"
                    val savedSummary = displayedFileOperation?.outputData?.getString(MediaFileOperationWorker.KEY_SUMMARY)
                        ?: savedOperationResult.optString("summary")
                    val hasIssues = fileOperationFailed > 0 || savedSummary.contains("album refresh failure", ignoreCase = true)
                    val operationTitle = when {
                        operationFinished && workState == WorkInfo.State.FAILED -> "$operationName failed"
                        operationFinished && workState == WorkInfo.State.CANCELLED -> "$operationName cancelled"
                        operationFinished && hasIssues -> "$operationName finished with errors"
                        operationFinished -> "$operationName completed"
                        operationType == MediaFileOperationWorker.TYPE_MOVE -> "Moving images"
                        else -> "Copying images"
                    }
                    val totalUnits = fileOperationTotal.coerceAtLeast(1) * 1000
                    val progressFraction = (fileOperationProgressUnits.toFloat() / totalUnits).coerceIn(0f, 1f)
                    val progressPercent = (progressFraction * 100f).toInt()
                    val startTime = operationStartedAtElapsedRealtime.takeIf { it > 0L } ?: 0L
                    var stopwatchNow by remember(latestFileOperationId) { mutableStateOf(SystemClock.elapsedRealtime()) }
                    var frozenElapsed by remember(latestFileOperationId) { mutableStateOf<Long?>(null) }
                    LaunchedEffect(workState, startTime, operationDurationMs) {
                        if (operationFinished) {
                            frozenElapsed = if (operationDurationMs >= 0L) operationDurationMs
                                else if (startTime > 0L) (SystemClock.elapsedRealtime() - startTime).coerceAtLeast(0L) else 0L
                        } else if (operationRunning) {
                            frozenElapsed = null
                            while (true) {
                                stopwatchNow = SystemClock.elapsedRealtime()
                                delay(32)
                            }
                        }
                    }
                    val elapsed = frozenElapsed ?: if (operationRunning && startTime > 0L)
                        (stopwatchNow - startTime).coerceAtLeast(0L) else 0L
                    val hours = elapsed / 3_600_000L
                    val minutes = (elapsed / 60_000L) % 60
                    val seconds = (elapsed / 1_000L) % 60
                    val millis = elapsed % 1_000L
                    val timerText = if (hours > 0) String.format(java.util.Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis)
                        else String.format(java.util.Locale.US, "%02d:%02d.%03d", minutes, seconds, millis)
                    Dialog(
                        onDismissRequest = {},
                        properties = DialogProperties(usePlatformDefaultWidth = false)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 520.dp).padding(12.dp),
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(operationDialogScrollState),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(operationTitle, style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    "$fileOperationProcessed of $fileOperationTotal items · $fileOperationSucceeded succeeded · $fileOperationFailed failed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (fileOperationTotal > 0) {
                                    LinearProgressIndicator(progress = { progressFraction }, modifier = Modifier.fillMaxWidth())
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("$fileOperationProcessed of $fileOperationTotal items", style = MaterialTheme.typography.labelMedium)
                                        Text("$progressPercent%", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                if (fileOperationCurrent.isNotBlank() && !operationFinished) {
                                    Text(fileOperationCurrent, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                }
                                if (!operationFinished) {
                                    Text(fileOperationStatus, style = MaterialTheme.typography.bodyMedium)
                                    if (fileOperationDetail.isNotBlank()) {
                                        Text(fileOperationDetail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else if (savedSummary.isNotBlank()) {
                                    Text(savedSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text("Elapsed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(timerText, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                                }
                                val queuedCount = fileOperationWorkInfos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                                if (queuedCount > 0) Text("$queuedCount operation(s) queued", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { advancedLogVisible = !advancedLogVisible }) {
                                        Text(if (advancedLogVisible) "Summary" else "Advanced log")
                                    }
                                    TextButton(onClick = { operationDialogVisible = false; advancedLogVisible = false }) { Text("Close") }
                                }
                                if (advancedLogVisible) {
                                    Text("Operation log", style = MaterialTheme.typography.titleSmall)
                                    OperationLogView(operationLogText)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
