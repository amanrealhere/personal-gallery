package com.amanrealhere.personalgallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.screens.addalbum.AddAlbumScreen
import com.amanrealhere.personalgallery.ui.screens.home.HomeScreen

private enum class GalleryDestination {
    Albums,
    AddAlbum
}

@Composable
fun GalleryShell(
    viewModel: GalleryViewModel
) {
    var sidebarExpanded by remember { mutableStateOf(true) }
    var selectedAlbumPath by remember { mutableStateOf<String?>(null) }
    var destination by remember {
        mutableStateOf(GalleryDestination.Albums)
    }

    val albums by viewModel.albums.collectAsStateWithLifecycle()

    val selectedAlbum = remember(albums, selectedAlbumPath) {
        albums.firstOrNull {
            it.canonicalPath == selectedAlbumPath
        }
    }

    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) 280.dp else 76.dp,
        label = "sidebar-width"
    )

    BackHandler(
        enabled = destination == GalleryDestination.AddAlbum ||
            selectedAlbumPath != null
    ) {
        if (destination == GalleryDestination.AddAlbum) {
            destination = GalleryDestination.Albums
        } else {
            selectedAlbumPath = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
            ) {
                AlbumsSidebar(
                    sidebarExpanded = sidebarExpanded,
                    albums = albums,
                    selectedAlbumPath = selectedAlbumPath,
                    isAlbumsOverviewSelected =
                        destination == GalleryDestination.Albums &&
                            selectedAlbumPath == null,
                    isAddAlbumSelected = destination == GalleryDestination.AddAlbum,
                    onToggleSidebar = {
                        sidebarExpanded = !sidebarExpanded
                    },
                    onAlbumsClick = {
                        destination = GalleryDestination.Albums
                        selectedAlbumPath = null
                    },
                    onAddAlbumClick = {
                        destination = GalleryDestination.AddAlbum
                    },
                    onAlbumClick = { album: Album ->
                        selectedAlbumPath = album.canonicalPath
                        destination = GalleryDestination.Albums
                    }
                )
            }

            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when (destination) {
                    GalleryDestination.Albums -> {
                        if (selectedAlbum == null) {
                            HomeScreen(
                                albums = albums,
                                onAlbumClick = { album ->
                                    selectedAlbumPath = album.canonicalPath
                                },
                                onAddAlbumClick = {
                                    destination = GalleryDestination.AddAlbum
                                }
                            )
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.background
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp)
                                ) {
                                    Text(
                                        text = selectedAlbum.name,
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )

                                    Spacer(Modifier.height(8.dp))

                                    Text(
                                        text = selectedAlbum.canonicalPath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(Modifier.height(24.dp))

                                    Text(
                                        text = "Image indexing will be implemented next.",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
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
