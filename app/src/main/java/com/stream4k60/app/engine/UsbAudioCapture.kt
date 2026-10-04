package com.stream4k60.app.engine

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * One USB microphone read directly over usbfs (UacIsoStream) into the mixer's external input [mixerInputId], started the
 * way Linux snd-usb-audio starts a stream. Only the AudioStreaming interface is claimed: the kernel's audio driver keeps
 * the AudioControl interface, and a camera on the same device keeps its own interfaces.
 *
 * [connection] should be a connection of its own: usbfs reaps completed transfers per fd, so a UVC stream reaping on
 * the same fd would take this stream's transfers and the other way round.
 */
class UsbAudioCapture(private val device: UsbDevice, private val connection: UsbDeviceConnection, val mixerInputId: String, private val preferredRate: Int = 48000) {
    @Volatile private var handle = 0L
    private val startedAtMs = System.currentTimeMillis()
    private var claimed: UsbInterface? = null
    private var stream: UacStream? = null
    private var rate = 0
    private var statsThread: Thread? = null
    private val name get() = device.productName ?: device.deviceName

    /** Returns a one-line description ("48000 Hz · 2 ch · 16-bit · UAC1"); throws IllegalStateException with a user-readable reason. */
    fun start(): String {
        check(handle == 0L && claimed == null) { "USB microphone capture already running" }
        val raw = connection.rawDescriptors ?: error("Android returned no USB descriptors for $name")
        dumpOnce(raw)
        val parsed = UacDescriptors.parse(raw)
        check(parsed.isNotEmpty()) { "$name has no USB audio input the app can read (PCM over isochronous USB)" }
        StreamLog.add("USB mic $name: ${UacDescriptors.describe(parsed)}")
        // USBDEVFS_GET_SPEED: 1-2 low/full (1 ms frames), 3 high, 5+ super (125 µs microframes). Unknown: full-speed audio
        // endpoints always use bInterval 1, and UAC2 devices are nearly always high speed.
        val speed = runCatching { NativeUsbBulk.linkSpeed(connection.fileDescriptor) }.getOrDefault(-1)
        val highSpeed = if (speed > 0) speed >= 3 else parsed.any { it.interval > 1 || it.uacVersion == 2 }
        // UAC2 descriptors carry no rates: ask each clock for its RANGE.
        val ranges = mutableMapOf<Int, List<Int>>()
        val streams = parsed.map { s ->
            if (s.uacVersion != 2 || s.rates.isNotEmpty() || s.clockSourceId < 0) s
            else s.copy(rates = ranges.getOrPut(s.clockSourceId) { clockRates(s).also { StreamLog.add("USB mic $name: clock ${s.clockSourceId} offers ${if (it.isEmpty()) "no readable rates" else it.joinToString("/") + " Hz"}") } })
        }
        val choice = UacDescriptors.choose(streams, preferredRate, highSpeed) ?: error("$name has no usable audio format")
        val s = choice.stream
        val intf = findInterface(s.interfaceNumber, s.alternateSetting) ?: error("Android does not list interface ${s.interfaceNumber} alternate setting ${s.alternateSetting} of $name")
        // force = true detaches the kernel audio driver from this streaming interface only.
        check(connection.claimInterface(intf, true)) { "Could not claim the microphone interface ${s.interfaceNumber} of $name; another app may be using it" }
        claimed = intf
        try {
            findInterface(s.interfaceNumber, 0)?.let { connection.setInterface(it) }
            val confirmed = if (s.uacVersion == 2) {
                // UAC2: the clock first, then the alternate setting.
                setClockRate(s, choice.rate).also { check(connection.setInterface(intf)) { "Could not select alternate setting ${s.alternateSetting} of $name" } }
            } else {
                // UAC1: the sampling frequency is an endpoint control, and the endpoint exists once its alternate setting is selected.
                check(connection.setInterface(intf)) { "Could not select alternate setting ${s.alternateSetting} of $name" }
                setEndpointRate(s, choice.rate)
            }
            val intervalUs = s.intervalUs(highSpeed)
            val packetsPerUrb = (8000 / intervalUs).coerceIn(1, 128) // ~8 ms per URB, 8 URBs queued
            val fd = connection.fileDescriptor
            check(fd >= 0) { "USB native file descriptor unavailable" }
            handle = NativeUsbAudio.start(fd, s.endpointAddress, s.maxPacketBytes, packetsPerUrb, 8, s.channels, s.subslotBytes, s.bitResolution, confirmed, mixerInputId)
            if (handle == 0L) error("The microphone's isochronous USB stream could not start: ${NativeUsbAudio.lastStartError()} (alternate setting ${s.alternateSetting})")
            stream = s; rate = confirmed
            // Unknown rate: the stream measures it within about 0.6 s; wait for it so the description is right.
            var waitedMs = 0
            while (confirmed <= 0 && waitedMs < 1500 && NativeUsbAudio.sampleRate(handle) <= 0) { Thread.sleep(50); waitedMs += 50 }
            StreamLog.add("USB mic $name → ${mixerInputId.take(8)}: ${info()} (${s.description}; ${if (highSpeed) "high" else "full"} speed, $packetsPerUrb packets of $intervalUs µs per URB)")
        } catch (t: Throwable) { stop(); throw t }
        startStatsLog()
        return info()
    }

