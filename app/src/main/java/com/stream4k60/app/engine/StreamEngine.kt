package com.stream4k60.app.engine

import com.stream4k60.app.data.model.*
import kotlinx.coroutines.flow.StateFlow

enum class StreamState {
    IDLE, CONNECTING, LIVE, RECONNECTING, STOPPING, ERROR
}

enum class RecordState {
    IDLE, RECORDING, PAUSED, STOPPING, ERROR
}

/**
 * Live output counters for the status bar and the Stats panel (since the stream started). [bitrate] is what was
 * sent over the network; [encoderBitrate] what the encoder produced.
 */
data class StreamStats(
    val bitrate: Long = 0,
    val droppedFrames: Long = 0,
    val totalFrames: Long = 0,
    val duration: Long = 0,
    val totalBytes: Long = 0,
    val encoderBitrate: Long = 0,
    val sentFrames: Long = 0,
    val sentBytes: Long = 0,
    val queuedPackets: Int = 0,
    val encoderInputFrames: Long = 0,
    /** Video bitrate the encoder is set to now (lowered by dynamic bitrate), and the configured one. */
    val currentVideoBitrate: Long = 0,
    val targetVideoBitrate: Long = 0
)

data class RecordStats(
    val duration: Long = 0,
    val fileSize: Long = 0,
    val totalFrames: Long = 0
)

interface StreamEngine {
    val streamState: StateFlow<StreamState>
    val recordState: StateFlow<RecordState>
    val streamStats: StateFlow<StreamStats>
    val recordStats: StateFlow<RecordStats>
    val replayBufferActive: StateFlow<Boolean>
    
    suspend fun startStreaming(config: StreamConfig)
    suspend fun stopStreaming()
    suspend fun startRecording(config: RecordingConfig)
    suspend fun stopRecording()
    suspend fun pauseRecording()
    suspend fun resumeRecording()
    suspend fun startReplayBuffer(maxSeconds: Int, maxSizeMb: Int)
    suspend fun stopReplayBuffer()
    suspend fun saveReplayBuffer()
    suspend fun takeScreenshot(outputPath: String)
    fun updateAudioRoute(route: AudioInputRoute): Boolean
    fun audioPeak(sourceId: String): Float
}
