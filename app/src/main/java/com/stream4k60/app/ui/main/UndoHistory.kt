package com.stream4k60.app.ui.main

import com.stream4k60.app.data.local.entity.SceneEntity
import com.stream4k60.app.data.local.entity.SourceEntity
import com.stream4k60.app.data.repository.SceneRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OBS's Undo / Redo for scene and source edits (add, remove, rename, reorder, show / hide, lock, transform, properties,
 * filters, groups). Each edit first records the scene collection as it was; a run of the same continuous edit (dragging
 * one source) is one step. History belongs to one collection and is cleared when another is opened, as in OBS.
 */
class UndoHistory(private val repo: SceneRepository, private val limit: Int = 50) {
    data class Snapshot(val label: String, val collectionId: String, val scenes: List<SceneEntity>, val sources: List<SourceEntity>)
    /** What Undo / Redo would do next (null = nothing), for the buttons' state and tooltips. */
    data class State(val undoLabel: String? = null, val redoLabel: String? = null)

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private val mutex = Mutex()
    private var lastKey: String? = null
    private var lastAt = 0L
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private suspend fun capture(collectionId: String, label: String): Snapshot {
        val scenes = repo.loadScenes(collectionId)
        val sources = mutableListOf<SourceEntity>()
        val queue = ArrayDeque(scenes.map { it.id })
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val container = queue.removeFirst()
            if (!seen.add(container)) continue
            val rows = repo.loadSources(container)
            sources += rows
            rows.filter { it.type.equals("GROUP", true) }.forEach { queue += NestedSources.GROUP_PREFIX + it.id }
        }
        return Snapshot(label, collectionId, scenes, sources)
    }

    /**
     * Records the state before an edit called [label]. Edits with the same [coalesceKey] less than 1.5 s apart (each step
     * of a drag) share the first one's record.
     */
    suspend fun checkpoint(collectionId: String, label: String, coalesceKey: String? = null) = mutex.withLock {
        val now = android.os.SystemClock.elapsedRealtime()
        val continuing = coalesceKey != null && coalesceKey == lastKey && now - lastAt < 1500
        lastKey = coalesceKey; lastAt = now
        if (continuing) return@withLock
        val snapshot = capture(collectionId, label)
        if (undo.lastOrNull()?.let { it.collectionId == collectionId && it.scenes == snapshot.scenes && it.sources == snapshot.sources } == true) return@withLock
        undo.addLast(snapshot)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
        publish()
    }

    /** Puts the collection back to before the last edit; true if something was undone. */
    suspend fun undo(): Boolean = mutex.withLock { step(undo, redo) }
    suspend fun redo(): Boolean = mutex.withLock { step(redo, undo) }

    private suspend fun step(from: ArrayDeque<Snapshot>, to: ArrayDeque<Snapshot>): Boolean {
        val target = from.removeLastOrNull() ?: return false
        to.addLast(capture(target.collectionId, target.label))
        restore(target)
        lastKey = null
        publish()
        return true
    }

    private suspend fun restore(s: Snapshot) {
        val current = capture(s.collectionId, "")
        val keepSources = s.sources.map { it.id }.toSet()
        current.sources.filter { it.id !in keepSources }.forEach { repo.deleteSource(it.id) }
        if (s.sources.isNotEmpty()) repo.saveSources(s.sources)
        val keepScenes = s.scenes.map { it.id }.toSet()
        current.scenes.filter { it.id !in keepScenes }.forEach { repo.deleteScene(it.id) }
        s.scenes.forEach { repo.saveScene(it) }
    }

    fun clear() { undo.clear(); redo.clear(); lastKey = null; publish() }

    private fun publish() { _state.value = State(undo.lastOrNull()?.label, redo.lastOrNull()?.label) }
}
