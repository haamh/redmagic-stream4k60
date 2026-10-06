package com.stream4k60.app.ui.main

/** Scene transitions the compositor draws (codes match GlCompositor's transition shader). */
object SceneTransitions {
    const val MOVE = 20
    val names = listOf(
        "Cut", "Fade", "Fade to black", "Zoom in", "Zoom out",
        "Slide left", "Slide right", "Slide up", "Slide down",
        "Whip left", "Whip right", "Wipe", "Circle reveal", "Move (layout)"
    )
    fun code(name: String): Int = when (name) {
        "Cut" -> 0
        "Fade", "Fast Fade", "Slow Fade" -> 1
        "Fade to black" -> 2
        "Zoom in" -> 3
        "Zoom out" -> 4
        "Slide left" -> 5
        "Slide right" -> 6
        "Slide up" -> 7
        "Slide down" -> 8
        "Whip left" -> 9
        "Whip right" -> 10
        "Wipe" -> 11
        "Circle reveal" -> 12
        "Move (layout)", "Move" -> MOVE
        else -> 1
    }
    const val MIN_MS = 100
    const val MAX_MS = 3000
}
