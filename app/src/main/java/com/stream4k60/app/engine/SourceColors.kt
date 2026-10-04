package com.stream4k60.app.engine

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Each source's own colour, as OBS shows it and lets it be overridden per source: what the file says, and the signal
 * the renderer treats it as (0 SDR, 1 HDR10 / PQ, 2 HLG). The renderer converts every source into the output's colour
 * (Settings → Video → Colour / HDR): in an HDR stream an HDR clip goes out untouched, in an SDR one it is tone-mapped.
 */
object SourceColors {
    /** [matrix] 0 Rec. 601, 1 Rec. 709, 2 BT.2020; [range] 0 limited, 1 full; -1 = not said. */
    data class Detected(val transfer: Int, val label: String, val matrix: Int = -1, val range: Int = -1)

    private val _detected = MutableStateFlow<Map<String, Detected>>(emptyMap())
    val detected = _detected.asStateFlow()

    /** USB cameras / capture cards: what the device declares about its YUV colour, in words (Properties). */
    private val _usb = MutableStateFlow<Map<String, String>>(emptyMap())
    val usb = _usb.asStateFlow()
    fun reportUsb(sourceId: String, note: String) = _usb.update { if (it[sourceId] == note) it else it + (sourceId to note) }

    fun report(sourceId: String, value: Detected) {
        _detected.update { if (it[sourceId] == value) it else it + (sourceId to value) }
        // What the decoder converts with by itself; a different Color space / range in Properties is re-applied from it.
        NativeEngine.setSourceDecodedColor(sourceId, 1, value.matrix, value.range)
    }
    fun get(sourceId: String): Detected? = _detected.value[sourceId]

    /** Source types that get OBS's video colour options (Color space, Color range, Signal). */
    val VIDEO_TYPES = setOf("USB_CAPTURE", "MEDIA", "CAMERA", "VIDEO_CAPTURE")

    /**
     * Applies a video source's colour settings, as OBS's Video Capture Device / Media Source: Signal (auto / sdr / hlg /
     * pq; media used to store it as "colorSpace"), Color space (YUV matrix) and Color range. "Auto" / "device" = what the
     * file, decoder or device says, so a mistagged source can be corrected.
     */
    fun apply(sourceId: String, settings: JSONObject) {
        val signal = settings.optString("yuvSignal", "").ifBlank { settings.optString("colorSpace", "auto") }
        val transfer = when (signal) {
            "sdr" -> 0
            "pq" -> 1
            "hlg" -> 2
            else -> get(sourceId)?.transfer ?: 0
        }
        NativeEngine.setSourceTransfer(sourceId, transfer)
        NativeEngine.setSourceYuvColor(sourceId, matrixSetting(settings), rangeSetting(settings))
        NativeEngine.setSourceHlgLook(sourceId, settings.optInt("hlgStrength", 100).coerceIn(0, 100) / 100f, settings.optInt("hlgColour", 100).coerceIn(0, 100) / 100f, settings.optInt("hlgPeak", 1000).coerceIn(203, 1000).toFloat())
    }
    fun matrixSetting(settings: JSONObject) = when (settings.optString("yuvColorSpace", "device")) { "601" -> 0; "709" -> 1; "2020" -> 2; else -> -1 }
    fun rangeSetting(settings: JSONObject) = when (settings.optString("yuvColorRange", "auto")) { "limited", "partial" -> 0; "full" -> 1; else -> -1 }
    fun matrixName(m: Int) = when (m) { 0 -> "Rec. 601"; 1 -> "Rec. 709"; 2 -> "BT.2020"; else -> "unspecified" }
    fun rangeName(r: Int) = when (r) { 0 -> "limited range"; 1 -> "full range"; else -> "range not said" }

    fun describe(info: ColorInfo?): Detected {
        if (info == null || info.colorTransfer == Format_NO_VALUE && info.colorSpace == Format_NO_VALUE) {
            return Detected(0, "SDR (not tagged in the file, shown as Rec. 709)")
        }
        val transfer = when (info.colorTransfer) { C.COLOR_TRANSFER_ST2084 -> 1; C.COLOR_TRANSFER_HLG -> 2; else -> 0 }
        val kind = when (transfer) { 1 -> "HDR10 (Rec. 2100 PQ)"; 2 -> "HLG (Rec. 2100 HLG)"; else -> "SDR" }
        val primaries = when (info.colorSpace) {
            C.COLOR_SPACE_BT2020 -> "BT.2020"
            C.COLOR_SPACE_BT709 -> "Rec. 709"
            C.COLOR_SPACE_BT601 -> "Rec. 601"
            else -> null
        }
        val range = when (info.colorRange) { C.COLOR_RANGE_FULL -> "full range"; C.COLOR_RANGE_LIMITED -> "limited range"; else -> null }
        val depth = info.lumaBitdepth.takeIf { it > 0 }?.let { "$it-bit" }
        val matrix = when (info.colorSpace) { C.COLOR_SPACE_BT2020 -> 2; C.COLOR_SPACE_BT709 -> 1; C.COLOR_SPACE_BT601 -> 0; else -> -1 }
        val fullRange = when (info.colorRange) { C.COLOR_RANGE_FULL -> 1; C.COLOR_RANGE_LIMITED -> 0; else -> -1 }
        return Detected(transfer, listOfNotNull(kind, primaries, range, depth).joinToString(" · "), matrix, fullRange)
    }

    private const val Format_NO_VALUE = androidx.media3.common.Format.NO_VALUE
}
