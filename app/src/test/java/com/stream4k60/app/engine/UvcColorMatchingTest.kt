package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The device's own colour statement (UVC VS_COLORFORMAT) is read per format instead of guessed from the resolution. */
class UvcColorMatchingTest {
    private fun nv12Format(o: ByteArrayOutputStream, index: Int) {
        val guid = byteArrayOf('N'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), '2'.code.toByte(), 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
        o.write(byteArrayOf(27, 0x24, 0x04, index.toByte(), 1)); o.write(guid); o.write(byteArrayOf(12, 1, 0, 0, 0, 0))
    }
    private fun frame(o: ByteArrayOutputStream, w: Int, h: Int, interval: Int) {
        val b = ByteArray(30); b[0] = 30; b[1] = 0x24; b[2] = 0x05; b[3] = 1
        fun u16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { for (k in 0 until 4) b[at + k] = (v shr (8 * k)).toByte() }
        u16(5, w); u16(7, h); u32(17, w * h * 3 / 2); u32(21, interval); b[25] = 1; u32(26, interval)
        o.write(b)
    }

    @Test
    fun readsColorMatchingDescriptorPerFormat() {
        val o = ByteArrayOutputStream()
        nv12Format(o, 1); frame(o, 2560, 1440, 166_666); o.write(byteArrayOf(6, 0x24, 0x0D, 1, 1, 1)) // BT.709 all round
        nv12Format(o, 2); frame(o, 640, 480, 333_333) // no descriptor
        val formats = UvcCaptureSession.parseFormats(o.toByteArray())
        val hd = formats.single { it.index == 1 }
        assertEquals(2560, hd.width)
        assertEquals(1, hd.color?.shaderMatrix)
        assertEquals("Rec. 709 matrix, BT.709 primaries, BT.709 transfer", hd.color?.description)
        assertNull(formats.single { it.index == 2 }.color)
    }

    @Test
    fun smpte170mIsRec601AndUnspecifiedIsUnknown() {
        assertEquals(0, UvcCaptureSession.UvcColor(1, 1, 4).shaderMatrix)
        assertEquals(-1, UvcCaptureSession.UvcColor(0, 0, 0).shaderMatrix)
    }
}

/** Elgato capture cards put the probe's format index in bmHint's high byte; it is moved back like Linux uvcvideo does. */
class UvcShiftedProbeTest {
    @Test
    fun correctsElgatoShiftedProbeAnswer() {
        val p = byteArrayOf(0x00, 0x03, 0x01, 0x02, 0x0A)
        org.junit.Assert.assertTrue(UvcCaptureSession.fixShiftedProbe(p))
        assertEquals(listOf<Byte>(0x01, 0x00, 0x03, 0x02, 0x0A), p.toList())
    }

    @Test
    fun leavesValidAnswersAlone() {
        val p = byteArrayOf(0x01, 0x00, 0x02, 0x05)
        org.junit.Assert.assertFalse(UvcCaptureSession.fixShiftedProbe(p))
        assertEquals(listOf<Byte>(0x01, 0x00, 0x02, 0x05), p.toList())
    }
}
