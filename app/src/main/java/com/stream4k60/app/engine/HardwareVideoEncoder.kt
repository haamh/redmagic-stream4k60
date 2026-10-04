package com.stream4k60.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Direct Android MediaCodec video encoder. The input is an ANativeWindow/Surface, so
 * composition can render straight into the Snapdragon VPU input queue.
 */
class HardwareVideoEncoder(
    private val config: Config,
    private val onSample: (Sample) -> Unit,
    private val onFormat: (MediaFormat) -> Unit
) {
    data class Config(
        val mime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val keyframeIntervalSec: Int = 2,
        val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
        val profile: Int? = null,
        val level: Int? = null,
        /** OBS's colour settings: P010 (10-bit), 0 SDR / 1 Rec. 2100 PQ / 2 Rec. 2100 HLG, full range, HDR peak nits. */
        val tenBit: Boolean = false,
        val transfer: Int = 0,
        val fullRange: Boolean = false,
        val hdrPeak: Int = 1000
    )
    data class Sample(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean, val codecConfig: Boolean)

    private var codec: MediaCodec? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var inputSurface: Surface? = null

    suspend fun prepare(): Surface = withContext(Dispatchers.Default) {
        val encoderInfo = findHardwareEncoder(config.mime, config.width, config.height, config.fps)
            ?: error("No hardware encoder supports ${config.mime} at ${config.width}x${config.height}@${config.fps}")
        val c = MediaCodec.createByCodecName(encoderInfo.name)
        // Use the requested rate control when the encoder has it, else the other one; say which in the stream log.
        val caps = encoderInfo.getCapabilitiesForType(config.mime).encoderCapabilities
        val mode = listOf(config.bitrateMode, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            .firstOrNull { caps.isBitrateModeSupported(it) } ?: config.bitrateMode
        // 10-bit / HDR needs a Main 10 profile (Main 10 HDR10 for PQ when offered), at the encoder's highest level.
        val levels = encoderInfo.getCapabilitiesForType(config.mime).profileLevels
        val profile = config.profile ?: if (config.mime == "video/hevc" && config.tenBit) {
            if (config.transfer == 1 && levels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 }) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        } else null
        val level = config.level ?: profile?.let { p -> levels.filter { it.profile == p }.maxOfOrNull { it.level } }
        val colour = (if (config.tenBit) "10-bit " else "8-bit ") + when (config.transfer) { 1 -> "HDR10 (Rec. 2100 PQ)"; 2 -> "HLG (Rec. 2100 HLG)"; else -> "SDR (Rec. 709)" } + if (config.fullRange) ", full range" else ", limited range"
        StreamLog.add("Video encoder ${encoderInfo.name}: ${config.width}x${config.height}@${config.fps}, ${config.bitrate / 1000} Kbps ${if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) "CBR" else if (mode == MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) "VBR" else "mode $mode"}, $colour${profile?.let { ", profile $it" } ?: ""}")
        val fmt = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSec)
            setInteger(MediaFormat.KEY_BITRATE_MODE, mode)
            profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            level?.let { setInteger(MediaFormat.KEY_LEVEL, it) }
            // Written into the stream (VUI) so YouTube and players know how to show it; the GPU hands the encoder RGB
            // tagged with the same colour (RGBA 1010102 BT.2020 PQ / HLG for HDR).
            setInteger(MediaFormat.KEY_COLOR_STANDARD, if (config.transfer != 0) MediaFormat.COLOR_STANDARD_BT2020 else MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, when (config.transfer) { 1 -> MediaFormat.COLOR_TRANSFER_ST2084; 2 -> MediaFormat.COLOR_TRANSFER_HLG; else -> MediaFormat.COLOR_TRANSFER_SDR_VIDEO })
            setInteger(MediaFormat.KEY_COLOR_RANGE, if (config.fullRange) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
            if (config.transfer == 1) setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo(config.hdrPeak))
        }
        // Real-time encoding at full clocks: priority 0 (real time) and an operating rate above the frame rate, so the encoder
        // isn't clocked for a lighter load and back-pressures the renderer (which then drops to the encoder's pace).
        fmt.setInteger(MediaFormat.KEY_PRIORITY, 0)
        fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        val configured = runCatching { c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        if (configured.isFailure) {
            StreamLog.add("Video encoder refused the real-time hints (${configured.exceptionOrNull()?.message}); configuring without them")
            c.reset(); fmt.removeKey(MediaFormat.KEY_OPERATING_RATE); fmt.removeKey(MediaFormat.KEY_PRIORITY)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } else StreamLog.add("Video encoder: real-time priority, maximum operating rate")
        inputSurface = c.createInputSurface()
        codec = c
        inputSurface!!
    }

    fun start() {
        val c = codec ?: error("prepare() first")
        check(!running)
        c.start()
        running = true
        thread = Thread({ drain(c) }, "Stream4k-VideoEncoder").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    fun requestKeyframe() {
        codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }

    /**
     * HDR10 static metadata (CTA-861.3, little-endian): BT.2020 primaries and D65 white in 0.00002 units, then mastering
     * max / min luminance (nits, 0.0001 nits) and MaxCLL / MaxFALL, all at the HDR nominal peak as OBS does.
     */
    private fun hdrStaticInfo(peak: Int): ByteBuffer = ByteBuffer.allocate(25).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put(0)
        intArrayOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, peak, 0, peak, peak).forEach { putShort(it.toShort()) }
        flip()
    }

    fun setBitrate(bitrate: Int) {
        codec?.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = c.dequeueOutputBuffer(info, 10_000)
            when {
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        val dup = buf.duplicate()
                        dup.position(info.offset)
                        dup.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        dup.get(bytes)
                        onSample(Sample(
                            bytes,
                            info.presentationTimeUs,
                            (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        ))
                    }
                    c.releaseOutputBuffer(idx, false)
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(c.outputFormat)
            }
        }
    }

    companion object {
        private val preferredMime = setOf("video/avc", "video/hevc", "video/av01")

        fun availableEncoders(): List<String> = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { info ->
                info.isEncoder &&
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it in preferredMime }
            }
            .map { it.name }
            .distinct()

        fun isSupported(mime: String): Boolean = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any {
            it.isEncoder && it.isHardwareAccelerated && !it.isSoftwareOnly && it.supportedTypes.contains(mime)
        }

        fun supportsResolution(mime: String, width: Int, height: Int, fps: Int): Boolean =
            findHardwareEncoder(mime, width, height, fps) != null

        fun findHardwareEncoder(mime: String, width: Int, height: Int, fps: Int): MediaCodecInfo? {
            return MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filter { info ->
                    info.isEncoder &&
                        info.isHardwareAccelerated &&
                        !info.isSoftwareOnly &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }
                .filter { info ->
                    runCatching {
                        val vc = info.getCapabilitiesForType(mime).videoCapabilities ?: return@runCatching false
                        if (!vc.isSizeSupported(width, height)) return@runCatching false
                        val rates = runCatching { vc.getSupportedFrameRatesFor(width, height) }.getOrNull()
                        rates == null || fps.toDouble() in rates.lower..rates.upper
                    }.getOrDefault(false)
                }
                .sortedWith(compareBy<MediaCodecInfo> { it.name.startsWith("c2.android", true) }
                    .thenBy { it.name.startsWith("OMX.google", true) }
                    .thenBy { it.name })
                .firstOrNull()
        }
    }
}
