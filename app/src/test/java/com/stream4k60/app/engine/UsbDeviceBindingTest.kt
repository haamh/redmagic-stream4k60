package com.stream4k60.app.engine

import com.stream4k60.app.ui.main.SourceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDeviceBindingTest {
    private fun device(id: Int, vid: Int, pid: Int, serial: String?, name: String, formats: List<String>) =
        UsbDeviceInfo(id, "/dev/bus/usb/001/%03d".format(id), name, vid, pid, null, name, serial, UsbDeviceType.CAPTURE_CARD, UsbSpeed.USB_3_0, supportedFormats = formats)
    private fun source(id: String, settings: String) = SourceItem(id, id, "USB_CAPTURE", true, false, "{\"settings\":$settings}", "{}")

    private val elgato = device(2031, 0x0fd9, 0x00af, "AC8KB5361O79MP", "Elgato 4K S", listOf("3840x2160@30:NV12", "2560x1440@60:NV12", "3840x2160@60:MJPEG"))
    private val insta = device(2032, 0x2e1a, 0x4c04, "IN360L2", "Insta360 Link 2", listOf("3840x2160@30:H264", "1920x1080@30:MJPEG"))
    private val brio = device(2033, 0x046d, 0x0944, "BRIO1", "MX Brio", listOf("2560x1440@30:NV12", "1920x1080@60:MJPEG"))

    @Test
    fun aRememberedDeviceIsFoundAgainUnderItsNewNumber() {
        val saved = source("cam", "{\"deviceId\":1027,\"usbDeviceKey\":\"${UsbAudioSources.key(elgato)}\",\"format\":\"NV12\",\"width\":3840,\"height\":2160,\"fps\":30}")
        val b = UsbDeviceBinding.resolve(listOf(saved), listOf(insta, elgato, brio))
        assertEquals(listOf(UsbDeviceBinding.Binding("cam", elgato)), b)
        val rebound = UsbDeviceBinding.bind(saved.configJson, elgato)
        assertTrue(UsbDeviceBinding.isBound(rebound, elgato))
    }

    @Test
    fun sourcesSavedWithoutIdentityAreMatchedByTheirFormatWhenOnlyOneDeviceHasIt() {
        val old1 = source("a", "{\"deviceId\":2007,\"format\":\"NV12\",\"width\":3840,\"height\":2160,\"fps\":30}")
        val old5 = source("b", "{\"deviceId\":1027,\"format\":\"H264\",\"width\":3840,\"height\":2160,\"fps\":30}")
        val b = UsbDeviceBinding.resolve(listOf(old1, old5), listOf(elgato, insta, brio)).associate { it.sourceId to it.device.displayName }
        assertEquals(mapOf("a" to "Elgato 4K S", "b" to "Insta360 Link 2"), b)
    }

    @Test
    fun eachDeviceIsUsedOnceAndAmbiguousFormatsWaitForTheUser() {
        val keyed = source("k", "{\"usbDeviceKey\":\"${UsbAudioSources.key(brio)}\"}")
        // 1440p30 NV12: only the Brio offers it, but the Brio is already taken by the remembered source.
        val legacy = source("l", "{\"deviceId\":9,\"format\":\"NV12\",\"width\":2560,\"height\":1440,\"fps\":30}")
        val b = UsbDeviceBinding.resolve(listOf(legacy, keyed), listOf(elgato, brio))
        assertEquals(listOf(UsbDeviceBinding.Binding("k", brio)), b)
        // Two devices with the same format: no guess.
        val twin = brio.copy(deviceId = 2040, serialNumber = "BRIO2")
        assertEquals(emptyList<UsbDeviceBinding.Binding>(), UsbDeviceBinding.resolve(listOf(legacy), listOf(brio, twin)))
    }
}
