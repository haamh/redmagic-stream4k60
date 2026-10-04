package com.stream4k60.app.engine

import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject

/**
 * Which connected USB device each capture source uses, remembered the way OBS remembers a capture device: by identity
 * (vendor, product, serial number), not by Android's device number, which changes on every replug and app start. A
 * source finds its device again by itself, with all its saved settings (format, colour, controls, audio).
 */
object UsbDeviceBinding {
    data class Binding(val sourceId: String, val device: UsbDeviceInfo)

    private fun settings(configJson: String): JSONObject {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        return root.optJSONObject("settings") ?: root
    }

    /** The saved identity of a source's device ("" when it was saved before devices were remembered). */
    fun keyOf(configJson: String): String = settings(configJson).optString("usbDeviceKey", "")
    fun nameOf(configJson: String): String = settings(configJson).optString("usbDeviceName", "")

    /**
     * Matches every USB capture source to a connected device, each device used once:
     * 1. its remembered identity (identical devices without serial numbers: the same Android number first, else in order);
     * 2. sources saved before identities were kept: the device with the same Android number, if it is still connected;
     * 3. else the one connected device that offers the source's saved format (e.g. NV12 3840x2160@30 is the capture card).
     */
    fun resolve(sources: List<SourceItem>, devices: List<UsbDeviceInfo>): List<Binding> {
        val usb = sources.filter { it.type.equals("USB_CAPTURE", true) && SourceReferences.targetOf(it.configJson) == null }
        val taken = mutableSetOf<Int>()
        val out = mutableListOf<Binding>()
        fun take(src: SourceItem, d: UsbDeviceInfo) { taken += d.deviceId; out += Binding(src.id, d) }
        val pending = mutableListOf<SourceItem>()
        for (src in usb) {
            val s = settings(src.configJson)
            val key = s.optString("usbDeviceKey", "")
            if (key.isBlank()) { pending += src; continue }
            val matches = devices.filter { UsbAudioSources.key(it) == key && it.deviceId !in taken }
            // Two devices with one identity: the older is a ghost a hub never reported unplugged; the newest is the real one.
            val d = if (matches.size > 1) matches.maxBy { it.deviceId } else matches.firstOrNull()
            if (d != null) take(src, d)
        }
        for (src in pending) {
            val s = settings(src.configJson)
            val byNumber = devices.firstOrNull { it.deviceId == s.optInt("deviceId", -1) && it.deviceId !in taken }
            if (byNumber != null) { take(src, byNumber); continue }
            val format = "${s.optInt("width", 0)}x${s.optInt("height", 0)}@${s.optInt("fps", 0)}:${s.optString("format", "")}"
            val candidates = devices.filter { it.deviceId !in taken && format in it.supportedFormats }
            if (candidates.size == 1) take(src, candidates.single())
        }
        return out
    }

    /** [configJson] pointing at [device] (Android's current number plus its lasting identity and name). */
    fun bind(configJson: String, device: UsbDeviceInfo): String {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        val s = root.optJSONObject("settings") ?: root
        s.put("deviceId", device.deviceId)
        s.put("usbDeviceKey", UsbAudioSources.key(device))
        s.put("usbDeviceName", device.displayName)
        return root.toString()
    }

    /** Whether [configJson] already points at [device] with its identity saved. */
    fun isBound(configJson: String, device: UsbDeviceInfo): Boolean {
        val s = settings(configJson)
        return s.optInt("deviceId", -1) == device.deviceId && s.optString("usbDeviceKey", "") == UsbAudioSources.key(device)
    }
}
