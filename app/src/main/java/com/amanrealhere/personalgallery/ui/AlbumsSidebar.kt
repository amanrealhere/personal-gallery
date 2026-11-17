package com.amanrealhere.personalgallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amanrealhere.personalgallery.domain.model.Album

@Composable
fun AlbumsSidebar(
    sidebarExpanded: Boolean,
    albumsExpanded: Boolean,
    albums: List<Album>,
    selectedAlbumPath: String?,
    onAlbumsClick: () -> Unit,
    onAddAlbumClick: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp)
        ) {
            NavigationDrawerItem(
                label = {
                    Text(if (sidebarExpanded) "Albums" else "A")
                },
                selected = albumsExpanded,
                onClick = onAlbumsClick,
                modifier = Modifier.fillMaxWidth()
            )

            if (sidebarExpanded && albumsExpanded) {
                if (albums.isEmpty()) {
                    Text(
                        text = "No albums added yet",
                        modifier = Modifier.padding(
                            start = 12.dp,
                            top = 12.dp,
                            bottom = 16.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(
                            items = albums,
                            key = { album -> album.id }
                        ) { album ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = album.name,
                                        maxLines = 1
                                    )
                                },
                                selected = selectedAlbumPath == album.canonicalPath,
                                onClick = { onAlbumClick(album) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            NavigationDrawerItem(
                label = {
                    Text(if (sidebarExpanded) "Add album" else "+")
                },
                selected = false,
                onClick = onAddAlbumClick,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
