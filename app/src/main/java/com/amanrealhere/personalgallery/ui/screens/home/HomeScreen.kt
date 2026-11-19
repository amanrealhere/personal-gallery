package com.amanrealhere.personalgallery.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.amanrealhere.personalgallery.data.local.AlbumThumbnailStateEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.ThumbnailPreview

@Composable
fun HomeScreen(
    albums: List<Album>,
    thumbnailStates: List<AlbumThumbnailStateEntity>,
    isGenerating: Boolean,
    progressMessage: String?,
    onAlbumClick: (Album) -> Unit,
    onAddAlbumClick: () -> Unit,
    onRegenerateAll: () -> Unit
) {
    val thumbnailStatesByAlbumId = remember(thumbnailStates) {
        thumbnailStates.associateBy { it.albumId }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (albums.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Your albums", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Your gallery is empty. Add a folder to get started.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledIconButton(onClick = onAddAlbumClick) {
                        Icon(Icons.Filled.Add, contentDescription = "Add album")
                    }
                    Text("Add album", style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 28.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Your albums", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "${albums.size} ${if (albums.size == 1) "album" else "albums"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = onRegenerateAll,
                            enabled = !isGenerating
                        ) {
                            Text("Regenerate covers")
                        }
                        FilledIconButton(
                            onClick = onAddAlbumClick,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add album")
                        }
                    }
                }

                if (progressMessage != null) {
                    Text(
                        progressMessage,
                        modifier = Modifier.padding(start = 28.dp, top = 4.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 180.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(
                        items = albums,
                        key = { it.id },
                        contentType = { "album-card" }
                    ) { album ->
                        val state = thumbnailStatesByAlbumId[album.id]
                        Card(
                            onClick = { onAlbumClick(album) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer
                            )
                        ) {
                            Column {
                                ThumbnailPreview(
                                    path = state?.coverThumbnailPath,
                                    modifier = Modifier.fillMaxWidth().padding(12.dp).height(130.dp)
                                )
                                Text(
                                    album.name,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${state?.imageCount ?: 0} ${if (state?.imageCount == 1) "image" else "images"}",
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 3.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
