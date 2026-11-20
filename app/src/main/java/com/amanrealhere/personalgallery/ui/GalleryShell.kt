package com.amanrealhere.personalgallery.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.amanrealhere.personalgallery.data.storage.GalleryStorage
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.screens.addalbum.AddAlbumScreen
import com.amanrealhere.personalgallery.ui.screens.album.AlbumContentsScreen
import com.amanrealhere.personalgallery.ui.screens.home.HomeScreen
import java.io.File

private enum class GalleryDestination { Albums, AddAlbum }

@Composable
fun GalleryShell(viewModel: GalleryViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var sidebarExpanded by remember { mutableStateOf(true) }
    var selectedAlbumPath by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf(GalleryDestination.Albums) }
    val pendingGeneration = remember { mutableStateOf<(() -> Unit)?>(null) }

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val thumbnailStates by viewModel.thumbnailStates.collectAsStateWithLifecycle()
    val thumbnailStatesByAlbumId = remember(thumbnailStates) {
        thumbnailStates.associateBy { it.albumId }
    }
    val images = viewModel.albumImages
    val isIndexingAlbum by viewModel.isIndexingAlbum.collectAsStateWithLifecycle()
    val indexedImageCount by viewModel.albumImageCount.collectAsStateWithLifecycle()
    val workInfos by viewModel.thumbnailWorkInfos.collectAsStateWithLifecycle(initialValue = emptyList())

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

    DisposableEffect(lifecycleOwner, selectedAlbum?.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                selectedAlbum?.let(viewModel::loadAlbumImages)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sidebarWidth = if (sidebarExpanded) 280.dp else 76.dp

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
                                images = images,
                                imageCount = indexedImageCount,
                                isIndexing = isIndexingAlbum,
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
            }
        }
    }
}
