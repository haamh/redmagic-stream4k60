package com.stream4k60.app.engine

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class MediaSourceController(private val context:Context,private val scope:CoroutineScope){
    private val players=ConcurrentHashMap<String,ExoPlayer>()
    /** The media URL alone determines whether the decoder/player has to be rebuilt. Mixer and
     * presentation controls are applied live and must not interrupt playback while a slider moves. */
    private val mediaUris=ConcurrentHashMap<String,String>()
    /** Whether each source was shown at the last sync: OBS restarts a video when it becomes active again. */
    private val lastShown=ConcurrentHashMap<String,Boolean>()
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    init{
        // OBS's media controls (Properties and hotkeys): play / pause, restart, stop, previous / next in a playlist, seek.
        scope.launch(Dispatchers.Main){SourceMediaCommands.requests.collect{r->players[r.sourceId]?.let{p->command(r.sourceId,p,r)}}}
        // Position and state for the controls, twice a second.
        main.post(object:Runnable{override fun run(){players.forEach{(id,p)->runCatching{SourceMediaCommands.report(id,SourceMediaCommands.State(p.playWhenReady&&p.playbackState!=Player.STATE_ENDED,p.playbackState==Player.STATE_IDLE||p.playbackState==Player.STATE_ENDED&&!p.playWhenReady,p.currentPosition.coerceAtLeast(0),p.duration.takeIf{it>0}?:0))}};main.postDelayed(this,500)}})
    }
    private fun command(id:String,p:ExoPlayer,r:SourceMediaCommands.Request){
        when(r.command){
            SourceMediaCommands.Command.PLAY_PAUSE->{if(p.playbackState==Player.STATE_ENDED||p.playbackState==Player.STATE_IDLE){p.seekTo(0);p.prepare();p.playWhenReady=true}else p.playWhenReady=!p.playWhenReady}
            SourceMediaCommands.Command.RESTART->{NativeEngine.setSourceBlank(id,false);p.seekTo(0,0);if(p.playbackState==Player.STATE_IDLE)p.prepare();p.playWhenReady=true}
            SourceMediaCommands.Command.STOP->{p.playWhenReady=false;p.seekTo(0,0);NativeEngine.setSourceBlank(id,true)}
            SourceMediaCommands.Command.NEXT->if(p.hasNextMediaItem())p.seekToNextMediaItem()
            SourceMediaCommands.Command.PREVIOUS->if(p.hasPreviousMediaItem())p.seekToPreviousMediaItem() else p.seekTo(0)
            SourceMediaCommands.Command.SEEK->p.seekTo(r.positionMs.coerceAtLeast(0))
        }
        if(r.command!=SourceMediaCommands.Command.STOP&&r.command!=SourceMediaCommands.Command.PLAY_PAUSE)NativeEngine.setSourceBlank(id,false)
    }
    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>){
        // Hidden media keeps playing, audio included (hiding only takes the picture off the canvas). References show
        // another source's frames and get no player of their own.
        val wanted=sources.filter{it.type.equals("MEDIA",true)&&SourceReferences.targetOf(it.configJson)==null}
        val ids=wanted.map{it.id}.toSet()
        (mediaUris.keys+players.keys).filterNot{it in ids}.toSet().forEach(::stop)
        // OBS's "Close file when inactive": a hidden video is closed and opened again when shown.
        wanted.filter{!it.isVisible&&settings(it.configJson).optBoolean("closeWhenInactive",settings(it.configJson).optBoolean("close_when_inactive",false))}.forEach{if(mediaUris.containsKey(it.id))stop(it.id)}
        wanted.filterNot{!it.isVisible&&settings(it.configJson).optBoolean("closeWhenInactive",settings(it.configJson).optBoolean("close_when_inactive",false))}.forEach{src->
            val cfg=settings(src.configJson)
            // OBS's "Restart playback when source becomes active" (on by default there): shown again after being hidden.
            val wasShown=lastShown.put(src.id,src.isVisible)
            if(wasShown==false&&src.isVisible&&cfg.optBoolean("restartOnActivate",cfg.optBoolean("restart_on_activate",true)))players[src.id]?.let{p->NativeEngine.setSourceBlank(src.id,false);p.seekTo(0,0);p.playWhenReady=!paused}
            val media=mediaItemsFromConfig(cfg)
            val identity="${org.json.JSONArray(media)}|decoder=${cfg.optString("decoderPreference","hardware")}"
            if(mediaUris[src.id]!=null && mediaUris[src.id]!=identity) stop(src.id)
            val player=players[src.id]
            if(player==null && mediaUris[src.id]==null)start(src,cfg,media,identity)
            else if(player!=null){
                player.repeatMode=if(cfg.optBoolean("loop",true))Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                player.playbackParameters=PlaybackParameters(cfg.optDouble("playbackSpeed",1.0).toFloat().coerceIn(0.25f,4f))
                // The video's own size (it used to be forced to 1920 × 1080 on every sync).
                SourceNativeSizes.get(src.id)?.let{(w,h)->NativeEngine.setSourceBufferSize(src.id,w,h)}
                NativeEngine.setSourceEffectsFromConfig(src.id,src.configJson)
                SourceColors.apply(src.id,cfg)
            }
        }
    }
    private fun settings(configJson:String)=runCatching{JSONObject(configJson).let{it.optJSONObject("settings")?:it}}.getOrDefault(JSONObject())
    private fun mediaItemsFromConfig(cfg:JSONObject):List<String>{
        cfg.optString("url").takeIf(String::isNotBlank)?.let{return listOf(it)}
        val playlist=cfg.optJSONArray("playlist")?.let{items->(0 until items.length()).mapNotNull{items.optString(it).takeIf(String::isNotBlank)}}.orEmpty()
        return playlist.ifEmpty{listOf(cfg.optString("file").ifBlank{cfg.optString("local_file")}).filter(String::isNotBlank)}
    }
    private fun start(src:com.stream4k60.app.ui.main.SourceItem,cfg:JSONObject,media:List<String>,identity:String){
        mediaUris[src.id]=identity
        scope.launch(Dispatchers.Main.immediate){
            if(media.isEmpty()){SourceRuntimeErrors.report(src.id,"Choose a media file or enter a media URL in source properties.");return@launch}
            if(mediaUris[src.id]!=identity)return@launch
            val surface=NativeEngine.createSourceSurface(src.id)?:run{SourceRuntimeErrors.report(src.id,"Could not create the media source compositor surface.");return@launch}
            NativeEngine.setSourceEffectsFromConfig(src.id,src.configJson)
            SourceNativeSizes.get(src.id)?.let{(w,h)->NativeEngine.setSourceBufferSize(src.id,w,h)}
            StreamLog.add("Media source ${src.name} [${src.id.take(8)}] started (${media.firstOrNull()?.substringAfterLast('/')?.take(60).orEmpty()})")
            val p=runCatching{
                val audioId="media_audio_${src.id}"
                val audioProcessor=MediaAudioProcessor(audioId)
                val decoderPreference=cfg.optString("decoderPreference","hardware")
                // OBS's network buffering (MB) for URLs; Media3's default buffer otherwise.
                val bufferMb=cfg.optInt("bufferingMb",cfg.optInt("buffering_mb",0))
                val loadControl=if(bufferMb>0)androidx.media3.exoplayer.DefaultLoadControl.Builder().setTargetBufferBytes(bufferMb*1024*1024).setPrioritizeTimeOverSizeThresholds(false).build() else androidx.media3.exoplayer.DefaultLoadControl()
                ExoPlayer.Builder(context,MediaSourceRenderersFactory(context,audioProcessor,decoderPreference)).setLoadControl(loadControl).build().apply{
                    val player=this
                    addListener(object : Player.Listener {
                        private var retryDelayMs=1_000L
                        private val lostAt=ArrayDeque<Long>()
                        override fun onPlayerError(error: PlaybackException) {
                            val decoderLost=error.errorCode==PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED||error.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                            if(decoderLost){
                                // Two videos over the decoder budget took the decoder from each other about once a second, both
                                // glitching. After 3 losses in 30 s this one waits (the others keep playing) until a video is removed.
                                val now=android.os.SystemClock.elapsedRealtime();lostAt.addLast(now);while(lostAt.isNotEmpty()&&now-lostAt.first()>30_000L)lostAt.removeFirst()
                                if(lostAt.size>=3){
                                    lostAt.clear();waiting[src.id]=player
                                    StreamLog.add("Media source ${src.name}: decoder budget full, waiting so the other videos keep playing")
                                    SourceRuntimeErrors.report(src.id,"Paused: the tablet's hardware video decoder is full (about one 4K video at 120 fps in total, shared with the stream encoder), and this video kept taking it from another. For copies of the same video use Duplicate (reference); otherwise remove a video or use a lower-resolution file. It resumes when a video is removed.")
                                    return
                                }
                            }
                            SourceRuntimeErrors.report(src.id,when(error.errorCode){
                                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED->"Android took this video's decoder for another video in the scene or another app (e.g. CapCut or YouTube in the foreground). Retrying…"
                                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED->"No hardware video decoder free: the tablet's video engine handles about one 4K video at 120 fps in total, shared with the stream encoder. Use Duplicate (reference) for copies of the same video, or a lower-resolution file. Retrying…"
                                else->"Media playback failed: ${error.errorCodeName}."
                            })
                            // Android takes decoders back for other apps (e.g. YouTube Studio opened while streaming);
                            // that left the source dead. Prepare again once the decoder can be had, backing off to 10 s.
                            // A network stream that dropped reconnects after OBS's "Reconnect delay" (10 s by default).
                            if(error.errorCode in 2000..2999&&media.any{it.startsWith("http",true)||it.startsWith("rtmp",true)||it.startsWith("rtsp",true)}){
                                val delay=cfg.optInt("reconnectDelaySec",cfg.optInt("reconnect_delay_sec",10)).coerceIn(1,60)*1000L
                                SourceRuntimeErrors.report(src.id,"The media stream dropped (${error.errorCodeName}); reconnecting in ${delay/1000} s.")
                                android.os.Handler(player.applicationLooper).postDelayed({runCatching{player.prepare();player.playWhenReady=!paused}},delay)
                                return
                            }
                            if(error.errorCode==PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED||error.errorCode==PlaybackException.ERROR_CODE_DECODER_INIT_FAILED||error.errorCode==PlaybackException.ERROR_CODE_DECODING_FAILED){
                                val delay=retryDelayMs;retryDelayMs=(retryDelayMs*2).coerceAtMost(10_000L)
                                android.os.Handler(player.applicationLooper).postDelayed({runCatching{player.prepare();player.playWhenReady=!paused}},delay)
                            }
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY) { SourceRuntimeErrors.clear(src.id); retryDelayMs=1_000L; NativeEngine.setSourceBlank(src.id,false) }
                            // OBS's "Show nothing when playback ends" (on by default): the last frame doesn't stay up.
                            if (playbackState == Player.STATE_ENDED && cfg.optBoolean("clearOnMediaEnd",cfg.optBoolean("clear_on_media_end",true))) NativeEngine.setSourceBlank(src.id,true)
                        }
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            // Rotation-aware display size, used for layout when the source has no explicit size.
                            val rotated = videoSize.unappliedRotationDegrees % 180 != 0
                            val w = if (rotated) videoSize.height else videoSize.width
                            val h = if (rotated) videoSize.width else videoSize.height
                            val width = (w * videoSize.pixelWidthHeightRatio).toInt()
                            SourceNativeSizes.report(src.id, width, h)
                            if (width > 0 && h > 0) NativeEngine.setSourceBufferSize(src.id, width, h)
                            reportColor(player.videoFormat?.colorInfo)
                        }
                        // The video track's colour is known from the file before the first frame: set it then, so an HDR clip
                        // never starts (or restarts after a decoder reclaim) as SDR for a second.
                        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                            val group = tracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
                            val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: return
                            reportColor(group.getTrackFormat(index).colorInfo)
                        }
                        // The file's own colour (HDR10 / HLG / SDR); the renderer converts it to the output's. Missing colour
                        // info (each start / restart, until the decoder reports) keeps what is known instead of flipping to SDR.
                        private fun reportColor(info: androidx.media3.common.ColorInfo?) {
                            if (info == null && SourceColors.get(src.id) != null) return
                            val detected = SourceColors.describe(info)
                            if (SourceColors.get(src.id) != detected) StreamLog.add("Media source ${src.name}: ${detected.label}")
                            SourceColors.report(src.id, detected)
                            SourceColors.apply(src.id, cfg)
                        }
                    })
                    setMediaItems(media.map(MediaItem::fromUri))
                    repeatMode=if(cfg.optBoolean("loop",true))Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                    playbackParameters=PlaybackParameters(cfg.optDouble("playbackSpeed",1.0).toFloat().coerceIn(0.25f,4f))
                    // Decoded PCM is routed through the studio mixer instead of playing directly twice.
                    volume=0f
                    val startPosition=cfg.optLong("startPositionMs",0).coerceAtLeast(0)
                    if(startPosition>0)seekTo(startPosition)
                    setVideoSurface(surface);prepare();playWhenReady=!paused
                }
            }.getOrElse{NativeEngine.releaseSourceSurface(src.id);SourceRuntimeErrors.report(src.id,"Could not start media playback: ${it.message ?: "unsupported media or unavailable file"}.");return@launch}
            if(mediaUris[src.id]==identity)players[src.id]=p else {p.release();NativeEngine.releaseSourceSurface(src.id)}
        }
    }
    private fun retryWaiting(){waiting.entries.toList().forEach{(id,p)->waiting.remove(id);android.os.Handler(p.applicationLooper).post{runCatching{p.prepare();p.playWhenReady=!paused}}}}
    /** Videos waiting for a hardware decoder; tried again whenever another video is removed. */
    private val waiting=ConcurrentHashMap<String,ExoPlayer>()
    fun stop(id:String){waiting.remove(id);if(players.containsKey(id))StreamLog.add("Media source $id stopped");mediaUris.remove(id);players.remove(id)?.let{runCatching{it.stop()};runCatching{it.release()}};NativeEngine.releaseSourceSurface(id);SourceRuntimeErrors.clear(id);retryWaiting()}
    fun stopAll(){players.keys.toList().forEach(::stop)}
    /**
     * Paused while the studio is in the background and not streaming or recording: three videos decoding there got the
     * app killed by Android for CPU use. Resumes when the studio is back on screen.
     */
    @Volatile private var paused=false
    fun setPaused(value:Boolean){if(paused==value)return;paused=value;players.values.forEach{runCatching{it.playWhenReady=!value}};StreamLog.add(if(value)"Studio in background and idle: media paused" else "Studio back: media resumed")}
}
