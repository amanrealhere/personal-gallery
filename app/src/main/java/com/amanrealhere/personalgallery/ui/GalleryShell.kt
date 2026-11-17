package com.amanrealhere.personalgallery.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.screens.addalbum.AddAlbumScreen
import com.amanrealhere.personalgallery.ui.screens.home.HomeScreen

private enum class SidebarDestination {
    Albums,
    AddAlbum
}

@Composable
fun GalleryShell(
    viewModel: GalleryViewModel
) {
    var sidebarExpanded by remember { mutableStateOf(true) }
    var albumsExpanded by remember { mutableStateOf(true) }
    var selectedAlbumPath by remember { mutableStateOf<String?>(null) }

    var destination by remember {
        mutableStateOf(SidebarDestination.Albums)
    }

    val albums by viewModel.albums.collectAsStateWithLifecycle()

    val selectedAlbum = remember(albums, selectedAlbumPath) {
        albums.firstOrNull {
            it.canonicalPath == selectedAlbumPath
        }
    }

    val sidebarWidth by animateDpAsState(
        targetValue = if (sidebarExpanded) 256.dp else 80.dp,
        label = "sidebar-width"
    )

    Column(modifier = Modifier.fillMaxSize()) {

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Personal Gallery",
                    style = MaterialTheme.typography.titleLarge
                )

                TextButton(
                    onClick = {
                        sidebarExpanded = !sidebarExpanded
                    }
                ) {
                    Text(
                        if (sidebarExpanded) {
                            "Collapse sidebar"
                        } else {
                            "Expand sidebar"
                        }
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {

            Box(
                modifier = Modifier
                    .width(sidebarWidth)
                    .fillMaxHeight()
            ) {
                AlbumsSidebar(
                    sidebarExpanded = sidebarExpanded,
                    albumsExpanded = albumsExpanded,
                    albums = albums,
                    selectedAlbumPath = selectedAlbumPath,
                    onAlbumsClick = {
                        if (!sidebarExpanded) {
                            sidebarExpanded = true
                            albumsExpanded = true
                        } else {
                            albumsExpanded = !albumsExpanded
                        }
                    },
                    onAddAlbumClick = {
                        sidebarExpanded = true
                        destination = SidebarDestination.AddAlbum
                    },
                    onAlbumClick = { album: Album ->
                        selectedAlbumPath = album.canonicalPath
                        destination = SidebarDestination.Albums
                    }
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when (destination) {

                    SidebarDestination.Albums -> {
                        if (selectedAlbum == null) {
                            HomeScreen()
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(32.dp),
                                verticalArrangement = Arrangement.Top
                            ) {
                                Text(
                                    text = selectedAlbum.name,
                                    style = MaterialTheme.typography.headlineMedium
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = selectedAlbum.canonicalPath,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Spacer(modifier = Modifier.height(24.dp))

                                Text(
                                    text = "Image indexing will be implemented next.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }

                    SidebarDestination.AddAlbum -> {
                        AddAlbumScreen(
                            viewModel = viewModel,
                            onAlbumSelected = { album ->
                                selectedAlbumPath = album.canonicalPath
                                destination = SidebarDestination.Albums
                            }
                        )
                    }
                }
            }
        }
    }
}
