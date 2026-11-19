package com.amanrealhere.personalgallery.ui.screens.album

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.MediaThumbnailPreview
import com.amanrealhere.personalgallery.ui.components.ThumbnailPreview

@Composable
fun AlbumContentsScreen(
    album: Album,
    images: LazyPagingItems<MediaItemEntity>,
    imageCount: Int,
    isIndexing: Boolean,
    coverPath: String?,
    progressMessage: String?,
    isLoading: Boolean,
    onRegenerate: () -> Unit
) {
    val isRefreshing = images.loadState.refresh is LoadState.Loading
    val hasNoItems = images.itemCount == 0
    var minimumCellSize by remember { mutableStateOf(148.dp) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(album.id) { gridState.scrollToItem(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        album.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$imageCount ${if (imageCount == 1) "image" else "images"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onRegenerate, enabled = !isLoading) {
                    Text("Regenerate cover")
                }
            }

            if (progressMessage != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text(
                        progressMessage,
                        modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (hasNoItems && (isIndexing || isRefreshing)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (hasNoItems) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ThumbnailPreview(
                            path = coverPath,
                            modifier = Modifier.fillMaxWidth(0.35f).height(120.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No supported images found in this folder.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minimumCellSize),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            var previousDistance = 0f
                            var accumulatedScale = 1f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val first = pressed[0].position
                                    val second = pressed[1].position
                                    val dx = second.x - first.x
                                    val dy = second.y - first.y
                                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (previousDistance > 0f && distance > 0f) {
                                        accumulatedScale *= distance / previousDistance
                                        if (accumulatedScale >= 1.12f || accumulatedScale <= 0.89f) {
                                            minimumCellSize = (minimumCellSize * accumulatedScale)
                                                .coerceIn(92.dp, 240.dp)
                                            accumulatedScale = 1f
                                        }
                                    }
                                    previousDistance = distance
                                    event.changes.forEach { it.consume() }
                                } else {
                                    previousDistance = 0f
                                    accumulatedScale = 1f
                                    if (event.changes.none { it.pressed }) break
                                }
                            }
                        }
                    },
                    contentPadding = PaddingValues(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        count = images.itemCount,
                        key = images.itemKey { it.id },
                        contentType = images.itemContentType { "media-thumbnail" }
                    ) { index ->
                        val media = images[index]
                        if (media != null) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                                )
                            ) {
                                Column {
                                    MediaThumbnailPreview(
                                        media = media,
                                        modifier = Modifier.fillMaxWidth().height(148.dp)
                                    )
                                    Text(
                                        media.displayName,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
