package com.stream4k60.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The text source with OBS's options (Text (GDI+) and Text (FreeType 2) together): font and style, read from file,
 * case transform, vertical text, colour + opacity with an optional gradient, background colour + opacity, alignment,
 * outline, drop shadow, chat log mode and custom text extents with word wrap. Settings use this app's keys; sources
 * imported from OBS fall back to OBS's own keys (and OBS's defaults, e.g. a background opacity of 0).
 */
object TextSourceRenderer {
    data class Style(
        val text: String,
        val readFromFile: Boolean, val file: String,
        val fontFamily: String, val fontFile: String, val size: Float,
        val bold: Boolean, val italic: Boolean, val underline: Boolean, val strikeout: Boolean,
        val antialias: Boolean,
        /** 0 none, 1 UPPERCASE, 2 lowercase, 3 Start Case. */
        val transform: Int,
        val vertical: Boolean,
        val color: Int, val gradient: Boolean, val gradientColor: Int, val gradientDirection: Float,
        val background: Int,
        /** 0 left, 1 center, 2 right; vertical: 0 top, 1 center, 2 bottom. */
        val align: Int, val valign: Int,
        val outline: Boolean, val outlineSize: Float, val outlineColor: Int,
        val shadow: Boolean, val shadowColor: Int, val shadowX: Float, val shadowY: Float,
        val chatlog: Boolean, val chatlogLines: Int,
        val extents: Boolean, val extentWidth: Int, val extentHeight: Int, val wrap: Boolean,
        /** Corner radius of the background (px). */
        val backgroundRadius: Float = 0f,
        /** 0: one box behind the whole text; 1: a highlight behind each line. */
        val backgroundMode: Int = 0,
        /** Space around each line inside its highlight (px). */
        val highlightPadding: Float = 12f
    )

    /** [s] drawn [k] times larger, so text scaled up on the canvas stays sharp instead of stretching a small bitmap. */
    fun scaled(s: Style, k: Float): Style = if (k == 1f) s else s.copy(
        size = s.size * k, outlineSize = s.outlineSize * k, shadowX = s.shadowX * k, shadowY = s.shadowY * k,
        extentWidth = (s.extentWidth * k).toInt().coerceAtLeast(1), extentHeight = (s.extentHeight * k).toInt(),
        backgroundRadius = s.backgroundRadius * k, highlightPadding = s.highlightPadding * k)

    /** OBS stores colours as 0xAABBGGRR integers. */
    fun obsColor(abgr: Long): Int { val v = abgr.toInt(); return (v and 0xFF00FF00.toInt()) or ((v and 0xFF) shl 16) or ((v shr 16) and 0xFF) }

