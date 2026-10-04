package com.stream4k60.app.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** HDR streams send OBS's Enhanced RTMP "colorInfo" video metadata ahead of the HEVC sequence header. */
class HdrMetadataTest {
    @Test
    fun colorInfoMatchesObsLayout() {
        val bytes = RtmpPublisher.FlvMetadata.colorInfo(RtmpPublisher.ColorInfo(10, 9, 16, 9, 1000))
        val o = ByteArrayOutputStream()
        o.write(0x84); o.write("hvc1".toByteArray()) // IsExHeader | PacketType 4 Metadata, FourCC
        o.write(2); key(o, "colorInfo") // AMF0 string
        o.write(3) // AMF0 object
        key(o, "colorConfig"); o.write(3)
        number(o, "bitDepth", 10.0); number(o, "colorPrimaries", 9.0)
        number(o, "transferCharacteristics", 16.0); number(o, "matrixCoefficients", 9.0)
        end(o)
        key(o, "hdrMdcv"); o.write(3)
        number(o, "maxLuminance", 1000.0); number(o, "minLuminance", 0.0)
        end(o)
        end(o)
        assertArrayEquals(o.toByteArray(), bytes)
    }

    @Test
    fun hdrStaticInfoLeavesOutMdcvWithoutPeak() {
        val bytes = RtmpPublisher.FlvMetadata.colorInfo(RtmpPublisher.ColorInfo(10, 9, 18, 9, 0))
        val o = ByteArrayOutputStream()
        o.write(0x84); o.write("hvc1".toByteArray())
        o.write(2); key(o, "colorInfo"); o.write(3)
        key(o, "colorConfig"); o.write(3)
        number(o, "bitDepth", 10.0); number(o, "colorPrimaries", 9.0)
        number(o, "transferCharacteristics", 18.0); number(o, "matrixCoefficients", 9.0)
        end(o)
        end(o)
        assertArrayEquals(o.toByteArray(), bytes)
    }

    private fun key(o: ByteArrayOutputStream, s: String) { val d = s.toByteArray(); o.write(d.size ushr 8); o.write(d.size); o.write(d) }
    private fun number(o: ByteArrayOutputStream, k: String, v: Double) {
        key(o, k); o.write(0)
        val bits = java.lang.Double.doubleToRawLongBits(v)
        for (shift in 56 downTo 0 step 8) o.write((bits ushr shift).toInt())
    }
    private fun end(o: ByteArrayOutputStream) { o.write(0); o.write(0); o.write(9) }
}
