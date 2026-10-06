package com.stream4k60.app.ui.main.components

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stream4k60.app.ui.main.StudioLiveState
import com.stream4k60.app.ui.main.StudioStreamState
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.ui.theme.ObsBorder
import com.stream4k60.app.ui.theme.ObsButton
import com.stream4k60.app.ui.theme.ObsDockTitle
import com.stream4k60.app.ui.theme.ObsRed
import kotlinx.coroutines.delay
import java.util.Locale

/** An OBS-style dock: bold title bar over its content. */
@Composable
fun Dock(title: String, modifier: Modifier = Modifier, contentScale: Float = 1f, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, ObsBorder).background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.fillMaxWidth().height(26.dp).background(ObsDockTitle).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        // Resizing a dock scales what is inside it (text, buttons, faders), not just the empty space around them.
        val d = androidx.compose.ui.platform.LocalDensity.current
        val scale = contentScale.coerceIn(0.75f, 1.6f)
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density * scale, d.fontScale)
        ) {
            Column(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** Flat full-width button used by the Controls dock, like OBS's. */
@Composable
fun ObsButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else ObsButton, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 13.sp, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The bar under the preview: selected source, Properties and Filters (OBS's source toolbar). */
@Composable
fun SourceToolbar(selectedName: String?, onProperties: () -> Unit, onFilters: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(selectedName ?: "No source selected", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp))
        ToolbarChip("Properties", Icons.Default.Settings, enabled = selectedName != null, onClick = onProperties)
        ToolbarChip("Filters", Icons.Default.FilterAlt, enabled = selectedName != null, onClick = onFilters)
    }
}

@Composable
private fun ToolbarChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(28.dp).border(1.dp, ObsBorder, RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 13.sp, color = tint)
    }
}

/** Scene Transitions dock body: transition type and duration. */
@Composable
fun TransitionsDockContent(selected: String, onSelect: (String) -> Unit, isStudioMode: Boolean, onTransition: () -> Unit, durationMs: Int = 400, onDuration: (Int) -> Unit = {}) {
    var open by remember { mutableStateOf(false) }
    val options = com.stream4k60.app.ui.main.SceneTransitions.names
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            Row(
                Modifier.fillMaxWidth().height(30.dp).background(ObsButton, RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Text(selected, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("▾", fontSize = 13.sp) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); open = false }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Duration", fontSize = 13.sp, modifier = Modifier.width(64.dp))
            Text(if (selected == "Cut") "—" else "$durationMs ms", fontSize = 13.sp)
        }
        // How fast the transition plays (OBS's Duration), saved for next time.
        if (selected != "Cut") androidx.compose.material3.Slider(
            value = durationMs.toFloat(),
            onValueChange = { onDuration((it / 50f).roundToInt() * 50) },
            valueRange = com.stream4k60.app.ui.main.SceneTransitions.MIN_MS.toFloat()..com.stream4k60.app.ui.main.SceneTransitions.MAX_MS.toFloat()
        )
        if (isStudioMode) ObsButton("Transition", onTransition, active = true)
    }
}

/** Controls dock body, with OBS's buttons. Stops can ask for confirmation. */
@Composable
fun ControlsDockContent(
    streamState: StudioStreamState,
    isStudioMode: Boolean,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    liveState: StudioLiveState = StudioLiveState.UNAVAILABLE,
    onGoLive: () -> Unit = {},
    onEndLive: () -> Unit = {},
    onToggleStudio: () -> Unit,
    onSettings: () -> Unit,
    onManageBroadcast: () -> Unit = {},
    broadcastTitle: String? = null
) {
    Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ObsButton(broadcastTitle?.let { "Broadcast: $it" } ?: "Manage Broadcast", onManageBroadcast)
        StreamButton(streamState, onStartStreaming, onStopStreaming)
        if (liveState != StudioLiveState.UNAVAILABLE) GoLiveButton(liveState, streamState == StudioStreamState.LIVE || streamState == StudioStreamState.RECONNECTING, onGoLive, onEndLive)
        ObsButton("Studio Mode", onToggleStudio, active = isStudioMode)
        ObsButton("Settings", onSettings)
    }
}

/**
 * Start/Stop Streaming. Yellow with a spinner while connecting or reconnecting (tap to cancel), red while live,
 * so a tap always shows that something is happening.
 */
@Composable
private fun StreamButton(state: StudioStreamState, onStart: () -> Unit, onStop: () -> Unit) {
    val (label, background, foreground) = when (state) {
        StudioStreamState.CONNECTING -> Triple("Connecting…", StreamYellow, Color.Black)
        StudioStreamState.RECONNECTING -> Triple("Reconnecting…", StreamYellow, Color.Black)
        StudioStreamState.LIVE -> Triple("Stop Streaming", StreamRed, Color.White)
        StudioStreamState.STOPPING -> Triple("Stopping…", ObsButton, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple("Start Streaming", ObsButton, MaterialTheme.colorScheme.onSurface)
    }
    val busy = state == StudioStreamState.CONNECTING || state == StudioStreamState.RECONNECTING || state == StudioStreamState.STOPPING
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(background, RoundedCornerShape(4.dp))
            .clickable(enabled = state != StudioStreamState.STOPPING) { if (state == StudioStreamState.IDLE || state == StudioStreamState.ERROR) onStart() else onStop() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = foreground, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 13.sp, color = foreground)
    }
}
/**
 * Go Live / End Live for the picked YouTube broadcast. Separate from streaming: video can be sent (and checked in
 * YouTube Studio's preview) before viewers see anything, and stopping the video doesn't end the broadcast.
 */
