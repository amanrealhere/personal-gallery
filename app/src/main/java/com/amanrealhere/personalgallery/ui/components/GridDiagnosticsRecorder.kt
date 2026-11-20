package com.amanrealhere.personalgallery.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.amanrealhere.personalgallery.data.local.MediaItemEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GridDiagnosticsRecorder(context: Context) {
    private val file = File(File(context.filesDir, "grid-diagnostics"), "latest-session.txt")
    val visibleLines = mutableStateListOf<String>()
    var isRecording by mutableStateOf(false)
        private set
    private val seen = LinkedHashSet<String>()
    private var previous: List<String>? = null
    private var lastScroll = 0L
    private val started = SystemClock.elapsedRealtime()
    fun beginSession(albumName: String, albumId: String) {
        if (isRecording) return
        file.parentFile?.mkdirs()
        file.writeText("")
        visibleLines.clear()
        seen.clear()
        previous = null
        lastScroll = 0L
        isRecording = true
        log("SESSION_START album=${q(albumName)} id=${q(albumId)} file=${file.absolutePath}")
        log("NOTE: snapshots cover loaded Paging items, not the full album database.")
    }
    @Synchronized fun log(message: String) {
        if (!isRecording) return
        val line = "${stamp()} +${SystemClock.elapsedRealtime()-started}ms $message"
        runCatching { file.appendText(line + "\n") }; visibleLines.add(line); if (visibleLines.size > 500) visibleLines.removeAt(0)
    }
    @Synchronized fun snapshot(reason: String, items: List<MediaItemEntity>) {
        val ids = items.map { it.id }; val old = previous
        val new = items.filter { it.id !in seen }; val gone = old?.filter { it !in ids } ?: emptyList()
        val reordered = old != null && old != ids && old.toSet() == ids.toSet()
        log("SNAPSHOT reason=$reason loaded=${ids.size} first=${ids.firstOrNull()?.let(::q) ?: "none"} last=${ids.lastOrNull()?.let(::q) ?: "none"}")
        if (old == null && ids.isNotEmpty()) log("INITIAL_BASELINE count=${ids.size}")
        else if (new.isNotEmpty()) { log("ITEMS_NEWLY_OBSERVED count=${new.size} (loaded into Paging; not proof of new files)"); new.forEach { log("ITEM id=${q(it.id)} name=${q(it.displayName)} path=${q(it.sourcePath)} bytes=${it.fileSizeBytes} modified=${it.modifiedAtEpochMillis}") } }
        if (ids.isEmpty() && old == null) log("WAITING_FOR_FIRST_ITEMS")
        if (gone.isNotEmpty()) log("ITEMS_LEFT_LOADED_SNAPSHOT count=${gone.size} ids=${gone.take(40).joinToString(",") { q(it) }}")
        if (old != null && (old.size != ids.size || reordered)) log("GRID_DATASET_CHANGE loadedCount=${old.size}->${ids.size} reordered=$reordered")
        if (reordered) log("GRID_ORDER_CHANGED ids=${ids.take(80).joinToString(",") { q(it) }}")
        seen.addAll(ids); if (ids.isNotEmpty() || old != null) previous = ids
    }
    @Synchronized fun adapterEvent(type: String, start: Int, count: Int, items: List<MediaItemEntity>) { log("ADAPTER_EVENT type=$type start=$start count=$count"); snapshot("adapter:$type", items) }
    @Synchronized fun scroll(first: Int, last: Int, offset: Int, keys: List<String>) { val now=SystemClock.elapsedRealtime(); if(now-lastScroll<350)return; lastScroll=now; log("SCROLL first=$first last=$last offsetPx=$offset visibleIds=${keys.joinToString(",") { q(it) }}") }
    fun readAll() = runCatching { file.readText() }.getOrDefault("Unable to read log")
    @Synchronized fun stop() { if(!isRecording)return; log("RECORDING_PAUSED uniqueSeen=${seen.size}"); isRecording=false }
    @Synchronized fun start() { if(isRecording)return; isRecording=true; log("RECORDING_RESUMED uniqueSeen=${seen.size}") }
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    private fun q(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}