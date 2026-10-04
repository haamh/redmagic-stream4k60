package com.stream4k60.app.data.model

data class VideoConfig(
    val baseResWidth: Int = 3840,
    val baseResHeight: Int = 2160,
    val outputResWidth: Int = 3840,
    val outputResHeight: Int = 2160,
    val videoBitrateKbps: Int = 40_000,
    val audioBitrateKbps: Int = 320,
    val rateControl: RateControl = RateControl.CBR,
    /** OBS's "Dynamically change bitrate to manage congestion": lower the bitrate instead of dropping frames. */
    val dynamicBitrate: Boolean = true,
    val outputCodec: OutputCodec = OutputCodec.H264,
    val fpsType: FpsType = FpsType.COMMON,
    val fpsCommon: FpsCommon = FpsCommon.FPS_60,
    val fpsInt: Int = 60,
    val fpsNum: Int = 60,
    val fpsDen: Int = 1,
    val downscaleFilter: DownscaleFilter = DownscaleFilter.BICUBIC,
    val colorFormat: ColorFormat = ColorFormat.NV12,
    val colorSpace: ColorSpace = ColorSpace.SRGB,
    val colorRange: ColorRange = ColorRange.PARTIAL,
    val hdrEnabled: Boolean = false,
    /** OBS's SDR White Level: how bright SDR sources (camera, SDR video, images) are in an HDR stream, in nits. */
    val sdrWhiteLevel: Int = 300,
    /** OBS's HDR Nominal Peak Level: the brightest HDR highlight, in nits (HDR10 metadata, tone-mapping roll-off). */
    val hdrNominalPeak: Int = 1000,
    /** HDR output on an HDR screen: the preview shows the HDR signal itself, as HDR viewers see the stream. */
    val hdrPreview: Boolean = true
) {
    /** HDR output is OBS's Rec. 2100 colour space (PQ or HLG), always 10-bit. */
    val hdrOutput: Boolean
        get() = colorSpace == ColorSpace.REC2100PQ || colorSpace == ColorSpace.REC2100HLG
    val outputColor: OutputColor
        get() = OutputColor(
            tenBit = hdrOutput || colorFormat == ColorFormat.P010 || colorFormat == ColorFormat.I010,
            transfer = when (colorSpace) { ColorSpace.REC2100PQ -> 1; ColorSpace.REC2100HLG -> 2; else -> 0 },
            fullRange = colorRange == ColorRange.FULL,
            sdrWhiteLevel = sdrWhiteLevel,
            hdrNominalPeak = hdrNominalPeak
        )

    val frameRate: Int
        get() = when (fpsType) {
            FpsType.COMMON -> fpsCommon.value
            FpsType.INTEGER -> fpsInt.coerceIn(1, 120)
            FpsType.FRACTIONAL -> (fpsNum.toDouble() / fpsDen.coerceAtLeast(1)).toInt().coerceIn(1, 120)
        }
}

/**
 * What the stream / recording is encoded as (OBS: Settings → Advanced → Video). Sources keep their own colour and are
 * converted to this: transfer 0 SDR (Rec. 709 / sRGB), 1 HDR10 (Rec. 2100 PQ), 2 HLG (Rec. 2100 HLG).
 */
data class OutputColor(
    val tenBit: Boolean = false,
    val transfer: Int = 0,
    val fullRange: Boolean = false,
    val sdrWhiteLevel: Int = 300,
    val hdrNominalPeak: Int = 1000
) {
    val hdr: Boolean get() = transfer != 0
}

/** Video rate control, as OBS's "Rate Control": CBR keeps the bitrate steady (what streaming services expect). */
enum class RateControl { CBR, VBR }

/** AAC bitrates offered in Output settings, as in OBS. */
val AUDIO_BITRATES_KBPS = listOf(64, 96, 128, 160, 192, 256, 320, 384, 512)

/**
 * Bounds a video size by its long and short side (3840 × 2160 by default), so portrait 9:16 sizes such as
 * 1080 × 1920 or 2160 × 3840 are allowed as well as landscape ones.
 */
fun clampVideoSize(width: Int, height: Int, maxLong: Int = 3840, maxShort: Int = 2160): Pair<Int, Int> {
    val long = maxOf(width, height).coerceIn(320, maxLong)
    val short = minOf(width, height).coerceIn(240, maxShort)
    return if (height > width) short to long else long to short
}

enum class FpsType {
    COMMON, INTEGER, FRACTIONAL
}

enum class FpsCommon(val value: Int) {
    FPS_30(30), FPS_60(60), FPS_120(120)
}

enum class DownscaleFilter {
    BILINEAR, BICUBIC, LANCZOS, AREA
}

enum class ColorFormat {
    NV12, I420, I444, P010, I010
}

enum class ColorSpace {
    SRGB, REC709, REC2100PQ, REC2100HLG
}

enum class ColorRange {
    PARTIAL, FULL
}
