package com.stream4k60.app.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What browser sources may read and control through OBS's `window.obsstudio` page API, filled in by the studio. The
 * level a page gets is its source's "Page permissions" (OBS: webpage_control_level, 0 none … 5 all).
 */
object StudioBridge {
    @Volatile var status: () -> JSONObject = { JSONObject().put("streaming", false).put("recording", false).put("recordingPaused", false).put("replaybuffer", false).put("virtualcam", false) }
    @Volatile var currentScene: () -> JSONObject = { JSONObject() }
    @Volatile var scenes: () -> JSONArray = { JSONArray() }
    @Volatile var transitions: () -> JSONArray = { JSONArray() }
    @Volatile var currentTransition: () -> String = { "" }
    @Volatile var setCurrentScene: (String) -> Unit = {}
    @Volatile var setCurrentTransition: (String) -> Unit = {}
    @Volatile var startStreaming: () -> Unit = {}
    @Volatile var stopStreaming: () -> Unit = {}

    /** OBS's page events (obsSceneChanged, obsStreamingStarted…): name and its `detail` JSON. */
    private val listeners = CopyOnWriteArrayList<(String, String) -> Unit>()
    fun addListener(l: (String, String) -> Unit) { listeners += l }
    fun removeListener(l: (String, String) -> Unit) { listeners -= l }
    fun emit(event: String, detail: JSONObject = JSONObject()) { val d = detail.toString(); listeners.forEach { runCatching { it(event, d) } } }
}