@Composable
private fun GoLiveButton(state: StudioLiveState, streaming: Boolean, onGoLive: () -> Unit, onEndLive: () -> Unit) {
    val (label, background, foreground) = when (state) {
        StudioLiveState.GOING_LIVE -> Triple("Going live…", StreamYellow, Color.Black)
        StudioLiveState.LIVE -> Triple("End Live", StreamRed, Color.White)
        StudioLiveState.ENDING -> Triple("Ending…", ObsButton, MaterialTheme.colorScheme.onSurfaceVariant)
        StudioLiveState.ENDED -> Triple("Broadcast ended", ObsButton, MaterialTheme.colorScheme.onSurfaceVariant)
        else -> if (streaming) Triple("Go Live", LiveGreen, Color.White) else Triple("Go Live (start streaming first)", ObsButton, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val enabled = state == StudioLiveState.LIVE || (state == StudioLiveState.READY && streaming)
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(background, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled) { if (state == StudioLiveState.LIVE) onEndLive() else onGoLive() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state == StudioLiveState.GOING_LIVE || state == StudioLiveState.ENDING) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = foreground, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 13.sp, color = foreground)
    }
}
private val LiveGreen = Color(0xFF2E7D32)
private val StreamYellow = Color(0xFFE8B400)
private val StreamRed = Color(0xFFC62828)

/** OBS's status bar: LIVE timer, FPS and render time from the compositor, thermal state. */
@Composable
fun StudioStatusBar(isStreaming: Boolean, targetFps: Int, thermal: String, modifier: Modifier = Modifier, reconnecting: Boolean = false, bitrateBps: Long = 0, networkDropped: Long = 0, statsOpen: Boolean = false, onToggleStats: (() -> Unit)? = null) {
    var liveSeconds by remember { mutableLongStateOf(0L) }
    var fps by remember { mutableFloatStateOf(0f) }
    var renderMs by remember { mutableFloatStateOf(0f) }
    var dropped by remember { mutableIntStateOf(0) }
    // Counted from the Stats panel's reset point (app start until Reset is pressed there).
    val base by StatsReset.base.collectAsState()
    val onAir = isStreaming || reconnecting
    LaunchedEffect(onAir) { if (onAir) { while (true) { delay(1000); liveSeconds++ } } else liveSeconds = 0 }
    LaunchedEffect(Unit) {
        var lastFrames = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(0L)
        if (StatsReset.base.value == null) StatsReset.base.value = Counters(
            rendered = lastFrames, missed = runCatching { NativeEngine.getDroppedFrames() }.getOrDefault(0L), netDropped = networkDropped)
        while (true) {
            delay(1000)
            val frames = runCatching { NativeEngine.getTotalFrames() }.getOrDefault(lastFrames)
            fps = (frames - lastFrames).toFloat(); lastFrames = frames
            renderMs = runCatching { NativeEngine.getRenderTimeMs() }.getOrDefault(0f)
            val start = StatsReset.base.value?.missed ?: 0L
            dropped = StatsReset.since(runCatching { NativeEngine.getDroppedFrames() }.getOrDefault(start), start).toInt()
        }
    }
    fun clock(s: Long) = String.format(Locale.US, "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    Row(
        modifier.fillMaxWidth().height(24.dp).background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Thermal: $thermal", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // OBS's View → Stats: a live panel with every counter.
        if (onToggleStats != null) Box(
            Modifier
                .height(20.dp)
                .background(if (statsOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                .clickable(onClick = onToggleStats)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(if (statsOpen) "Hide stats" else "Show stats", fontSize = 11.sp,
                color = if (statsOpen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.weight(1f))
        if (reconnecting) Text("Reconnecting…", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        StatusDot(isStreaming || reconnecting); Text("LIVE ${clock(liveSeconds)}", fontSize = 11.sp)
        if (isStreaming || reconnecting) Text("${bitrateBps / 1000} kb/s", fontSize = 11.sp)
        Text(String.format(Locale.US, "Render %.1f ms", renderMs), fontSize = 11.sp)
        // Render: frames the compositor missed. Network: frames dropped because the upload fell behind.
        Text("Dropped $dropped render · ${StatsReset.since(networkDropped, base?.netDropped ?: 0L)} network", fontSize = 11.sp)
        Text(String.format(Locale.US, "%.2f / %d FPS", fps, targetFps), fontSize = 11.sp)
    }
}

@Composable
private fun StatusDot(on: Boolean) {
    Box(Modifier.size(8.dp).background(if (on) ObsRed else Color(0xFF55585F), CircleShape))
}

/** Dock widths/heights sized from the screen, tuned for the Astra's 2400×1504 panel. */
data class StudioLayoutMetrics(val leftWidth: Dp, val bottomHeight: Dp, val transitionsWidth: Dp, val controlsWidth: Dp)

fun studioLayoutMetrics(width: Dp, height: Dp) = StudioLayoutMetrics(
    leftWidth = (width * 0.18f).coerceIn(200.dp, 300.dp),
    bottomHeight = (height * 0.30f).coerceIn(170.dp, 250.dp),
    transitionsWidth = (width * 0.16f).coerceIn(180.dp, 260.dp),
    controlsWidth = (width * 0.18f).coerceIn(190.dp, 280.dp)
)
