package com.stream4k60.app.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HardwareAudioEncoderTest {
    @Test
    fun convertsTheMixersNativeOrderFloatsToPcm16() {
        // As the native mixer fills it: floats in the device's byte order, in a buffer whose Java order is the default.
        val raw = ByteBuffer.allocateDirect(5 * 4).order(ByteOrder.nativeOrder())
        floatArrayOf(0f, 0.5f, -0.5f, 1f, -2f).forEach { raw.putFloat(it) }
        val asHandedOver = raw.duplicate().order(ByteOrder.BIG_ENDIAN)
        assertArrayEquals(shortArrayOf(0, 16384, -16383, 32767, -32767), HardwareAudioEncoder.toPcm16(asHandedOver, 5))
    }
}
