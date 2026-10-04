package com.stream4k60.app.ui.main.components

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.StreamStats
import com.stream4k60.app.ui.main.StudioStreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private val Good = Color(0xFF4CAF50)
private val Warn = Color(0xFFE8B400)
private val Bad = Color(0xFFE53935)

/** Counters at the last Reset; the panel shows the change since then (OBS's Stats → Reset). */
/**
 * Where Stats counts from: set at the first reading (so from the app's start) and by Reset. Shared with the status bar,
 * so its dropped-frame line resets together with the panel.
 */
internal object StatsReset {
    val base = kotlinx.coroutines.flow.MutableStateFlow<Counters?>(null)
    /** [cur] counted from [start]; a value below it means its counter restarted (a new stream), so it counts from zero. */
    fun since(cur: Long, start: Long) = if (cur >= start) cur - start else cur
}

internal data class Counters(
    val rendered: Long = 0, val missed: Long = 0, val encoderIn: Long = 0, val encoded: Long = 0,
    val sent: Long = 0, val netDropped: Long = 0, val sentBytes: Long = 0
)

/** What the app itself is using, sampled once a second. */
private data class Usage(val cpuPercent: Float = 0f, val memoryMb: Long = 0, val fps: Float = 0f, val renderMs: Float = 0f)

/**
 * OBS-style Stats panel, live and toggleable: rendering, encoding and network counters with drop rates, colour-coded
 * green / yellow / red as OBS does, and Reset to count from now. A floating window over the docks area
 * ([areaWidth] × [areaHeight]): drag the title bar to move it, the corner grip to resize it; the contents scroll both
 * ways when the window is smaller than them. Position and size are remembered.
 */
