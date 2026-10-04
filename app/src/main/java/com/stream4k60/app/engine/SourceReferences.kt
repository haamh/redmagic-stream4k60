package com.stream4k60.app.engine

import org.json.JSONObject

/**
 * OBS's Paste (Reference): a scene item that shows another source — the same frames, always in sync, without a
 * second decoder or capture — with its own position, size, crop and visibility. Stored as `settings.referenceOf`.
 * References get no player / capture of their own and no separate audio (the original's audio plays once).
 */
object SourceReferences {
    fun targetOf(configJson: String): String? = runCatching {
        val root = JSONObject(configJson)
        (root.optJSONObject("settings") ?: root).optString("referenceOf", "")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