    /** "48000 Hz · 2 ch · 16-bit · UAC1", with the rate the stream actually runs at. */
    fun info(): String {
        val s = stream ?: return ""
        val r = handle.takeIf { it != 0L }?.let { NativeUsbAudio.sampleRate(it) }?.takeIf { it > 0 } ?: rate
        return "${if (r > 0) "$r Hz" else "rate unknown"} · ${s.channels} ch · ${s.bitResolution}-bit · UAC${s.uacVersion}"
    }

    fun stats(): String = handle.takeIf { it != 0L }?.let(NativeUsbAudio::stats).orEmpty()

    fun stop(deviceGone: Boolean = false) {
        statsThread?.interrupt(); statsThread = null
        val h = handle; handle = 0L
        if (h != 0L) { runCatching { StreamLog.add("USB mic $name stopped: ${NativeUsbAudio.stats(h)}") }; runCatching { NativeUsbAudio.stop(h) } }
        val intf = claimed ?: return
        claimed = null
        if (deviceGone) {
            // The physical device is already gone. Do not issue SET_INTERFACE, RELEASEINTERFACE, or USBDEVFS_CONNECT.
            StreamLog.add("USB mic $name: device detached; skipped interface reset/rebind")
            return
        }
        val resetOk = findInterface(intf.id, 0)?.let { runCatching { connection.setInterface(it) }.getOrDefault(false) } ?: true
        if (!resetOk) {
            // A failed alt-0 reset can mean the device disappeared. Do not continue with another USB control path on it.
            StreamLog.add("USB mic $name: interface reset failed; skipped release/rebind because the USB connection may be gone")
            return
        }
        val releaseOk = runCatching { connection.releaseInterface(intf) }.getOrDefault(false)
        if (!releaseOk) {
            StreamLog.add("USB mic $name: interface release failed; skipped kernel rebind because the USB connection may be gone")
            return
        }
        if (runCatching { NativeUsbAudio.reattachKernelDriver(connection.fileDescriptor, intf.id) }.getOrDefault(false) != true)
            StreamLog.add("USB mic $name: interface ${intf.id} could not be handed back to the kernel audio driver")
    }

    /** UAC1: SET_CUR sampling frequency on the endpoint (3 bytes), read back with GET_CUR. The rate to stream at, 0 if unknown. */
    private fun setEndpointRate(s: UacStream, rate: Int): Int {
        if (!s.continuous && s.rates.size <= 1) return s.rates.firstOrNull() ?: rate
        val ep = s.endpointAddress
        // Like Linux, only when the endpoint declares the control; other devices run at their own rate, read or measured.
        val set = if (s.rateSettable) connection.controlTransfer(0x22, 0x01, 0x0100, ep, le(rate, 3), 3, 1000) else -1
        val cur = ByteArray(3)
        val read = if (connection.controlTransfer(0xA2, 0x81, 0x0100, ep, cur, 3, 1000) >= 3) le(cur) else 0
        val result = when { read in 8000..768000 -> read; set >= 0 -> rate; else -> 0 }
        StreamLog.add("USB mic $name: ${if (!s.rateSettable) "the endpoint has no rate control" else if (set >= 0) "rate set to $rate Hz" else "setting $rate Hz failed"}, the device reports ${if (read > 0) "$read Hz" else "no rate"}${if (result <= 0) "; measuring it from the stream" else ""}")
        return result
    }

