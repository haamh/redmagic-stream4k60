package com.stream4k60.app.engine

import com.stream4k60.app.ui.main.SourceItem
import org.json.JSONObject

/**
 * Sources whose sound is read straight from a USB device (UsbAudioCapture), not through Android's audio system, which
 * on the Astra records from one USB microphone at a time: a capture card or webcam with "Audio: this device (direct
 * USB)", and Audio Input sources (Mic/Aux too) set to a USB microphone. Each gets its own mixer input.
 */
object UsbAudioSources {
    private fun settings(configJson: String): JSONObject {
        val root = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        return root.optJSONObject("settings") ?: root
    }

    /** The mixer input of a source's direct USB audio; null when it doesn't use direct USB audio. */
    fun mixerInputId(src: SourceItem): String? {
        if (SourceReferences.targetOf(src.configJson) != null) return null
        val s = settings(src.configJson)
        return when (src.type.uppercase()) {
            "USB_CAPTURE" -> if (s.optBoolean("usbAudio", false)) "usb_audio_${src.id}" else null
            "AUDIO_INPUT" -> if (s.optString("usbAudioKey").isNotBlank()) src.id else null
            else -> null
        }
    }

    /** The source a mixer input belongs to (for its error message). */
    fun sourceIdOf(mixerInputId: String) = mixerInputId.removePrefix("usb_audio_")

    /** Identifies a USB device across replugging (Android's device id changes each time). */
    fun key(d: UsbDeviceInfo) = "${d.vendorId}:${d.productId}:${d.serialNumber ?: d.productName ?: d.deviceName}"

    /** The device a direct-audio source reads: the capture source's own device, or the microphone picked for it. */
    fun deviceFor(src: SourceItem, devices: List<UsbDeviceInfo>): UsbDeviceInfo? {
        val s = settings(src.configJson)
        return when (src.type.uppercase()) {
            "USB_CAPTURE" -> s.optString("usbDeviceKey").takeIf { it.isNotBlank() }?.let { k -> devices.filter { key(it) == k }.let { m -> m.firstOrNull { it.deviceId == s.optInt("deviceId", -1) } ?: m.firstOrNull() } }
                ?: devices.firstOrNull { it.deviceId == s.optInt("deviceId", -1) }
            "AUDIO_INPUT" -> s.optString("usbAudioKey").takeIf { it.isNotBlank() }?.let { k -> devices.firstOrNull { key(it) == k } }
            else -> null
        }
    }
}
