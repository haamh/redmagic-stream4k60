package com.stream4k60.app.engine

import android.content.Context
import android.media.MediaFormat
import android.view.Surface
import com.stream4k60.app.data.model.*
import java.util.concurrent.atomic.AtomicLong

/** One hardware encoder session. Exactly one stream target and/or one recorder can consume its encoded samples. */
class StreamOutputSession(
    private val context: Context,
    /** RTMP connection changes (connecting, publishing, reconnecting, gave up), called on the publisher's threads. */
    private val onPublisherState: (RtmpPublisher.State, String) -> Unit = { _, _ -> }
){
    data class Stats(val bytesSent:Long=0, val encodedFrames:Long=0, val bitrate:Long=0, val droppedFrames:Long=0)
    @Volatile var stats=Stats(); private set
    private var encoder:HardwareVideoEncoder?=null
    private var audio:HardwareAudioEncoder?=null
    private var rtmp:RtmpPublisher?=null
    private var hls:YouTubeHlsPublisher?=null
    private var muxer:RecordingMuxer?=null
    private var videoConfig:RtmpPublisher.VideoCodecConfig?=null
    private var audioConfig:RtmpPublisher.AudioCodecConfig?=null
    private var lastVideoFormat:MediaFormat?=null
    private var lastAudioFormat:MediaFormat?=null
    private var replayEnabled=false
    private val bytes=AtomicLong(); private val frames=AtomicLong()
    /** Encoded video bytes so far; the engine samples it once a second for the live bitrate. */
    fun encodedBytes()=bytes.get()
    data class NetworkStats(val sentBytes:Long,val queuedPackets:Int,val droppedFrames:Long,val sentFrames:Long)
    /** What the RTMP connection has sent, has waiting, and dropped because the upload fell behind (RTMP only). */
    fun networkStats():NetworkStats?=rtmp?.let{NetworkStats(it.sentBytes(),it.queuedPackets(),it.droppedVideoFrames,it.sentVideoFrames)}
    /** Video frames (and setup packets) the encoder has produced. */
    fun encodedFrames()=frames.get()

    suspend fun prepareAndStart(config:StreamConfig):Surface = prepare(config, null)

    suspend fun prepareAndStart(recording:RecordingConfig, audioDevice:android.media.AudioDeviceInfo?=null):Surface {
        val cfg=StreamConfig(
            outputWidth=recording.outputWidth,
            outputHeight=recording.outputHeight,
            fps=recording.fps,
            bitrate=recording.bitrate,
            outputCodec=recording.codec,
            audioDeviceIds=recording.audioDeviceIds,
            audioInputs=recording.audioInputs,
            monitorDeviceId=recording.monitorDeviceId,
            monitorEnabled=recording.monitorEnabled,
            color=recording.color
        )
        return prepare(cfg, recording, audioDevice)
    }

    private suspend fun prepare(config:StreamConfig, recording:RecordingConfig?, audioDevice:android.media.AudioDeviceInfo?=null):Surface {
        require(encoder==null) { "Output session is already running" }
        val mime=when(config.outputCodec){OutputCodec.HEVC->"video/hevc";OutputCodec.AV1->"video/av01";else->"video/avc"}
        // Server address only: the stream key never goes into the log.
        StreamLog.add("Start ${config.protocol} to ${config.ingestionUrl.substringBefore('?')}: ${config.outputWidth}x${config.outputHeight}@${config.fps} ${config.outputCodec}, ${config.bitrate/1000} Kbps video, ${config.audioBitrate/1000} Kbps audio")
        require(HardwareVideoEncoder.isSupported(mime)){"$mime encoder unavailable on this device"}
        require(config.outputWidth>0 && config.outputHeight>0 && config.fps>0)
        val bitrate=if(config.bitrate>0)config.bitrate else defaultBitrate(config.outputCodec,config.outputWidth,config.outputHeight,config.fps)
        // Colour (Settings → Video → Colour / HDR). HDR and 10-bit need HEVC: Android's H.264 encoders are 8-bit only.
        require(!(config.color.hdr&&config.outputCodec==OutputCodec.H264)){"HDR output needs the HEVC encoder: choose HEVC in Settings → Output, or turn off HDR output in Settings → Video."}
        val color=if(config.color.tenBit&&config.outputCodec==OutputCodec.H264){StreamLog.add("10-bit colour needs HEVC: H.264 stream sent 8-bit");config.color.copy(tenBit=false)}else config.color
        NativeEngine.setOutputColor(color.transfer,color.tenBit,color.sdrWhiteLevel.toFloat(),color.hdrNominalPeak.toFloat())
        encoder=HardwareVideoEncoder(HardwareVideoEncoder.Config(mime,config.outputWidth,config.outputHeight,config.fps,bitrate,config.keyframeInterval,if(config.rateControl==RateControl.VBR)android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR else android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,tenBit=color.tenBit,transfer=color.transfer,fullRange=color.fullRange,hdrPeak=color.hdrNominalPeak),
            onSample={sample->
                frames.incrementAndGet(); bytes.addAndGet(sample.data.size.toLong())
                if (replayEnabled) ReplayBufferController.addVideo(sample, lastVideoFormat)
                when(config.protocol){
                    StreamProtocol.HLS -> hls?.video(sample)
                    StreamProtocol.RTMP, StreamProtocol.RTMPS -> if(rtmp!=null) rtmp?.sendVideo(sample,videoConfig)
                    else -> Unit
                }
                muxer?.writeVideo(sample)
            },
            onFormat={format->
                lastVideoFormat=format
                videoConfig=extractCodec(format, config.outputCodec)
                muxer?.setVideoFormat(format)
                if(config.protocol==StreamProtocol.HLS) hls?.setVideoCodecConfig(extractVideoCodecConfigBytes(format))
            })
        val surface=encoder!!.prepare()
        check(NativeEngine.setEncoderSurface(surface)) { "GPU compositor could not attach the hardware encoder surface" }
        StreamLog.add("Encoder input: ${NativeEngine.encoderSurfaceInfo()}")
        if(recording!=null){
            muxer=RecordingMuxer(recording.outputPath)
        } else {
            when(config.protocol){
                StreamProtocol.HLS -> hls=YouTubeHlsPublisher(config.ingestionUrl).also{it.start(mime)}
                StreamProtocol.RTMP, StreamProtocol.RTMPS -> RtmpPublisher{st,msg->StreamLog.add("RTMP $st: $msg");onPublisherState(st,msg)}.also{rtmp=it}.let{ok -> ok.onKeyframeNeeded={encoder?.requestKeyframe()}
                    // HDR: Enhanced RTMP colorInfo (BT.2020 = 9, PQ = 16 / HLG = 18), as OBS sends to YouTube.
                    if(color.hdr&&config.outputCodec==OutputCodec.HEVC)ok.colorInfo=RtmpPublisher.ColorInfo(10,9,if(color.transfer==1)16 else 18,9,if(color.transfer==1)color.hdrNominalPeak else 1000);ok.configureReconnect(config.autoReconnect,config.reconnectDelayMs,config.maxReconnectAttempts);check(ok.start(config.ingestionUrl,config.streamName,config.protocol==StreamProtocol.RTMPS,config.outputWidth,config.outputHeight,config.fps,config.outputCodec)) { "RTMP(S) publisher could not start: ${ok.lastError ?: "unknown error"}" }}
                else -> error("Unsupported output protocol: ${config.protocol}")
            }
        }
        encoder!!.start()
        // The RTMP publisher can ask for an IDR while the encoder is only configured, before MediaCodec.start().
        // That request is easy to lose on hardware encoders, so request a fresh keyframe once the encoder is actually running.
        if (config.protocol == StreamProtocol.RTMP || config.protocol == StreamProtocol.RTMPS) {
            runCatching { encoder?.requestKeyframe() }
            StreamLog.add("Video encoder: requested initial keyframe after start")
        }
        val routes = if (config.audioInputs.isNotEmpty()) config.audioInputs else resolveAudioDeviceIds(config.audioDeviceIds, audioDevice).map { id -> com.stream4k60.app.data.model.AudioInputRoute("audio_device_$id", id) }
        if (routes.isNotEmpty() || config.audioPlaybackCaptureEnabled) {
            audio=HardwareAudioEncoder(context,bitrate=config.audioBitrate,onSample={sample->
                if (replayEnabled) ReplayBufferController.addAudio(sample, lastAudioFormat)
                when(config.protocol){
                    StreamProtocol.HLS -> hls?.audio(sample)
                    StreamProtocol.RTMP,StreamProtocol.RTMPS -> rtmp?.sendAudio(sample,audioConfig)
                    else -> Unit
                }
                muxer?.writeAudio(sample)
            },onFormat={format->lastAudioFormat=format;audioConfig=extractAac(format);muxer?.setAudioFormat(format)})
            runCatching {
                audio!!.start(
                    routes,
                    monitorDeviceId = config.monitorDeviceId,
                    monitorEnabled = config.monitorEnabled,
                    playbackCaptureEnabled = config.audioPlaybackCaptureEnabled
                )
            }.onFailure { stop(); throw it }
        }
        return surface
    }

    fun awaitPublisherReady(timeoutMs:Long):Boolean {
        if (rtmp != null) {
            // RTMP "Publishing" only means the handshake/publish command succeeded. Do not report LIVE until the
            // writer has actually transmitted the first video frame; otherwise a dead encoder path can look healthy.
            val end=System.nanoTime()+timeoutMs*1_000_000L
            while(System.nanoTime()<end){
                if(rtmp?.sentVideoFrames?.let { it > 0L }==true)return true
                if(rtmp?.isPublishing()!=true)return false
                Thread.sleep(20)
            }
            return false
        }
        // HLS readiness is stronger already: this latch is released only after YouTube has acknowledged a segment.
        return hls?.awaitFirstSegment(timeoutMs) ?: true
    }
    fun enableRecording(recording:RecordingConfig){
        check(encoder!=null){"Encoder is not running"};if(muxer!=null)return
        require(recording.outputPath.isNotBlank()){"Recording path is empty"}
        muxer=RecordingMuxer(recording.outputPath)
        lastVideoFormat?.let{muxer?.setVideoFormat(it)};lastAudioFormat?.let{muxer?.setAudioFormat(it)}
    }
    fun disableRecording(){muxer?.stop();muxer=null}
    fun pauseRecording(){muxer?.setPaused(true)}
    fun resumeRecording(){muxer?.setPaused(false)}
    fun startReplayBuffer(seconds:Int,sizeMb:Int){ReplayBufferController.start(seconds,sizeMb);replayEnabled=true}
    fun stopReplayBuffer(){replayEnabled=false;ReplayBufferController.stop()}
    fun updateAudioRoute(route: AudioInputRoute): Boolean = audio?.setInputConfig(route) ?: false
    fun audioPeak(sourceId: String): Float = audio?.peak(sourceId) ?: 0f
    /** Audio problems since the last call, for the 10-s stream-log line; empty when the audio ran clean. */
    fun audioStats():String{
        val enc=audio?.takeStats().orEmpty()
        val h=NativeAudioGraph.currentHandle()
        val mix=if(h!=0L)runCatching{NativeAudioMixer.takeDebugStats(h)}.getOrDefault("") else ""
        return listOf(enc,mix).filter{it.isNotBlank()}.joinToString("; ")
    }
    fun stopStreamingOnly(){rtmp?.stop();rtmp=null;hls?.stop();hls=null}

    fun stop(){if(rtmp!=null||hls!=null)StreamLog.add("Stop after ${bytes.get()/1024} KB of encoded video");replayEnabled=false;runCatching{audio?.stop()};NativeEngine.setEncoderSurface(null);runCatching{encoder?.stop()};runCatching{rtmp?.stop()};runCatching{hls?.stop()};runCatching{muxer?.stop()};audio=null;encoder=null;rtmp=null;hls=null;muxer=null}
    fun requestKeyframe(){encoder?.requestKeyframe()}
    fun setBitrate(v:Int){encoder?.setBitrate(v)}
    private fun resolveAudioDeviceIds(ids:List<Int>,fallback:android.media.AudioDeviceInfo?):List<Int>{
        if(ids.isNotEmpty())return ids
        fallback?.let{return listOf(fallback.id)}
        return emptyList()
    }

    private fun extractVideoCodecConfigBytes(f:MediaFormat): ByteArray? {
        val a = f.getByteBuffer("csd-0")?.duplicate() ?: return null
        val out = ByteArray(a.remaining())
        a.get(out)
        return out
    }

    private fun extractCodec(f:MediaFormat, codec:OutputCodec):RtmpPublisher.VideoCodecConfig?{
        val b0=f.getByteBuffer("csd-0")?.duplicate() ?: return null
        val csd=ByteArray(b0.remaining()).also{b0.get(it)}
        return when(codec){
            OutputCodec.H264 -> {
                // csd-0/csd-1 are Annex B (with 00 00 00 01 start codes), and some encoders put SPS and PPS both in
                // csd-0: take the bare SPS (NAL type 7) and PPS (type 8) the FLV decoder record needs.
                val more=f.getByteBuffer("csd-1")?.duplicate()?.let{b->ByteArray(b.remaining()).also{b.get(it)}}
                val nals=RtmpPublisher.AnnexB.nalus(csd)+(more?.let{RtmpPublisher.AnnexB.nalus(it)}?:emptyList())
                val sps=nals.firstOrNull{it.isNotEmpty()&&(it[0].toInt() and 0x1F)==7}?:return null
                val pps=nals.firstOrNull{it.isNotEmpty()&&(it[0].toInt() and 0x1F)==8}?:return null
                RtmpPublisher.VideoCodecConfig(OutputCodec.H264,sps=sps,pps=pps)
            }
            OutputCodec.HEVC -> {
                val profile=runCatching{f.getInteger(MediaFormat.KEY_PROFILE)}.getOrDefault(1)
                val level=runCatching{f.getInteger(MediaFormat.KEY_LEVEL)}.getOrDefault(153).let{if(it in 30..255)it else 153}
                RtmpPublisher.VideoCodecConfig(OutputCodec.HEVC,hvcC=RtmpPublisher.HevcConfiguration.fromCsd(csd,profile,level))
            }
            else -> null
        }
    }
    private fun extractAac(f:MediaFormat):RtmpPublisher.AudioCodecConfig?{val b=f.getByteBuffer("csd-0")?.duplicate()?:return null;val x=ByteArray(b.remaining());b.get(x);return RtmpPublisher.AudioCodecConfig(x)}
    companion object{fun defaultBitrate(codec:OutputCodec,w:Int,h:Int,fps:Int)=when{w>=3840&&fps>=60&&codec==OutputCodec.HEVC->35_000_000;w>=3840&&fps>=60->40_000_000;w>=2560&&fps>=60&&codec==OutputCodec.HEVC->20_000_000;w>=2560&&fps>=60->24_000_000;h>=1080&&fps>=60&&codec==OutputCodec.HEVC->10_000_000;h>=1080&&fps>=60->12_000_000;else->6_000_000}}
}
