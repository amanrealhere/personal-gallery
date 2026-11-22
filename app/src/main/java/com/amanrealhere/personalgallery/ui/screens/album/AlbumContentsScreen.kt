package com.amanrealhere.personalgallery.ui.screens.album

import android.view.ScaleGestureDetector
import androidx.activity.compose.BackHandler
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.paging.PagingData
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.MediaGridPagingAdapter
import com.amanrealhere.personalgallery.ui.components.ThumbnailPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.floor

@Composable
fun AlbumContentsScreen(
    sidebarExpanded: Boolean,
    album: Album,
    images: Flow<PagingData<MediaItemEntity>>,
    imageCount: Int,
    liveScanImageCount: Int,
    isIndexing: Boolean,
    scanFinished: Boolean,
    scanComplete: Boolean,
    coverPath: String?,
    progressMessage: String?,
    isLoading: Boolean,
    albums: List<Album>,
    onFileOperation: (type: String, sourcePaths: List<String>, destination: Album) -> Unit,
    onRegenerate: () -> Unit
) {
    var selectedPaths by remember(album.id) { mutableStateOf<Set<String>>(emptySet()) }
    var destinationPickerOpen by remember(album.id) { mutableStateOf(false) }
    var pendingOperationType by remember(album.id) { mutableStateOf("COPY") }
    BackHandler(enabled = selectedPaths.isNotEmpty()) { selectedPaths = emptySet() }

    // Use a fresh PagingAdapter for each album to avoid carrying recycled cells
    // and adapter state across different PagingData generations.
    val pagingAdapter = remember(album.id) { MediaGridPagingAdapter() }
    val latestSelection = rememberUpdatedState(selectedPaths)
    LaunchedEffect(selectedPaths, pagingAdapter) {
        pagingAdapter.updateSelection(selectedPaths) { path ->
            val current = latestSelection.value
            selectedPaths = if (path in current) current - path else current + path
        }
    }
    var loadState by remember(pagingAdapter) { mutableStateOf<androidx.paging.CombinedLoadStates?>(null) }
    LaunchedEffect(pagingAdapter) {
        pagingAdapter.loadStateFlow.collect { loadState = it }
    }

    var hasItems by remember(pagingAdapter) { mutableStateOf(pagingAdapter.itemCount > 0) }
    var initialPagingAccessHintSent by remember(pagingAdapter) { mutableStateOf(false) }
    DisposableEffect(pagingAdapter) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update() {
                hasItems = pagingAdapter.itemCount > 0
                if (!initialPagingAccessHintSent && pagingAdapter.itemCount > 0) {
                    initialPagingAccessHintSent = true
                    pagingAdapter.requestLoad(0)
                }
            }
            override fun onChanged() = update()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = update()
        }
        pagingAdapter.registerAdapterDataObserver(observer)
        hasItems = pagingAdapter.itemCount > 0
        onDispose { pagingAdapter.unregisterAdapterDataObserver(observer) }
    }

    var minimumCellSizeDp by remember { mutableFloatStateOf(148f) }
    var recyclerView by remember { mutableStateOf<RecyclerView?>(null) }
    var expandedHeaderHeightPx by remember(album.id) { mutableStateOf(0) }

    // While scanning, report discoveries rather than presenting the previous catalogue total as current.
    val headerCountLabel = when {
        isIndexing -> if (liveScanImageCount == 0) "Scanning…" else "${liveScanImageCount} found"
        scanFinished && scanComplete -> "${liveScanImageCount} ${if (liveScanImageCount == 1) "image" else "images"}"
        scanFinished && liveScanImageCount > 0 -> "≥ ${liveScanImageCount} found"
        else -> "${imageCount} ${if (imageCount == 1) "image" else "images"}"
    }

    // Keep one stable adapter composition in both sidebar modes: the header slot
    // remains at position 0 and photos always start at position 1.
    val headerAdapter = remember(album.id) {
        CollapsedAlbumHeaderAdapter(
            albumName = album.name,
            countLabel = headerCountLabel,
            isLoading = isLoading,
            progressMessage = progressMessage,
            visible = !sidebarExpanded,
            onRegenerate = onRegenerate
        )
    }
    SideEffect {
        headerAdapter.update(
            albumName = album.name,
            countLabel = headerCountLabel,
            isLoading = isLoading,
            progressMessage = progressMessage,
            visible = !sidebarExpanded
        )
    }
    val concatAdapter = remember(headerAdapter, pagingAdapter) {
        ConcatAdapter(headerAdapter, pagingAdapter)
    }

    LaunchedEffect(images, pagingAdapter) {
        images.collectLatest { pagingData -> pagingAdapter.submitData(pagingData) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            key(album.id) {
                AndroidView(
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    factory = { viewContext ->
                        val viewDensity = viewContext.resources.displayMetrics.density
                        val rv = RecyclerView(viewContext).apply {
                            clipChildren = true
                            clipToPadding = true
                            setPadding(
                                (18f * viewDensity).toInt(),
                                if (sidebarExpanded) expandedGridTopPaddingPx(expandedHeaderHeightPx, viewDensity) else 0,
                                (18f * viewDensity).toInt(),
                                (18f * viewDensity).toInt()
                            )
                            setHasFixedSize(true)
                            setItemViewCacheSize(4)
                            itemAnimator = null

                            val gridLayoutManager = GridLayoutManager(
                                viewContext,
                                calculateSpanCount(width, paddingLeft, paddingRight, viewDensity, minimumCellSizeDp)
                            ).apply {
                                isItemPrefetchEnabled = true
                            }
                            gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                                override fun getSpanSize(position: Int): Int =
                                    if (position == 0) gridLayoutManager.spanCount else 1
                            }
                            layoutManager = gridLayoutManager
                            adapter = concatAdapter
                            tag = sidebarExpanded
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
                            updateGridSpanCount(
                                view as RecyclerView,
                                viewDensity,
                                minimumCellSizeDp,
                                preserveAnchor = true
                            )
                        }
                        recyclerView = rv
                        rv.post {
                            // A newly keyed RecyclerView belongs to one album only.
                            // Start at the stable header slot, then request the first photo.
                            rv.stopScroll()
                            rv.scrollToPosition(0)
                            if (pagingAdapter.itemCount > 0) pagingAdapter.requestLoad(0)
                            rv.requestLayout()
                        }
                        rv
                    },
                    update = { rv ->
                        val previousExpanded = (rv.tag as? Boolean) ?: sidebarExpanded
                        if (previousExpanded != sidebarExpanded) {
                            // Keep the header adapter at position 0 in both modes. Only the
                            // padding and header visibility change, so photo positions never shift.
                            rv.setPadding(
                                (18f * rv.resources.displayMetrics.density).toInt(),
                                if (sidebarExpanded) expandedGridTopPaddingPx(expandedHeaderHeightPx, rv.resources.displayMetrics.density) else 0,
                                (18f * rv.resources.displayMetrics.density).toInt(),
                                (18f * rv.resources.displayMetrics.density).toInt()
                            )
                            rv.tag = sidebarExpanded
                        }
                        updateGridSpanCount(rv, rv.resources.displayMetrics.density, minimumCellSizeDp)
                    },
                    onRelease = { rv ->
                        rv.adapter = null
                        if (recyclerView === rv) recyclerView = null
                    }
                )
            }

                if (sidebarExpanded) {
                    AlbumHeader(
                        albumName = album.name,
                        countLabel = headerCountLabel,
                        isLoading = isLoading,
                        progressMessage = progressMessage,
                        collapsedStyle = true,
                        onRegenerate = onRegenerate,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(1f).onSizeChanged { size ->
                            if (expandedHeaderHeightPx != size.height) {
                                expandedHeaderHeightPx = size.height
                                recyclerView?.let { rv ->
                                    rv.setPadding(
                                        rv.paddingLeft,
                                        expandedGridTopPaddingPx(size.height, rv.resources.displayMetrics.density),
                                        rv.paddingRight,
                                        rv.paddingBottom
                                    )
                                    rv.requestLayout()
                                }
                            }
                        }
                    )
                }

                if (selectedPaths.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp).zIndex(4f),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("${selectedPaths.size} selected", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = {
                                pendingOperationType = "COPY"
                                destinationPickerOpen = true
                            }) { Text("Copy to") }
                            TextButton(onClick = {
                                pendingOperationType = "MOVE"
                                destinationPickerOpen = true
                            }) { Text("Move to") }
                            TextButton(onClick = { selectedPaths = emptySet() }) { Text("Done") }
                        }
                    }
                }

                if (destinationPickerOpen) {
                    AlertDialog(
                        onDismissRequest = { destinationPickerOpen = false },
                        title = { Text(if (pendingOperationType == "COPY") "Copy to album" else "Move to album") },
                        text = {
                            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                                items(albums, key = { it.id }) { destinationAlbum ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable(enabled = pendingOperationType != "MOVE" || destinationAlbum.id != album.id) {
                                                val snapshot = selectedPaths.toList()
                                                destinationPickerOpen = false
                                                onFileOperation(pendingOperationType, snapshot, destinationAlbum)
                                                selectedPaths = emptySet()
                                            }
                                            .padding(vertical = 14.dp, horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(destinationAlbum.name, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                destinationAlbum.canonicalPath,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (pendingOperationType == "MOVE" && destinationAlbum.id == album.id) {
                                                Text("Current album", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { destinationPickerOpen = false }) { Text("Cancel") }
                        }
                    )
                }

                if (!hasItems && !isIndexing && loadState?.refresh !is androidx.paging.LoadState.Loading) {
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

private fun expandedGridTopPaddingPx(headerHeightPx: Int, density: Float): Int {
    // The expanded header is pinned above the RecyclerView, while the hidden
    // ConcatAdapter header slot can still contribute layout space. Offset the
    // grid upward to avoid the oversized gap, but retain a small breathing room.
    val reducedHeaderOffset = (84f * density).toInt()
    val minimumGap = (24f * density).toInt()
    return (headerHeightPx - reducedHeaderOffset).coerceAtLeast(minimumGap)
}


private data class GridScrollAnchor(val position: Int, val offsetFromPadding: Int)

private fun captureGridScrollAnchor(recyclerView: RecyclerView): GridScrollAnchor? {
    val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return null
    val position = layoutManager.findFirstVisibleItemPosition()
    if (position == RecyclerView.NO_POSITION) return null
    val child = layoutManager.findViewByPosition(position)
    return GridScrollAnchor(position, (child?.top ?: recyclerView.paddingTop) - recyclerView.paddingTop)
}

private fun restoreGridScrollAnchor(recyclerView: RecyclerView, anchor: GridScrollAnchor?) {
    val adapter = recyclerView.adapter ?: return
    if (anchor == null || adapter.itemCount == 0) return
    val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return
    layoutManager.scrollToPositionWithOffset(anchor.position.coerceIn(0, adapter.itemCount - 1), anchor.offsetFromPadding)
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
    val spacingDp = 8f
    return floor((availableDp + spacingDp) / (minimumCellSizeDp + spacingDp))
        .toInt()
        .coerceAtLeast(1)
}

private fun updateGridSpanCount(
    recyclerView: RecyclerView,
    density: Float,
    minimumCellSizeDp: Float,
    preserveAnchor: Boolean = true
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
        val anchor = if (preserveAnchor) captureGridScrollAnchor(recyclerView) else null
        layoutManager.spanCount = spanCount
        if (preserveAnchor) restoreGridScrollAnchor(recyclerView, anchor)
    }
}

@Composable
private fun AlbumHeader(
    albumName: String,
    countLabel: String,
    isLoading: Boolean,
    progressMessage: String?,
    collapsedStyle: Boolean,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = if (collapsedStyle) 24.dp else 18.dp,
                    vertical = if (collapsedStyle) 22.dp else 8.dp
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (collapsedStyle) 16.dp else 12.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (collapsedStyle) 8.dp else 5.dp)
                ) {
                    Text(
                        text = albumName,
                        style = if (collapsedStyle) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(shape = RoundedCornerShape(50.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            text = countLabel,
                            modifier = Modifier.padding(
                                horizontal = if (collapsedStyle) 14.dp else 10.dp,
                                vertical = if (collapsedStyle) 7.dp else 4.dp
                            ),
                            style = if (collapsedStyle) MaterialTheme.typography.labelLarge
                                else MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                FilledTonalIconButton(onClick = onRegenerate, enabled = !isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Regenerate album cover")
                }
            }
            if (progressMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        start = if (collapsedStyle) 24.dp else 20.dp,
                        end = if (collapsedStyle) 24.dp else 20.dp,
                        bottom = if (collapsedStyle) 16.dp else 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        progressMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private class CollapsedAlbumHeaderAdapter(
    albumName: String,
    countLabel: String,
    isLoading: Boolean,
    progressMessage: String?,
    visible: Boolean,
    private val onRegenerate: () -> Unit
) : RecyclerView.Adapter<CollapsedAlbumHeaderAdapter.HeaderViewHolder>() {
    private var albumName by mutableStateOf(albumName)
    private var countLabel by mutableStateOf(countLabel)
    private var isLoading by mutableStateOf(isLoading)
    private var progressMessage by mutableStateOf(progressMessage)
    private var visible by mutableStateOf(visible)

    fun update(
        albumName: String,
        countLabel: String,
        isLoading: Boolean,
        progressMessage: String?,
        visible: Boolean
    ) {
        val changed = this.albumName != albumName ||
            this.countLabel != countLabel ||
            this.isLoading != isLoading ||
            this.progressMessage != progressMessage ||
            this.visible != visible
        if (!changed) return
        this.albumName = albumName
        this.countLabel = countLabel
        this.isLoading = isLoading
        this.progressMessage = progressMessage
        this.visible = visible
        notifyItemChanged(0)
    }

    // The header stays at adapter position 0 to stabilize photo grid spans.
    // It is hidden in expanded mode; the fixed Compose header is used instead.
    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): HeaderViewHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
            id = View.generateViewId()
            setContent {
                AlbumHeader(
                    albumName = albumName,
                    countLabel = countLabel,
                    isLoading = isLoading,
                    progressMessage = progressMessage,
                    collapsedStyle = true,
                    onRegenerate = onRegenerate
                )
            }
        }
        return HeaderViewHolder(composeView)
    }

    override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
        holder.itemView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun onViewRecycled(holder: HeaderViewHolder) {
        holder.composeView.disposeComposition()
        super.onViewRecycled(holder)
    }

    class HeaderViewHolder(val composeView: ComposeView) : RecyclerView.ViewHolder(composeView)
}
