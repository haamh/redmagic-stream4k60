package com.stream4k60.app.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Native UVC transport supporting both bulk and true isochronous USB streaming.
 * Compressed UVC payloads are fed directly into hardware MediaCodec; uncompressed
 * YUYV/UYVY/NV12 is uploaded to the GPU without a Kotlin pixel conversion stage.
 */
class UvcCaptureSession(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val sourceId: String,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val formatName: String,
    private val sourceConfigJson: String = "{}"
) {
    /** Called (from the watchdog thread) when the stream gave up on a device that only answers with errors. */
    @Volatile var onDead: (() -> Unit)? = null
    data class Format(
        val index:Int,val frameIndex:Int,val width:Int,val height:Int,val fps:Int,
        val codec:String,val maxFrameSize:Int,
        /** What the device declares about this format's colour; null when it has no Color Matching descriptor. */
        val color:UvcColor?=null
    )
    /**
     * A UVC Color Matching descriptor (VS_COLORFORMAT): the device's own statement of its colour primaries, transfer
     * curve and YUV matrix for a format (codes from the UVC 1.5 spec: 1 BT.709, 2 BT.470-2 M / FCC, 3 BT.470-2 B/G,
     * 4 SMPTE 170M, 5 SMPTE 240M, 6 linear, 7 sRGB; 0 unspecified). It says nothing about range.
     */
    data class UvcColor(val primaries:Int,val transfer:Int,val matrix:Int){
        /** For the shader: 0 Rec. 601, 1 Rec. 709; -1 when the device leaves it unspecified. */
        val shaderMatrix:Int get()=when(matrix){1,5->1;2,3,4->0;else->-1}
        val matrixName:String get()=when(matrix){1->"Rec. 709";2->"FCC";3->"BT.470 B/G (Rec. 601)";4->"SMPTE 170M (Rec. 601)";5->"SMPTE 240M";else->"unspecified"}
        private fun curve(c:Int)=when(c){1->"BT.709";2->"BT.470 M";3->"BT.470 B/G";4->"SMPTE 170M";5->"SMPTE 240M";6->"linear";7->"sRGB";else->"unspecified"}
        val description:String get()="${matrixName} matrix, ${curve(primaries)} primaries, ${curve(transfer)} transfer"
    }

    private data class EndpointChoice(val intf:UsbInterface,val endpoint:UsbEndpoint,val iso:Boolean,val packetBytes:Int)
    /** What the camera answered to the UVC probe; it may differ from what was asked. */
    internal data class Committed(val formatIndex:Int,val frameIndex:Int,val interval:Long,val maxVideoFrameSize:Long,val maxPayload:Int)

    private val running = AtomicBoolean(false)
    private var decoder: MediaCodec? = null
    /** Set when the phone has no MJPEG MediaCodec (the Astra doesn't): frames are decoded on the CPU instead. */
    private var softwareJpeg = false
    private var jpegBitmap: android.graphics.Bitmap? = null
    private var streamInterface: UsbInterface? = null
    private var endpoint: UsbEndpoint? = null
    private var selectedFormat: Format? = null
    private var surface: Surface? = null
    private var isoHandle: Long = 0
    private var bulkHandle: Long = 0
    private data class CompressedFrame(val data: ByteArray, val ptsUs: Long)
    private val decodeQueue = ArrayBlockingQueue<CompressedFrame>(64)
    /** H.264/HEVC frames depend on earlier ones: after any loss, wait for the next keyframe. */
    @Volatile private var needKeyframe = true
    private var parameterSets: ByteArray? = null
    @Volatile private var decodedCount = 0L
    @Volatile private var lastDecodeError: String? = null
    private var watchdog: Thread? = null
    private var decodeThread: Thread? = null
    private var frameErrors = 0L
    private var frameCount = 0L
    /** Set when the camera wants more USB bandwidth for this mode than any alternate setting carries. */
    private var bandwidthShortfall: String? = null

    /** The decoder surface for compressed formats; null for uncompressed YUV, which is uploaded directly. */
    fun start(): Surface? {
        check(!running.get()) { "UVC session already running" }
        val formats = parseFormats(connection.rawDescriptors)
        check(formats.isNotEmpty()) { "The camera did not advertise any video formats" }
        val requested = chooseFormat(formats, width, height, fps, formatName)

        val candidates = endpointCandidates()
        val widest = candidates.maxByOrNull { it.packetBytes }
            ?: error("UVC device has no usable real-time bulk or isochronous streaming endpoint")
        check(connection.claimInterface(widest.intf, true)) { "Could not claim UVC streaming interface ${widest.intf.id}" }
        streamInterface = widest.intf

        // UVC negotiation is performed while the streaming interface is in the zero-bandwidth
        // alternate setting. The selected alternate is applied only after PROBE/COMMIT succeeds.
        val zeroBandwidth = (0 until device.interfaceCount)
            .map(device::getInterface)
            .firstOrNull { it.id == widest.intf.id && it.interfaceClass == 14 && it.interfaceSubclass == 2 && it.alternateSetting == 0 }
        if (zeroBandwidth != null) connection.setInterface(zeroBandwidth)
        val committed = negotiate(widest, requested)
        // The camera may answer with a different frame or rate than asked; stream (and size the decoder) by its answer.
        val selected = committed?.let { committedFormat(formats, requested, it) } ?: requested
        if (selected != requested) android.util.Log.i("Stream4k60", "UVC camera chose $selected instead of $requested")
        selectedFormat = selected

        // Like Linux uvcvideo: the smallest isochronous setting that carries the camera's payload size, so one camera
        // does not reserve the whole bus and a second camera on the same port can still get bandwidth.
        val choice = if (widest.iso && committed != null && committed.maxPayload > 0) {
            val iso = candidates.filter { it.iso && it.intf.id == widest.intf.id }
            iso.getOrNull(pickIsoAlternate(iso.map { it.packetBytes }, committed.maxPayload)) ?: widest
        } else widest
        if (choice.iso && committed != null && committed.maxPayload > choice.packetBytes) {
            bandwidthShortfall = "this mode needs ${committed.maxPayload} bytes per USB interval but the port carries at most ${choice.packetBytes}"
            android.util.Log.w("Stream4k60", "UVC: $bandwidthShortfall")
        }
        streamInterface = choice.intf
        endpoint = choice.endpoint
        // Isochronous video needs its bandwidth alternate setting. Bulk cameras stream on alternate setting 0, already
        // selected before the probe; Linux uvcvideo and libuvc send no SET_INTERFACE after COMMIT for them, as it can
        // reset the streaming interface.
        if (choice.iso) check(connection.setInterface(choice.intf)) { "Could not select UVC alternate setting ${choice.intf.alternateSetting}" }
        applyConfiguredVideoControls()
        // Only compressed video goes through a decoder surface. Uncompressed YUV is uploaded as plain textures: creating the
        // surface for it too left a decoder (external) texture on the source, which the NV12/YUYV upload then reused, so the
        // GPU dropped the brightness plane: a dark, blue, stalling picture.
        if (selected.codec.uppercase() in setOf("MJPEG","H264","HEVC","H265","AVC"))
            surface = NativeEngine.createSourceSurface(sourceId) ?: error("Could not create GPU source surface")
        // Registered now (not at the first frame) so its position and size, sent meanwhile, aren't dropped.
        else NativeEngine.ensureRawSource(sourceId)
        NativeEngine.setSourceEffectsFromConfig(sourceId, sourceConfigJson)
        NativeEngine.setSourceBufferSize(sourceId, selected.width, selected.height)
        // The editor sizes the source from its real frame (as for video files), not from the format saved in settings.
        SourceNativeSizes.report(sourceId, selected.width, selected.height)
        // Ask the device how its YUV is encoded (its Color Matching descriptor) instead of guessing from the resolution.
        val declared = selected.color?.takeIf { it.shaderMatrix >= 0 }
        NativeEngine.setSourceYuvReported(sourceId, declared?.shaderMatrix ?: -1)
        val colorNote = if (declared != null) "the device reports ${declared.description}"
            else if (selected.color != null) "the device's colour descriptor leaves the matrix unspecified; using ${if (selected.height >= 720) "Rec. 709" else "Rec. 601"} (what Windows, macOS and OBS assume at this size)"
            else "the device doesn't report its colour; using ${if (selected.height >= 720) "Rec. 709" else "Rec. 601"} (what Windows, macOS and OBS assume at this size)"
        SourceColors.reportUsb(sourceId, colorNote)
        // UVC can't say whether the HDMI input is HDR, and the Elgato passes it through unchanged (measured in P010 and NV12):
        // an iPhone casting in HDR arrives as HLG with BT.2020 colour, an HDR10 console or PC as PQ.
        if (selected.codec.equals("P010", true))
            SourceColors.reportUsb(sourceId, "$colorNote. 10-bit: if the HDMI source sends HDR, set Signal (HLG for an iPhone casting in HDR, PQ for an HDR10 console or PC) and Color space Rec. 2020")
        StreamLog.add("USB source ${sourceId.take(8)} ${selected.width}x${selected.height}@${selected.fps} ${selected.codec}: $colorNote")
        if (selected.codec.uppercase() in setOf("MJPEG","H264","HEVC","H265","AVC")) {
            val hw = runCatching { createDecoder(selected, surface!!) }
            if (hw.isSuccess) {
                decoder = hw.getOrThrow(); decoder?.start()
                // A hardware decoder hands the GPU YUV; its colour is known once it reports its output format.
                NativeEngine.setSourceDecodedColor(sourceId, 1, -1, -1)
            }
            else if (selected.codec.equals("MJPEG", true)) {
                android.util.Log.i("Stream4k60", "No MJPEG MediaCodec (${hw.exceptionOrNull()?.message}); decoding MJPEG in software")
                softwareJpeg = true
                NativeEngine.setSourceDecodedColor(sourceId, 2, 0, 1)
                SourceColors.reportUsb(sourceId, "JPEG frames, decoded as Rec. 601 full range (the JPEG standard)")
            } else throw hw.exceptionOrNull()!!
        }

        val fd = connection.fileDescriptor
        check(fd >= 0) { "USB native file descriptor unavailable" }
        // Mark the session live before submitting the first URBs; an attached device may
        // deliver the first frame immediately after SUBMITURB.
        running.set(true)
        if (decoder != null || softwareJpeg) {
            decodeThread = Thread({ decodeLoop() }, "Stream4k-UVCDecode-${sourceId.take(8)}").apply {
                priority = Thread.NORM_PRIORITY + 1
                start()
            }
        }
        try {
            if (choice.iso) {
                isoHandle = NativeUsbIso.start(fd, choice.endpoint.address, choice.packetBytes, 32, 16, this)
                if (isoHandle == 0L) error("The camera's isochronous USB stream could not start: ${NativeUsbIso.lastStartError()} (alternate setting ${choice.intf.alternateSetting})")
            } else {
                bulkHandle = NativeUsbBulk.start(fd, choice.endpoint.address, choice.packetBytes, NativeUsbBulk.transferSize(choice.endpoint), committed?.maxPayload?.takeIf { it >= 64 } ?: 0, 16, this)
                if (bulkHandle == 0L) error("The camera's bulk USB stream could not start: ${NativeUsbIso.lastStartError()}")
            }
        } catch (t: Throwable) {
            running.set(false)
            decodeThread?.interrupt()
            runCatching { decodeThread?.join(500) }
            decodeThread = null
            decodeQueue.clear()
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            decoder = null
            runCatching { connection.releaseInterface(choice.intf) }
            streamInterface = null
            runCatching { surface?.release() }
            surface = null
            runCatching { NativeEngine.releaseSourceSurface(sourceId) }
            throw t
        }
        startWatchdog()
        return surface
    }

    /**
     * Says where the video stops when the preview stays black: nothing from USB, data that doesn't decode, or a
     * decoder error. Clears the message once pictures come out.
     */
    private fun startWatchdog(){
        watchdog=Thread({
            var lastFrames=0L;var lastDecoded=0L
            // Every 30 s: the frame rate the camera really delivers and how many frames arrived cut short (lost USB data).
            var tick=0;var rateFrames=0L;var rateRejected=0L;var rateErrors=0L;var rateStart=android.os.SystemClock.elapsedRealtime()
            try{
                Thread.sleep(4000)
                while(running.get()){
                    if((isoHandle!=0L&&NativeUsbIso.isDead(isoHandle))||(bulkHandle!=0L&&NativeUsbBulk.isDead(bulkHandle))){
                        StreamLog.add("USB source ${sourceId.take(8)}: the camera answered nothing but errors for 1.5 s (gone without an unplug event?); stream stopped and the device closed")
                        SourceRuntimeErrors.report(sourceId,"The camera stopped answering over USB. Replug it; the app closed its stream so the USB controller isn't flooded.")
                        onDead?.invoke();break
                    }
                    val frames=frameCount;val raw=selectedFormat?.codec?.uppercase() in setOf("YUYV","YUY2","UYVY","NV12","I420","P010");val decoded=if(softwareJpeg||raw)frames-frameErrors else decodedCount
                    val fmt=selectedFormat?.let{"${it.width}x${it.height}@${it.fps} ${it.codec}"}.orEmpty()
                    val msg=when{
                        frames==lastFrames->"The camera accepted $fmt but sent no video over USB in the last few seconds (${transportLabel()}, ${frameErrors} packet errors${bandwidthShortfall?.let{"; $it"}.orEmpty()}). Try another format or USB port."
                        !softwareJpeg&&!raw&&decoded==lastDecoded->"Receiving $fmt from the camera (${frames} frames) but the decoder produced no picture${lastDecodeError?.let{": $it"}?:""}."
                        else->null
                    }
                    if(msg!=null)SourceRuntimeErrors.report(sourceId,msg) else SourceRuntimeErrors.clear(sourceId)
                    lastFrames=frames;lastDecoded=decoded
                    if(++tick%10==0){
                        val now=android.os.SystemClock.elapsedRealtime();val secs=(now-rateStart)/1000.0
                        StreamLog.add("USB source ${sourceId.take(8)} $fmt: %.1f fps over %.0f s, %d frames incomplete, %d packet errors".format(java.util.Locale.US,(frames-rateFrames)/secs,secs,rejectedFrames-rateRejected,frameErrors-rateErrors)+
                            if(isoHandle!=0L&&frameErrors>0)" (${NativeUsbIso.errorBreakdown(isoHandle)})" else "")
                        rateFrames=frames;rateRejected=rejectedFrames;rateErrors=frameErrors.toLong();rateStart=now
                    }
                    Thread.sleep(3000)
                }
            }catch(_:InterruptedException){}
        },"Stream4k-UVCWatch").apply{isDaemon=true;start()}
    }
    private var rejectedFrames = 0L
    private var lastGrabCheckMs = 0L
    private var lastGrabStamp = 0L
    private val sessionStartWallMs = System.currentTimeMillis()
    private var sizeSamples = 0
    private var sizeMin = Int.MAX_VALUE
    private var sizeMax = 0
    private var sizeStartMs = 0L
    /** Logs the first 2 s of frame sizes against what the selected format should send. */
    private fun noteFrameSize(format: Format, frame: ByteBuffer) {
        val bytes = frame.remaining()
        if (sizeSamples < 0) {
            // On request (adb: touch files/grab-frame), save the next frame too, without restarting the capture.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastGrabCheckMs > 1000) {
                lastGrabCheckMs = now
                val dir = StreamLog.directory()
                val trigger = dir?.let { java.io.File(it, "grab-frame") }
                // Every capture session answers the same request (the file's time), so none takes it from another.
                val stamp = trigger?.takeIf { it.exists() }?.lastModified() ?: 0L
                if (stamp > lastGrabStamp && stamp > sessionStartWallMs) {
                    lastGrabStamp = stamp
                    val copy = ByteArray(bytes).also { frame.duplicate().get(it) }
                    Thread { runCatching { java.io.File(dir, "uvc-frame-${format.codec.lowercase()}-${format.width}x${format.height}.raw").writeBytes(copy) } }.start()
                    StreamLog.add("USB source ${sourceId.take(8)}: frame saved on request")
                }
            }
            return
        }
        if (sizeSamples == 0) sizeStartMs = android.os.SystemClock.elapsedRealtime()
        sizeSamples++; sizeMin = minOf(sizeMin, bytes); sizeMax = maxOf(sizeMax, bytes)
        val elapsed = android.os.SystemClock.elapsedRealtime() - sizeStartMs
        if (elapsed < 2000) return
        val expected = when (format.codec.uppercase()) { "YUYV", "YUY2", "UYVY" -> format.width * format.height * 2; "NV12", "I420" -> format.width * format.height * 3 / 2; "P010" -> format.width * format.height * 3; else -> 0 }
        StreamLog.add("USB source ${sourceId.take(8)} ${format.width}x${format.height} ${format.codec}: $sizeSamples frames in ${elapsed / 1000.0} s, sizes $sizeMin–$sizeMax bytes${if (expected > 0) " (this format is $expected)" else ""}, $rejectedFrames rejected by the compositor; compositor: ${NativeEngine.describeSource(sourceId)}${if (isoHandle != 0L) "; packet errors " + NativeUsbIso.errorBreakdown(isoHandle) else ""}")
        // One raw frame kept for checking what the camera really sends (pulled over adb; overwritten each session).
        val copy = ByteArray(bytes).also { frame.duplicate().get(it) }
        val dir = StreamLog.directory()
        if (dir != null) Thread { runCatching { java.io.File(dir, "uvc-frame-${format.codec.lowercase()}-${format.width}x${format.height}.raw").writeBytes(copy) } }.start()
        sizeSamples = -1
    }
    private fun transportLabel()=if(isoHandle!=0L)"isochronous" else if(bulkHandle!=0L)"bulk" else "no transfer"

    /** Called synchronously by native usbfs from the URB completion thread. */
    @Suppress("UNUSED_PARAMETER")
    fun onNativeFrame(buffer: ByteBuffer, ptsUs: Long, packetErrorCount: Int) {
        if (!running.get()) return
        frameCount++
        frameErrors += packetErrorCount
        val format = selectedFormat ?: return
        noteFrameSize(format, buffer)
        when (format.codec.uppercase()) {
            "YUYV", "YUY2" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 1)
            "UYVY" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 2)
            "NV12" -> if (!NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 3)) rejectedFrames++
            "I420" -> NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 4)
            "P010" -> if (!NativeEngine.updateSourceYuvDirect(sourceId, buffer, format.width, format.height, 6)) rejectedFrames++
            else -> {
                val src = buffer.duplicate()
                val bytes = ByteArray(src.remaining())
                src.get(bytes)
                if (format.codec.uppercase() == "MJPEG") {
                    // Every MJPEG frame stands alone: keep only the newest.
                    while (decodeQueue.size >= 2) decodeQueue.poll()
                    decodeQueue.offer(CompressedFrame(bytes, ptsUs))
                } else if (!decodeQueue.offer(CompressedFrame(bytes, ptsUs))) {
                    // Decoder fell behind: dropping part of an H.264 stream corrupts it, so restart at a keyframe.
                    decodeQueue.clear(); needKeyframe = true
                }
            }
        }
    }

    fun stop(deviceGone: Boolean = false) {
        if (!running.getAndSet(false) && isoHandle == 0L && bulkHandle == 0L && streamInterface == null && surface == null && decoder == null) return
        val h = isoHandle; isoHandle = 0L
        // What went wrong on the bus up to now (often right before the device dropped off), before the stream is gone.
        if (h != 0L) runCatching { StreamLog.add("USB source ${sourceId.take(8)} stopped: ${NativeUsbIso.packetErrors(h)} packet errors ${NativeUsbIso.errorBreakdown(h)}") }
        if (h != 0L) runCatching { NativeUsbIso.stop(h) }
        val b = bulkHandle; bulkHandle = 0L
        if (b != 0L) runCatching { NativeUsbBulk.stop(b) }
        watchdog?.interrupt(); watchdog = null
        decodeThread?.interrupt()
        runCatching { decodeThread?.join(500) }
        decodeThread = null
        decodeQueue.clear()
        runCatching { decoder?.stop() }; runCatching { decoder?.release() }; decoder = null
        if (!deviceGone) {
            // The physical device is still present: release our userspace interface claim normally.
            streamInterface?.let { runCatching { connection.releaseInterface(it) } }
        } else {
            // Device is physically gone: do not issue further USB interface I/O on the dead connection.
            StreamLog.add("USB source ${sourceId.take(8)}: device detached; skipped interface release")
        }
        streamInterface = null; endpoint = null
        runCatching { surface?.release() }; surface = null
        runCatching { NativeEngine.releaseSourceSurface(sourceId) }
    }

    fun currentFormat(): Format? = selectedFormat
    fun currentTransport(): String = if (isoHandle != 0L) "ISOCHRONOUS" else if (bulkHandle != 0L) "BULK" else ""
    fun deliveredFrames():Long = frameCount
    fun transportErrors():Long = frameErrors

    /** Every video-streaming IN endpoint: isochronous ones (alternate settings > 0) if the camera has any, else bulk. */
    private fun endpointCandidates():List<EndpointChoice> {
        val interfaces = (0 until device.interfaceCount).asSequence()
            .map(device::getInterface)
            .filter { it.interfaceClass == 14 && it.interfaceSubclass == 2 }
            .toList()
        val iso = interfaces.flatMap { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).filter {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && intf.alternateSetting > 0
            }.map { ep -> EndpointChoice(intf,ep,true,packetCapacity(intf,ep)) }
        }
        if (iso.isNotEmpty()) return iso
        return interfaces.flatMap { intf ->
            (0 until intf.endpointCount).map(intf::getEndpoint).filter {
                it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
            }.map { ep -> EndpointChoice(intf,ep,false,ep.maxPacketSize) }
        }
    }

    private fun packetCapacity(intf:UsbInterface,endpoint:UsbEndpoint):Int {
        val raw=endpoint.maxPacketSize
        val base=raw and 0x07ff
        val transactions=1+((raw ushr 11) and 0x3)
        val companion=superSpeedBytesPerInterval(connection.rawDescriptors,intf.id,intf.alternateSetting,endpoint.address)
        return companion ?: (base*transactions).coerceAtLeast(base)
    }

    private fun createDecoder(f:Format,surface:Surface):MediaCodec {
        val mime=when(f.codec.uppercase()){
            "MJPEG"->"video/mjpeg"
            "H264","AVC","AVC1"->"video/avc"
            "H265","HEVC"->"video/hevc"
            else->error("No hardware decoder mapping for ${f.codec}")
        }
        val cfg=runCatching{org.json.JSONObject(sourceConfigJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(org.json.JSONObject())
        val preference=cfg.optString("videoDecoderPreference","hardware").lowercase()
        if(preference=="automatic") return configureDecoder(MediaCodec.createDecoderByType(mime),mime,f,surface)
        val preferHardware=preference!="software"
        val candidates=MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter{info->!info.isEncoder&&info.supportedTypes.any{it.equals(mime,true)}}
            .sortedByDescending{it.isHardwareAccelerated==preferHardware}
        var lastFailure:Throwable?=null
        for(info in candidates){
            val codec=try{MediaCodec.createByCodecName(info.name)}catch(error:Throwable){lastFailure=error;continue}
            val configured=runCatching{configureDecoder(codec,mime,f,surface)}
            if(configured.isSuccess){
                // Which chip decodes it: c2.qti.* is the Snapdragon's video hardware, c2.android.* the CPU.
                StreamLog.add("USB source ${sourceId.take(8)} ${f.width}x${f.height} ${f.codec}: decoded by ${info.name} (${if(info.isHardwareAccelerated)"hardware" else "software"}, low-latency mode requested)")
                return configured.getOrThrow()
            }
            lastFailure=configured.exceptionOrNull()
        }
        throw lastFailure?:IllegalStateException("Android has no decoder for $mime")
    }

    private fun configureDecoder(codec:MediaCodec,mime:String,f:Format,surface:Surface):MediaCodec {
        val format=MediaFormat.createVideoFormat(mime,f.width,f.height).apply {
            if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY,1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, f.maxFrameSize.coerceAtLeast(512*1024))
        }
        return try{codec.configure(format,surface,null,0);codec}catch(error:Throwable){runCatching{codec.release()};throw error}
    }

    private fun decodeLoop(){
        while(running.get() && !Thread.currentThread().isInterrupted){
            val frame=runCatching{decodeQueue.poll(20,TimeUnit.MILLISECONDS)}.getOrNull() ?: continue
            try{
                if(softwareJpeg)decodeJpeg(frame.data)
                else{val prepared=prepareCompressed(frame.data)?:continue;feedDecoder(ByteBuffer.wrap(prepared),frame.ptsUs)}
            }catch(t:Throwable){
                if(t is InterruptedException)return
                lastDecodeError="${t.javaClass.simpleName}: ${t.message}";android.util.Log.w("Stream4k60","UVC decode failed",t)
            }
        }
    }

    /**
     * Software MJPEG: Android's JPEG decoder (libjpeg-turbo) into a reused bitmap, then drawn into the source's
     * GPU surface with a hardware canvas. Only the newest frame is decoded, so a slow frame never builds a backlog.
     */
    private fun decodeJpeg(data:ByteArray){
        val target=surface?:return
        val jpeg=MjpegFrames.withHuffmanTables(data)
        val opts=android.graphics.BitmapFactory.Options().apply{inMutable=true;inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;inBitmap=jpegBitmap}
        val bmp=try{android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,opts)}catch(_:IllegalArgumentException){
            // The reusable bitmap no longer fits (size changed): decode into a fresh one.
            jpegBitmap=null;android.graphics.BitmapFactory.decodeByteArray(jpeg,0,jpeg.size,android.graphics.BitmapFactory.Options().apply{inMutable=true})
        }?:run{frameErrors++;return}
        jpegBitmap=bmp
        val canvas=try{target.lockHardwareCanvas()}catch(_:Throwable){return}
        try{canvas.drawBitmap(bmp,null,android.graphics.Rect(0,0,canvas.width,canvas.height),null)}finally{runCatching{target.unlockCanvasAndPost(canvas)}}
    }

    /**
     * H.264/HEVC: remembers the camera's parameter sets (SPS/PPS, and VPS for HEVC), adds them to keyframes that
     * lack them, and skips frames until the first keyframe so the decoder always starts from a complete picture.
     */
    private fun prepareCompressed(data:ByteArray):ByteArray?{
        val hevc=selectedFormat?.codec?.uppercase().let{it=="HEVC"||it=="H265"}
        var hasParams=false;var keyframe=false
        val params=java.io.ByteArrayOutputStream()
        forEachNal(data){start,end,header->
            val type=if(hevc)(header shr 1) and 0x3F else header and 0x1F
            val isParam=if(hevc)type in 32..34 else type==7||type==8
            if(isParam){hasParams=true;params.write(byteArrayOf(0,0,0,1));params.write(data,start,end-start)}
            if(if(hevc)type in 16..21 else type==5)keyframe=true
        }
        if(hasParams)parameterSets=params.toByteArray()
        if(needKeyframe){
            if(!keyframe||parameterSets==null){lastDecodeError=if(parameterSets==null)"Waiting for the camera's stream setup (SPS/PPS)" else "Waiting for a keyframe";return null}
            needKeyframe=false
        }
        val ps=parameterSets
        return if(keyframe&&!hasParams&&ps!=null)ps+data else data
    }

    /** Calls [block] with (start of NAL payload, end, first payload byte) for each Annex-B NAL unit. */
    private inline fun forEachNal(d:ByteArray,block:(Int,Int,Int)->Unit){
        var i=0;var nalStart=-1
        while(i+3<=d.size){
            val sc=if(d[i]==0.toByte()&&d[i+1]==0.toByte()&&d[i+2]==1.toByte())3 else if(i+4<=d.size&&d[i]==0.toByte()&&d[i+1]==0.toByte()&&d[i+2]==0.toByte()&&d[i+3]==1.toByte())4 else 0
            if(sc>0){
                if(nalStart>=0&&nalStart<i)block(nalStart,i,d[nalStart].toInt() and 0xFF)
                nalStart=i+sc;i+=sc
            }else i++
        }
        if(nalStart in 0 until d.size)block(nalStart,d.size,d[nalStart].toInt() and 0xFF)
    }

    private fun feedDecoder(frame:ByteBuffer,ptsUs:Long){
        val c=decoder?:return
        var inIndex=c.dequeueInputBuffer(20_000)
        if(inIndex<0){drain(c);inIndex=c.dequeueInputBuffer(40_000)}
        if(inIndex<0){needKeyframe=true;lastDecodeError="The hardware decoder stopped accepting frames";return}
        val src=frame.duplicate()
        val size=src.remaining()
        val dst=c.getInputBuffer(inIndex)?:return
        dst.clear()
        if(size>dst.remaining())return
        dst.put(src)
        c.queueInputBuffer(inIndex,0,size,ptsUs,0)
        drain(c)
    }

    private fun drain(codec:MediaCodec){
        val info=MediaCodec.BufferInfo()
        while(true){
            val out=codec.dequeueOutputBuffer(info,0)
            when{
                out>=0->{codec.releaseOutputBuffer(out,true);decodedCount++;lastDecodeError=null}
                out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED->reportDecoderColour(codec.outputFormat)
                else->return
            }
        }
    }

    /**
     * The colour the decoder found in the camera's stream (or Android's default when the stream says nothing), which is
     * how the GPU converts it; Color space / range / Signal in Properties are applied on top of it.
     */
    private fun reportDecoderColour(format:MediaFormat){
        fun int(key:String)=if(format.containsKey(key))format.getInteger(key) else -1
        val standard=int(MediaFormat.KEY_COLOR_STANDARD);val range=int(MediaFormat.KEY_COLOR_RANGE);val transfer=int(MediaFormat.KEY_COLOR_TRANSFER)
        val matrix=when(standard){MediaFormat.COLOR_STANDARD_BT709->1;MediaFormat.COLOR_STANDARD_BT601_PAL,MediaFormat.COLOR_STANDARD_BT601_NTSC->0;MediaFormat.COLOR_STANDARD_BT2020->2;else->-1}
        val full=when(range){MediaFormat.COLOR_RANGE_FULL->1;MediaFormat.COLOR_RANGE_LIMITED->0;else->-1}
        val signal=when(transfer){MediaFormat.COLOR_TRANSFER_ST2084->1;MediaFormat.COLOR_TRANSFER_HLG->2;else->0}
        val kind=when(signal){1->"HDR10 (PQ)";2->"HLG";else->"SDR"}
        val label="$kind · ${if(matrix>=0)SourceColors.matrixName(matrix) else "matrix not said (Rec. ${if(height>=720)"709" else "601"} assumed)"} · ${SourceColors.rangeName(full)}"
        SourceColors.report(sourceId,SourceColors.Detected(signal,label,matrix,full))
        SourceColors.reportUsb(sourceId,"the decoder reports $label")
        val settings=runCatching{org.json.JSONObject(sourceConfigJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(org.json.JSONObject())
        SourceColors.apply(sourceId,settings)
        StreamLog.add("USB source ${sourceId.take(8)} decoder output: $label")
    }

    private fun negotiate(choice:EndpointChoice,f:Format):Committed?{
        // UVC 1.1 probe/commit. The selected alternate setting's bandwidth becomes the
        // negotiated max payload transfer size, so isochronous endpoints are not starved.
        // Probe/commit size depends on the camera's UVC version: 26 bytes (1.0), 34 (1.1), 48 (1.5).
        val probeLen=probeLength(connection.rawDescriptors)
        val interval=10_000_000/f.fps.coerceAtLeast(1)
        val payload=choice.packetBytes.coerceAtLeast(1024)
        val probe=ByteArray(probeLen)
        u16le(probe,0,1) // bmHint: keep the requested frame interval
        u8(probe,2,f.index);u8(probe,3,f.frameIndex);u32le(probe,4,interval.toLong());
        u32le(probe,18,f.maxFrameSize.toLong());u32le(probe,22,payload.toLong())
        runCatching{controlSet(choice.intf.id,1,probe)}.getOrElse{error("The camera rejected the video format request (UVC probe, ${probeLen} bytes): ${it.message}")}
        val returned=ByteArray(probeLen)
        val got=controlGet(choice.intf.id,1,returned)
        // Elgato capture cards (the Cam Link 4K, and the same firmware family) answer the probe with the format index
        // in bmHint's high byte and a constant 1 where bFormatIndex belongs; Linux uvcvideo corrects it the same way.
        // Committing that answer as-is made the card send its first format while another was decoded: wrong colours
        // whatever the settings, and frozen frames when the sizes didn't match.
        val raw=returned.copyOf(minOf(4,returned.size)).joinToString(" "){"%02x".format(it)}
        val fixed=got>=4&&fixShiftedProbe(returned)
        StreamLog.add("UVC probe for format ${f.index}/${f.frameIndex} (${f.width}x${f.height} ${f.codec}): camera answered [$raw]${if(fixed)" — shifted bytes corrected to format ${returned.getOrZero(2)}" else ""}")
        val commit=if(got>=probeLen)returned.copyOf() else probe
        runCatching{controlSet(choice.intf.id,2,commit)}.getOrElse{error("The camera rejected the video format commit: ${it.message}")}
        return if(got>=26)parseCommitted(returned) else null
    }

    private fun applyConfiguredVideoControls(){
        val root=runCatching{org.json.JSONObject(sourceConfigJson)}.getOrDefault(org.json.JSONObject())
        val settings=root.optJSONObject("settings")?:root
        val controls=settings.optJSONObject("uvcControls")?:return
        controls.keys().forEach{key->
            if(!controls.isNull(key))runCatching{UvcVideoControls.set(connection,key,controls.optInt(key))}
        }
    }

    private fun controlSet(interfaceNumber:Int,selector:Int,data:ByteArray){
        val r=connection.controlTransfer(0x21,0x01,selector shl 8,interfaceNumber,data,0,data.size,1000)
        check(r>=0){"UVC SET_CUR failed ($r)"}
    }
    private fun controlGet(interfaceNumber:Int,selector:Int,data:ByteArray):Int = connection.controlTransfer(0xA1,0x81,selector shl 8,interfaceNumber,data,0,data.size,1000)

    companion object {
        /** Requested mode if advertised; otherwise the closest mode, preferring the requested codec, then MJPEG, H.264, HEVC, raw. */
        fun chooseFormat(formats:List<Format>,width:Int,height:Int,fps:Int,codec:String):Format{
            formats.filter{it.width==width&&it.height==height&&it.fps==fps&&it.codec.equals(codec,true)}.minByOrNull{it.maxFrameSize}?.let{return it}
            val preference=listOf(codec.uppercase(),"MJPEG","H264","HEVC","YUYV","NV12","UYVY")
            val codecs=formats.map{it.codec.uppercase()}.toSet()
            val best=preference.firstOrNull{it in codecs}?:formats.first().codec.uppercase()
            return formats.filter{it.codec.equals(best,true)}.minByOrNull{abs(it.width-width)+abs(it.height-height)+abs(it.fps-fps)*8}!!
        }
        /** Reads the fields of a UVC probe/commit answer that decide what and how the camera will stream. */
        /**
         * Valid bmHint values are below 256 (UVC 1.5 defines 5 bits). A larger one is the Elgato answer with the format
         * index in its high byte: move it to bFormatIndex and restore bmHint = 1. Returns whether it changed anything.
         */
        internal fun fixShiftedProbe(p:ByteArray):Boolean{
            if(p.size<4)return false
            val hint=(p[0].toInt() and 0xff) or ((p[1].toInt() and 0xff) shl 8)
            if(hint<=255)return false
            p[2]=p[1];p[0]=1;p[1]=0
            return true
        }
        internal fun parseCommitted(probe:ByteArray):Committed{
            var payload=u32(probe,22)
            // Some cameras fill the upper half with 0xffff (Linux uvcvideo masks it the same way).
            if((payload and 0xffff0000L)==0xffff0000L)payload=payload and 0xffffL
            return Committed(probe.getOrZero(2),probe.getOrZero(3),u32(probe,4),u32(probe,18),payload.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
        /** The advertised mode the camera committed to; [requested] when its answer names no advertised mode. */
        internal fun committedFormat(formats:List<Format>,requested:Format,c:Committed):Format{
            val frame=formats.filter{it.index==c.formatIndex&&it.frameIndex==c.frameIndex}
            if(frame.isEmpty())return requested
            val fps=if(c.interval>0)Math.round(10_000_000.0/c.interval).toInt().coerceAtLeast(1) else requested.fps
            val base=frame.minByOrNull{abs(it.fps-fps)}!!
            return if(base.index==requested.index&&base.frameIndex==requested.frameIndex&&fps==requested.fps)requested else base.copy(fps=fps)
        }
        /** Index of the smallest packet capacity that carries [maxPayload]; the largest one when none does. */
        internal fun pickIsoAlternate(capacities:List<Int>,maxPayload:Int):Int{
            if(capacities.isEmpty())return -1
            val fits=capacities.withIndex().filter{it.value>=maxPayload}.minByOrNull{it.value}
            return (fits?:capacities.withIndex().maxBy{it.value}).index
        }
        /**
         * wBytesPerInterval from the SuperSpeed endpoint companion descriptor of [address] in the given alternate
         * setting. Every alternate setting repeats the same endpoint address with a different size, so the
         * interface/alternate setting has to be tracked; null for USB 2 devices.
         */
        internal fun superSpeedBytesPerInterval(d:ByteArray,interfaceNumber:Int,alternateSetting:Int,address:Int):Int?{
            var i=0;var inAlt=false
            while(i+2<d.size){
                val len=d[i].toInt() and 0xff;if(len<2||i+len>d.size)break
                when(d[i+1].toInt() and 0xff){
                    0x04->if(len>=4)inAlt=(d[i+2].toInt() and 0xff)==interfaceNumber&&(d[i+3].toInt() and 0xff)==alternateSetting
                    0x05->if(inAlt&&len>=7&&(d[i+2].toInt() and 0xff)==address){
                        val next=i+len
                        if(next+6<=d.size&&(d[next].toInt() and 0xff)>=6&&(d[next+1].toInt() and 0xff)==0x30){
                            val w=u16(d,next+4)
                            return if(w>0)w else null
                        }
                        return null
                    }
                }
                i+=len
            }
            return null
        }
        /** bcdUVC from the VideoControl interface header. */
        fun probeLength(bytes:ByteArray):Int{
            var i=0;var subclass=-1
            while(i+2<bytes.size){
                val len=bytes[i].toInt() and 0xff;if(len<2||i+len>bytes.size)break
                val type=bytes[i+1].toInt() and 0xff
                if(type==0x04&&len>=7)subclass=if((bytes[i+5].toInt() and 0xff)==14)(bytes[i+6].toInt() and 0xff) else -1
                if(type==0x24&&subclass==1&&len>=5&&(bytes[i+2].toInt() and 0xff)==0x01){
                    val bcd=u16(bytes,i+3)
                    return when{bcd>=0x0150->48;bcd>=0x0110->34;else->26}
                }
                i+=len
            }
            return 26
        }
        fun listFormats(device:UsbDevice,connection:UsbDeviceConnection):List<Format> = parseFormats(connection.rawDescriptors)
        internal fun parseFormats(bytes:ByteArray):List<Format>{
            val result=mutableListOf<Format>();var i=0;var formatIndex=0;var codec=""
            val colors=mutableMapOf<Int,UvcColor>()
            while(i+2<bytes.size){
                val len=bytes[i].toInt() and 0xff;if(len<2||i+len>bytes.size)break
                val type=bytes[i+1].toInt() and 0xff
                if(type==0x24&&len>=3){
                    when(bytes[i+2].toInt() and 0xff){
                        0x06->{formatIndex=bytes.getOrZero(i+3);codec="MJPEG"}
                        // VS_COLORFORMAT follows a format's frames: bColorPrimaries@3, bTransferCharacteristics@4, bMatrixCoefficients@5.
                        0x0D->if(formatIndex>0&&len>=6)colors[formatIndex]=UvcColor(bytes.getOrZero(i+3),bytes.getOrZero(i+4),bytes.getOrZero(i+5))
                        0x04->{formatIndex=bytes.getOrZero(i+3);codec=guidFourcc(bytes,i+5)}
                        0x10->{formatIndex=bytes.getOrZero(i+3);codec=guidFourcc(bytes,i+5).ifBlank{"H264"}}
                        // UVC 1.5 H.264 (VS_FORMAT_H264 / VS_FRAME_H264), used by newer webcams such as the Insta360 Link.
                        0x13->{formatIndex=bytes.getOrZero(i+3);codec="H264"}
                        0x14->if(formatIndex>0&&len>=44){
                            // wWidth@4, wHeight@6, dwDefaultFrameInterval@39, bNumFrameIntervals@43, intervals @44.
                            val frameIndex=bytes.getOrZero(i+3);val w=u16(bytes,i+4);val h=u16(bytes,i+6)
                            val maxFrame=(w*h*2L).toInt().coerceAtLeast(256*1024)
                            val count=bytes.getOrZero(i+43)
                            val intervals=if(count==0)listOf(u32(bytes,i+39)) else List(count.coerceAtMost(32)){n->u32(bytes,i+44+n*4)}
                            intervals.filter{it>0&&i+44<=bytes.size}.forEach{interval->result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                        }
                        0x07,0x05,0x11->{
                            if(formatIndex>0&&len>=26){
                                // MJPEG/uncompressed: dwMaxVideoFrameBufferSize@17, bFrameIntervalType@25.
                                // Frame-based (H.264/HEVC, 0x11): no buffer size, bFrameIntervalType@21. Intervals start @26 in both.
                                val frameBased=(bytes[i+2].toInt() and 0xff)==0x11
                                val frameIndex=bytes.getOrZero(i+3);val w=u16(bytes,i+5);val h=u16(bytes,i+7)
                                val maxFrame=(if(frameBased)w*h*2L else u32(bytes,i+17)).toInt().coerceAtLeast(256*1024)
                                val count=bytes.getOrZero(if(frameBased)i+21 else i+25)
                                if(count==0){val interval=u32(bytes,i+26);if(interval>0)result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                                else repeat(count.coerceAtMost(32)){n->val interval=u32(bytes,i+26+n*4);if(interval>0)result+=Format(formatIndex,frameIndex,w,h,(10_000_000/interval).toInt().coerceAtLeast(1),codec,maxFrame)}
                            }
                        }
                    }
                }
                i+=len
            }
            return result.distinctBy{listOf(it.index,it.frameIndex,it.width,it.height,it.fps,it.codec)}.map{it.copy(color=colors[it.index])}
        }
        private fun guidFourcc(b:ByteArray,o:Int):String{
            if(o+3>=b.size)return "UNKNOWN"
            val chars=String(byteArrayOf(b[o],b[o+1],b[o+2],b[o+3]),Charsets.US_ASCII).trim('\u0000',' ')
            return when(chars.uppercase()){"YUY2"->"YUYV";"YUYV"->"YUYV";"UYVY"->"UYVY";"NV12"->"NV12";"I420","IYUV"->"I420";"H264","AVC1"->"H264";"HEVC","H265"->"HEVC";else->chars.ifBlank{"UNKNOWN"}}
        }
        private fun ByteArray.getOrZero(i:Int)=getOrNull(i)?.toInt()?.and(0xff)?:0
        private fun u16(b:ByteArray,i:Int)=if(i+1<b.size)(b[i].toInt() and 0xff) or ((b[i+1].toInt() and 0xff) shl 8) else 0
        private fun u32(b:ByteArray,i:Int):Long{if(i+3>=b.size)return 0;return (b[i].toLong() and 255) or ((b[i+1].toLong() and 255) shl 8) or ((b[i+2].toLong() and 255) shl 16) or ((b[i+3].toLong() and 255) shl 24)}
        private fun u8(b:ByteArray,i:Int,v:Int){if(i<b.size)b[i]=v.toByte()}
        private fun u16le(b:ByteArray,i:Int,v:Int){u8(b,i,v);u8(b,i+1,v ushr 8)}
        private fun u32le(b:ByteArray,i:Int,v:Long){for(n in 0..3)u8(b,i+n,(v ushr (8*n)).toInt())}
    }
}
