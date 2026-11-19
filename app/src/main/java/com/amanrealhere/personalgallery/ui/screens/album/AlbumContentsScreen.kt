package com.amanrealhere.personalgallery.ui.screens.album

import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.MediaGridPagingAdapter
import com.amanrealhere.personalgallery.ui.components.ThumbnailPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor

@Composable
fun AlbumContentsScreen(
    album: Album,
    images: Flow<PagingData<MediaItemEntity>>,
    imageCount: Int,
    isIndexing: Boolean,
    coverPath: String?,
    progressMessage: String?,
    isLoading: Boolean,
    onRegenerate: () -> Unit
) {
    val pagingAdapter = remember { MediaGridPagingAdapter() }
    val loadState by pagingAdapter.loadStateFlow.collectAsState(initial = pagingAdapter.loadState)
    var hasItems by remember(pagingAdapter) { mutableStateOf(pagingAdapter.itemCount > 0) }
    var minimumCellSizeDp by remember { mutableFloatStateOf(148f) }
    var recyclerView by remember { mutableStateOf<RecyclerView?>(null) }

    LaunchedEffect(images, pagingAdapter) {
        images.collectLatest { pagingData ->
            pagingAdapter.submitData(pagingData)
        }
    }

    DisposableEffect(pagingAdapter) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update() {
                hasItems = pagingAdapter.itemCount > 0
            }
            override fun onChanged() = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
        }
        pagingAdapter.registerAdapterDataObserver(observer)
        hasItems = pagingAdapter.itemCount > 0
        onDispose { pagingAdapter.unregisterAdapterDataObserver(observer) }
    }

    LaunchedEffect(album.id) {
        recyclerView?.post { recyclerView?.scrollToPosition(0) }
    }

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

            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        val density = viewContext.resources.displayMetrics.density
                        val rv = RecyclerView(viewContext).apply {
                            clipToPadding = false
                            setPadding(
                                (18f * density).toInt(),
                                (18f * density).toInt(),
                                (18f * density).toInt(),
                                (18f * density).toInt()
                            )
                            setHasFixedSize(true)
                            setItemViewCacheSize(4)
                            itemAnimator = null
                            layoutManager = GridLayoutManager(
                                viewContext,
                                calculateSpanCount(width, paddingLeft, paddingRight, density, minimumCellSizeDp)
                            ).apply {
                                isItemPrefetchEnabled = true
                            }
                            adapter = pagingAdapter
                        }

                        val scaleDetector = ScaleGestureDetector(
                            viewContext,
                            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                                private var accumulatedScale = 1f
                                override fun onScale(detector: ScaleGestureDetector): Boolean {
                                    accumulatedScale *= detector.scaleFactor
                                    if (accumulatedScale >= 1.12f || accumulatedScale <= 0.89f) {
                                        minimumCellSizeDp = (minimumCellSizeDp * accumulatedScale)
                                            .coerceIn(92f, 240f)
                                        accumulatedScale = 1f
                                    }
                                    return true
                                }
                                override fun onScaleEnd(detector: ScaleGestureDetector) {
                                    accumulatedScale = 1f
                                }
                            }
                        )
                        rv.setOnTouchListener { _, event ->
                            scaleDetector.onTouchEvent(event)
                            false
                        }
                        rv.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                            updateGridSpanCount(view as RecyclerView, density, minimumCellSizeDp)
                        }
                        recyclerView = rv
                        rv
                    },
                    update = { rv ->
                        if (rv.adapter !== pagingAdapter) rv.adapter = pagingAdapter
                        updateGridSpanCount(rv, rv.resources.displayMetrics.density, minimumCellSizeDp)
                    },
                    onRelease = { rv ->
                        rv.adapter = null
                        if (recyclerView === rv) recyclerView = null
                    }
                )

                if (!hasItems && (isIndexing || loadState.refresh is LoadState.Loading)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (!hasItems && loadState.refresh !is LoadState.Loading) {
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
                }
            }
        }
    }
}

private fun calculateSpanCount(
    widthPx: Int,
    paddingLeftPx: Int,
    paddingRightPx: Int,
    density: Float,
    minimumCellSizeDp: Float
): Int {
    if (widthPx <= 0) return 1
    val availableDp = (widthPx - paddingLeftPx - paddingRightPx) / density
    val spacingDp = 12f
    return floor((availableDp + spacingDp) / (minimumCellSizeDp + spacingDp))
        .toInt()
        .coerceAtLeast(1)
}

private fun updateGridSpanCount(
    recyclerView: RecyclerView,
    density: Float,
    minimumCellSizeDp: Float
) {
    val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
    val spanCount = calculateSpanCount(
        recyclerView.width,
        recyclerView.paddingLeft,
        recyclerView.paddingRight,
        density,
        minimumCellSizeDp
    )
    if (layoutManager.spanCount != spanCount) {
        layoutManager.spanCount = spanCount
    }
}
