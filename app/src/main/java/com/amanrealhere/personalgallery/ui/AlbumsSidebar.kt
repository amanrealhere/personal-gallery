package com.amanrealhere.personalgallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.amanrealhere.personalgallery.domain.model.Album

@Composable
fun AlbumsSidebar(
    sidebarExpanded: Boolean,
    albums: List<Album>,
    selectedAlbumPath: String?,
    isAlbumsOverviewSelected: Boolean,
    isAddAlbumSelected: Boolean,
    onToggleSidebar: () -> Unit,
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
                .padding(horizontal = 10.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = if (sidebarExpanded) {
                            "Collapse sidebar"
                        } else {
                            "Expand sidebar"
                        },
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (sidebarExpanded) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Personal Gallery",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationDrawerItem(
                    label = {
                        Text(if (sidebarExpanded) "Albums" else "")
                    },
                    icon = {
                        Image(
                            painter = painterResource(R.drawable.ic_gallery_folder),
                            contentDescription = null
                        )
                    },
                    selected = isAlbumsOverviewSelected,
                    onClick = onAlbumsClick,
                    modifier = Modifier.weight(1f)
                )

                if (sidebarExpanded) {
                    if (isAddAlbumSelected) {
                        FilledTonalIconButton(onClick = onAddAlbumClick) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add album"
                            )
                        }
                    } else {
                        IconButton(onClick = onAddAlbumClick) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add album",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (!sidebarExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isAddAlbumSelected) {
                        FilledTonalIconButton(onClick = onAddAlbumClick) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add album"
                            )
                        }
                    } else {
                        IconButton(onClick = onAddAlbumClick) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add album",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (sidebarExpanded) {
                Text(
                    text = "YOUR ALBUMS",
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = 22.dp,
                        bottom = 10.dp
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(18.dp))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = albums,
                    key = { album -> album.id }
                ) { album ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = if (sidebarExpanded) album.name else "",
                                maxLines = 1
                            )
                        },
                        icon = {
                            Image(
                                painter = painterResource(R.drawable.ic_gallery_folder),
                                contentDescription = album.name
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
}
