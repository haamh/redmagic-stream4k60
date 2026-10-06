package com.stream4k60.app.ui.settings

import com.stream4k60.app.data.model.ColorRange
import com.stream4k60.app.data.model.ColorFormat
import com.stream4k60.app.data.model.ColorSpace
import com.stream4k60.app.data.model.AccessibilitySettings
import com.stream4k60.app.data.model.RateControl
import com.stream4k60.app.data.model.clampVideoSize
import com.stream4k60.app.data.model.AdvancedSettings
import com.stream4k60.app.data.model.AudioSettings
import com.stream4k60.app.data.model.GeneralSettings
import com.stream4k60.app.data.model.HotkeyAction
import com.stream4k60.app.data.model.HotkeyBinding
import com.stream4k60.app.engine.HotkeyDispatcher
import kotlinx.coroutines.flow.map
import com.stream4k60.app.data.model.StreamSettings
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.model.VideoConfig
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.data.model.OutputCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val database: com.stream4k60.app.data.local.AppDatabase,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) : ViewModel() {
    /** Settings backups (Settings → General → Backups). */
    private val _backups = MutableStateFlow<List<com.stream4k60.app.data.backup.SettingsBackups.Backup>>(emptyList())
    val backups: StateFlow<List<com.stream4k60.app.data.backup.SettingsBackups.Backup>> = _backups.asStateFlow()
    fun refreshBackups() { _backups.value = com.stream4k60.app.data.backup.SettingsBackups.list(appContext) }
    fun backupNow() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.stream4k60.app.data.backup.SettingsBackups.backup(appContext, database, "saved from Settings", force = true) }
        refreshBackups()
    }
    /** Result of the last export / import, shown under the buttons. */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()
    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _backupMessage.value = runCatching {
            val files = appContext.contentResolver.openOutputStream(uri, "w")?.use { com.stream4k60.app.data.backup.SettingsBackups.exportTo(appContext, database, it) }
                ?: error("the chosen file could not be opened")
            "Full backup saved (database, preferences and $files source files). It contains your stream keys, so keep it private."
        }.getOrElse { "Backup failed: ${it.message}" }
    }
    fun importBackup(uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { com.stream4k60.app.data.backup.SettingsBackups.importFrom(appContext, database, it) }
                ?: error("the chosen file could not be opened")
        }.onFailure { _backupMessage.value = "Restore failed: ${it.message}" }
    }
    fun restoreBackup(backup: com.stream4k60.app.data.backup.SettingsBackups.Backup) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        com.stream4k60.app.data.backup.SettingsBackups.restore(appContext, database, backup.file)
    }

    private val _videoConfig = MutableStateFlow(VideoConfig())
    val videoConfig: StateFlow<VideoConfig> = _videoConfig.asStateFlow()

    /**
     * Saves still being written. Each change saves in the background and the stored value comes back a moment later;
     * an older value coming back after a newer change used to replace it (a codec switch lost to an earlier size
     * change). While saves are pending the local value is the newest, and they are written in order.
     */
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger()
    private val saveLock = kotlinx.coroutines.sync.Mutex()

    init {
        viewModelScope.launch {
            settingsRepository.videoConfig.collect { config ->
                if (pendingSaves.get() > 0) return@collect
                _videoConfig.value = config
                NativeEngine.setVideoSettings(config.baseResWidth, config.baseResHeight, config.frameRate)
            }
        }
    }

    private val _theme = MutableStateFlow("Dark")
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _language = MutableStateFlow("English")
    val language: StateFlow<String> = _language.asStateFlow()

    val streamSettings: StateFlow<StreamSettings> = settingsRepository.streamSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, StreamSettings())
    val generalSettings: StateFlow<GeneralSettings> = settingsRepository.generalSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, GeneralSettings())

    fun saveStreamSettings(settings: StreamSettings) { viewModelScope.launch { settingsRepository.saveStreamSettings(settings) } }
    fun saveGeneralSettings(settings: GeneralSettings) { viewModelScope.launch { settingsRepository.saveGeneralSettings(settings) } }

    val audioSettings: StateFlow<AudioSettings> = settingsRepository.audioSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudioSettings())
    val advancedSettings: StateFlow<AdvancedSettings> = settingsRepository.advancedSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AdvancedSettings())
    /** Effective bindings: the saved set, or the defaults when the profile has never customised hotkeys. */
    val hotkeys: StateFlow<Map<HotkeyAction, HotkeyBinding>> = settingsRepository.hotkeys
        .map { it ?: HotkeyDispatcher.defaultBindings }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HotkeyDispatcher.defaultBindings)
    val accessibilitySettings: StateFlow<AccessibilitySettings> = settingsRepository.accessibilitySettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AccessibilitySettings())

    fun saveAudioSettings(settings: AudioSettings) { viewModelScope.launch { settingsRepository.saveAudioSettings(settings) } }
    fun saveAdvancedSettings(settings: AdvancedSettings) { viewModelScope.launch { settingsRepository.saveAdvancedSettings(settings) } }
    fun saveHotkeys(bindings: Map<HotkeyAction, HotkeyBinding>) { viewModelScope.launch { settingsRepository.saveHotkeys(bindings) } }
    fun saveAccessibilitySettings(settings: AccessibilitySettings) {
        viewModelScope.launch { settingsRepository.saveAccessibilitySettings(settings.copy(uiScale = settings.uiScale.coerceIn(0.75f, 1.5f))) }
    }

    // Add setters for all states
    fun setTheme(theme: String) { _theme.value = theme }
    fun setLanguage(language: String) { _language.value = language }
    fun setVideoBitrate(bitrateKbps: Int) {
        setVideoConfig(_videoConfig.value.copy(videoBitrateKbps = bitrateKbps.coerceIn(1_000, 100_000)))
    }
    fun setAudioBitrate(bitrateKbps: Int) {
        setVideoConfig(_videoConfig.value.copy(audioBitrateKbps = bitrateKbps))
    }
    /** Settings → Video → Colour / HDR (OBS: Settings → Advanced → Video). HDR is Rec. 2100, always 10-bit (P010). */
    fun setHdrOutput(enabled: Boolean) {
        val c = _videoConfig.value
        setVideoConfig(
            if (enabled) c.copy(colorSpace = if (c.colorSpace == ColorSpace.REC2100HLG) ColorSpace.REC2100HLG else ColorSpace.REC2100PQ, colorFormat = ColorFormat.P010, hdrEnabled = true)
            else c.copy(colorSpace = ColorSpace.SRGB, colorFormat = ColorFormat.NV12, hdrEnabled = false)
        )
    }
    fun setColorSpace(space: ColorSpace) {
        val hdr = space == ColorSpace.REC2100PQ || space == ColorSpace.REC2100HLG
        val c = _videoConfig.value
        setVideoConfig(c.copy(colorSpace = space, hdrEnabled = hdr, colorFormat = if (hdr) ColorFormat.P010 else c.colorFormat))
    }
    fun setColorFormat(format: ColorFormat) = setVideoConfig(_videoConfig.value.copy(colorFormat = format))
    fun setColorRange(range: ColorRange) = setVideoConfig(_videoConfig.value.copy(colorRange = range))
    fun setSdrWhiteLevel(nits: Int) = setVideoConfig(_videoConfig.value.copy(sdrWhiteLevel = nits.coerceIn(80, 480)))
    fun setHdrNominalPeak(nits: Int) = setVideoConfig(_videoConfig.value.copy(hdrNominalPeak = nits.coerceIn(400, 10_000)))
    fun setHdrPreview(on: Boolean) = setVideoConfig(_videoConfig.value.copy(hdrPreview = on))

    fun setDynamicBitrate(enabled: Boolean) {
        setVideoConfig(_videoConfig.value.copy(dynamicBitrate = enabled))
    }
    fun setRateControl(mode: RateControl) {
        setVideoConfig(_videoConfig.value.copy(rateControl = mode))
    }
    fun setEncoder(encoder: String) {
        val codec = if (encoder == "H.265 / HEVC") OutputCodec.HEVC else OutputCodec.H264
        setVideoConfig(_videoConfig.value.copy(outputCodec = codec))
    }

    fun setVideoConfig(config: VideoConfig) {
        val (baseW, baseH) = clampVideoSize(config.baseResWidth, config.baseResHeight)
        val (outW, outH) = clampVideoSize(config.outputResWidth, config.outputResHeight)
        val bounded = config.copy(
            baseResWidth = baseW,
            baseResHeight = baseH,
            outputResWidth = outW,
            outputResHeight = outH,
            fpsInt = config.fpsInt.coerceIn(1, 120),
            fpsNum = config.fpsNum.coerceIn(1, 120_000),
            videoBitrateKbps = config.videoBitrateKbps.coerceIn(1_000, 100_000),
            audioBitrateKbps = config.audioBitrateKbps.coerceIn(32, 512)
        )
        _videoConfig.value = bounded
        NativeEngine.setVideoSettings(bounded.baseResWidth, bounded.baseResHeight, bounded.frameRate)
        pendingSaves.incrementAndGet()
        viewModelScope.launch {
            try { saveLock.withLock { settingsRepository.saveVideoConfig(bounded) } } finally { pendingSaves.decrementAndGet() }
        }
    }

    fun resetSettings() {
        setVideoConfig(VideoConfig())
    }

    fun resetVideoSettings() {
        val defaults = VideoConfig()
        setVideoConfig(_videoConfig.value.copy(
            baseResWidth = defaults.baseResWidth,
            baseResHeight = defaults.baseResHeight,
            outputResWidth = defaults.outputResWidth,
            outputResHeight = defaults.outputResHeight,
            fpsType = defaults.fpsType,
            fpsCommon = defaults.fpsCommon,
            fpsInt = defaults.fpsInt,
            fpsNum = defaults.fpsNum,
            fpsDen = defaults.fpsDen,
            downscaleFilter = defaults.downscaleFilter,
            colorFormat = defaults.colorFormat,
            colorSpace = defaults.colorSpace,
            colorRange = defaults.colorRange,
            hdrEnabled = defaults.hdrEnabled
        ))
    }

    fun resetOutputSettings() {
        val defaults = VideoConfig()
        setVideoConfig(_videoConfig.value.copy(
            videoBitrateKbps = defaults.videoBitrateKbps,
            audioBitrateKbps = defaults.audioBitrateKbps,
            outputCodec = defaults.outputCodec
        ))
    }
}
