package com.amanrealhere.personalgallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.amanrealhere.personalgallery.R
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.ThumbnailPreview

@Composable
fun AlbumsSidebar(
    sidebarExpanded: Boolean,
    albums: List<Album>,
    thumbnailStates: List<AlbumThumbnailStateEntity>,
    selectedAlbumPath: String?,
    isAlbumsOverviewSelected: Boolean,
    isAddAlbumSelected: Boolean,
    onToggleSidebar: () -> Unit,
    onAlbumsClick: () -> Unit,
    onAddAlbumClick: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    val thumbnailStatesByAlbumId = remember(thumbnailStates) {
        thumbnailStates.associateBy { it.albumId }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        if (sidebarExpanded) {
            ExpandedAlbumsSidebar(
                albums = albums,
                thumbnailStatesByAlbumId = thumbnailStatesByAlbumId,
                selectedAlbumPath = selectedAlbumPath,
                isAlbumsOverviewSelected = isAlbumsOverviewSelected,
                isAddAlbumSelected = isAddAlbumSelected,
                onToggleSidebar = onToggleSidebar,
                onAlbumsClick = onAlbumsClick,
                onAddAlbumClick = onAddAlbumClick,
                onAlbumClick = onAlbumClick
            )
        } else {
            CollapsedAlbumsSidebar(
                albums = albums,
                thumbnailStatesByAlbumId = thumbnailStatesByAlbumId,
                selectedAlbumPath = selectedAlbumPath,
                isAlbumsOverviewSelected = isAlbumsOverviewSelected,
                isAddAlbumSelected = isAddAlbumSelected,
                onToggleSidebar = onToggleSidebar,
                onAlbumsClick = onAlbumsClick,
                onAddAlbumClick = onAddAlbumClick,
                onAlbumClick = onAlbumClick
            )
        }
    }
}

@Composable
private fun ExpandedAlbumsSidebar(
    albums: List<Album>,
    thumbnailStatesByAlbumId: Map<String, AlbumThumbnailStateEntity>,
    selectedAlbumPath: String?,
    isAlbumsOverviewSelected: Boolean,
    isAddAlbumSelected: Boolean,
    onToggleSidebar: () -> Unit,
    onAlbumsClick: () -> Unit,
    onAddAlbumClick: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleSidebar) {
                Icon(Icons.Filled.Menu, contentDescription = "Collapse sidebar", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Personal Gallery",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationDrawerItem(
                label = { Text("Albums") },
                icon = {
                    Image(
                        painter = painterResource(R.drawable.ic_gallery_folder),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                selected = isAlbumsOverviewSelected,
                onClick = onAlbumsClick,
                modifier = Modifier.weight(1f)
            )
            if (isAddAlbumSelected) {
                FilledTonalIconButton(onClick = onAddAlbumClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Add album")
                }
            } else {
                IconButton(onClick = onAddAlbumClick) {
                    Icon(Icons.Filled.Add, contentDescription = "Add album", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(
            text = "COLLECTIONS",
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(albums, key = { album -> album.id }) { album ->
                val state = thumbnailStatesByAlbumId[album.id]
                NavigationDrawerItem(
                    label = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(album.name, maxLines = 1)
                            Text(
                                "${state?.imageCount ?: 0} ${if (state?.imageCount == 1) "photo" else "photos"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    },
                    icon = {
                        ThumbnailPreview(
                            path = state?.coverThumbnailPath,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
                            showFolderPlaceholder = true
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

@Composable
private fun CollapsedAlbumsSidebar(
    albums: List<Album>,
    thumbnailStatesByAlbumId: Map<String, AlbumThumbnailStateEntity>,
    selectedAlbumPath: String?,
    isAlbumsOverviewSelected: Boolean,
    isAddAlbumSelected: Boolean,
    onToggleSidebar: () -> Unit,
    onAlbumsClick: () -> Unit,
    onAddAlbumClick: () -> Unit,
    onAlbumClick: (Album) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 8.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(onClick = onToggleSidebar, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Menu, contentDescription = "Expand sidebar")
        }

        Spacer(Modifier.height(10.dp))
        CollapsedSidebarButton(
            selected = isAlbumsOverviewSelected,
            contentDescription = "Albums",
            onClick = onAlbumsClick
        ) {
            Image(
                painter = painterResource(R.drawable.ic_gallery_folder),
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        if (isAddAlbumSelected) {
            FilledTonalIconButton(onClick = onAddAlbumClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add album")
            }
        } else {
            IconButton(onClick = onAddAlbumClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add album", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(10.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(albums, key = { album -> album.id }) { album ->
                val state = thumbnailStatesByAlbumId[album.id]
                CollapsedSidebarButton(
                    selected = selectedAlbumPath == album.canonicalPath,
                    contentDescription = album.name,
                    onClick = { onAlbumClick(album) }
                ) {
                    ThumbnailPreview(
                        path = state?.coverThumbnailPath,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)),
                        showFolderPlaceholder = true
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedSidebarButton(
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier.size(56.dp).clip(shape).clickable(onClick = onClick).semantics { this.contentDescription = contentDescription },
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            content()
        }
    }
}
