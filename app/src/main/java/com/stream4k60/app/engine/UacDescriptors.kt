package com.stream4k60.app.engine

import kotlin.math.abs

/**
 * One USB Audio Class capture format: an AudioStreaming alternate setting with an isochronous IN endpoint carrying
 * Type I PCM. The app reads these itself over usbfs (UacIsoStream) because Android's audio policy on the Astra opens
 * only one USB input device at a time.
 */
data class UacStream(
    val interfaceNumber: Int,
    val alternateSetting: Int,
    val endpointAddress: Int,
    /** wMaxPacketSize including the high-bandwidth multiplier (bits 11-12: extra transactions per microframe). */
    val maxPacketBytes: Int,
    val interval: Int,
    /** Endpoint sync type: 0 none, 1 asynchronous, 2 adaptive, 3 synchronous. */
    val syncType: Int,
    val channels: Int,
    val subslotBytes: Int,
    val bitResolution: Int,
    /** Discrete rates. Empty for a continuous range ([minRate]..[maxRate]), and for UAC2, whose clock's RANGE request lists them. */
    val rates: List<Int>,
    val minRate: Int = 0,
    val maxRate: Int = 0,
    val uacVersion: Int,
    val controlInterface: Int,
    val terminalLink: Int,
    /** UAC2: the clock source reached from the linked terminal (through selectors and multipliers); -1 for UAC1. */
    val clockSourceId: Int = -1,
    /** UAC1: the endpoint has the sampling-frequency control. UAC2: the clock's frequency is host-programmable. */
    val rateSettable: Boolean = true,
) {
    val continuous: Boolean get() = rates.isEmpty() && maxRate > 0
    fun supports(rate: Int) = rate in rates || (continuous && rate in minRate..maxRate)
    /** One service interval: 2^(bInterval-1) frames (1 ms) at full speed, microframes (125 µs) at high speed and above. */
    fun intervalUs(highSpeed: Boolean) = (if (highSpeed) 125 else 1000) shl (interval.coerceIn(1, 16) - 1)
    /** Bytes one packet needs at [rate], with the extra frame an asynchronous device may send now and then. */
    fun bytesNeeded(rate: Int, highSpeed: Boolean) =
        ((rate.toLong() * intervalUs(highSpeed) + 999_999) / 1_000_000 + 1).toInt() * channels * subslotBytes
    val rateText: String get() = when {
        rates.isNotEmpty() -> rates.joinToString("/") + " Hz"
        continuous -> "$minRate–$maxRate Hz"
        else -> "rates from clock $clockSourceId"
    }
    val description: String get() =
        "UAC$uacVersion if$interfaceNumber/alt$alternateSetting ep 0x${endpointAddress.toString(16)}: $channels ch $bitResolution-bit in $subslotBytes B, $rateText, " +
            "${SYNC_NAMES[syncType and 3]} $maxPacketBytes B bInterval $interval, AC if$controlInterface terminal $terminalLink" +
            (if (clockSourceId >= 0) " clock $clockSourceId${if (rateSettable) "" else " (fixed)"}" else if (!rateSettable) " (no rate control)" else "")

    private companion object { val SYNC_NAMES = arrayOf("no sync", "async", "adaptive", "sync") }
}

data class UacChoice(val stream: UacStream, val rate: Int)

