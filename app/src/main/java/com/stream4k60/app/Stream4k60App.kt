package com.stream4k60.app

import android.app.Application
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.AstraDeviceMonitor
import com.stream4k60.app.engine.LutLibrary
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class Stream4k60App:Application(){
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var database: com.stream4k60.app.data.local.AppDatabase
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(){
        super.onCreate()
        CrashReporter.install(this)
        com.stream4k60.app.engine.SourceRuntimeErrors.init(this)
        com.stream4k60.app.engine.StreamLog.init(this)
        AstraDeviceMonitor.start(this)
        LutLibrary.initialize(this)
        if(BuildConfig.DEBUG)Timber.plant(Timber.DebugTree())
        check(NativeEngine.initializeRenderer(3840,2160,60)){
            "Native GPU compositor initialization failed: ${NativeEngine.getLastError().ifBlank{"no EGL/shader details reported"}}"
        }
        // A settings backup whenever the app's last screen stops: closed or sent to the background (skipped if unchanged).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: android.app.Activity) { started++ }
            override fun onActivityStopped(activity: android.app.Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) appScope.launch {
                    runCatching { com.stream4k60.app.data.backup.SettingsBackups.backup(this@Stream4k60App, database, "app closed or sent to the background") }
                        .onFailure { Timber.w(it, "Settings backup failed") }
                }
            }
            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        appScope.launch {
            settingsRepository.videoConfig.collectLatest { config ->
                NativeEngine.setVideoSettings(config.baseResWidth, config.baseResHeight, config.frameRate)
            }
        }
    }
}
