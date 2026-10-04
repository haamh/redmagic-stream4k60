package com.stream4k60.app.ui.youtube

import android.app.Activity
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.model.*
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.HardwareVideoEncoder
import com.stream4k60.app.engine.StreamOutputSession
import com.stream4k60.app.youtube.YouTubeAccountManager
import com.stream4k60.app.youtube.YouTubeBroadcast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class YouTubeBroadcastPickerViewModel @Inject constructor(
    settingsRepository: SettingsRepository
) : ViewModel() {
    private val manager = YouTubeAccountManager()
    val connected: StateFlow<Boolean> = manager.connected
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private val _broadcasts = MutableStateFlow<List<YouTubeBroadcast>>(emptyList())
    val broadcasts: StateFlow<List<YouTubeBroadcast>> = _broadcasts.asStateFlow()
    /** The channel the sign-in reaches (null until known); [channelChecked] tells "no channel" from "not loaded". */
    private val _channel = MutableStateFlow<String?>(null)
    val channel: StateFlow<String?> = _channel.asStateFlow()
    private val _channelChecked = MutableStateFlow(false)
    val channelChecked: StateFlow<Boolean> = _channelChecked.asStateFlow()
    private val _videoConfig = MutableStateFlow(VideoConfig())
    val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.videoConfig.collect {
                _videoConfig.value = it
                _settingsLoaded.value = true
            }
        }
    }

    fun authorize(activity: Activity, account: android.accounts.Account?, onResolution: (IntentSenderRequest) -> Unit, onDone: (Boolean, String?) -> Unit) {
        viewModelScope.launch { manager.authorize(activity, account, onResolution, onDone) }
    }

    fun handleResult(activity: Activity, intent: Intent?, onDone: (Boolean, String?) -> Unit) {
        manager.handleAuthorizationResult(activity, intent, onDone)
    }

    /** On open: reuse an earlier Google grant silently, then fetch the broadcasts. */
    fun restoreAndLoad(activity: Activity) {
        viewModelScope.launch { if (connected.value || manager.restore(activity)) load() }
    }

    fun disconnect(context: android.content.Context) {
        _broadcasts.value = emptyList(); _loadError.value = null; _channel.value = null; _channelChecked.value = false
        viewModelScope.launch { manager.disconnect(context) }
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true; _loadError.value = null
            // Broadcasts belong to one channel; show which one, since a Google account can own several (brand accounts).
            runCatching { manager.service().channelTitle() }.onSuccess { _channel.value = it; _channelChecked.value = true }
            runCatching { manager.service().listBroadcasts() }
                // Finished and revoked broadcasts can't be streamed to; upcoming ones first.
                .onSuccess { list -> _broadcasts.value = list.filter { it.lifeCycle !in setOf("complete", "revoked") } }
                .onFailure { _loadError.value = "Couldn't load your broadcasts: ${it.message}" }
            _loading.value = false
        }
    }

    /** Changes a broadcast's Stream latency on YouTube, then reloads the list; [onDone] gets an error text or null. */
    fun setLatency(broadcast: YouTubeBroadcast, preference: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { manager.service().setLatency(broadcast.id, preference) }
            onDone(result.exceptionOrNull()?.let { "YouTube didn't change the latency: ${it.message}" })
            if (result.isSuccess) _broadcasts.value = _broadcasts.value.map { if (it.id == broadcast.id) it.copy(latency = preference) else it }
        }
    }

    fun toConfig(broadcast: YouTubeBroadcast): StreamConfig {
        check(_settingsLoaded.value) { "Loading active profile video settings" }
        val video = _videoConfig.value
        val width = video.outputResWidth
        val height = video.outputResHeight
        val fps = video.frameRate

        require(!(video.hdrOutput && video.outputCodec == OutputCodec.H264)) {
            "HDR output needs the HEVC encoder: choose HEVC in Settings → Output, or turn off HDR output in Settings → Video."
        }
        require(fps <= 60) {
            "YouTube output supports up to 60 FPS. Change the active profile frame rate before streaming."
        }
        // Portrait (9:16) output is allowed: the long side up to 3840, the short side up to 2160.
        require(clampVideoSize(width, height) == (width to height) && width % 2 == 0 && height % 2 == 0) {
            "Choose an even output size no larger than 3840 × 2160 (or 2160 × 3840 portrait) in Video settings."
        }

        val useHls = broadcast.ingestionType.equals("hls", true) && !broadcast.ingestionUrl.isNullOrBlank()
        val protocol = if (useHls) StreamProtocol.HLS else StreamProtocol.RTMPS
        val ingestUrl = if (protocol == StreamProtocol.RTMPS) broadcast.rtmpsUrl ?: broadcast.ingestionUrl else broadcast.ingestionUrl
        val codec = video.outputCodec
        val mime = if (codec == OutputCodec.HEVC) "video/hevc" else "video/avc"
        require(HardwareVideoEncoder.supportsResolution(mime, width, height, fps)) {
            "The selected ${codec.name} hardware encoder does not support ${width} × ${height} at $fps FPS on this device."
        }
        return StreamConfig(
            service = StreamService.YOUTUBE,
            protocol = protocol,
            ingestionUrl = ingestUrl.orEmpty(),
            streamName = broadcast.streamName.orEmpty(),
            broadcastId = broadcast.id,
            outputCodec = codec,
            outputWidth = width,
            outputHeight = height,
            fps = fps,
            bitrate = video.videoBitrateKbps * 1_000,
            audioBitrate = video.audioBitrateKbps * 1_000
        )
    }
}
