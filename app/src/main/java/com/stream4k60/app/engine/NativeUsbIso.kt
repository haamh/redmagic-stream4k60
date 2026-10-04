package com.stream4k60.app.engine

import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer

/** Native usbfs isochronous queue. It deliberately operates on the fd owned by UsbDeviceConnection. */
object NativeUsbIso {
    init { System.loadLibrary("stream4k60_engine") }

    external fun start(
        fd: Int,
        endpointAddress: Int,
        packetBytes: Int,
        packetsPerUrb: Int,
        urbCount: Int,
        callback: Any
    ): Long

    external fun stop(handle: Long)
    /** Why the last isochronous or bulk start failed (kernel error and transfer sizes); empty if none yet. */
    external fun lastStartError(): String
    external fun frames(handle: Long): Long
    external fun packetErrors(handle: Long): Long
    external fun errorBreakdown(handle: Long): String
    external fun isDead(handle: Long): Boolean
    external fun droppedFrames(handle: Long): Long

    fun packetCapacity(endpoint: UsbEndpoint): Int {
        val raw = endpoint.maxPacketSize
        val base = raw and 0x07ff
        val transactions = 1 + ((raw ushr 11) and 0x3)
        return (base * transactions).coerceAtLeast(base)
    }
}
