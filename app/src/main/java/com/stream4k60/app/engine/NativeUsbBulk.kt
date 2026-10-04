package com.stream4k60.app.engine

import android.hardware.usb.UsbEndpoint

/** Native usbfs bulk transport. Used only for UVC devices whose descriptor exposes a bulk video endpoint. ISO is preferred whenever the device advertises it. */
object NativeUsbBulk {
    init { System.loadLibrary("stream4k60_engine") }
    /** [maxPayloadBytes]: the camera's committed dwMaxPayloadTransferSize, or 0 when unknown. */
    external fun start(fd: Int, endpointAddress: Int, packetBytes: Int, transferBytes: Int, maxPayloadBytes: Int, urbCount: Int, callback: Any): Long
    external fun stop(handle: Long)
    /** Negotiated link speed of the device behind [fd] (kernel usb_device_speed), -1 if unknown. */
    external fun linkSpeed(fd: Int): Int
    external fun frames(handle: Long): Long
    external fun errors(handle: Long): Long
    external fun isDead(handle: Long): Boolean
    external fun droppedFrames(handle: Long): Long

    /** URB size: a whole number of packets, 64 KB, so fewer reaps are needed at USB 3 rates. */
    fun transferSize(endpoint: UsbEndpoint): Int { val packet = endpoint.maxPacketSize.coerceAtLeast(64); return (64 * 1024 / packet).coerceAtLeast(16) * packet }
}
