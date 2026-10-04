package com.stream4k60.app.ui.sources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.SourceColors
import com.stream4k60.app.engine.SourceMediaCommands
import com.stream4k60.app.ui.dialogs.ColorPickerDialog
import com.stream4k60.app.ui.dialogs.Swatch
import com.stream4k60.app.ui.util.showImeOnFocus
import java.util.Locale
import kotlin.math.roundToInt

/** A small heading between groups of options. */
@Composable
internal fun OptionSection(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider(Modifier.padding(top = 2.dp, bottom = 4.dp))
}

/** A colour: a swatch that opens the colour wheel, and its hex value (typed values apply once valid). */
@Composable
internal fun ColorField(label: String, value: String, showAlpha: Boolean = true, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val parsed = runCatching { android.graphics.Color.parseColor(value) }.getOrNull()
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatch(parsed ?: 0, Modifier.size(44.dp, 36.dp).clickable { picking = true })
        OutlinedTextField(
            value = text,
            onValueChange = { typed -> text = typed.take(9); if (runCatching { android.graphics.Color.parseColor(text) }.isSuccess) onChange(text.uppercase(Locale.US)) },
            label = { Text(label) }, singleLine = true, isError = parsed == null,
            modifier = Modifier.weight(1f).showImeOnFocus()
        )
        OutlinedButton(onClick = { picking = true }) { Text("Select…") }
    }
    if (picking) ColorPickerDialog(initialColor = parsed ?: 0xFFFFFFFF.toInt(), title = label, showAlpha = showAlpha, onDismiss = { picking = false }) { c ->
        val hex = if (showAlpha) String.format(Locale.US, "#%08X", c) else String.format(Locale.US, "#%06X", c and 0xFFFFFF)
        text = hex; onChange(hex); picking = false
    }
}

/** A labelled slider over whole numbers with the value shown. */
@Composable
internal fun SliderField(label: String, value: Int, range: IntRange, suffix: String = "", onChange: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ${current.roundToInt()}$suffix", style = MaterialTheme.typography.bodyMedium)
        Slider(value = current.coerceIn(range.first.toFloat(), range.last.toFloat()), onValueChange = { current = it }, onValueChangeFinished = { onChange(current.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat())
    }
}

/** A checkbox row, as OBS shows boolean options. */
@Composable
internal fun CheckField(label: String, checked: Boolean, help: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Column(Modifier.weight(1f)) {
            Text(label)
            if (help != null) Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A labelled read-only dropdown over (key, label) choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChoiceDropdown(label: String, options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(value = options.firstOrNull { it.first == selectedKey }?.second ?: options.first().second, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth().padding(vertical = 3.dp))
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); expanded = false }) }
        }
    }
}

/**
 * OBS's colour options for a video source (Video Capture Device, Media Source): what the device / file / decoder says,
 * then Signal, Color space (YUV matrix) and Color range, each "auto" by default so only a mistagged source needs them.
 */
