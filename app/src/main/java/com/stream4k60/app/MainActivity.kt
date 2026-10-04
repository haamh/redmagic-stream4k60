package com.stream4k60.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.stream4k60.app.navigation.AppNavHost
import com.stream4k60.app.engine.HotkeyDispatcher
import android.view.KeyEvent
import com.stream4k60.app.ui.theme.Stream4k60Theme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.stream4k60.app.data.model.AccessibilitySettings
import com.stream4k60.app.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface UsbEntryPoint { fun usb(): com.stream4k60.app.engine.NativeUsbManager }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Hotkeys without Ctrl/Alt/Meta (OBS-style "1", "2") stay typing while a text field or an interacted page has the keyboard.
        val plainKey = event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON) == 0
        val typing = plainKey && (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).isAcceptingText
        if (!typing && HotkeyDispatcher.handle(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        useFullAstraScreen()
        
        requestPermissions()
        lifecycleScope.launch { settingsRepository.videoConfig.collect { applyHdrPreview(it) } }
        setContent {
            // Settings → Accessibility → UI scale resizes the whole app.
            val access by settingsRepository.accessibilitySettings.collectAsState(AccessibilitySettings())
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density * access.uiScale, base.fontScale)) {
                Stream4k60Theme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }

    /**
     * The studio is laid out for the Astra's whole 2400×1504 landscape panel: hide the status and navigation
     * bars (swipe from an edge to show them briefly) and keep the screen on while streaming.
     */
    private fun useFullAstraScreen() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private var loggedDisplay = false

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // adb-triggered measurement (see MjpegBench).
        // Measurement (adb): --es gpubench off|<flags>  then  --es render log
        intent.getStringExtra("gpubench")?.let { v -> com.stream4k60.app.engine.NativeEngine.setGpuBench(v != "off", v.toIntOrNull() ?: 0) }
        if (intent.getStringExtra("grab") == "canvas") com.stream4k60.app.engine.StreamLog.directory()?.let { com.stream4k60.app.engine.NativeEngine.grabCanvas(java.io.File(it, "canvas.rgba").path) }
        // adb: close the studio cleanly (USB streams stopped, interfaces handed back) before an install replaces the app;
        // killing it mid-stream coincided with kernel panics on this tablet.
        if (intent.getStringExtra("quit") == "1") { finishAndRemoveTask(); return }
        intent.getStringExtra("describe")?.let { id -> com.stream4k60.app.engine.StreamLog.add("Source $id: ${com.stream4k60.app.engine.NativeEngine.describeSource(id)}") }
        if (intent.getStringExtra("render") == "log") com.stream4k60.app.engine.StreamLog.add("Renderer: ${com.stream4k60.app.engine.NativeEngine.describeRender()}")
        // Test switches (adb): --es refresh 60|90|120|144|165, --es hdrpreview off|on
        intent.getStringExtra("refresh")?.toFloatOrNull()?.let { hz -> panelRateOverride = hz; preferPanelRate(hz) }
        intent.getStringExtra("hdrpreview")?.let { v -> hdrPreviewOverride = v != "off"; lastVideoConfig?.let { applyHdrPreview(it) } }
        intent.getStringExtra("usbaudio")?.let { v -> dagger.hilt.android.EntryPointAccessors.fromApplication(applicationContext, UsbEntryPoint::class.java).usb().setNativeAudioDisabled(v == "off") }
        if (intent.getStringExtra("bench") == "mjpeg") com.stream4k60.app.engine.StreamLog.directory()?.let { com.stream4k60.app.engine.MjpegBench.start(it) }
    }

    /**
     * HDR output on the Astra's HDR panel: the window goes into HDR colour mode and the preview is the HDR stream itself
     * (10-bit PQ / HLG), instead of an SDR tone map that looked different from the stream.
     */
    private var hdrPreviewOverride: Boolean? = null
    private var panelRateOverride: Float? = null
    private var appliedPanelRate = -1
    private var lastVideoConfig: com.stream4k60.app.data.model.VideoConfig? = null

    @Suppress("DEPRECATION")
    private fun preferPanelRate(hz: Float) {
        val mode = windowManager.defaultDisplay.supportedModes.minByOrNull { kotlin.math.abs(it.refreshRate - hz) } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        com.stream4k60.app.engine.StreamLog.add("Panel mode requested: ${mode.refreshRate} Hz")
    }

    private fun applyHdrPreview(v: com.stream4k60.app.data.model.VideoConfig) {
        lastVideoConfig = v
        // The panel runs at the stream's frame rate: at 165 Hz the studio UI redrew 2.75x as often as the video changes,
        // and its GPU time came out of the 4K canvas's.
        if (panelRateOverride == null && appliedPanelRate != v.frameRate) { appliedPanelRate = v.frameRate; preferPanelRate(v.frameRate.toFloat()) }
        val display = runCatching { (getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(android.view.Display.DEFAULT_DISPLAY) }.getOrNull()
        val screenHdr = display?.isHdr == true
        if (!loggedDisplay && display != null) {
            loggedDisplay = true
            runCatching {
                @Suppress("DEPRECATION") val types = display.hdrCapabilities?.supportedHdrTypes?.joinToString { when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "type $it" } }.orEmpty()
                val ratio = if (Build.VERSION.SDK_INT >= 34 && display.isHdrSdrRatioAvailable) ", HDR/SDR headroom now ${"%.1f".format(display.hdrSdrRatio)}x" else ""
                com.stream4k60.app.engine.StreamLog.add("Display: HDR ${if (screenHdr) "yes" else "no"} ($types)$ratio")
            }
        }
        val hdr = v.hdrOutput && (hdrPreviewOverride ?: v.hdrPreview) && screenHdr
        window.colorMode = if (hdr) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        com.stream4k60.app.engine.NativeEngine.setPreviewHdr(hdr)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and permission prompts can bring the bars back; hide them again.
        if (hasFocus) useFullAstraScreen()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val notGranted = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isNotEmpty()) {
            requestPermissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}