@Composable
fun StatsPanel(
    stats: StreamStats,
    streamState: StudioStreamState,
    targetFps: Int,
    thermal: String,
    onClose: () -> Unit,
    areaWidth: Dp,
    areaHeight: Dp
) {
    val latestStats by rememberUpdatedState(stats)
    var usage by remember { mutableStateOf(Usage()) }
    var now by remember { mutableStateOf(Counters()) }
    val base by StatsReset.base.collectAsState()

    fun read(s: StreamStats) = Counters(
        rendered = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(0L),
        missed = runCatching { NativeEngine.getDroppedFrames() }.getOrDefault(0L),
        encoderIn = s.encoderInputFrames, encoded = s.totalFrames, sent = s.sentFrames,
        netDropped = s.droppedFrames, sentBytes = s.sentBytes
    )

    LaunchedEffect(Unit) {
        var lastCpuMs = android.os.Process.getElapsedCpuTime()
        var lastWallMs = android.os.SystemClock.elapsedRealtime()
        var lastRendered = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(0L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        now = read(latestStats); if (StatsReset.base.value == null) StatsReset.base.value = now
        while (true) {
            delay(1000)
            val cpuMs = android.os.Process.getElapsedCpuTime()
            val wallMs = android.os.SystemClock.elapsedRealtime()
            val counters = read(latestStats)
            val pssKb = withContext(Dispatchers.Default) { runCatching { android.os.Debug.getPss() }.getOrDefault(0L) }
            usage = Usage(
                cpuPercent = 100f * (cpuMs - lastCpuMs) / ((wallMs - lastWallMs).coerceAtLeast(1) * cores),
                memoryMb = pssKb / 1024,
                fps = (counters.rendered - lastRendered) * 1000f / (wallMs - lastWallMs).coerceAtLeast(1),
                renderMs = runCatching { NativeEngine.getRenderTimeMs() }.getOrDefault(0f)
            )
            lastCpuMs = cpuMs; lastWallMs = wallMs; lastRendered = counters.rendered
            now = counters
        }
    }

    val b = base ?: now
    // Stream counters restart with each stream: a value below its baseline means a new stream, counted from zero.
    fun since(cur: Long, start: Long) = StatsReset.since(cur, start)
    val rendered = since(now.rendered, b.rendered)
    val missed = since(now.missed, b.missed)
    val encoderIn = since(now.encoderIn, b.encoderIn)
    val encoded = since(now.encoded, b.encoded)
    val sent = since(now.sent, b.sent)
    val netDropped = since(now.netDropped, b.netDropped)
    val sentBytes = since(now.sentBytes, b.sentBytes)
    // A few frames are always inside the encoder; only what's beyond that was skipped for encoding lag.
    val skipped = (encoderIn - encoded - 3).coerceAtLeast(0)
    val streaming = streamState == StudioStreamState.LIVE || streamState == StudioStreamState.RECONNECTING
    val budgetMs = 1000f / targetFps.coerceAtLeast(1)

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("stats_panel", Context.MODE_PRIVATE) }
    val density = LocalDensity.current.density
    val maxW = areaWidth.value
    val maxH = areaHeight.value
    // Window geometry in dp, within the docks area; first shown in the bottom-right corner.
    var w by remember { mutableFloatStateOf(prefs.getFloat("w", 400f)) }
    var h by remember { mutableFloatStateOf(prefs.getFloat("h", 470f)) }
    var x by remember { mutableFloatStateOf(prefs.getFloat("x", maxW - 408f)) }
    var y by remember { mutableFloatStateOf(prefs.getFloat("y", maxH - 478f)) }
    // Kept inside the area, also after the area shrinks (rotation, dock changes).
    val cw = w.coerceIn(MIN_W, maxW.coerceAtLeast(MIN_W))
    val ch = h.coerceIn(MIN_H, maxH.coerceAtLeast(MIN_H))
    val cx = x.coerceIn(0f, (maxW - cw).coerceAtLeast(0f))
    val cy = y.coerceIn(0f, (maxH - ch).coerceAtLeast(0f))
    fun save() = prefs.edit().putFloat("x", x).putFloat("y", y).putFloat("w", w).putFloat("h", h).apply()
    /**
     * Resizes by a drag on an edge or corner. A left/top drag moves that edge and keeps the opposite one in place;
     * the window never goes below its minimum size or outside the area.
     */
    fun resizeBy(dxPx: Float, dyPx: Float, left: Boolean, top: Boolean, right: Boolean, bottom: Boolean) {
        val dx = dxPx / density; val dy = dyPx / density
        var nx = x.coerceIn(0f, maxOf(0f, maxW - MIN_W)); var ny = y.coerceIn(0f, maxOf(0f, maxH - MIN_H))
        var nw = w.coerceIn(MIN_W, maxOf(MIN_W, maxW - nx)); var nh = h.coerceIn(MIN_H, maxOf(MIN_H, maxH - ny))
        if (right) nw = (nw + dx).coerceIn(MIN_W, maxOf(MIN_W, maxW - nx))
        if (bottom) nh = (nh + dy).coerceIn(MIN_H, maxOf(MIN_H, maxH - ny))
        if (left) { val edge = nx + nw; nx = (nx + dx).coerceIn(0f, maxOf(0f, edge - MIN_W)); nw = edge - nx }
        if (top) { val edge = ny + nh; ny = (ny + dy).coerceIn(0f, maxOf(0f, edge - MIN_H)); nh = edge - ny }
        x = nx; y = ny; w = nw; h = nh
    }
    /** An invisible grab area on an edge or corner that resizes from that side. */
    @Composable
    fun Handle(modifier: Modifier, left: Boolean = false, top: Boolean = false, right: Boolean = false, bottom: Boolean = false) = Box(
        modifier.pointerInput(maxW, maxH) {
            detectDragGestures(onDragEnd = { save() }) { change, drag -> change.consume(); resizeBy(drag.x, drag.y, left, top, right, bottom) }
        }
    )

    Surface(
        modifier = Modifier.offset(cx.dp, cy.dp).size(cw.dp, ch.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Box {
            Column(Modifier.fillMaxSize()) {
                // Title bar: drag to move.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pointerInput(maxW, maxH) {
                            detectDragGestures(onDragEnd = { save() }) { change, drag ->
                                change.consume()
                                x = (x.coerceIn(0f, (maxW - w).coerceAtLeast(0f)) + drag.x / density).coerceIn(0f, (maxW - w).coerceAtLeast(0f))
                                y = (y.coerceIn(0f, (maxH - h).coerceAtLeast(0f)) + drag.y / density).coerceIn(0f, (maxH - h).coerceAtLeast(0f))
                            }
                        }
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DragIndicator, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Stats", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { StatsReset.base.value = now }) { Text("Reset") }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Close stats", Modifier.size(18.dp)) }
                }
                // Contents keep a readable width; a narrower window scrolls sideways as well as up and down.
                val contentWidth = maxOf(cw - 24f, CONTENT_MIN_W).dp
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .verticalScroll(rememberScrollState())
                ) {
                    Column(Modifier.width(contentWidth).padding(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
                        Section("General")
                        Stat("CPU usage (this app)", pct(usage.cpuPercent), level(usage.cpuPercent, 60f, 85f))
                        Stat("Memory usage", "${usage.memoryMb} MB", null)
                        Stat("Thermal state", thermal, when (thermal) { "COOL", "LIGHT" -> Good; "MODERATE" -> Warn; else -> Bad })
                        Stat("FPS", String.format(Locale.US, "%.2f / %d", usage.fps, targetFps),
                            when { usage.fps >= targetFps * 0.98f -> Good; usage.fps >= targetFps * 0.9f -> Warn; else -> Bad })
                        Stat("Average time to render frame", String.format(Locale.US, "%.1f ms (budget %.1f ms)", usage.renderMs, budgetMs),
                            level(usage.renderMs, budgetMs * 0.6f, budgetMs * 0.9f))
                        Stat("Frames missed due to rendering lag", ratio(missed, rendered), rateColor(missed, rendered))

                        Section("Encoding")
                        Stat("Frames encoded", if (streaming || encoded > 0) "%,d".format(encoded) else "—", null)
                        Stat("Frames skipped due to encoding lag", if (encoderIn > 0) ratio(skipped, encoderIn) else "—", if (encoderIn > 0) rateColor(skipped, encoderIn) else null)
                        Stat("Encoder bitrate", if (streaming) "%,d kb/s".format(stats.encoderBitrate / 1000) else "—", null)
                        // Dynamic bitrate: below the setting while the upload is congested.
                        Stat("Video bitrate setting", if (streaming && stats.targetVideoBitrate > 0) "%,d of %,d kb/s".format(stats.currentVideoBitrate / 1000, stats.targetVideoBitrate / 1000) else "—",
                            if (streaming && stats.targetVideoBitrate > 0) level((1f - stats.currentVideoBitrate.toFloat() / stats.targetVideoBitrate) * 100f, 1f, 40f) else null)

                        Section("Streaming")
                        Stat("Status", when (streamState) {
                            StudioStreamState.LIVE -> "Live"; StudioStreamState.CONNECTING -> "Connecting"; StudioStreamState.RECONNECTING -> "Reconnecting"
                            StudioStreamState.STOPPING -> "Stopping"; StudioStreamState.ERROR -> "Error"; else -> "Inactive"
                        }, when (streamState) { StudioStreamState.LIVE -> Good; StudioStreamState.CONNECTING, StudioStreamState.RECONNECTING -> Warn; StudioStreamState.ERROR -> Bad; else -> null })
                        Stat("Frames sent (network)", if (streaming || sent > 0) "%,d".format(sent) else "—", null)
                        Stat("Dropped frames (network)", if (encoded > 0) ratio(netDropped, encoded) else "—", if (encoded > 0) rateColor(netDropped, encoded) else null)
                        Stat("Send queue", if (streaming) "${stats.queuedPackets} packets" else "—",
                            if (streaming) when { stats.queuedPackets < 60 -> Good; stats.queuedPackets < 160 -> Warn; else -> Bad } else null)
                        Stat("Bitrate (sent)", if (streaming) "%,d kb/s".format(stats.bitrate / 1000) else "—", null)
                        Stat("Total data output", String.format(Locale.US, "%.1f MB", sentBytes / 1_048_576.0), null)
                    }
                }
            }
            // Every edge and corner resizes: thin grab strips along the edges, larger squares at the corners.
            Handle(Modifier.align(Alignment.CenterStart).width(EDGE.dp).fillMaxHeight(), left = true)
            Handle(Modifier.align(Alignment.CenterEnd).width(EDGE.dp).fillMaxHeight(), right = true)
            Handle(Modifier.align(Alignment.TopCenter).height(EDGE.dp).fillMaxWidth(), top = true)
            Handle(Modifier.align(Alignment.BottomCenter).height(EDGE.dp).fillMaxWidth(), bottom = true)
            Handle(Modifier.align(Alignment.TopStart).size(CORNER.dp), left = true, top = true)
            Handle(Modifier.align(Alignment.TopEnd).size(CORNER.dp), right = true, top = true)
            Handle(Modifier.align(Alignment.BottomStart).size(CORNER.dp), left = true, bottom = true)
            // The visible grip, bottom-right.
            Box(Modifier.align(Alignment.BottomEnd).size(26.dp)) {
                val grip = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    for (n in 1..3) {
                        val o = size.width * n / 3f
                        drawLine(grip, Offset(size.width - o, size.height), Offset(size.width, size.height - o), strokeWidth = 1.5.dp.toPx())
                    }
                }
                Handle(Modifier.fillMaxSize(), right = true, bottom = true)
            }
        }
    }
}

/** Grab-strip thickness along the edges, and the corner squares (dp). */
private const val EDGE = 10f
private const val CORNER = 22f
private const val MIN_W = 240f
private const val MIN_H = 160f
/** Below this the rows would wrap and squash; the panel scrolls sideways instead. */
private const val CONTENT_MIN_W = 360f

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(vertical = 2.dp))
}

@Composable
private fun Stat(label: String, value: String, color: Color?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = if (color != null) Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp) else Modifier)
    }
}

private fun pct(v: Float) = String.format(Locale.US, "%.1f %%", v)

/** "dropped / total (x.x %)", as OBS writes it. */
private fun ratio(part: Long, total: Long) =
    String.format(Locale.US, "%,d / %,d (%.1f %%)", part, total, if (total > 0) 100.0 * part / total else 0.0)

/** Dropped / missed / skipped frames, as OBS colours them: none green, under 5 % yellow, 5 % or more red. */
private fun rateColor(part: Long, total: Long): Color {
    if (part <= 0L || total <= 0L) return Good
    return if (100.0 * part / total < 5.0) Warn else Bad
}

private fun level(v: Float, warnAt: Float, badAt: Float) = when { v < warnAt -> Good; v < badAt -> Warn; else -> Bad }