@Composable
internal fun VideoColorOptions(sourceId: String, str: (String, String) -> String, set: (String, Any?) -> Unit) {
    val usbNotes by SourceColors.usb.collectAsState()
    val detected by SourceColors.detected.collectAsState()
    OptionSection("Colour")
    val note = usbNotes[sourceId] ?: detected[sourceId]?.label
    Text("Source reports: " + (note ?: "shown once the video starts"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Media stored its signal as "colorSpace" before every source had these options.
    val signal = str("yuvSignal", "").ifBlank { str("colorSpace", "auto") }
    ChoiceDropdown("Signal", listOf("auto" to "Auto (as the source says; SDR if it doesn't)", "sdr" to "SDR (Rec. 709)", "hlg" to "HDR (HLG) — iPhone casting in HDR (set Color space Rec. 2020), iPhone HDR clips; or as a look on SDR cameras", "pq" to "HDR10 (PQ) — consoles / PCs in HDR, a capture card's 10-bit P010 mode, HDR files"), signal) { set("yuvSignal", it); set("colorSpace", it) }
    // An HDR signal can't travel in an 8-bit USB format: picking HLG / PQ there only re-reads an SDR picture (too bright, over-saturated).
    val format = str("format", "").uppercase()
    if (signal == "pq" && format in setOf("NV12", "MJPEG", "YUYV", "UYVY", "I420", "H264"))
        Text("This mode ($format) is 8-bit SDR, so it carries no HDR10: reading it as PQ makes it wrong. Use Auto here; real HDR from a capture card comes only in its 10-bit P010 mode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (signal == "hlg") {
        OptionSection("HLG look")
        if (format in setOf("NV12", "MJPEG", "YUYV", "UYVY", "I420", "H264"))
            Text("$format is an 8-bit SDR mode, so HLG here is a look rather than real HDR. These keep it from going too bright or too saturated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SliderField("Strength", str("hlgStrength", "100").toIntOrNull() ?: 100, 0..100, " %") { set("hlgStrength", it) }
        SliderField("Colour (0 = true colours, 100 = HLG's vivid colours)", str("hlgColour", "100").toIntOrNull() ?: 100, 0..100, " %") { set("hlgColour", it) }
        SliderField("Peak brightness", str("hlgPeak", "1000").toIntOrNull() ?: 1000, 203..1000, " nits") { set("hlgPeak", it) }
        Text("Strength fades between the normal picture (0) and the full HLG reading (100). Colour lowers the over-saturation while keeping the HLG tone curve. Peak brightness lowers highlights and overall brightness (1000 = plain HLG). The preview shows the result as the stream gets it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ChoiceDropdown("Color space", listOf("device" to "Auto (as reported)", "709" to "Rec. 709", "601" to "Rec. 601", "2020" to "Rec. 2020"), str("yuvColorSpace", "device").let { if (it == "default") "device" else it }) { set("yuvColorSpace", it) }
    ChoiceDropdown("Color range", listOf("auto" to "Auto (as reported; limited if not said)", "limited" to "Limited (16–235)", "full" to "Full (0–255)"), str("yuvColorRange", "auto").let { if (it == "default") "auto" else if (it == "partial") "limited" else it }) { set("yuvColorRange", it) }
    Text("Change these only if colours look wrong: washed out or too contrasty (range), tinted greens and reds (color space), or HDR shown flat or blown out (signal).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * One tap to make a camera look its best, the way OBS users tune webcams: a light sharpen plus a little contrast and
 * saturation, added as ordinary filters (named "Enhance …") that can be adjusted in Filters or removed here.
 */
@Composable
internal fun EnhanceButton(configJson: String, onChange: (String) -> Unit) {
    val stages = com.stream4k60.app.engine.VideoFilterChain.read(configJson)
    val enhanced = stages.any { it.name.startsWith("Enhance") }
    OptionSection("Enhance")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (enhanced) "On: sharpen 0.12, contrast +8 %, saturation +15 % (adjust them in Filters)." else "Webcams ship soft and flat. This adds a light sharpen and a contrast / saturation lift.",
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = {
            val next = if (enhanced) stages.filterNot { it.name.startsWith("Enhance") } else stages + listOf(
                com.stream4k60.app.engine.VideoFilterStage(type = com.stream4k60.app.engine.VideoFilterType.COLOR_CORRECTION, name = "Enhance colour")
                    .with("contrast", 1.08).with("saturation", 1.15),
                com.stream4k60.app.engine.VideoFilterStage(type = com.stream4k60.app.engine.VideoFilterType.SHARPEN, name = "Enhance sharpen").with("sharpness", 0.12)
            )
            onChange(com.stream4k60.app.engine.VideoFilterChain.write(configJson, next))
        }) { Text(if (enhanced) "Remove" else "Enhance") }
    }
}

/** OBS's media controls for a media or slideshow source. */
@Composable
internal fun MediaControls(sourceId: String, slideshow: Boolean) {
    val states by SourceMediaCommands.states.collectAsState()
    val state = states[sourceId]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.PREVIOUS) }) { Icon(Icons.Default.SkipPrevious, "Previous") }
        IconButton(onClick = { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.PLAY_PAUSE) }) { Icon(if (state?.playing == true) Icons.Default.Pause else Icons.Default.PlayArrow, if (state?.playing == true) "Pause" else "Play") }
        IconButton(onClick = { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.RESTART) }) { Icon(Icons.Default.Replay, "Restart") }
        IconButton(onClick = { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.STOP) }) { Icon(Icons.Default.Stop, "Stop") }
        IconButton(onClick = { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.NEXT) }) { Icon(Icons.Default.SkipNext, "Next") }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                state == null -> ""
                slideshow -> "Slide ${state.slide} of ${state.slides}"
                else -> "${time(state.positionMs)} / ${time(state.durationMs)}"
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    if (!slideshow && state != null && state.durationMs > 0) {
        var dragging by remember { mutableStateOf<Float?>(null) }
        Slider(
            value = dragging ?: (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { SourceMediaCommands.send(sourceId, SourceMediaCommands.Command.SEEK, (it * state.durationMs).toLong()) }; dragging = null }
        )
    }
}

private fun time(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60) }
