package com.stream4k60.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.stream4k60.app.data.model.AudioInputRoute
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Hardware AAC encoder fed by the native AAudio mixer. No Kotlin AudioRecord capture loops are
 * used for live device inputs: Android's native low-latency audio callback owns the capture clock.
 */
class HardwareAudioEncoder(
    private val context: Context,
    private val sampleRate: Int = 48_000,
    private val channels: Int = 2,
    private val bitrate: Int = 192_000,
    private val onSample: (HardwareVideoEncoder.Sample) -> Unit,
    private val onFormat: (MediaFormat) -> Unit
) {
    private data class PcmBlock(val samples: ShortArray, val ptsUs: Long)
    /**
     * Up to about a second of mixed audio. Sources such as the media player deliver in bursts ahead of real time; the
     * old 0.32 s queue overflowed on every burst and dropped 10 ms pieces (heard as crackling distortion).
     */
    private val pcmQueue = ArrayBlockingQueue<PcmBlock>(100)
    private var codec: MediaCodec? = null
    private var encodeThread: Thread? = null
    @Volatile private var running = false
    /** Blocks dropped because the queue was full, and silent blocks filled in because none arrived (stream log). */
    private val droppedBlocks = java.util.concurrent.atomic.AtomicLong()
    private val silenceBlocks = java.util.concurrent.atomic.AtomicLong()
    fun takeStats(): String {
        val dropped = droppedBlocks.getAndSet(0); val silent = silenceBlocks.getAndSet(0)
        return if (dropped == 0L && silent == 0L) "" else "encoder dropped ${dropped * 10} ms, filled ${silent * 10} ms of silence"
    }
    private val sink: (ByteBuffer, Long, Int, Int, Int) -> Unit = { buffer, ptsUs, frames, channels, sampleRate -> callback.onMixed(buffer, ptsUs, frames, channels, sampleRate) }
    private val callback = object : NativeAudioMixer.Callback {
        override fun onMixed(buffer: ByteBuffer, ptsUs: Long, frames: Int, channels: Int, sampleRate: Int) {
            if (!running) return
            val out = toPcm16(buffer, frames * channels)
            // Drop oldest audio under overload; never let live latency grow without bound.
            while (!pcmQueue.offer(PcmBlock(out, ptsUs))) { pcmQueue.poll(); droppedBlocks.incrementAndGet() }
        }
    }

    companion object {
        /**
         * The mixer's float samples (native byte order) as 16-bit PCM. `duplicate()` resets a ByteBuffer to big-endian,
         * which read every float byte-swapped: near-silence with bursts of full-scale noise on the stream.
         */
        internal fun toPcm16(buffer: ByteBuffer, count: Int): ShortArray {
            val src = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder())
            src.clear()
            return ShortArray(count) { (src.getFloat().coerceIn(-1f, 1f) * 32767f).roundToInt().toShort() }
        }
    }

    fun start(
        inputRoutes: List<AudioInputRoute> = emptyList(),
        deviceIds: List<Int> = emptyList(),
        monitorDeviceId: Int? = null,
        monitorEnabled: Boolean = false,
        playbackCaptureEnabled: Boolean = false
    ) {
        check(!running)
        val routes = if (inputRoutes.isNotEmpty()) inputRoutes else deviceIds.mapIndexed { index, id ->
            AudioInputRoute(sourceId = "audio_device_$id", deviceId = id)
        }
        require(routes.isNotEmpty() || playbackCaptureEnabled || NativeAudioGraph.hasConfiguredSources()) { "No audio input device, media source, or Android playback source is available" }

        val c = MediaCodec.createEncoderByType("audio/mp4a-latm")
        c.configure(
            MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, 2)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        c.start()
        codec = c
        running = true

        NativeAudioGraph.configure(routes, playbackCaptureEnabled, monitorDeviceId, monitorEnabled)
        NativeAudioGraph.registerSink(sink)

        encodeThread = Thread({
            // stop() interrupts the wait for audio; that is a normal end, and it used to crash the app at Stop Streaming.
            try { encodeLoop(c) }
            catch (_: InterruptedException) {}
            catch (t: Throwable) { if (running) StreamLog.add("Audio encoder stopped: ${t.javaClass.simpleName}: ${t.message}") }
        }, "Stream4k-AudioEncoder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun setInputConfig(route: AudioInputRoute): Boolean = NativeAudioGraph.setVolume(route.sourceId, route)
    fun addInput(route: AudioInputRoute): Boolean {
        val current = NativeAudioGraph.currentHandle()
        if (current == 0L) return false
        return NativeAudioMixer.addInput(current, route.sourceId, route.deviceId, route.volume, route.balance, route.muted, route.monitoring.ordinal, route.syncOffsetMs)
    }
    fun removeInput(sourceId: String): Boolean {
        val current = NativeAudioGraph.currentHandle()
        return current != 0L && NativeAudioMixer.removeInput(current, sourceId)
    }
    fun setMonitorVolume(volume: Float) { val current = NativeAudioGraph.currentHandle(); if (current != 0L) NativeAudioMixer.setMonitorVolume(current, volume) }
    fun setMonitorMuted(muted: Boolean) { val current = NativeAudioGraph.currentHandle(); if (current != 0L) NativeAudioMixer.setMonitorMuted(current, muted) }
    fun peak(sourceId: String): Float = NativeAudioGraph.peak(sourceId)

    /**
     * Feeds the AAC encoder at real-time pace and stamps audio from the monotonic clock (the video's clock) by counting
     * samples. The mixer passes bursts on as fast as they arrive; stamped with the source's own times they reached
     * YouTube as "multiple seconds each second", which it rejects. When the sources fall behind, silence keeps the
     * audio timeline continuous (as OBS does) instead of leaving gaps that make players stall.
     */
    private fun encodeLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var formatSent = false
        var anchorUs = -1L
        var framesFed = 0L
        var pending: ShortArray? = null
        val silence = ShortArray(480 * channels)
        while (running && !Thread.currentThread().isInterrupted) {
            val nowUs = System.nanoTime() / 1_000L
            if (anchorUs < 0) anchorUs = nowUs
            var nextPtsUs = anchorUs + framesFed * 1_000_000L / sampleRate
            // A long stall (the thread was starved): continue from now rather than racing to catch up.
            if (nowUs - nextPtsUs > 500_000L) { anchorUs = nowUs - framesFed * 1_000_000L / sampleRate; nextPtsUs = nowUs }
            if (pending == null && nextPtsUs <= nowUs + 20_000L) {
                pending = pcmQueue.poll()?.samples ?: if (nowUs - nextPtsUs > 150_000L) silence.also { silenceBlocks.incrementAndGet() } else null
            }
            val block = pending
            if (block != null) {
                val index = c.dequeueInputBuffer(5_000)
                if (index >= 0) {
                    val input = c.getInputBuffer(index)
                    if (input != null) {
                        input.clear()
                        val bytes = block.size * 2
                        if (input.remaining() >= bytes) {
                            for (sample in block) input.putShort(sample)
                            c.queueInputBuffer(index, 0, bytes, nextPtsUs, 0)
                        } else c.queueInputBuffer(index, 0, 0, nextPtsUs, 0)
                    }
                    framesFed += block.size / channels
                    pending = null
                }
            } else {
                // Ahead of real time, or waiting for the next block.
                Thread.sleep(if (nextPtsUs > nowUs + 20_000L) ((nextPtsUs - nowUs - 20_000L) / 1_000L).coerceIn(1L, 10L) else 2L)
            }
            var outIndex = c.dequeueOutputBuffer(info, 0)
            while (outIndex >= 0) {
                val out = c.getOutputBuffer(outIndex)
                if (out != null && info.size > 0) {
                    val dup = out.duplicate()
                    dup.position(info.offset)
                    dup.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size)
                    dup.get(bytes)
                    onSample(
                        HardwareVideoEncoder.Sample(
                            bytes,
                            info.presentationTimeUs,
                            false,
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        )
                    )
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(info, 0)
            }
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !formatSent) {
                formatSent = true
                onFormat(c.outputFormat)
            }
        }
    }

    fun stop() {
        running = false
        encodeThread?.interrupt()
        runCatching { encodeThread?.join(1000) }
        encodeThread = null
        NativeAudioGraph.unregisterSink(sink)
        pcmQueue.clear()
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
