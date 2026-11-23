package com.amanrealhere.personalgallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
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
    var savedOperationState by remember { mutableStateOf(JSONObject())
    var savedOperationResult by remember { mutableStateOf(JSONObject()) }
    LaunchedEffect(latestFileOperationId, displayedFileOperation?.state, displayedFileOperation?.progress, displayedFileOperation?.outputData) {
        val id = latestFileOperationId
        savedOperationState = if (id == null) JSONObject() else withContext(Dispatchers.IO) {
            val stateFile = File(context.filesDir, "media-operation-" + id + ".state.json")
            runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        }
        savedOperationResult = savedOperationState.optJSONObject("result") ?: JSONObject()
    }
    val fileOperationProcessed = displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_PROCESSED, 0)
        ?: displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_PROCESSED, 0) ?: savedOperationResult.optInt("processed", 0)
    val fileOperationTotal = displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_TOTAL, 0)
        ?: displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_TOTAL, 0) ?: savedOperationResult.optInt("total", 0)
    val fileOperationSucceeded = displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, 0)
        ?: displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_SUCCEEDED, 0) ?: savedOperationResult.optInt("succeeded", 0)
    val fileOperationFailed = displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_FAILED, 0)
        ?: displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_FAILED, 0) ?: savedOperationResult.optInt("failed", 0)
    val fileOperationCurrent = displayedFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_CURRENT).orEmpty()
    val fileOperationDetail = displayedFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_DETAIL).orEmpty()
    val fileOperationProgressUnits = displayedFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, fileOperationProcessed * 1000)
        ?: displayedFileOperation?.outputData?.getInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, fileOperationProcessed * 1000)
        ?: savedOperationResult.optInt(MediaFileOperationWorker.KEY_PROGRESS_UNITS, fileOperationProcessed * 1000)
    val operationStartedAtMillis = savedOperationState.optLong("startedAtMillis", 0L)
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
    val operationLogScrollState = rememberScrollState()
    val operationDialogScrollState = rememberScrollState()
    var operationLaunchError by remember { mutableStateOf<String?>(null) }
    val pendingToastOperationIds = remember { mutableSetOf<String>() }
    LaunchedEffect(viewModel) {
        launch { viewModel.fileOperationStartedIds.collect { pendingToastOperationIds.add(it) } }
        launch { viewModel.fileOperationMessages.collect { operationLaunchError = it } }
    }
    LaunchedEffect(fileOperationWorkInfos) {
        for (work in fileOperationWorkInfos.filter { it.state.isFinished }) {
            val id = work.id.toString()
            if (!pendingToastOperationIds.remove(id)) continue
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
    LaunchedEffect(operationLogText, advancedLogVisible) {
        if (advancedLogVisible) operationLogScrollState.animateScrollTo(operationLogScrollState.maxValue)
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
        if (!granted && pendingFileOperation.value != null) {
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
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text(if (displayedFileOperation?.state?.isFinished == true) "File operation finished" else "File operation") },
                        text = {
                            Column(Modifier.fillMaxWidth().verticalScroll(operationLogScrollState)) {
                                val queuedOperations = fileOperationWorkInfos.count {
                                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                                }
                                if (displayedFileOperation != null) {
                                    Text(
                                        "${displayedFileOperation.state.name} · $fileOperationProcessed / $fileOperationTotal · $fileOperationSucceeded succeeded · $fileOperationFailed failed",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                } else {
                                    Text(
                                        "Saved operation result · $fileOperationProcessed / $fileOperationTotal · $fileOperationSucceeded succeeded · $fileOperationFailed failed",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                if (queuedOperationDetails.isNotEmpty()) {
                                    Text("Queued operations", style = MaterialTheme.typography.titleSmall)
                                    queuedOperationDetails.forEach { detail ->
                                        Text(
                                            detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 3
                                        )
                                    }
                                } else if (queuedOperations > 0) {
                                    Text("$queuedOperations operation(s) queued", style = MaterialTheme.typography.bodySmall)
                                }
                                if (fileOperationTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { (fileOperationProcessed.toFloat() / fileOperationTotal).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                    )
                                }
                                if (fileOperationCurrent.isNotBlank()) Text("Current: $fileOperationCurrent")
                                Text("Status: $fileOperationStatus", style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(12.dp))
                                Text("Operation log", style = MaterialTheme.typography.titleSmall)
                                Text(operationLogText, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { operationDialogVisible = false }) { Text("Close") }
                        }
                    )
                }
            }
        }
    }
}
