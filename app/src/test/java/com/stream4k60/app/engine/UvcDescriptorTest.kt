package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class UvcDescriptorTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }
    private fun u16(v: Int) = bytes(v and 0xff, v shr 8 and 0xff)
    private fun u32(v: Int) = bytes(v and 0xff, v shr 8 and 0xff, v shr 16 and 0xff, v ushr 24 and 0xff)

    private fun interfaceDesc(subclass: Int) = bytes(9, 0x04, 0, 0, 0, 14, subclass, 0, 0)
    private fun vcHeader(bcd: Int) = bytes(13, 0x24, 0x01) + u16(bcd) + u16(0) + u32(0) + bytes(1, 1)
    private fun mjpegFormat(index: Int) = bytes(11, 0x24, 0x06, index, 1, 0, 1, 0, 0, 0, 0)
    private fun mjpegFrame(frame: Int, w: Int, h: Int, fps: Int, maxBuf: Int) =
        bytes(30, 0x24, 0x07, frame, 0) + u16(w) + u16(h) + u32(0) + u32(0) + u32(maxBuf) + u32(10_000_000 / fps) + bytes(1) + u32(10_000_000 / fps)
    // Frame-based (H.264): GUID "H264" at offset 5, frame descriptors use subtype 0x11.
    private fun h264Format(index: Int) = bytes(28, 0x24, 0x10, index, 1) + bytes('H'.code, '2'.code, '6'.code, '4'.code) + List(12) { 0.toByte() } + bytes(16, 1, 0, 0, 0, 0, 0)
    private fun h264Frame(frame: Int, w: Int, h: Int, fps: Int) =
        bytes(30, 0x24, 0x11, frame, 0) + u16(w) + u16(h) + u32(0) + u32(0) + u32(10_000_000 / fps) + bytes(1) + u32(0) + u32(10_000_000 / fps)

    @Test
    fun parsesMjpegAndFrameBasedH264() {
        val d = (interfaceDesc(2) + mjpegFormat(1) + mjpegFrame(1, 1920, 1080, 30, 4_147_200) +
            h264Format(2) + h264Frame(1, 3840, 2160, 30)).toByteArray()
        val formats = UvcCaptureSession.parseFormats(d)
        val mjpeg = formats.single { it.codec == "MJPEG" }
        assertEquals(listOf(1920, 1080, 30, 4_147_200), listOf(mjpeg.width, mjpeg.height, mjpeg.fps, mjpeg.maxFrameSize))
        val h264 = formats.single { it.codec == "H264" }
        assertEquals(listOf(3840, 2160, 30), listOf(h264.width, h264.height, h264.fps))
    }

    // UVC 1.5 H.264: VS_FORMAT_H264 (0x13) and VS_FRAME_H264 (0x14), width at offset 4, intervals from 44.
    private fun uvc15H264Format(index: Int) = bytes(52, 0x24, 0x13, index, 1) + List(47) { 0.toByte() }
    private fun uvc15H264Frame(frame: Int, w: Int, h: Int, vararg fps: Int) =
        bytes(44 + fps.size * 4, 0x24, 0x14, frame) + u16(w) + u16(h) + List(31) { 0.toByte() } + u32(10_000_000 / fps[0]) + bytes(fps.size) + fps.flatMap { u32(10_000_000 / it) }

    @Test
    fun parsesUvc15H264() {
        val d = (interfaceDesc(2) + uvc15H264Format(1) + uvc15H264Frame(1, 1920, 1080, 60, 30) + uvc15H264Frame(2, 3840, 2160, 30)).toByteArray()
        val formats = UvcCaptureSession.parseFormats(d).filter { it.codec == "H264" }
        assertEquals(setOf(Triple(1920, 1080, 60), Triple(1920, 1080, 30), Triple(3840, 2160, 30)), formats.map { Triple(it.width, it.height, it.fps) }.toSet())
    }

    @Test
    fun probeLengthFollowsUvcVersion() {
        assertEquals(26, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0100)).toByteArray()))
        assertEquals(34, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0110)).toByteArray()))
        assertEquals(48, UvcCaptureSession.probeLength((interfaceDesc(1) + vcHeader(0x0150)).toByteArray()))
        // A VideoStreaming input header (also subtype 0x01) must not be mistaken for the VC header.
        assertEquals(26, UvcCaptureSession.probeLength((interfaceDesc(2) + vcHeader(0x0150)).toByteArray()))
    }

    @Test
    fun fallsBackToAnAdvertisedFormat() {
        val formats = listOf(
            UvcCaptureSession.Format(1, 1, 1920, 1080, 30, "H264", 1),
            UvcCaptureSession.Format(2, 1, 1280, 720, 30, "YUYV", 1)
        )
        val chosen = UvcCaptureSession.chooseFormat(formats, 3840, 2160, 60, "MJPEG")
        assertEquals("H264", chosen.codec)
        assertEquals(1920, chosen.width)
    }

    private fun probeAnswer(format: Int, frame: Int, interval: Int, maxFrame: Int, maxPayload: Int) =
        (u16(1) + bytes(format, frame) + u32(interval) + List(10) { 0.toByte() } + u32(maxFrame) + u32(maxPayload) + List(8) { 0.toByte() }).toByteArray()

    @Test
    fun readsTheCamerasProbeAnswer() {
        val c = UvcCaptureSession.parseCommitted(probeAnswer(2, 3, 333_333, 900_000, 3072))
        assertEquals(listOf(2, 3, 3072), listOf(c.formatIndex, c.frameIndex, c.maxPayload))
        assertEquals(listOf(333_333L, 900_000L), listOf(c.interval, c.maxVideoFrameSize))
        // Some cameras put 0xffff in the upper half of dwMaxPayloadTransferSize.
        assertEquals(0x0c00, UvcCaptureSession.parseCommitted(probeAnswer(1, 1, 333_333, 0, 0xffff0c00.toInt())).maxPayload)
    }

    @Test
    fun followsTheModeTheCameraCommitted() {
        val asked = UvcCaptureSession.Format(1, 2, 2560, 1440, 60, "H264", 1)
        val formats = listOf(asked, asked.copy(fps = 30), UvcCaptureSession.Format(1, 1, 1920, 1080, 30, "H264", 1))
        // Same frame, but the camera can only do 30 fps.
        assertEquals(30, UvcCaptureSession.committedFormat(formats, asked, UvcCaptureSession.Committed(1, 2, 333_333, 0, 3072)).fps)
        // A different frame altogether.
        assertEquals(1920, UvcCaptureSession.committedFormat(formats, asked, UvcCaptureSession.Committed(1, 1, 333_333, 0, 3072)).width)
        // As asked.
        assertEquals(asked, UvcCaptureSession.committedFormat(formats, asked, UvcCaptureSession.Committed(1, 2, 166_667, 0, 3072)))
        // An answer naming no advertised mode keeps the request.
        assertEquals(asked, UvcCaptureSession.committedFormat(formats, asked, UvcCaptureSession.Committed(9, 9, 0, 0, 0)))
    }

    @Test
    fun picksTheSmallestAlternateSettingThatFits() {
        val capacities = listOf(1024, 3072, 2048, 512)
        assertEquals(2, UvcCaptureSession.pickIsoAlternate(capacities, 1500))
        assertEquals(3, UvcCaptureSession.pickIsoAlternate(capacities, 512))
        // Nothing is big enough: the largest.
        assertEquals(1, UvcCaptureSession.pickIsoAlternate(capacities, 9000))
    }

    private fun altInterface(number: Int, alt: Int) = bytes(9, 0x04, number, alt, 1, 14, 2, 0, 0)
    private fun isoEndpoint(address: Int) = bytes(7, 0x05, address, 0x05) + u16(1024) + bytes(1)
    private fun ssCompanion(bytesPerInterval: Int) = bytes(6, 0x30, 15, 2) + u16(bytesPerInterval)

    @Test
    fun readsSuperSpeedSizePerAlternateSetting() {
        // Every alternate setting repeats endpoint 0x81 with its own companion size.
        val d = (altInterface(1, 0) + altInterface(1, 1) + isoEndpoint(0x81) + ssCompanion(1024) +
            altInterface(1, 2) + isoEndpoint(0x81) + ssCompanion(16384) +
            altInterface(1, 3) + isoEndpoint(0x81) + ssCompanion(49152)).toByteArray()
        assertEquals(1024, UvcCaptureSession.superSpeedBytesPerInterval(d, 1, 1, 0x81))
        assertEquals(16384, UvcCaptureSession.superSpeedBytesPerInterval(d, 1, 2, 0x81))
        assertEquals(49152, UvcCaptureSession.superSpeedBytesPerInterval(d, 1, 3, 0x81))
        // USB 2 descriptors have no companion.
        assertEquals(null, UvcCaptureSession.superSpeedBytesPerInterval((altInterface(1, 1) + isoEndpoint(0x81)).toByteArray(), 1, 1, 0x81))
    }
}
