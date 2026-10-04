package com.stream4k60.app.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * OBS's media controls (and their hotkeys) for media and slideshow sources: play / pause, restart, stop, next and
 * previous, plus seeking for media. The Properties window and the source toolbar send them; the source's controller acts.
 */
object SourceMediaCommands {
    enum class Command { PLAY_PAUSE, RESTART, STOP, NEXT, PREVIOUS, SEEK }
    data class Request(val sourceId: String, val command: Command, val positionMs: Long = 0)
    /** What a media or slideshow source is doing, for the controls: state, position / duration (media) or slide n of m. */
    data class State(val playing: Boolean, val stopped: Boolean = false, val positionMs: Long = 0, val durationMs: Long = 0, val slide: Int = 0, val slides: Int = 0)

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 32)
    val requests = _requests.asSharedFlow()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states = _states.asStateFlow()

    fun send(sourceId: String, command: Command, positionMs: Long = 0) { _requests.tryEmit(Request(sourceId, command, positionMs)) }
    fun report(sourceId: String, state: State) = _states.update { if (it[sourceId] == state) it else it + (sourceId to state) }
    fun clear(sourceId: String) = _states.update { it - sourceId }
}
