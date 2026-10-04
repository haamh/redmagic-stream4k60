package com.stream4k60.app.engine

import android.content.Context
import com.stream4k60.app.data.model.*
import com.stream4k60.app.youtube.YouTubeAuthSession
import com.stream4k60.app.youtube.YouTubeService
import com.stream4k60.app.service.RecordingService
import com.stream4k60.app.service.StreamingService
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamEngineImpl @Inject constructor(@ApplicationContext private val context:Context):StreamEngine{
    private val _streamState=MutableStateFlow(StreamState.IDLE);override val streamState:StateFlow<StreamState> = _streamState.asStateFlow()
    private val _recordState=MutableStateFlow(RecordState.IDLE);override val recordState:StateFlow<RecordState> = _recordState.asStateFlow()
    private val _streamStats=MutableStateFlow(StreamStats());override val streamStats:StateFlow<StreamStats> = _streamStats.asStateFlow()
    private val _recordStats=MutableStateFlow(RecordStats());override val recordStats:StateFlow<RecordStats> = _recordStats.asStateFlow()
    private val _replayBufferActive=MutableStateFlow(false);override val replayBufferActive:StateFlow<Boolean> = _replayBufferActive.asStateFlow()
    private var session:StreamOutputSession?=null
    private var activeStream:StreamConfig?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var statsJob:Job?=null

    /** Publisher events after the stream went live: a dropped connection shows as RECONNECTING, giving up as ERROR. */
    private fun onPublisherState(state:RtmpPublisher.State,message:String){
        val live=_streamState.value==StreamState.LIVE||_streamState.value==StreamState.RECONNECTING
        if(!live)return
        when(state){
            RtmpPublisher.State.CONNECTING->_streamState.value=StreamState.RECONNECTING
            RtmpPublisher.State.PUBLISHING->_streamState.value=StreamState.LIVE
            RtmpPublisher.State.ERROR->{
                Timber.w("Stream ended: %s",message)
                scope.launch{
                    statsJob?.cancel()
                    runCatching{session?.stop()};session=null;activeStream=null
                    StreamingService.stop(context)
                    _streamState.value=StreamState.ERROR
                }
            }
            else->Unit
        }
    }

    private fun startStats(){
        statsJob?.cancel()
        statsJob=scope.launch{
            val started=System.currentTimeMillis()
            var last=session?.encodedBytes()?:0L
            var lastSent=session?.networkStats()?.sentBytes?:0L
            val encoderInputAtStart=runCatching{NativeEngine.getEncoderFrames()}.getOrDefault(0L)
            var tick=0
            var encodedSum=0L;var sentSum=0L
            // Dynamic bitrate (OBS's congestion control): lower the encoder bitrate while the upload can't keep up,
            // raise it back step by step once the send queue has stayed empty for a while.
            val target=activeStream?.bitrate?:0
            val dynamic=activeStream?.dynamicBitrate==true&&target>0
            var current=target
            var calmSeconds=0
            var lastDropped=0L
            while(isActive){
                delay(1000)
                val s=session?:break
                val now=s.encodedBytes()
                val net=s.networkStats()
                val sent=net?.sentBytes?:now
                // The status bar shows what actually went out (OBS shows the network rate), not the encoder's output.
                _streamStats.value=StreamStats(bitrate=(sent-lastSent)*8,droppedFrames=net?.droppedFrames?:0L,totalFrames=s.encodedFrames(),
                    duration=System.currentTimeMillis()-started,totalBytes=now,encoderBitrate=(now-last)*8,sentFrames=net?.sentFrames?:0L,
                    sentBytes=sent,queuedPackets=net?.queuedPackets?:0,
                    encoderInputFrames=runCatching{NativeEngine.getEncoderFrames()}.getOrDefault(encoderInputAtStart)-encoderInputAtStart)
                if(dynamic&&net!=null){
                    val congested=net.queuedPackets>90||net.droppedFrames>lastDropped
                    if(congested){
                        calmSeconds=0
                        // Floor 15 % of the setting (at least 2.5 Mbps): at 30 % a weak Wi-Fi link still couldn't keep up.
                        val lower=maxOf(maxOf(target*15/100,minOf(target,2_500_000)),current*8/10)
                        if(lower<current){current=lower;s.setBitrate(current);StreamLog.add("Dynamic bitrate: upload behind (send queue ${net.queuedPackets}), video lowered to ${current/1000} Kbps")}
                    }else if(net.queuedPackets<15&&current<target){
                        if(++calmSeconds>=5){
                            calmSeconds=0
                            current=minOf(target,current+maxOf(target/20,current/10))
                            s.setBitrate(current);StreamLog.add("Dynamic bitrate: upload keeping up, video raised to ${current/1000} Kbps")
                        }
                    }else calmSeconds=0
                    lastDropped=net.droppedFrames
                }
                _streamStats.value=_streamStats.value.copy(currentVideoBitrate=current.toLong(),targetVideoBitrate=target.toLong())
                encodedSum+=now-last;sentSum+=sent-lastSent
                last=now;lastSent=sent
                // Every 10 s in stream-log: if "sent" stays below "encoded" and the queue grows, the upload is the limit.
                if(++tick%10==0){
                    val audioIssues=runCatching{s.audioStats()}.getOrDefault("")
                    StreamLog.add("Encoded ${encodedSum*8/10_000} Kbps, sent ${sentSum*8/10_000} Kbps, send queue ${net?.queuedPackets?:0} packets, dropped ${net?.droppedFrames?:0} frames (network); audio ${audioIssues.ifBlank{"clean"}}")
                    encodedSum=0L;sentSum=0L
                    // Where each frame's time goes (latch + upload, canvas, encoder copy, preview), to find render drops.
                    runCatching{StreamLog.add("Renderer: ${NativeEngine.describeRender()}")}
                }
            }
        }
    }

    override suspend fun startStreaming(config:StreamConfig){
        require(config.ingestionUrl.isNotBlank()){"Set a server in Settings → Stream first"}
        // Every service (YouTube, Twitch, Facebook, Kick, custom) takes RTMP(S); YouTube's API broadcasts can also use HLS.
        require(config.protocol==StreamProtocol.RTMP||config.protocol==StreamProtocol.RTMPS||(config.service==StreamService.YOUTUBE&&config.protocol==StreamProtocol.HLS)){"Unsupported streaming protocol for this destination"}
        require(clampVideoSize(config.outputWidth, config.outputHeight) == (config.outputWidth to config.outputHeight) && config.fps in 1..120){"Streaming output is limited to 3840 × 2160 (or 2160 × 3840 portrait) at 120 FPS"}
        if(config.service==StreamService.YOUTUBE) require(config.fps<=60){"YouTube Live supports up to 60 FPS. Choose a custom RTMP(S) destination for a service that accepts 120 FPS."}
        if(_streamState.value==StreamState.LIVE)return
        startCancelled=false
        _streamState.value=StreamState.CONNECTING
        ContextCompat.startForegroundService(context,android.content.Intent(context,StreamingService::class.java))
        withContext(Dispatchers.IO){
            runCatching{
                check(session==null){"An output session is already running; stop the existing stream/recording before changing the encoder format"}
                // A local reference: Stop may clear `session` while this start is still connecting.
                val s=StreamOutputSession(context,::onPublisherState);session=s
                s.prepareAndStart(config)
                check(!startCancelled){"Stopped while connecting"}
                check(s.awaitPublisherReady(if(config.protocol==StreamProtocol.HLS)15_000 else 20_000)){"YouTube ingestion did not become ready"}
                // Streaming only sends video to YouTube. Taking the broadcast live is Go Live (MainStudioViewModel.goLive);
                // doing it here kept the button on "Connecting" for up to a minute and went live without being asked.
                check(!startCancelled){"Stopped while connecting"}
                activeStream=config;_streamState.value=StreamState.LIVE;startStats()
            }.onFailure{
                Timber.e(it,"Stream start failed");session?.stop();session=null;activeStream=null;StreamingService.stop(context)
                _streamState.value=if(startCancelled)StreamState.IDLE else StreamState.ERROR;throw it
            }
        }
    }

    /** Set when Stop is pressed while a start is still connecting. */
    @Volatile private var startCancelled=false

    override suspend fun stopStreaming(){
        if(_streamState.value==StreamState.CONNECTING)startCancelled=true
        _streamState.value=StreamState.STOPPING
        statsJob?.cancel();_streamStats.value=StreamStats()
        withContext(Dispatchers.IO){
            val s=session
            if(_recordState.value==RecordState.RECORDING||_recordState.value==RecordState.PAUSED){s?.stopStreamingOnly()}else{s?.stop();session=null}
            activeStream=null
            // Stopping the video doesn't end the YouTube broadcast; End Live does.
            StreamingService.stop(context)
        }
        _streamState.value=StreamState.IDLE
    }

    override suspend fun startRecording(config:RecordingConfig){
        val path=config.outputPath.ifBlank{java.io.File(context.getExternalFilesDir("recordings"),"recording_${System.currentTimeMillis()}.mp4").apply{parentFile?.mkdirs()}.absolutePath}
        val actual=config.copy(outputPath=path)
        withContext(Dispatchers.IO){
            runCatching{
                if(session==null){session=StreamOutputSession(context);session!!.prepareAndStart(actual)}else{session!!.enableRecording(actual)}
                ContextCompat.startForegroundService(context,android.content.Intent(context,RecordingService::class.java))
                _recordState.value=RecordState.RECORDING
            }.onFailure{_recordState.value=RecordState.ERROR;Timber.e(it,"Recording start failed");throw it}
        }
    }

    override suspend fun stopRecording(){
        _recordState.value=RecordState.STOPPING
        withContext(Dispatchers.IO){session?.disableRecording();if(_streamState.value==StreamState.IDLE){session?.stop();session=null} ; RecordingService.stop(context)}
        _recordState.value=RecordState.IDLE
    }
    override suspend fun pauseRecording(){if(_recordState.value==RecordState.RECORDING){session?.pauseRecording();_recordState.value=RecordState.PAUSED}}
    override suspend fun resumeRecording(){if(_recordState.value==RecordState.PAUSED){session?.resumeRecording();_recordState.value=RecordState.RECORDING}}
    override suspend fun startReplayBuffer(maxSeconds:Int,maxSizeMb:Int){if(session==null)error("Start streaming or recording before enabling Replay Buffer");session!!.startReplayBuffer(maxSeconds,maxSizeMb);_replayBufferActive.value=true}
    override suspend fun stopReplayBuffer(){session?.stopReplayBuffer()?:ReplayBufferController.stop();_replayBufferActive.value=false}
    override suspend fun saveReplayBuffer(){ReplayBufferController.save(context)}
    override suspend fun takeScreenshot(outputPath:String){ScreenshotController.request(outputPath)}
    override fun updateAudioRoute(route:AudioInputRoute):Boolean = session?.updateAudioRoute(route) ?: false
    override fun audioPeak(sourceId:String):Float = session?.audioPeak(sourceId) ?: NativeAudioGraph.peak(sourceId)
}