/** Parses UAC1/UAC2 capture formats out of a device's raw descriptors (UsbDeviceConnection.rawDescriptors). */
object UacDescriptors {
    val STANDARD_RATES = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 176400, 192000, 352800, 384000)

    private class Entity(val subtype: Int, val clock: Int = -1, val inputs: List<Int> = emptyList(), val controls: Int = 0)
    private class Control(val number: Int, val version: Int, val order: Int) {
        val entities = mutableMapOf<Int, Entity>()
        val streaming = mutableSetOf<Int>() // UAC1 header baInterfaceNr
    }
    private class Alt(val number: Int, val alt: Int, val uac2: Boolean, val order: Int) {
        var terminalLink = -1; var pcm = false; var typeI = false
        var channels = 0; var subslot = 0; var bits = 0
        var rates = emptyList<Int>(); var minRate = 0; var maxRate = 0
        var endpoint = -1; var maxPacket = 0; var interval = 1; var sync = 0
        var freqControl: Boolean? = null
    }

    fun hasCapture(raw: ByteArray): Boolean = parse(raw).isNotEmpty()

    /** Every PCM capture alternate setting (UAC1 and UAC2) of the first configuration, the one Android and Linux select. */
    fun parse(raw: ByteArray): List<UacStream> {
        val controls = mutableListOf<Control>()
        val alts = mutableListOf<Alt>()
        val associations = mutableListOf<IntRange>()
        var control: Control? = null // current interface is AudioControl
        var alt: Alt? = null         // current interface is an AudioStreaming alternate setting we may use
        var dataEndpoint = false     // the last endpoint descriptor was alt's data endpoint (its CS endpoint follows)
        var configs = 0; var order = 0; var i = 0
        while (i + 1 < raw.size) {
            val len = u8(raw, i); if (len < 2 || i + len > raw.size) break
            when (u8(raw, i + 1)) {
                0x02 -> if (++configs > 1) break
                0x0B -> if (len >= 4) associations += u8(raw, i + 2) until u8(raw, i + 2) + u8(raw, i + 3)
                0x04 -> if (len >= 9) {
                    val number = u8(raw, i + 2); val altNo = u8(raw, i + 3); val cls = u8(raw, i + 5); val sub = u8(raw, i + 6); val protocol = u8(raw, i + 7)
                    order++; control = null; alt = null; dataEndpoint = false
                    // Only audio-class interfaces: video (14) reuses the same CS subtypes (header 1, terminals 2/3) and has iso IN endpoints too.
                    if (cls == 1 && sub == 1) control = controls.firstOrNull { it.number == number } ?: Control(number, if (protocol == 0x20) 2 else 1, order).also { controls += it }
                    else if (cls == 1 && sub == 2 && altNo > 0 && (protocol == 0x00 || protocol == 0x20)) alt = Alt(number, altNo, protocol == 0x20, order).also { alts += it }
                }
                0x24 -> if (len >= 3) {
                    val st = u8(raw, i + 2); val c = control; val a = alt
                    if (c != null) when (st) {
                        0x01 -> if (c.version == 1 && len >= 8) for (k in 0 until u8(raw, i + 7)) if (8 + k < len) c.streaming += u8(raw, i + 8 + k)
                        // Terminal clock links are UAC2 only: input terminal bCSourceID @7, output terminal bCSourceID @8.
                        0x02 -> if (len >= 8) c.entities[u8(raw, i + 3)] = Entity(st, clock = if (c.version == 2) u8(raw, i + 7) else -1)
                        0x03 -> if (len >= 9) c.entities[u8(raw, i + 3)] = Entity(st, clock = if (c.version == 2) u8(raw, i + 8) else -1)
                        0x0A -> if (c.version == 2 && len >= 6) c.entities[u8(raw, i + 3)] = Entity(st, controls = u8(raw, i + 5))
                        0x0B -> if (c.version == 2 && len >= 5) c.entities[u8(raw, i + 3)] = Entity(st, inputs = (0 until u8(raw, i + 4)).filter { 5 + it < len }.map { u8(raw, i + 5 + it) })
                        0x0C -> if (c.version == 2 && len >= 5) c.entities[u8(raw, i + 3)] = Entity(st, inputs = listOf(u8(raw, i + 4)))
                    } else if (a != null) when (st) {
                        // AS_GENERAL. UAC2: bTerminalLink@3, bFormatType@5, bmFormats@6 (bit 0 PCM), bNrChannels@10. UAC1: wFormatTag@5 (1 = PCM).
                        0x01 -> if (a.uac2) { if (len >= 11) { a.terminalLink = u8(raw, i + 3); a.pcm = u8(raw, i + 5) == 1 && (u8(raw, i + 6) and 1) != 0; a.channels = u8(raw, i + 10) } }
                            else if (len >= 7) { a.terminalLink = u8(raw, i + 3); a.pcm = u16(raw, i + 5) == 0x0001 }
                        // FORMAT_TYPE I. UAC2: bSubslotSize@4, bBitResolution@5. UAC1: bNrChannels@4, bSubframeSize@5, bBitResolution@6, bSamFreqType@7, rates @8 (3 bytes each; 0 = continuous min/max).
                        0x02 -> if (len >= 4 && u8(raw, i + 3) == 1) {
                            if (a.uac2) { if (len >= 6) { a.typeI = true; a.subslot = u8(raw, i + 4); a.bits = u8(raw, i + 5) } }
                            else if (len >= 8) {
                                a.typeI = true; a.channels = u8(raw, i + 4); a.subslot = u8(raw, i + 5); a.bits = u8(raw, i + 6)
                                val count = u8(raw, i + 7)
                                if (count == 0) { if (len >= 14) { a.minRate = u24(raw, i + 8); a.maxRate = u24(raw, i + 11) } }
                                else a.rates = (0 until count).filter { 8 + it * 3 + 3 <= len }.map { u24(raw, i + 8 + it * 3) }.filter { it > 0 }.distinct()
                            }
                        }
                    }
                }
                0x05 -> {
                    dataEndpoint = false
                    val a = alt
                    if (a != null && a.endpoint < 0 && len >= 7) {
                        val address = u8(raw, i + 2); val attributes = u8(raw, i + 3)
                        // IN, isochronous, and not an explicit feedback endpoint (usage type 1).
                        if (address and 0x80 != 0 && attributes and 3 == 1 && (attributes shr 4) and 3 != 1) {
                            val w = u16(raw, i + 4)
                            a.endpoint = address; a.maxPacket = (w and 0x7ff) * (1 + ((w shr 11) and 3)); a.interval = u8(raw, i + 6); a.sync = (attributes shr 2) and 3
                            dataEndpoint = true
                        }
                    }
                }
                // CS EP_GENERAL: UAC1 bmAttributes bit 0 = sampling-frequency control (Linux sends SET_CUR only when it is set).
                0x25 -> if (dataEndpoint && len >= 4 && u8(raw, i + 2) == 1) alt?.let { if (!it.uac2) it.freqControl = (u8(raw, i + 3) and 1) != 0 }
            }
            i += len
        }
        return alts.mapNotNull { a ->
            if (!a.pcm || !a.typeI || a.endpoint < 0 || a.channels <= 0 || a.subslot !in 1..4 || a.maxPacket <= 0) return@mapNotNull null
            // UAC1 lists its streaming interfaces in the AC header; UAC2 groups them with an interface association.
            val c = controls.firstOrNull { a.number in it.streaming }
                ?: associations.firstOrNull { a.number in it }?.let { r -> controls.firstOrNull { it.number in r } }
                ?: controls.lastOrNull { it.order < a.order } ?: controls.firstOrNull()
            var clock = -1; var settable = a.freqControl ?: true
            if (a.uac2 && c != null) {
                clock = resolveClock(c, c.entities[a.terminalLink]?.clock ?: -1)
                settable = c.entities[clock]?.let { it.subtype != 0x0A || (it.controls and 3) == 3 } ?: true
            }
            UacStream(a.number, a.alt, a.endpoint, a.maxPacket, a.interval, a.sync, a.channels, a.subslot, a.bits.takeIf { it > 0 } ?: (a.subslot * 8),
                a.rates, a.minRate, a.maxRate, if (a.uac2) 2 else 1, c?.number ?: 0, a.terminalLink, clock, settable)
        }
    }

    /** Follows clock selectors (to their first input, which Linux also starts from) and multipliers to the clock source. */
    private fun resolveClock(c: Control, start: Int): Int {
        var id = start
        repeat(8) {
            val e = c.entities[id] ?: return id // unknown entity: address the id the terminal named
            when (e.subtype) { 0x0A -> return id; 0x0B, 0x0C -> id = e.inputs.firstOrNull() ?: return id; else -> return -1 }
        }
        return id
    }

    /** The rate to ask [s] for: [preferred] when it is offered (or unknown, as for UAC2 before RANGE), else the nearest offered one. */
    fun rateFor(s: UacStream, preferred: Int): Int = when {
        s.supports(preferred) || (s.rates.isEmpty() && !s.continuous) -> preferred
        s.continuous -> preferred.coerceIn(s.minRate, s.maxRate)
        else -> s.rates.minWith(compareBy<Int>({ abs(it - preferred) }, { -it }))
    }

    /**
     * Prefers [preferredRate] (else the nearest rate), then stereo, then mono, then 17–24-bit (HDMI audio is at most
     * 24-bit, and 24 bits are exact in the mixer's float; a capture card's 16-bit mode would drop bits), then 32-bit,
     * then 16-bit, then the smallest packet that
     * still carries the rate (so a microphone does not reserve more of the bus than it needs, as Linux snd-usb-audio does).
     */
    fun choose(streams: List<UacStream>, preferredRate: Int = 48000, highSpeed: Boolean = false): UacChoice? =
        streams.map { UacChoice(it, rateFor(it, preferredRate)) }.minWithOrNull(compareBy<UacChoice>(
            { abs(it.rate - preferredRate) },
            { when (it.stream.channels) { 2 -> 0; 1 -> 1; else -> 2 } },
            { val b = it.stream.bitResolution; when { b in 17..24 -> 0; b > 24 -> 1; b == 16 -> 2; else -> 3 } },
            { if (it.stream.maxPacketBytes >= it.stream.bytesNeeded(it.rate, highSpeed)) 0 else 1 },
            { it.stream.maxPacketBytes },
            { it.stream.interfaceNumber * 256 + it.stream.alternateSetting }))

    /** Discrete rates from a UAC2 clock RANGE reply: wNumSubRanges, then dMIN/dMAX/dRES per subrange. */
    fun ratesFromRange(reply: ByteArray, length: Int = reply.size): List<Int> {
        val end = length.coerceAtMost(reply.size); val out = sortedSetOf<Int>()
        for (k in 0 until u16(reply, 0)) {
            val o = 2 + k * 12; if (o + 12 > end) break
            val min = u32(reply, o); val max = u32(reply, o + 4); val res = u32(reply, o + 8)
            if (min <= 0 || max < min) continue
            if (min == max) { out += min; continue }
            val inRange = STANDARD_RATES.filter { it in min..max && (res <= 0 || (it - min) % res == 0) }
            if (inRange.isEmpty()) out += min else out += inRange
        }
        return out.toList()
    }

    fun describe(streams: List<UacStream>): String = if (streams.isEmpty()) "no PCM capture formats" else streams.joinToString("; ") { it.description }

    /** Raw descriptors as hex, one descriptor per line, for the stream log when an unknown device misbehaves. */
    fun hexDump(raw: ByteArray): String {
        val sb = StringBuilder(); var i = 0
        while (i < raw.size) {
            val len = u8(raw, i).let { if (it < 2 || i + it > raw.size) raw.size - i else it }
            for (k in i until i + len) sb.append("%02x".format(raw[k].toInt() and 0xff)).append(if (k + 1 < i + len) " " else "\n")
            i += len
        }
        return sb.toString().trimEnd()
    }

    private fun u8(b: ByteArray, i: Int) = if (i < b.size) b[i].toInt() and 0xff else 0
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u24(b: ByteArray, i: Int) = u16(b, i) or (u8(b, i + 2) shl 16)
    private fun u32(b: ByteArray, i: Int) = (u24(b, i).toLong() or (u8(b, i + 3).toLong() shl 24)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
