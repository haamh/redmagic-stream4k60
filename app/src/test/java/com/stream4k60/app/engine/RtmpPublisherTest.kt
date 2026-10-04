package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Publishes to a minimal local RTMP server. Like real ingest servers, it says nothing after releaseStream/FCPublish,
 * reads connect's properties from the command object and expects publish on the stream createStream returned.
 */
class RtmpPublisherTest {
    private data class Received(val name: String, val messageStream: Int, val commandObject: Any?, val args: List<Any?>)

    private class FakeIngest(private val acceptKey: String) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val received = CopyOnWriteArrayList<Received>()
        /** First two bytes of each FLV video tag: frame type/codec, then AVC packet type (0 = sequence header). */
        val video = CopyOnWriteArrayList<List<Int>>()
        val videoBodies = CopyOnWriteArrayList<ByteArray>()
        @Volatile var failure: Throwable? = null
        private val thread = Thread { runCatching { serve() }.onFailure { failure = it } }.apply { isDaemon = true; start() }

        private fun serve() = server.accept().use { socket ->
            val i = DataInputStream(socket.getInputStream().buffered())
            val o = BufferedOutputStream(socket.getOutputStream())
            check(i.read() == 3)
            val c1 = ByteArray(1536).also(i::readFully)
            o.write(3); o.write(ByteArray(1536)); o.write(c1); o.flush()
            i.readFully(ByteArray(1536))
            var chunkSize = 128
            val headers = HashMap<Int, IntArray>() // csid → length, type, message stream
            while (true) {
                val b = i.read().takeIf { it >= 0 } ?: return
                val fmt = b ushr 6
                val csid = b and 63
                val h = headers.getOrPut(csid) { IntArray(3) }
                if (fmt <= 2) i.readFully(ByteArray(3))
                if (fmt <= 1) { h[0] = u24(i); h[1] = i.read() }
                if (fmt == 0) h[2] = Integer.reverseBytes(i.readInt())
                val body = ByteArray(h[0])
                var off = 0
                while (off < body.size) {
                    val n = minOf(chunkSize, body.size - off)
                    i.readFully(body, off, n); off += n
                    if (off < body.size) i.read() // continuation chunk header (fmt 3)
                }
                when (h[1]) {
                    1 -> chunkSize = ByteBuffer.wrap(body).int
                    9 -> { video += listOf(body[0].toInt() and 0xff, body[1].toInt() and 0xff); videoBodies += body }
                    20 -> {
                        val values = Amf0(body).readAll()
                        val name = values[0] as String
                        val tx = values[1] as Double
                        received += Received(name, h[2], values.getOrNull(2), values.drop(3))
                        when (name) {
                            // Shaped like YouTube's answer: over one 128-byte chunk, with an ECMA array (`data`).
                            "connect" -> send(o, 0, listOf("_result", tx,
                                mapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31.0),
                                mapOf("level" to "status", "code" to "NetConnection.Connect.Success", "description" to "Connection succeeded.",
                                    "objectEncoding" to 0.0, "data" to Ecma(mapOf("version" to "3,5,1,525")))))
                            "createStream" -> send(o, 0, listOf("_result", tx, null, 1.0))
                            "publish" -> send(o, 1, listOf("onStatus", 0.0, null,
                                if (values[3] == acceptKey) mapOf("level" to "status", "code" to "NetStream.Publish.Start")
                                else mapOf("level" to "error", "code" to "NetStream.Publish.BadName", "description" to "Invalid stream key")))
                            // releaseStream and FCPublish get no answer, as on YouTube and most servers.
                        }
                    }
                }
            }
        }

        private fun send(o: OutputStream, messageStream: Int, values: List<Any?>) {
            val body = ByteArrayOutputStream().also { out -> values.forEach { Amf0.write(out, it) } }.toByteArray()
            o.write(3); o.write(ByteArray(3)); writeU24(o, body.size); o.write(20)
            o.write(messageStream); o.write(0); o.write(0); o.write(0)
            // 128-byte chunks (the RTMP default), each continuation with a type-3 header for chunk stream 3.
            body.toList().chunked(128).forEachIndexed { n, part -> if (n > 0) o.write(0xC3); o.write(part.toByteArray()) }
            o.flush()
        }

        override fun close() { server.close(); thread.join(2000) }
    }

    @Test
    fun publishesWhenTheServerStaysQuietBetweenCommands() = FakeIngest("good-key").use { ingest ->
        val publisher = RtmpPublisher()
        try {
            val started = publisher.start("rtmp://127.0.0.1:${ingest.port}/live2", "good-key", false, 1080, 1920, 30, timeoutMs = 5_000)
            assertTrue("${publisher.lastError}; server: ${ingest.failure}", started)
            assertTrue(publisher.isPublishing())
            val connect = ingest.received.first { it.name == "connect" }
            val props = connect.commandObject as Map<*, *>
            assertEquals("live2", props["app"])
            assertEquals("rtmp://127.0.0.1:${ingest.port}/live2", props["tcUrl"])
            val publish = ingest.received.first { it.name == "publish" }
            assertEquals(1, publish.messageStream)
            assertEquals(listOf("good-key", "live"), publish.args)
        } finally {
            publisher.stop()
        }
    }

    @Test
    fun reportsTheServersReasonForARefusedKey() = FakeIngest("good-key").use { ingest ->
        val publisher = RtmpPublisher()
        assertFalse(publisher.start("rtmp://127.0.0.1:${ingest.port}/live2", "wrong-key", false, 1920, 1080, 30, timeoutMs = 5_000))
        assertTrue("${publisher.lastError}; server: ${ingest.failure}", publisher.lastError!!.contains("NetStream.Publish.BadName"))
        publisher.stop()
    }

    @Test
    fun startsEveryConnectionWithTheDecoderSetupThenAKeyframe() = FakeIngest("good-key").use { ingest ->
        val publisher = RtmpPublisher()
        try {
            assertTrue(publisher.lastError, publisher.start("rtmp://127.0.0.1:${ingest.port}/live2", "good-key", false, 1920, 1080, 30, timeoutMs = 5_000))
            // As Android's encoder hands them over: with 00 00 00 01 start codes.
            val setup = RtmpPublisher.VideoCodecConfig(com.stream4k60.app.data.model.OutputCodec.H264,
                sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC.toByte()), pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xEE.toByte(), 0x3C, 0x80.toByte()))
            fun frame(key: Boolean, pts: Long) = HardwareVideoEncoder.Sample(byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 1, 2, 3), pts, key, false)
            // The encoder's own setup packet came before publishing (or on an earlier connection): never seen here.
            publisher.sendVideo(frame(false, 0), setup)   // not a keyframe: dropped, but the setup goes out first
            publisher.sendVideo(frame(true, 33_000), setup)
            publisher.sendVideo(frame(false, 66_000), setup)
            val deadline = System.currentTimeMillis() + 3_000
            while (ingest.video.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(listOf(listOf(0x17, 0), listOf(0x17, 1), listOf(0x27, 1)), ingest.video.toList())
            // FLV AVC sequence header: keyframe/AVC, packet type 0, CTS 0, then the AVCDecoderConfigurationRecord with
            // profile 0x64, compatibility 0x00, level 0x28, 4-byte NAL lengths, one SPS and one PPS, without start codes.
            val expected = listOf(0x17, 0, 0, 0, 0, 1, 0x64, 0x00, 0x28, 0xFF, 0xE1, 0, 5, 0x67, 0x64, 0x00, 0x28, 0xAC, 1, 0, 4, 0x68, 0xEE, 0x3C, 0x80)
            assertEquals(expected, ingest.videoBodies.first().map { it.toInt() and 0xff })
            // Frames go out with 4-byte lengths in place of start codes.
            assertEquals(listOf(0x17, 1, 0, 0, 0, 0, 0, 0, 4, 0x65, 1, 2, 3), ingest.videoBodies[1].map { it.toInt() and 0xff })
        } finally {
            publisher.stop()
        }
    }

    /** Adds H.26x emulation prevention (00 00 → 00 00 03 before a byte ≤ 3), as encoders do inside NAL units. */
    private fun escape(raw: List<Int>): List<Int> {
        val out = mutableListOf<Int>(); var zeros = 0
        for (b in raw) { if (zeros >= 2 && b <= 3) { out += 3; zeros = 0 }; out += b; zeros = if (b == 0) zeros + 1 else 0 }
        return out
    }

    @Test
    fun sendsHevcAsEnhancedRtmpWithTheSpsProfile() = FakeIngest("good-key").use { ingest ->
        val publisher = RtmpPublisher()
        try {
            assertTrue(publisher.lastError, publisher.start("rtmp://127.0.0.1:${ingest.port}/live2", "good-key", false, 2160, 3840, 60, com.stream4k60.app.data.model.OutputCodec.HEVC, timeoutMs = 5_000))
            val vps = listOf(0x40, 0x01, 0x0C, 0x01)
            // Main profile, tier 0, compatibility 0x60000000, progressive/frame-only constraint flags, level 5.1 (153).
            val ptl = listOf(0x01, 0x60, 0, 0, 0, 0x90, 0, 0, 0, 0, 0, 0x99)
            val sps = listOf(0x42, 0x01) + escape(listOf(0x01) + ptl + listOf(0xA0, 0x01, 0xE0))
            val pps = listOf(0x44, 0x01, 0xC1, 0x72)
            val csd = (listOf(0, 0, 0, 1) + vps + listOf(0, 0, 0, 1) + sps + listOf(0, 0, 0, 1) + pps).map { it.toByte() }.toByteArray()
            val setup = RtmpPublisher.VideoCodecConfig(com.stream4k60.app.data.model.OutputCodec.HEVC, hvcC = RtmpPublisher.HevcConfiguration.fromCsd(csd))
            fun frame(key: Boolean, pts: Long) = HardwareVideoEncoder.Sample(byteArrayOf(0, 0, 0, 1, if (key) 0x26 else 0x02, 0x01, 9), pts, key, false)
            publisher.sendVideo(frame(true, 0), setup)
            publisher.sendVideo(frame(false, 16_000), setup)
            val deadline = System.currentTimeMillis() + 3_000
            while (ingest.videoBodies.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val hvc1 = listOf(0x68, 0x76, 0x63, 0x31)
            fun array(type: Int, nal: List<Int>) = listOf(0x80 or type, 0, 1, nal.size ushr 8, nal.size and 0xff) + nal
            val record = listOf(1) + ptl + listOf(0xF0, 0x00, 0xFC, 0xFD, 0xF8, 0xF8, 0, 0, 0x0F, 3) + array(32, vps) + array(33, sps) + array(34, pps)
            val bodies = ingest.videoBodies.map { b -> b.map { it.toInt() and 0xff } }
            // Enhanced RTMP: IsExHeader | keyframe | SequenceStart, the FourCC, then the HEVCDecoderConfigurationRecord.
            assertEquals(listOf(0x90) + hvc1 + record, bodies[0])
            // Coded frames: IsExHeader | key (0x91) or inter (0xA1) | CodedFrames, FourCC, composition time, 4-byte lengths.
            assertEquals(listOf(0x91) + hvc1 + listOf(0, 0, 0) + listOf(0, 0, 0, 3, 0x26, 0x01, 9), bodies[1])
            assertEquals(listOf(0xA1) + hvc1 + listOf(0, 0, 0) + listOf(0, 0, 0, 3, 0x02, 0x01, 9), bodies[2])
        } finally {
            publisher.stop()
        }
    }

    private companion object {
        fun u24(i: DataInputStream) = (i.read() shl 16) or (i.read() shl 8) or i.read()
        fun writeU24(o: OutputStream, v: Int) { o.write(v ushr 16); o.write(v ushr 8); o.write(v) }
    }

    private class Ecma(val properties: Map<String, Any?>)

    /** Just enough AMF0 for RTMP commands. */
    private class Amf0(private val b: ByteArray) {
        private var p = 0
        fun readAll(): List<Any?> = buildList { while (p < b.size) add(read()) }
        private fun read(): Any? = when (val t = b[p++].toInt()) {
            0 -> java.lang.Double.longBitsToDouble(ByteBuffer.wrap(b, p, 8).long).also { p += 8 }
            1 -> (b[p++].toInt() != 0)
            2 -> string()
            3 -> buildMap { while (true) { val k = string(); if (k.isEmpty() && b[p].toInt() == 9) { p++; break }; put(k, read()) } }
            5 -> null
            10 -> List(ByteBuffer.wrap(b, p, 4).int.also { p += 4 }) { read() }
            else -> error("AMF0 type $t")
        }
        private fun string(): String { val n = ByteBuffer.wrap(b, p, 2).short.toInt() and 0xffff; p += 2; return String(b, p, n).also { p += n } }

        companion object {
            fun write(o: ByteArrayOutputStream, v: Any?) {
                when (v) {
                    null -> o.write(5)
                    is String -> { o.write(2); str(o, v) }
                    is Double -> { o.write(0); o.write(ByteBuffer.allocate(8).putDouble(v).array()) }
                    is Map<*, *> -> { o.write(3); v.forEach { (k, x) -> str(o, k.toString()); write(o, x) }; o.write(0); o.write(0); o.write(9) }
                    is Ecma -> { o.write(8); o.write(ByteBuffer.allocate(4).putInt(v.properties.size).array()); v.properties.forEach { (k, x) -> str(o, k); write(o, x) }; o.write(0); o.write(0); o.write(9) }
                    else -> error("unsupported $v")
                }
            }
            private fun str(o: ByteArrayOutputStream, s: String) { val d = s.toByteArray(); o.write(d.size ushr 8); o.write(d.size); o.write(d) }
        }
    }
}
