package com.stream4k60.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UacDescriptorsTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }
    private fun u16(v: Int) = bytes(v and 0xff, v shr 8 and 0xff)
    private fun u24(v: Int) = bytes(v and 0xff, v shr 8 and 0xff, v shr 16 and 0xff)
    private fun u32(v: Int) = bytes(v and 0xff, v shr 8 and 0xff, v shr 16 and 0xff, v ushr 24 and 0xff)

    private fun device(bcdUsb: Int) = bytes(18, 0x01) + u16(bcdUsb) + bytes(0, 0, 0, 64) + u16(0x0951) + u16(0x16b0) + u16(0x0100) + bytes(1, 2, 3, 1)
    private fun config(interfaces: Int) = bytes(9, 0x02) + u16(0) + bytes(interfaces, 1, 0, 0x80, 50)
    private fun iad(first: Int, count: Int, cls: Int, sub: Int, protocol: Int) = bytes(8, 0x0B, first, count, cls, sub, protocol, 0)
    private fun intf(number: Int, alt: Int, endpoints: Int, cls: Int, sub: Int, protocol: Int = 0) = bytes(9, 0x04, number, alt, endpoints, cls, sub, protocol, 0)
    // Audio-class endpoints in UAC1 carry bRefresh/bSynchAddress (9 bytes); UAC2 uses the standard 7.
    private fun isoEp(address: Int, maxPacket: Int, interval: Int, sync: Int = 1, usage: Int = 0, uac1: Boolean = true) =
        bytes(if (uac1) 9 else 7, 0x05, address, 0x01 or (sync shl 2) or (usage shl 4)) + u16(maxPacket) + bytes(interval) + (if (uac1) bytes(0, 0) else emptyList())

    // UAC1 AudioControl: header listing its streaming interfaces, microphone input terminal, feature unit, USB streaming output terminal.
    private fun uac1Control(streaming: List<Int>, channels: Int) =
        bytes(8 + streaming.size, 0x24, 0x01) + u16(0x0100) + u16(0) + bytes(streaming.size) + streaming.flatMap { bytes(it) } +
            bytes(12, 0x24, 0x02, 1) + u16(0x0201) + bytes(0, channels) + u16(if (channels == 2) 3 else 0) + bytes(0, 0) +
            bytes(7 + channels + 1, 0x24, 0x06, 2, 1, 1) + List(channels + 1) { 0x03.toByte() } + bytes(0) +
            bytes(9, 0x24, 0x03, 3) + u16(0x0101) + bytes(0, 2, 0)
    private fun uac1General(link: Int, formatTag: Int = 1) = bytes(7, 0x24, 0x01, link, 1) + u16(formatTag)
    private fun uac1Format(channels: Int, subframe: Int, bits: Int, vararg rates: Int) =
        bytes(8 + rates.size * 3, 0x24, 0x02, 1, channels, subframe, bits, rates.size) + rates.flatMap { u24(it) }
    private fun uac1Continuous(channels: Int, subframe: Int, bits: Int, min: Int, max: Int) = bytes(14, 0x24, 0x02, 1, channels, subframe, bits, 0) + u24(min) + u24(max)
    private fun uac1EpGeneral(freqControl: Boolean) = bytes(7, 0x25, 0x01, if (freqControl) 1 else 0, 0) + u16(0)

    /** HyperX SoloCast-like full-speed microphone: stereo and mono 16-bit at 44.1/48 kHz, plus a HID interface. */
    private fun soloCast() = (device(0x0200) + config(3) +
        intf(0, 0, 0, 1, 1) + uac1Control(listOf(1), 2) +
        intf(1, 0, 0, 1, 2) +
        intf(1, 1, 1, 1, 2) + uac1General(3) + uac1Format(2, 2, 16, 44100, 48000) + isoEp(0x82, 196, 1) + uac1EpGeneral(true) +
        intf(1, 2, 1, 1, 2) + uac1General(3) + uac1Format(1, 2, 16, 44100, 48000) + isoEp(0x82, 98, 1) + uac1EpGeneral(true) +
        intf(2, 0, 1, 3, 0) + bytes(9, 0x21) + u16(0x0111) + bytes(0, 1, 0x22) + u16(40) + bytes(7, 0x05, 0x83, 0x03) + u16(8) + bytes(10)).toByteArray()

    @Test
    fun parsesAUac1FullSpeedMicrophone() {
        val streams = UacDescriptors.parse(soloCast())
        assertEquals(2, streams.size)
        val stereo = streams[0]
        assertEquals(listOf(1, 1, 0x82, 196, 1, 1), listOf(stereo.interfaceNumber, stereo.alternateSetting, stereo.endpointAddress, stereo.maxPacketBytes, stereo.interval, stereo.syncType))
        assertEquals(listOf(2, 2, 16, 1, 0, 3), listOf(stereo.channels, stereo.subslotBytes, stereo.bitResolution, stereo.uacVersion, stereo.controlInterface, stereo.terminalLink))
        assertEquals(listOf(44100, 48000), stereo.rates)
        assertTrue(stereo.rateSettable)
        assertEquals(-1, stereo.clockSourceId)
        assertEquals(listOf(2, 1, 98), listOf(streams[1].alternateSetting, streams[1].channels, streams[1].maxPacketBytes))
        assertTrue(UacDescriptors.hasCapture(soloCast()))
    }

    @Test
    fun choosesStereo48kWithTheSmallestFittingPacket() {
        val streams = UacDescriptors.parse(soloCast())
        assertEquals(UacChoice(streams[0], 48000), UacDescriptors.choose(streams))
        assertEquals(UacChoice(streams[0], 44100), UacDescriptors.choose(streams, 44100))
        // Not offered: the nearest rate.
        assertEquals(48000, UacDescriptors.choose(streams, 96000)!!.rate)
        // Stereo 16-bit at full speed: (48 + 1) * 4 = 196 bytes per 1 ms packet; 44.1 kHz needs (45 + 1) * 4.
        assertEquals(196, streams[0].bytesNeeded(48000, highSpeed = false))
        assertEquals(184, streams[0].bytesNeeded(44100, highSpeed = false))
        // Among equal formats the smallest packet that fits wins; one too small for the rate loses to a bigger one.
        val big = streams[0].copy(alternateSetting = 3, maxPacketBytes = 400)
        val tooSmall = streams[0].copy(alternateSetting = 4, maxPacketBytes = 100)
        assertEquals(1, UacDescriptors.choose(listOf(big, tooSmall, streams[0]))!!.stream.alternateSetting)
        assertEquals(3, UacDescriptors.choose(listOf(big, tooSmall))!!.stream.alternateSetting)
        // 32-bit before 16-bit (no bits dropped), but stereo before mono.
        val wide = streams[0].copy(alternateSetting = 5, subslotBytes = 4, bitResolution = 32, maxPacketBytes = 392)
        assertEquals(5, UacDescriptors.choose(listOf(wide, streams[0]))!!.stream.alternateSetting)
        assertEquals(1, UacDescriptors.choose(listOf(wide.copy(channels = 1), streams[0]))!!.stream.alternateSetting)
        assertEquals(null, UacDescriptors.choose(emptyList()))
    }

    @Test
    fun ignoresPcm8AndUnknownFormatsAndReadsContinuousRanges() {
        val d = (device(0x0110) + config(2) + intf(0, 0, 0, 1, 1) + uac1Control(listOf(1), 1) + intf(1, 0, 0, 1, 2) +
            intf(1, 1, 1, 1, 2) + uac1General(3, formatTag = 0x0002) + uac1Format(1, 1, 8, 8000) + isoEp(0x81, 9, 1) +
            intf(1, 2, 1, 1, 2) + uac1General(3) + uac1Continuous(1, 2, 16, 8000, 48000) + isoEp(0x81, 98, 1) + uac1EpGeneral(false)).toByteArray()
        val s = UacDescriptors.parse(d).single()
        assertEquals(2, s.alternateSetting)
        assertTrue(s.continuous)
        assertEquals(listOf(8000, 48000), listOf(s.minRate, s.maxRate))
        assertFalse(s.rateSettable) // its endpoint has no sampling-frequency control
        assertEquals(48000, UacDescriptors.choose(listOf(s))!!.rate)
        assertEquals(48000, UacDescriptors.rateFor(s, 96000))
    }

    // UAC2 (high speed): clock source 0x29 behind clock selector 0x28; a playback path (OUT endpoint + feedback IN endpoint)
    // and a capture interface with 24-bit, 16-bit and high-bandwidth 32-bit alternate settings. No rates in the descriptors.
    private fun uac2Control() =
        bytes(9, 0x24, 0x01) + u16(0x0200) + bytes(0x08) + u16(0) + bytes(0) +
            bytes(8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0, 0) +
            bytes(8, 0x24, 0x0B, 0x28, 1, 0x29, 0x03, 0) +
            bytes(17, 0x24, 0x02, 1) + u16(0x0201) + bytes(0, 0x28, 2) + u32(3) + bytes(0) + u16(0) + bytes(0) +
            bytes(12, 0x24, 0x03, 2) + u16(0x0101) + bytes(0, 1, 0x28) + u16(0) + bytes(0) +
            bytes(17, 0x24, 0x02, 3) + u16(0x0101) + bytes(0, 0x28, 2) + u32(3) + bytes(0) + u16(0) + bytes(0) +
            bytes(12, 0x24, 0x03, 4) + u16(0x0301) + bytes(0, 3, 0x28) + u16(0) + bytes(0)
    private fun uac2General(link: Int, channels: Int) = bytes(16, 0x24, 0x01, link, 0, 1) + u32(1) + bytes(channels) + u32(3) + bytes(0)
    private fun uac2Format(subslot: Int, bits: Int) = bytes(6, 0x24, 0x02, 1, subslot, bits)
    private fun uac2EpGeneral() = bytes(8, 0x25, 0x01, 0, 0, 0) + u16(0)
    private fun uac2Device() = (device(0x0200) + config(3) + iad(0, 3, 1, 0, 0x20) +
        intf(0, 0, 0, 1, 1, 0x20) + uac2Control() +
        intf(1, 0, 0, 1, 2, 0x20) +
        intf(1, 1, 2, 1, 2, 0x20) + uac2General(3, 2) + uac2Format(4, 24) + isoEp(0x01, 104, 1, uac1 = false) + uac2EpGeneral() + isoEp(0x81, 4, 4, sync = 0, usage = 1, uac1 = false) +
        intf(2, 0, 0, 1, 2, 0x20) +
        intf(2, 1, 1, 1, 2, 0x20) + uac2General(2, 2) + uac2Format(4, 24) + isoEp(0x82, 104, 1, uac1 = false) + uac2EpGeneral() +
        intf(2, 2, 1, 1, 2, 0x20) + uac2General(2, 2) + uac2Format(2, 16) + isoEp(0x82, 52, 1, uac1 = false) + uac2EpGeneral() +
        intf(2, 3, 1, 1, 2, 0x20) + uac2General(2, 2) + uac2Format(4, 32) + isoEp(0x82, 0x0800 or 0x200, 1, uac1 = false) + uac2EpGeneral()).toByteArray()

    @Test
    fun parsesUac2CaptureWithItsClockSource() {
        val streams = UacDescriptors.parse(uac2Device())
        assertEquals(listOf(1, 2, 3), streams.map { it.alternateSetting }) // the playback interface (OUT + feedback) is not capture
        assertTrue(streams.all { it.interfaceNumber == 2 && it.uacVersion == 2 && it.controlInterface == 0 && it.terminalLink == 2 })
        assertTrue(streams.all { it.clockSourceId == 0x29 && it.rateSettable && it.rates.isEmpty() && !it.continuous })
        assertEquals(listOf(2, 4, 24, 104), streams[0].let { listOf(it.channels, it.subslotBytes, it.bitResolution, it.maxPacketBytes) })
        assertEquals(1024, streams[2].maxPacketBytes) // 512 bytes, two transactions per microframe
        // Rates unknown until RANGE: assume the preferred one. 16-bit needs (6 + 1) * 4 = 28 B every 125 µs, but the 24-bit
        // mode fits its packet too and loses nothing (24 bits are exact in float; 32-bit is next), so it wins.
        assertEquals(28, streams[1].bytesNeeded(48000, highSpeed = true))
        assertEquals(UacChoice(streams[0], 48000), UacDescriptors.choose(streams, highSpeed = true))
        // A clock whose frequency is read-only.
        val fixed = uac2Device().also { it[it.indexOfClock() + 5] = 0x05 }
        assertTrue(UacDescriptors.parse(fixed).none { it.rateSettable })
    }

    private fun ByteArray.indexOfClock(): Int = (0 until size - 3).first { this[it] == 8.toByte() && this[it + 1] == 0x24.toByte() && this[it + 2] == 0x0A.toByte() }

    @Test
    fun readsRatesFromAUac2RangeReply() {
        val reply = (u16(3) + u32(44100) + u32(44100) + u32(0) + u32(48000) + u32(48000) + u32(0) + u32(8000) + u32(96000) + u32(8000)).toByteArray()
        assertEquals(listOf(8000, 16000, 24000, 32000, 44100, 48000, 64000, 88200, 96000).filter { it % 8000 == 0 || it == 44100 }, UacDescriptors.ratesFromRange(reply))
        // A truncated reply keeps the complete subranges.
        assertEquals(listOf(44100), UacDescriptors.ratesFromRange(reply, 2 + 12 + 5))
        val streams = UacDescriptors.parse(uac2Device()).map { it.copy(rates = listOf(44100)) }
        assertEquals(44100, UacDescriptors.choose(streams, highSpeed = true)!!.rate)
    }

    /** UVC webcam with a UAC1 microphone (mono 16 kHz and 48 kHz): the video interfaces use the same CS subtypes and an iso IN endpoint. */
    private fun webcam(withAudio: Boolean) = (device(0x0200) + config(if (withAudio) 4 else 2) +
        iad(0, 2, 14, 3, 0) +
        intf(0, 0, 1, 14, 1) + bytes(13, 0x24, 0x01) + u16(0x0100) + u16(0) + u32(48_000_000) + bytes(1, 1) +
        bytes(18, 0x24, 0x02, 1) + u16(0x0201) + bytes(0, 0) + u16(0) + u16(0) + u16(0) + bytes(3, 0, 0, 0) +
        bytes(9, 0x24, 0x03, 2) + u16(0x0101) + bytes(0, 1, 0) +
        bytes(7, 0x05, 0x83, 0x03) + u16(16) + bytes(6) + bytes(5, 0x25, 0x03) + u16(16) +
        intf(1, 0, 0, 14, 2) + bytes(14, 0x24, 0x01, 1) + u16(0) + bytes(0x81, 0, 2, 0, 0, 0, 1, 0) +
        bytes(11, 0x24, 0x06, 1, 1, 0, 1, 0, 0, 0, 0) +
        intf(1, 1, 1, 14, 2) + bytes(7, 0x05, 0x81, 0x05) + u16(0x1400) + bytes(1) +
        (if (withAudio) iad(2, 2, 1, 1, 0) +
            intf(2, 0, 0, 1, 1) + uac1Control(listOf(3), 1) + intf(3, 0, 0, 1, 2) +
            intf(3, 1, 1, 1, 2) + uac1General(3) + uac1Format(1, 2, 16, 16000) + isoEp(0x84, 34, 4) + uac1EpGeneral(false) +
            intf(3, 2, 1, 1, 2) + uac1General(3) + uac1Format(1, 2, 16, 48000) + isoEp(0x84, 98, 4) + uac1EpGeneral(false)
        else emptyList())).toByteArray()

    @Test
    fun ignoresVideoInterfacesOfACompositeWebcam() {
        val streams = UacDescriptors.parse(webcam(withAudio = true))
        assertEquals(listOf(3 to 1, 3 to 2), streams.map { it.interfaceNumber to it.alternateSetting })
        assertTrue(streams.all { it.uacVersion == 1 && it.controlInterface == 2 && it.channels == 1 && it.endpointAddress == 0x84 })
        assertEquals(listOf(listOf(16000), listOf(48000)), streams.map { it.rates })
        // 48 kHz wins over the smaller 16 kHz setting; at high speed bInterval 4 is one packet per millisecond.
        assertEquals(UacChoice(streams[1], 48000), UacDescriptors.choose(streams, highSpeed = true))
        assertEquals(1000, streams[1].intervalUs(highSpeed = true))
        assertFalse(UacDescriptors.hasCapture(webcam(withAudio = false)))
    }

    @Test
    fun parsesOnlyTheFirstConfiguration() {
        val d = soloCast()
        val second = (config(1) + intf(0, 0, 0, 1, 1) + uac1Control(listOf(1), 2) + intf(1, 1, 1, 1, 2) + uac1General(3) +
            uac1Format(2, 3, 24, 96000) + isoEp(0x82, 582, 1)).toByteArray()
        assertEquals(2, UacDescriptors.parse(d + second).size)
        assertTrue(UacDescriptors.hexDump(byteArrayOf(9, 2, 0, 0, 1, 1, 0, 0x80.toByte(), 50, 4, 0x24, 1, 2)).startsWith("09 02 00 00 01 01 00 80 32\n04 24 01 02"))
    }
}