    private fun withOpacity(color: Int, percent: Int): Int {
        val a = ((color ushr 24) * percent.coerceIn(0, 100) / 100f).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0xFFFFFF)
    }

    fun style(cfg: JSONObject, fallbackText: String, imported: Boolean): Style {
        fun color(key: String, obsKey: String?, default: Int): Int {
            cfg.optString(key, "").takeIf { it.isNotBlank() }?.let { s ->
                if (s.equals("transparent", true)) return 0
                runCatching { android.graphics.Color.parseColor(s) }.getOrNull()?.let { return it }
            }
            if (obsKey != null && cfg.has(obsKey)) return obsColor(cfg.optLong(obsKey)) or 0xFF000000.toInt() // GDI+ colours carry no alpha; opacity is separate
            return default
        }
        fun int(key: String, obsKey: String?, default: Int) = if (cfg.has(key)) cfg.optInt(key, default) else if (obsKey != null && cfg.has(obsKey)) cfg.optInt(obsKey, default) else default
        fun bool(key: String, obsKey: String?, default: Boolean) = if (cfg.has(key)) cfg.optBoolean(key, default) else if (obsKey != null && cfg.has(obsKey)) cfg.optBoolean(obsKey, default) else default
        val font = cfg.optJSONObject("font")
        val flags = font?.optInt("flags", 0) ?: 0 // OBS: 1 bold, 2 italic, 4 underline, 8 strikeout
        val ft2 = cfg.has("color1") || cfg.has("color2")
        var textColor = color("textColor", if (ft2) "color1" else "color", 0xFFFFFFFF.toInt())
        // A fully transparent text colour is never meant (old imports kept OBS's empty alpha byte).
        if (imported && (textColor ushr 24) == 0) textColor = textColor or 0xFF000000.toInt()
        val ft2Gradient = ft2 && cfg.has("color2") && cfg.optLong("color2") != cfg.optLong("color1", cfg.optLong("color2"))
        val align = when (cfg.optString("alignment", cfg.optString("align", "left")).lowercase()) { "center" -> 1; "right" -> 2; else -> 0 }
        val valign = when (cfg.optString("verticalAlignment", cfg.optString("valign", "top")).lowercase()) { "center" -> 1; "bottom" -> 2; else -> 0 }
        val extents = if (cfg.has("useCustomExtents")) cfg.optBoolean("useCustomExtents") else cfg.optBoolean("extents", false) || cfg.optInt("custom_width", 0) > 0
        return Style(
            text = cfg.optString("text", fallbackText),
            readFromFile = bool("readFromFile", if (cfg.has("from_file")) "from_file" else "read_from_file", false),
            file = cfg.optString("textFile", cfg.optString("text_file", cfg.optString("file", ""))),
            fontFamily = cfg.optString("fontFamily", font?.optString("face", "sans-serif") ?: "sans-serif").ifBlank { "sans-serif" },
            fontFile = cfg.optString("fontFile", ""),
            size = cfg.optDouble("fontSize", (font?.optInt("size", 64) ?: 64).toDouble()).toFloat().coerceIn(1f, 1024f),
            bold = bool("bold", null, flags and 1 != 0), italic = bool("italic", null, flags and 2 != 0),
            underline = bool("underline", null, flags and 4 != 0), strikeout = bool("strikeout", null, flags and 8 != 0),
            antialias = bool("antialiasing", "antialiasing", true),
            transform = int("textTransform", "transform", 0).coerceIn(0, 3),
            vertical = bool("vertical", "vertical", false),
            color = withOpacity(textColor, int("textOpacity", "opacity", 100)),
            gradient = bool("gradient", "gradient", ft2Gradient),
            gradientColor = withOpacity(color("gradientColor", if (ft2) "color2" else "gradient_color", 0xFFFFFFFF.toInt()), int("gradientOpacity", "gradient_opacity", 100)),
            gradientDirection = (if (cfg.has("gradientDirection")) cfg.optDouble("gradientDirection", 90.0) else cfg.optDouble("gradient_dir", 90.0)).toFloat(),
            // OBS's background defaults to black at opacity 0. Sources made here keep their colour's own alpha (the
            // default colour is transparent) unless an opacity was set.
            background = withOpacity(color("backgroundColor", "bk_color", 0x00000000), int("backgroundOpacity", "bk_opacity", if (imported && !cfg.has("backgroundOpacity")) 0 else 100)),
            align = align, valign = valign,
            outline = bool("outline", "outline", false),
            outlineSize = int("outlineSize", "outline_size", 2).coerceIn(1, 64).toFloat(),
            outlineColor = withOpacity(color("outlineColor", "outline_color", 0xFF000000.toInt()), int("outlineOpacity", "outline_opacity", 100)),
            shadow = bool("dropShadow", "drop_shadow", false),
            shadowColor = withOpacity(color("shadowColor", null, 0xFF000000.toInt()), int("shadowOpacity", null, 100)),
            shadowX = int("shadowOffsetX", null, 4).toFloat(), shadowY = int("shadowOffsetY", null, 4).toFloat(),
            chatlog = bool("chatlog", if (cfg.has("chatlog_mode")) "chatlog_mode" else "chatlog", false),
            chatlogLines = int("chatlogLines", "chatlog_lines", 6).coerceIn(1, 1000),
            extents = extents,
            extentWidth = int("extentWidth", if (cfg.has("custom_width")) "custom_width" else "extents_cx", 1280).coerceIn(1, 8192),
            extentHeight = int("extentHeight", "extents_cy", if (ft2) 0 else 720).coerceIn(0, 8192),
            wrap = bool("wrap", if (cfg.has("word_wrap")) "word_wrap" else "extents_wrap", true),
            backgroundRadius = int("backgroundRadius", null, 0).coerceIn(0, 1024).toFloat(),
            backgroundMode = if (cfg.optString("backgroundStyle", "box") == "lines") 1 else 0,
            highlightPadding = int("highlightPadding", null, 12).coerceIn(0, 512).toFloat()
        )
    }

    /** OBS's Transform: none, uppercase, lowercase, start case. */
    fun transform(text: String, mode: Int): String = when (mode) {
        1 -> text.uppercase()
        2 -> text.lowercase()
        3 -> buildString { var start = true; for (c in text) { append(if (start && c.isLetter()) c.titlecaseChar() else c); start = c.isWhitespace() } }
        else -> text
    }

    /** Chat log mode: only the last [lines] lines are shown. */
    fun chatlog(text: String, lines: Int): String = text.trimEnd('\n', '\r').split('\n').takeLast(lines.coerceAtLeast(1)).joinToString("\n")

    /** Text from a UTF-8 or UTF-16 (with byte order mark) file, as OBS reads it. */
    fun readTextFile(context: Context, path: String): String? = runCatching {
        val uri = Uri.parse(path)
        val bytes = when (uri.scheme?.lowercase()) {
            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            "file" -> uri.path?.let { java.io.File(it).readBytes() }
            else -> java.io.File(path).takeIf { it.isFile }?.readBytes()
        } ?: return@runCatching null
        val limited = if (bytes.size > 1_000_000) bytes.copyOfRange(bytes.size - 1_000_000, bytes.size) else bytes
        when {
            limited.size >= 2 && limited[0] == 0xFF.toByte() && limited[1] == 0xFE.toByte() -> String(limited, 2, limited.size - 2, Charsets.UTF_16LE)
            limited.size >= 2 && limited[0] == 0xFE.toByte() && limited[1] == 0xFF.toByte() -> String(limited, 2, limited.size - 2, Charsets.UTF_16BE)
            limited.size >= 3 && limited[0] == 0xEF.toByte() && limited[1] == 0xBB.toByte() && limited[2] == 0xBF.toByte() -> String(limited, 3, limited.size - 3, Charsets.UTF_8)
            else -> String(limited, Charsets.UTF_8)
        }
    }.getOrNull()

    private val typefaces = java.util.concurrent.ConcurrentHashMap<String, Typeface>()
    private fun typeface(context: Context, s: Style): Typeface {
        val styleBits = (if (s.bold) Typeface.BOLD else 0) or (if (s.italic) Typeface.ITALIC else 0)
        val base = s.fontFile.takeIf { it.isNotBlank() }?.let { file ->
            typefaces[file] ?: runCatching {
                val uri = Uri.parse(file)
                val tf = if (uri.scheme == "content") context.contentResolver.openFileDescriptor(uri, "r")?.use { Typeface.Builder(it.fileDescriptor).build() }
                else Typeface.createFromFile(uri.path ?: file)
                tf?.also { typefaces[file] = it }
            }.getOrNull()
        } ?: Typeface.create(s.fontFamily, Typeface.NORMAL)
        return Typeface.create(base, styleBits)
    }

    /** Draws [rawText] with [s]; the bitmap is the text's own size (plus outline / shadow), or the custom extents. */
    fun render(context: Context, s: Style, rawText: String): Bitmap {
        var text = rawText.replace("\r\n", "\n")
        if (s.chatlog) text = chatlog(text, s.chatlogLines)
        text = transform(text, s.transform)
        if (text.isEmpty()) text = " "
        val paint = TextPaint(if (s.antialias) Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG else 0).apply {
            textSize = s.size
            typeface = typeface(context, s)
            isUnderlineText = s.underline
            isStrikeThruText = s.strikeout
            // Synthetic bold / italic when the font has no such face.
            val missing = ((if (s.bold) Typeface.BOLD else 0) or (if (s.italic) Typeface.ITALIC else 0)) and typeface.style.inv()
            isFakeBoldText = missing and Typeface.BOLD != 0
            textSkewX = if (missing and Typeface.ITALIC != 0) -0.25f else 0f
        }
        val highlight = if (s.backgroundMode == 1 && (s.background ushr 24) != 0 && !s.vertical) s.highlightPadding else 0f
        val pad = ceil(max((if (s.outline) s.outlineSize else 0f) + (if (s.shadow) max(abs(s.shadowX), abs(s.shadowY)) else 0f), highlight) + 1f).toInt()
        return if (s.vertical) renderVertical(s, text, paint, pad) else renderHorizontal(s, text, paint, pad)
    }

    private fun renderHorizontal(s: Style, text: String, paint: TextPaint, pad: Int): Bitmap {
        val natural = text.split('\n').maxOf { ceil(Layout.getDesiredWidth(it, paint)).toInt() }.coerceAtLeast(1)
        val layoutWidth = if (s.extents && s.wrap) (s.extentWidth - 2 * pad).coerceAtLeast(1) else natural
        val alignment = when (s.align) { 1 -> Layout.Alignment.ALIGN_CENTER; 2 -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth).setAlignment(alignment).setIncludePad(false).build()
        val contentW = layoutWidth + 2 * pad
        val contentH = layout.height + 2 * pad
        val w = (if (s.extents) s.extentWidth else contentW).coerceIn(1, MAX_SIZE)
        val h = (if (s.extents && s.extentHeight > 0) s.extentHeight else contentH).coerceIn(1, MAX_SIZE).coerceAtMost((MAX_PIXELS / w).toInt().coerceAtLeast(1))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val x = when { !s.extents || s.wrap -> pad.toFloat(); s.align == 1 -> (w - layoutWidth) / 2f; s.align == 2 -> (w - layoutWidth - pad).toFloat(); else -> pad.toFloat() }
        val y = when (s.valign) { 1 -> (h - layout.height) / 2f; 2 -> (h - layout.height - pad).toFloat(); else -> pad.toFloat() }
        val lines = if (s.backgroundMode == 1) (0 until layout.lineCount).mapNotNull { i ->
            val l = layout.getLineLeft(i); val r = layout.getLineRight(i)
            if (r - l < 1f) null else android.graphics.RectF(x + l - s.highlightPadding, y + layout.getLineTop(i) - s.highlightPadding * 0.5f,
                x + r + s.highlightPadding, y + layout.getLineBottom(i) + s.highlightPadding * 0.5f)
        } else emptyList()
        drawBackground(canvas, s, w, h, lines)
        drawPasses(canvas, s, paint, x, y, layoutWidth.toFloat(), layout.height.toFloat()) { layout.draw(canvas) }
        return bitmap
    }

    /** Vertical text (OBS "Vertical"): each line is a column, characters top to bottom, columns right to left. */
    private fun renderVertical(s: Style, text: String, paint: TextPaint, pad: Int): Bitmap {
        val columns = text.split('\n').map { line -> line.codePoints().toArray().map { String(Character.toChars(it)) } }
        val step = paint.fontSpacing
        val columnWidth = max(step, columns.flatten().maxOfOrNull { paint.measureText(it) } ?: step)
        val rows = columns.maxOf { it.size }.coerceAtLeast(1)
        val contentW = ceil(columns.size * columnWidth).toInt() + 2 * pad
        val contentH = ceil(rows * step).toInt() + 2 * pad
        val w = (if (s.extents) s.extentWidth else contentW).coerceIn(1, MAX_SIZE)
        val h = (if (s.extents && s.extentHeight > 0) s.extentHeight else contentH).coerceIn(1, MAX_SIZE).coerceAtMost((MAX_PIXELS / w).toInt().coerceAtLeast(1))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, s, w, h, emptyList())
        paint.textAlign = Paint.Align.CENTER
        val blockW = columns.size * columnWidth
        val left = when (s.align) { 1 -> (w - blockW) / 2f; 2 -> w - blockW - pad; else -> pad.toFloat() }
        drawPasses(canvas, s, paint, left, pad.toFloat(), blockW, rows * step) {
            columns.forEachIndexed { c, chars ->
                val cx = left + blockW - (c + 0.5f) * columnWidth
                val colH = chars.size * step
                val top = when (s.valign) { 1 -> (h - colH) / 2f; 2 -> h - colH - pad; else -> pad.toFloat() }
                chars.forEachIndexed { r, ch -> canvas.drawText(ch, cx, top + r * step - paint.ascent(), paint) }
            }
        }
        return bitmap
    }

    /** The background: one box (rounded by [Style.backgroundRadius]) or, with [lines], a rounded highlight behind each line. */
    private fun drawBackground(canvas: Canvas, s: Style, w: Int, h: Int, lines: List<android.graphics.RectF>) {
        if ((s.background ushr 24) == 0) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.background }
        when {
            s.backgroundMode == 1 && lines.isNotEmpty() -> lines.forEach { canvas.drawRoundRect(it, s.backgroundRadius, s.backgroundRadius, p) }
            s.backgroundRadius <= 0f -> canvas.drawColor(s.background, PorterDuff.Mode.SRC)
            else -> canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), s.backgroundRadius, s.backgroundRadius, p)
        }
    }

    /** Shadow, then outline, then the fill (solid or gradient), each a pass of [draw] with the paint set up for it. */
    private inline fun drawPasses(canvas: Canvas, s: Style, paint: TextPaint, x: Float, y: Float, w: Float, h: Float, draw: () -> Unit) {
        canvas.save()
        canvas.translate(x, y)
        if (s.shadow) {
            canvas.save(); canvas.translate(s.shadowX, s.shadowY)
            paint.shader = null; paint.color = s.shadowColor
            if (s.outline) { paint.style = Paint.Style.FILL_AND_STROKE; paint.strokeWidth = s.outlineSize * 2; paint.strokeJoin = Paint.Join.ROUND } else paint.style = Paint.Style.FILL
            draw(); canvas.restore()
        }
        if (s.outline) {
            paint.shader = null; paint.color = s.outlineColor
            paint.style = Paint.Style.STROKE; paint.strokeWidth = s.outlineSize * 2; paint.strokeJoin = Paint.Join.ROUND
            draw()
        }
        paint.style = Paint.Style.FILL
        if (s.gradient) {
            // Along the gradient direction (degrees, clockwise from the x axis, as GDI+), from the colour to the gradient colour.
            val rad = Math.toRadians(s.gradientDirection.toDouble())
            val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
            val half = (abs(w * dx) + abs(h * dy)) / 2f
            val cx = w / 2f; val cy = h / 2f
            paint.color = 0xFFFFFFFF.toInt()
            paint.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, s.color, s.gradientColor, Shader.TileMode.CLAMP)
        } else { paint.shader = null; paint.color = s.color }
        draw()
        paint.shader = null
        canvas.restore()
    }

    /** Longest side of a text image (the GPU's limit): OBS tickers are often one long line at size 256, 8000+ px wide. */
    const val MAX_SIZE = 16384
    /** Pixel budget of one text image (about 160 MB): a huge multi-line text is cut at the bottom instead of running out of memory. */
    private const val MAX_PIXELS = 40_000_000L
}
