package com.stream4k60.app.ui.dialogs

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.util.showImeOnFocus
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * OBS-style colour picker: a hue / saturation wheel, brightness and opacity sliders, basic colours and a hex field
 * (#RRGGBB or #AARRGGBB). [showAlpha] hides the opacity slider where a separate opacity setting exists.
 */
@Composable
fun ColorPickerDialog(
    initialColor: Int,
    onDismiss: () -> Unit,
    title: String = "Select colour",
    showAlpha: Boolean = true,
    onColorSelected: (Int) -> Unit
) {
    var hue by remember(initialColor) { mutableFloatStateOf(hsvOf(initialColor)[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(hsvOf(initialColor)[1]) }
    var value by remember(initialColor) { mutableFloatStateOf(hsvOf(initialColor)[2]) }
    var alpha by remember(initialColor) { mutableFloatStateOf(android.graphics.Color.alpha(initialColor) / 255f) }
    val selected = android.graphics.Color.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, saturation, value))
    var hexValue by remember(initialColor) { mutableStateOf(formatColor(initialColor, showAlpha)) }
    var editingHex by remember { mutableStateOf(false) }
    val shownHex = if (editingHex) hexValue else formatColor(selected, showAlpha)
    fun setColor(c: Int) { val hsv = hsvOf(c); hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; alpha = android.graphics.Color.alpha(c) / 255f }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle(title, onDismiss) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ColorWheel(hue, saturation, value, Modifier.size(200.dp)) { h, s -> editingHex = false; hue = h; saturation = s }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Old", style = MaterialTheme.typography.labelSmall)
                        Swatch(initialColor, Modifier.size(56.dp, 28.dp))
                        Text("New", style = MaterialTheme.typography.labelSmall)
                        Swatch(selected, Modifier.size(56.dp, 28.dp))
                    }
                }
                Text("Brightness · ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { editingHex = false; value = it }, valueRange = 0f..1f)
                if (showAlpha) {
                    Text("Opacity · ${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = alpha, onValueChange = { editingHex = false; alpha = it }, valueRange = 0f..1f)
                }
                Text("Basic colours", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BASIC.forEach { c -> Swatch(c, Modifier.size(26.dp).clip(CircleShape).clickable { editingHex = false; setColor(if (showAlpha) c else c or (0xFF shl 24)) }) }
                }
                OutlinedTextField(
                    value = shownHex,
                    onValueChange = { typed ->
                        editingHex = true
                        hexValue = typed.take(9)
                        runCatching { android.graphics.Color.parseColor(hexValue) }.getOrNull()?.let { c -> setColor(if (showAlpha) c else c or (0xFF shl 24)) }
                    },
                    label = { Text("Hex colour") },
                    supportingText = { Text(if (showAlpha) "#RRGGBB or #AARRGGBB (AA = opacity)" else "#RRGGBB") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().showImeOnFocus()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onColorSelected(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun Swatch(color: Int, modifier: Modifier = Modifier) {
    // A checkerboard under the colour shows its transparency.
    Box(modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp))) {
        Canvas(Modifier.matchParentSize()) {
            val cell = 6.dp.toPx()
            var y = 0f; var row = 0
            while (y < size.height) { var x = 0f; var col = 0; while (x < size.width) { drawRect(if ((row + col) % 2 == 0) ComposeColor(0xFFCCCCCC) else ComposeColor.White, Offset(x, y), androidx.compose.ui.geometry.Size(cell, cell)); x += cell; col++ }; y += cell; row++ }
            drawRect(ComposeColor(color))
        }
    }
}

/** Hue around, saturation outwards; the selection ring follows touches and drags. */
@Composable
fun ColorWheel(hue: Float, saturation: Float, value: Float, modifier: Modifier = Modifier, onChange: (Float, Float) -> Unit) {
    fun pick(p: Offset, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f; val r = minOf(cx, cy)
        val dx = p.x - cx; val dy = p.y - cy
        val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0).toFloat()
        onChange(angle, (hypot(dx, dy) / r).coerceIn(0f, 1f))
    }
    Canvas(modifier
        .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { pick(it, size.width.toFloat(), size.height.toFloat()) }) { change, _ -> pick(change.position, size.width.toFloat(), size.height.toFloat()) } }
    ) {
        val r = size.minDimension / 2f
        drawCircle(Brush.sweepGradient(listOf(ComposeColor.Red, ComposeColor.Yellow, ComposeColor.Green, ComposeColor.Cyan, ComposeColor.Blue, ComposeColor.Magenta, ComposeColor.Red)), r)
        drawCircle(Brush.radialGradient(listOf(ComposeColor.White, ComposeColor.White.copy(alpha = 0f)), center, r), r)
        drawCircle(ComposeColor.Black.copy(alpha = 1f - value), r)
        val a = Math.toRadians(hue.toDouble())
        val p = Offset(center.x + (cos(a) * saturation * r).toFloat(), center.y + (sin(a) * saturation * r).toFloat())
        drawCircle(ComposeColor.Black, 9.dp.toPx(), p, style = Stroke(3.dp.toPx()))
        drawCircle(ComposeColor.White, 9.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
    }
}

private val BASIC = listOf(0xFF000000, 0xFFFFFFFF, 0xFF808080, 0xFFFF0000, 0xFFFF8000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFF8000FF, 0xFFFF00FF).map { it.toInt() }
private fun hsvOf(c: Int) = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
private fun formatColor(color: Int, alpha: Boolean = true): String = if (alpha) String.format(Locale.US, "#%08X", color) else String.format(Locale.US, "#%06X", color and 0xFFFFFF)
