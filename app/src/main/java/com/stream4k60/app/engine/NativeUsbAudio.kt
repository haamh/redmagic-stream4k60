package com.stream4k60.app.engine

/**
 * Native USB Audio Class capture over usbfs (uac_iso_stream.cpp): isochronous IN transfers on an fd the app owns, PCM
 * pushed straight into the studio mixer's external input. Android's audio policy on the Astra opens only one USB input
 * at a time, so a second USB microphone has to be read this way, like UVC video.
 */
object NativeUsbAudio {
    init { System.loadLibrary("stream4k60_engine") }

    /**
     * Starts reading [endpoint] and pushing into mixer input [mixerInputId] (an external input of the mixer set with
     * [setMixer]). [sampleRate] 0 means unknown: the stream measures it from the data before pushing. Returns 0 on
     * failure ([lastStartError] says why).
     */
    external fun start(
        fd: Int,
        endpoint: Int,
        packetBytes: Int,
        packetsPerUrb: Int,
        urbCount: Int,
        channels: Int,
        subslotBytes: Int,
        bitResolution: Int,
        sampleRate: Int,
        mixerInputId: String
    ): Long

    external fun stop(handle: Long)
    /** One line for the stream log: rate (configured and measured), packets, failures, frames pushed, push failures. */
    external fun stats(handle: Long): String
    /** The rate the stream pushes at (0 while it is still measuring an unknown rate). */
    external fun sampleRate(handle: Long): Int
    external fun lastStartError(): String
    /** The NativeAudioMixer every USB microphone pushes into; 0 for none. Set again whenever NativeAudioGraph restarts it. */
    external fun setMixer(handle: Long)
    /** Saves the next [bytes] of raw USB audio payload to [path] (debugging what a device really sends). */
    external fun dumpRaw(handle: Long, path: String, bytes: Int)
    /**
     * USBDEVFS_CONNECT on [interfaceNumber] after the app released it, so a kernel driver may bind it again. True when the
     * kernel accepted the request or a driver already holds it.
     */
    external fun reattachKernelDriver(fd: Int, interfaceNumber: Int): Boolean
}