    /** UAC2: SET_CUR on the clock source (4 bytes), read back with GET_CUR. The confirmed rate, 0 if unknown. */
    private fun setClockRate(s: UacStream, rate: Int): Int {
        if (s.clockSourceId < 0) { StreamLog.add("USB mic $name: no clock source found; measuring the rate from the stream"); return 0 }
        val index = clockIndex(s)
        val set = if (s.rateSettable) connection.controlTransfer(0x21, 0x01, 0x0100, index, le(rate, 4), 4, 1000) else -1
        val cur = ByteArray(4)
        val got = connection.controlTransfer(0xA1, 0x01, 0x0100, index, cur, 4, 1000)
        val read = if (got >= 4) le(cur) else 0
        val result = when { read in 8000..768000 -> read; set >= 0 -> rate; else -> 0 }
        // Clock requests go to the AudioControl interface; usbfs refuses them while the kernel audio driver holds it.
        val why = if (got < 0 && (set < 0 || !s.rateSettable)) " (the clock did not answer: Android's audio driver probably holds AudioControl interface ${s.controlInterface})" else ""
        StreamLog.add("USB mic $name: clock ${s.clockSourceId} ${if (!s.rateSettable) "has a fixed rate" else if (set >= 0) "set to $rate Hz" else "could not be set to $rate Hz"}, reports ${if (read > 0) "$read Hz" else "no rate"}$why${if (result <= 0) "; measuring the rate from the stream" else ""}")
        return result
    }

    private fun clockIndex(s: UacStream) = (s.clockSourceId shl 8) or s.controlInterface

    /** UAC2 RANGE of the stream's clock: wNumSubRanges first, then the whole reply. */
    private fun clockRates(s: UacStream): List<Int> {
        val head = ByteArray(2)
        if (connection.controlTransfer(0xA1, 0x02, 0x0100, clockIndex(s), head, 2, 1000) < 2) return emptyList()
        val count = le(head).coerceIn(0, 32)
        if (count == 0) return emptyList()
        val reply = ByteArray(2 + 12 * count)
        val got = connection.controlTransfer(0xA1, 0x02, 0x0100, clockIndex(s), reply, reply.size, 1000)
        return if (got > 2) UacDescriptors.ratesFromRange(reply, got) else emptyList()
    }

    private fun startStatsLog() {
        val h = handle
        statsThread = Thread({
            try {
                Thread.sleep(3000)
                var lastStats = 0L; var lastGrab = 0L
                while (handle == h) {
                    val now = System.currentTimeMillis()
                    if (now - lastStats >= 60_000) { lastStats = now; NativeUsbAudio.stats(h).takeIf { it.isNotEmpty() }?.let { StreamLog.add("USB mic $name → ${mixerInputId.take(8)}: $it") } }
                    // The native stream ended on its own (device gone): report once and stop, instead of a dead session forever.
                    if (NativeUsbAudio.stats(h).contains(", stopped")) { StreamLog.add("USB mic $name: stream ended (${NativeUsbAudio.stats(h).substringAfter(", stopped").take(80)}); session stopped"); break }
                    // adb: touch files/grab-audio → the next 2 s of raw USB audio is saved as files/uac-<device>.raw.
                    StreamLog.directory()?.let { dir ->
                        val trigger = java.io.File(dir, "grab-audio")
                        val stamp = trigger.takeIf { it.exists() }?.lastModified() ?: 0L
                        if (stamp > lastGrab && stamp > startedAtMs) {
                            lastGrab = stamp
                            val file = java.io.File(dir, "uac-${(device.productName ?: "device").replace(Regex("[^A-Za-z0-9]+"), "_")}.raw")
                            NativeUsbAudio.dumpRaw(h, file.absolutePath, 48_000 * 4 * 2)
                            StreamLog.add("USB mic $name: saving 2 s of raw audio to ${file.name}")
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {}
        }, "Stream4k-UAC-${device.deviceId}").apply { isDaemon = true; start() }
    }

    private fun findInterface(number: Int, alt: Int): UsbInterface? =
        (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.id == number && it.alternateSetting == alt && it.interfaceClass == 1 }

    private fun dumpOnce(raw: ByteArray) {
        val key = "%04x:%04x:%s".format(device.vendorId, device.productId, runCatching { device.serialNumber }.getOrNull() ?: device.deviceName)
        if (dumped.add(key)) StreamLog.add("USB descriptors of $name ($key), ${raw.size} bytes:\n${UacDescriptors.hexDump(raw)}")
    }

    private fun le(value: Int, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }
    private fun le(b: ByteArray): Int = b.foldIndexed(0) { i, acc, v -> acc or ((v.toInt() and 0xff) shl (8 * i)) }

    private companion object { val dumped: MutableSet<String> = ConcurrentHashMap.newKeySet() }
}
