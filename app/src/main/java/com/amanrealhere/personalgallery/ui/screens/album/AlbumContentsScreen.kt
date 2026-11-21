package com.amanrealhere.personalgallery.ui.screens.album

import android.view.ScaleGestureDetector
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.setContent
import androidx.recyclerview.widget.ConcatAdapter
import androidx.paging.CombinedLoadStates
import androidx.paging.PagingData
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import com.amanrealhere.personalgallery.domain.model.Album
import com.amanrealhere.personalgallery.ui.components.MediaGridPagingAdapter
import com.amanrealhere.personalgallery.ui.components.GridDiagnosticsRecorder
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
    isIndexing: Boolean,
    coverPath: String?,
    progressMessage: String?,
    isLoading: Boolean,
    onRegenerate: () -> Unit
) {
    val context = LocalContext.current
    val pagingAdapter = remember { MediaGridPagingAdapter() }
    val diagnostics = remember(album.id) { GridDiagnosticsRecorder(context) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val exportLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching { context.contentResolver.openOutputStream(uri)?.use { stream -> stream.write(diagnostics.readAll().toByteArray(Charsets.UTF_8)) } }
    }
    DisposableEffect(diagnostics) {
        diagnostics.beginSession(album.name, album.id)
        onDispose { diagnostics.log("ALBUM_SCREEN_DISPOSED"); diagnostics.stop() }
    }
    var loadState by remember(pagingAdapter) { mutableStateOf<CombinedLoadStates?>(null) }
    LaunchedEffect(pagingAdapter, diagnostics) {
        pagingAdapter.loadStateFlow.collect { states ->
            loadState = states
            diagnostics.log("LOAD_STATE refresh=${states.refresh} prepend=${states.prepend} append=${states.append}")
        }
    }
    LaunchedEffect(pagingAdapter, diagnostics) {
        pagingAdapter.onPagesUpdatedFlow.collect {
            val itemsNow = pagingAdapter.snapshot().items
            diagnostics.log("PAGING_PAGES_UPDATED loaded=${itemsNow.size} adapterCount=${pagingAdapter.itemCount}")
            diagnostics.snapshot("pages-updated", itemsNow)
        }
    }

    var hasItems by remember(pagingAdapter) { mutableStateOf(pagingAdapter.itemCount > 0) }
    var minimumCellSizeDp by remember { mutableFloatStateOf(148f) }
    var recyclerView by remember { mutableStateOf<RecyclerView?>(null) }
    var stableImageCount by remember(album.id) { mutableStateOf(imageCount) }
    val headerAdapter = remember(album.id) {
        CollapsedAlbumHeaderAdapter(
            albumName = album.name,
            imageCount = stableImageCount,
            isLoading = isLoading,
            progressMessage = progressMessage,
            visible = !sidebarExpanded,
            onDiagnostics = { showDiagnostics = true },
            onRegenerate = onRegenerate
        )
    }
    SideEffect {
        headerAdapter.update(
            albumName = album.name,
            imageCount = stableImageCount,
            isLoading = isLoading,
            progressMessage = progressMessage,
            visible = !sidebarExpanded
        )
    }
    val concatAdapter = remember(headerAdapter, pagingAdapter) { ConcatAdapter(headerAdapter, pagingAdapter) }
    LaunchedEffect(imageCount, isIndexing, album.id) {
        if (imageCount > 0 || !isIndexing) stableImageCount = imageCount
    }

    LaunchedEffect(images, pagingAdapter) {
        images.collectLatest { pagingData ->
            diagnostics.log("PAGING_GENERATION_RECEIVED")
            pagingAdapter.submitData(pagingData)
        }
    }

    DisposableEffect(pagingAdapter) {
        val observer = object : RecyclerView.AdapterDataObserver() {
            private fun update(event: String, start: Int = -1, count: Int = -1) {
                hasItems = pagingAdapter.itemCount > 0
                diagnostics.adapterEvent(event, start, count, pagingAdapter.snapshot().items)
            }
            override fun onChanged() = update("changed")
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = update("inserted", positionStart, itemCount)
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = update("removed", positionStart, itemCount)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = update("changed-range", positionStart, itemCount)
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = update("changed-payload", positionStart, itemCount)
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = update("moved-from-$fromPosition-to-$toPosition", fromPosition, itemCount)
        }
        pagingAdapter.registerAdapterDataObserver(observer)
        hasItems = pagingAdapter.itemCount > 0
        onDispose { pagingAdapter.unregisterAdapterDataObserver(observer) }
    }

    LaunchedEffect(album.id) {
        recyclerView?.post { recyclerView?.scrollToPosition(0) }
    }

    val density = LocalDensity.current
    val overlayBottomGapPx = with(density) { 10.dp.roundToPx() }
    var topOverlayHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(sidebarExpanded) {
        if (!sidebarExpanded) topOverlayHeightPx = 0
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val viewDensity = viewContext.resources.displayMetrics.density
                    val rv = RecyclerView(viewContext).apply {
                        clipToPadding = false
                        setPadding(
                            (18f * viewDensity).toInt(),
                            if (sidebarExpanded) topOverlayHeightPx + overlayBottomGapPx else 0,
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
                                if (position < headerAdapter.itemCount) gridLayoutManager.spanCount else 1
                        }
                        layoutManager = gridLayoutManager
                        adapter = concatAdapter
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
                    rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
                            val first = lm.findFirstVisibleItemPosition(); val last = lm.findLastVisibleItemPosition()
                            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return
                            val child = lm.findViewByPosition(first)
                            val offset = (child?.top ?: recyclerView.paddingTop) - recyclerView.paddingTop
                            val headerCount = headerAdapter.itemCount
                            val gridFirst = (first - headerCount).coerceAtLeast(0)
                            val gridLast = (last - headerCount).coerceAtLeast(0)
                            val keys = (first..last).mapNotNull { position ->
                                (position - headerCount).takeIf { it >= 0 }?.let { pagingAdapter.peek(it)?.id }
                            }
                            diagnostics.scroll(gridFirst, gridLast, offset, keys)
                        }
                    })
                    var previousWidth = 0
                    rv.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        val changedRv = view as RecyclerView
                        if (changedRv.width != previousWidth) {
                            val anchor = captureGridScrollAnchor(changedRv)
                            updateGridSpanCount(changedRv, viewDensity, minimumCellSizeDp, preserveAnchor = false)
                            restoreGridScrollAnchor(changedRv, anchor)
                            previousWidth = changedRv.width
                        } else {
                            updateGridSpanCount(changedRv, viewDensity, minimumCellSizeDp)
                        }
                    }
                    recyclerView = rv
                    diagnostics.log("RECYCLERVIEW_CREATED width=${rv.width} height=${rv.height} paddingTop=${rv.paddingTop}")
                    rv
                },
                update = { rv ->
                    if (rv.adapter !== concatAdapter) rv.adapter = concatAdapter
                    val topPadding = if (sidebarExpanded) topOverlayHeightPx + overlayBottomGapPx else 0
                    val paddingChanged = rv.paddingTop != topPadding
                    val anchor = if (paddingChanged) captureGridScrollAnchor(rv) else null
                    if (paddingChanged) {
                        rv.setPadding(rv.paddingLeft, topPadding, rv.paddingRight, rv.paddingBottom)
                    }
                    updateGridSpanCount(rv, rv.resources.displayMetrics.density, minimumCellSizeDp)
                    if (paddingChanged) restoreGridScrollAnchor(rv, anchor)
                },
                onRelease = { rv ->
                    rv.adapter = null
                    if (recyclerView === rv) recyclerView = null
                }
            )

            if (!hasItems && (isIndexing || loadState?.refresh is androidx.paging.LoadState.Loading)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!hasItems && loadState?.refresh !is androidx.paging.LoadState.Loading) {
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

            if (sidebarExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { size ->
                        if (topOverlayHeightPx != size.height) topOverlayHeightPx = size.height
                    }
                ) {
                    AlbumHeader(
                        albumName = album.name,
                        imageCount = stableImageCount,
                        isLoading = isLoading,
                        progressMessage = progressMessage,
                        onDiagnostics = { showDiagnostics = true },
                        onRegenerate = onRegenerate
                    )
                }
            }
        }
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("Grid diagnostics") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("Dataset changes, newly observed images, Paging load states, adapter events, and scroll positions are recorded.")
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { exportLogLauncher.launch("grid-diagnostics-${album.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.txt") }) { Text("Export log") }
                        OutlinedButton(onClick = { if (diagnostics.isRecording) diagnostics.stop() else diagnostics.start() }) {
                            Text(if (diagnostics.isRecording) "Stop recording" else "Resume recording")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Latest ${diagnostics.visibleLines.size} lines; full log is saved privately in app storage.")
                    Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState())) {
                        diagnostics.visibleLines.forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("Close") } }
        )
    }
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
    val spacingDp = 12f
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
    imageCount: Int,
    isLoading: Boolean,
    progressMessage: String?,
    onDiagnostics: () -> Unit,
    onRegenerate: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = albumName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(shape = RoundedCornerShape(50.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(
                            text = "$imageCount ${if (imageCount == 1) "image" else "images"}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                IconButton(onClick = onDiagnostics) {
                    Icon(Icons.Filled.Info, contentDescription = "Grid diagnostics", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalIconButton(onClick = onRegenerate, enabled = !isLoading) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Regenerate album cover")
                }
            }
            if (progressMessage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        progressMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private class CollapsedAlbumHeaderAdapter(
    albumName: String,
    imageCount: Int,
    isLoading: Boolean,
    progressMessage: String?,
    visible: Boolean,
    private val onDiagnostics: () -> Unit,
    private val onRegenerate: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var albumName = albumName
    private var imageCount = imageCount
    private var isLoading = isLoading
    private var progressMessage = progressMessage
    private var visible = visible

    fun update(
        albumName: String,
        imageCount: Int,
        isLoading: Boolean,
        progressMessage: String?,
        visible: Boolean
    ) {
        val wasVisible = this.visible
        val changed = this.albumName != albumName || this.imageCount != imageCount ||
            this.isLoading != isLoading || this.progressMessage != progressMessage
        this.albumName = albumName
        this.imageCount = imageCount
        this.isLoading = isLoading
        this.progressMessage = progressMessage
        this.visible = visible
        when {
            !wasVisible && visible -> notifyItemInserted(0)
            wasVisible && !visible -> notifyItemRemoved(0)
            visible && changed -> notifyItemChanged(0)
        }
    }

    override fun getItemCount() = if (visible) 1 else 0

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val composeView = ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT
            )
        }
        return object : RecyclerView.ViewHolder(composeView) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        (holder.itemView as ComposeView).setContent {
            AlbumHeader(
                albumName = albumName,
                imageCount = imageCount,
                isLoading = isLoading,
                progressMessage = progressMessage,
                onDiagnostics = onDiagnostics,
                onRegenerate = onRegenerate
            )
        }
    }
}
