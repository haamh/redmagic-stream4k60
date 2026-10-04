package com.stream4k60.app.ui.sources

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stream4k60.app.engine.TextSourceRenderer
import com.stream4k60.app.ui.util.showImeOnFocus
import org.json.JSONObject
import java.util.Locale

/** Android's built-in font families (the text source's font list; any installed font file can be added too). */
private val FONT_FAMILIES = listOf(
    "sans-serif" to "Sans serif (Roboto)", "sans-serif-medium" to "Sans serif medium", "sans-serif-light" to "Sans serif light",
    "sans-serif-thin" to "Sans serif thin", "sans-serif-black" to "Sans serif black", "sans-serif-condensed" to "Sans serif condensed",
    "sans-serif-smallcaps" to "Small caps", "serif" to "Serif (Noto Serif)", "monospace" to "Monospace", "serif-monospace" to "Serif monospace",
    "casual" to "Casual", "cursive" to "Cursive"
)

/**
 * Every option of OBS's text sources (Text (GDI+) and Text (FreeType 2)), simple ones first. Values come from the
 * renderer's own reading of the settings, so sources imported from OBS show their OBS values.
 */
@Composable
internal fun TextSourceOptions(sourceName: String, imported: Boolean, settings: JSONObject, set: (String, Any?) -> Unit, onError: (String?) -> Unit) {
    val context = LocalContext.current
    val style = remember(settings.toString()) { TextSourceRenderer.style(settings, sourceName, imported) }
    fun hex(c: Int) = String.format(Locale.US, "#%06X", c and 0xFFFFFF)
    fun opacity(c: Int) = ((c ushr 24) * 100f / 255f).toInt()
    fun persist(uri: android.net.Uri, key: String) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            .onSuccess { set(key, uri.toString()); onError(null) }
            .onFailure { onError("Android did not grant lasting access to this file. Choose it again and allow document access.") }
    }
    val textFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> persist(uri, "textFile") } }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> persist(uri, "fontFile") } }

    CheckField("Read from file", style.readFromFile, "Shows the contents of a text file and updates when the file changes (UTF-8 or UTF-16).") { set("readFromFile", it) }
    if (style.readFromFile) {
        Text("Text file: " + style.file.ifBlank { "none selected" }.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { textFilePicker.launch(arrayOf("text/*")) }) { Text("Choose text file…") }
    } else {
        OutlinedTextField(style.text, { set("text", it) }, Modifier.fillMaxWidth().padding(vertical = 3.dp).showImeOnFocus(), label = { Text("Text") }, minLines = 3)
    }

    OptionSection("Font")
    val familyKnown = FONT_FAMILIES.any { it.first == style.fontFamily }
    val familyOptions = (if (familyKnown) FONT_FAMILIES else FONT_FAMILIES + (style.fontFamily to "${style.fontFamily} (not on Android: closest system font)"))
    ChoiceDropdown("Font", familyOptions, style.fontFamily) { set("fontFamily", it); set("fontFile", "") }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Font file: " + style.fontFile.ifBlank { "none (uses the font above)" }.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) }) { Text("Choose .ttf / .otf…") }
        if (style.fontFile.isNotBlank()) TextButton(onClick = { set("fontFile", "") }) { Text("Clear") }
    }
    SliderField("Size", style.size.toInt(), 8..512, " px") { set("fontSize", it) }
    Row {
        Box(Modifier.weight(1f)) { CheckField("Bold", style.bold) { set("bold", it) } }
        Box(Modifier.weight(1f)) { CheckField("Italic", style.italic) { set("italic", it) } }
    }
    Row {
        Box(Modifier.weight(1f)) { CheckField("Underline", style.underline) { set("underline", it) } }
        Box(Modifier.weight(1f)) { CheckField("Strikeout", style.strikeout) { set("strikeout", it) } }
    }

    OptionSection("Colour")
    ColorField("Color", hex(style.color), showAlpha = false) { set("textColor", "#FF" + it.removePrefix("#").takeLast(6)); set("textOpacity", opacity(style.color)) }
    SliderField("Opacity", opacity(style.color), 0..100, "%") { set("textColor", "#FF" + hex(style.color).removePrefix("#")); set("textOpacity", it) }
    CheckField("Gradient", style.gradient, "Blends from the colour into a second colour across the text.") { set("gradient", it) }
    if (style.gradient) {
        ColorField("Gradient color", hex(style.gradientColor), showAlpha = false) { set("gradientColor", "#FF" + it.removePrefix("#").takeLast(6)); set("gradientOpacity", opacity(style.gradientColor)) }
        SliderField("Gradient opacity", opacity(style.gradientColor), 0..100, "%") { set("gradientColor", "#FF" + hex(style.gradientColor).removePrefix("#")); set("gradientOpacity", it) }
        SliderField("Gradient direction", style.gradientDirection.toInt(), 0..360, "°") { set("gradientDirection", it) }
    }
    ColorField("Background color", hex(style.background), showAlpha = false) { set("backgroundColor", "#FF" + it.removePrefix("#").takeLast(6)); set("backgroundOpacity", opacity(style.background).takeIf { o -> o > 0 } ?: 100) } // choosing a colour shows it
    SliderField("Background opacity", opacity(style.background), 0..100, "%") { set("backgroundColor", "#FF" + hex(style.background).removePrefix("#")); set("backgroundOpacity", it) }
    ChoiceDropdown("Background shape", listOf("box" to "One box behind the text", "lines" to "Highlight behind each line"), if (style.backgroundMode == 1) "lines" else "box") { set("backgroundStyle", it) }
    SliderField("Corner radius", style.backgroundRadius.toInt(), 0..200, " px") { set("backgroundRadius", it) }
    if (style.backgroundMode == 1) SliderField("Highlight padding", style.highlightPadding.toInt(), 0..100, " px") { set("highlightPadding", it) }

    OptionSection("Layout")
    ChoiceDropdown("Alignment", listOf("left" to "Left", "center" to "Center", "right" to "Right"), listOf("left", "center", "right")[style.align]) { set("alignment", it) }
    ChoiceDropdown("Vertical alignment", listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom"), listOf("top", "center", "bottom")[style.valign]) { set("verticalAlignment", it) }
    ChoiceDropdown("Transform", listOf("0" to "None", "1" to "Uppercase", "2" to "Lowercase", "3" to "Start Case"), style.transform.toString()) { set("textTransform", it.toInt()) }
    CheckField("Vertical", style.vertical, "Characters run top to bottom; each line becomes a column, right to left.") { set("vertical", it) }

    OptionSection("Rolling text")
    CheckField("Rolling text", style.rolling, "Keeps a compact curved window while the text rolls inside it. The text and box move and resize as one source.") { set("rollingText", it) }
    if (style.rolling) {
        SliderField("Window width", style.rollingWidth, 64..4096, " px") { set("rollingWidth", it) }
        SliderField("Window height", style.rollingHeight, 0..1024, " px (0 = fit text)") { set("rollingHeight", it) }
        SliderField("Rolling speed", style.rollingSpeed.toInt(), -2000..2000, " px/s") { set("rollingSpeed", it) }
        SliderField("Gap", style.rollingGap, 0..2048, " px") { set("rollingGap", it) }
        Text("Positive speed rolls left; negative rolls right. The window is the source itself, so its curved ends stay visible instead of requiring the whole sentence width.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    OptionSection("Outline and shadow")
    CheckField("Outline", style.outline) { set("outline", it) }
    if (style.outline) {
        SliderField("Outline size", style.outlineSize.toInt(), 1..64, " px") { set("outlineSize", it) }
        ColorField("Outline color", hex(style.outlineColor), showAlpha = false) { set("outlineColor", "#FF" + it.removePrefix("#").takeLast(6)); set("outlineOpacity", opacity(style.outlineColor)) }
        SliderField("Outline opacity", opacity(style.outlineColor), 0..100, "%") { set("outlineColor", "#FF" + hex(style.outlineColor).removePrefix("#")); set("outlineOpacity", it) }
    }
    CheckField("Drop shadow", style.shadow) { set("dropShadow", it) }
    if (style.shadow) {
        ColorField("Shadow color", hex(style.shadowColor), showAlpha = false) { set("shadowColor", "#FF" + it.removePrefix("#").takeLast(6)); set("shadowOpacity", opacity(style.shadowColor)) }
        SliderField("Shadow opacity", opacity(style.shadowColor), 0..100, "%") { set("shadowColor", "#FF" + hex(style.shadowColor).removePrefix("#")); set("shadowOpacity", it) }
        SliderField("Shadow offset X", style.shadowX.toInt(), -64..64, " px") { set("shadowOffsetX", it) }
        SliderField("Shadow offset Y", style.shadowY.toInt(), -64..64, " px") { set("shadowOffsetY", it) }
    }

    OptionSection("Advanced")
    CheckField("Antialiasing", style.antialias, "Smooth glyph edges. Turn off for pixel fonts.") { set("antialiasing", it) }
    CheckField("Chat log mode", style.chatlog, "Shows only the last lines, as a chat log.") { set("chatlog", it) }
    if (style.chatlog) SliderField("Chat log lines", style.chatlogLines, 1..100) { set("chatlogLines", it) }
    CheckField("Use custom text extents", style.extents, "A fixed box instead of sizing to the text; the source's bounding box is this size.") { set("useCustomExtents", it) }
    if (style.extents) {
        SliderField("Width", style.extentWidth, 16..4096, " px") { set("extentWidth", it) }
        SliderField("Height", style.extentHeight, 0..4096, " px (0 = fit the text)") { set("extentHeight", it) }
        CheckField("Wrap", style.wrap, "Breaks lines at the box width.") { set("wrap", it) }
    }
}
