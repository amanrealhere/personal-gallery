package com.amanrealhere.personalgallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import com.amanrealhere.personalgallery.data.fileoperations.MediaFileOperationWorker
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.screens.addalbum.AddAlbumScreen
import com.amanrealhere.personalgallery.ui.screens.album.AlbumContentsScreen
import com.amanrealhere.personalgallery.ui.screens.home.HomeScreen
import java.io.File

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
    val activeFileOperation = fileOperationWorkInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    val fileOperationProcessed = activeFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_PROCESSED, 0) ?: 0
    val fileOperationTotal = activeFileOperation?.progress?.getInt(MediaFileOperationWorker.KEY_TOTAL, 0) ?: 0
    val fileOperationCurrent = activeFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_CURRENT).orEmpty()
    val fileOperationStatus = activeFileOperation?.progress?.getString(MediaFileOperationWorker.KEY_STATUS)
        ?: if (activeFileOperation != null) "Working…" else ""
    LaunchedEffect(viewModel) {
        viewModel.fileOperationMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
    ) {
        pendingGeneration.value?.invoke()
        pendingGeneration.value = null
        pendingFileOperation.value?.invoke()
        pendingFileOperation.value = null
    }

    fun startFileOperation(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val marker = File(GalleryStorage.getInstance(context).root, ".notification-permission-requested")
            if (!marker.exists()) {
                marker.createNewFile()
                pendingFileOperation.value = action
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
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

                if (activeFileOperation != null) {
                    Surface(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 5.dp
                    ) {
                        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
                            Text(
                                if (fileOperationTotal > 0) "$fileOperationStatus · $fileOperationProcessed / $fileOperationTotal"
                                else fileOperationStatus,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (fileOperationTotal > 0) {
                                LinearProgressIndicator(
                                    progress = { (fileOperationProcessed.toFloat() / fileOperationTotal).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            if (fileOperationCurrent.isNotBlank()) {
                                Text(fileOperationCurrent, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
