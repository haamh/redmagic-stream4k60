package com.stream4k60.app.engine

import android.view.Surface

/** Native GPU compositor. Pixels remain on the GPU after a source reaches a SurfaceTexture. */
object NativeEngine {
    init { System.loadLibrary("stream4k60_engine") }

    external fun initializeRenderer(canvasWidth: Int, canvasHeight: Int, fps: Int): Boolean
    /** The EGL/shader error behind a failed [initializeRenderer]. */
    external fun getLastError(): String
    /** Writes native crashes (signal + stack) to [path]. */
    external fun installCrashHandler(path: String)
    external fun shutdownRenderer()
    external fun startRenderer(): Boolean
    external fun stopRenderer()
    external fun setCanvasSize(width: Int, height: Int)
    external fun setVideoSettings(canvasWidth: Int, canvasHeight: Int, fps: Int)
    external fun setPreviewSurface(surface: Surface?): Boolean
    /** Preview in the output's HDR signal (for an HDR screen) instead of tone-mapped to SDR. */
    external fun setPreviewHdr(enabled: Boolean)
    /** Shows one source on its own in [surface] (the Properties preview); null id or surface turns it off. */
    external fun setSoloPreview(sourceId: String?, surface: Surface?): Boolean
    external fun setEncoderSurface(surface: Surface?): Boolean
    external fun createSourceSurface(sourceId: String): Surface?
    external fun releaseSourceSurface(sourceId: String)
    external fun setSourceBufferSize(sourceId: String, width: Int, height: Int)
    external fun setSourceTextureParameters(
        sourceId: String,
        x: Float, y: Float, width: Float, height: Float, pivotX: Float, pivotY: Float,
        rotation: Float, scaleX: Float, scaleY: Float,
        opacity: Float, cropLeft: Float, cropTop: Float,
        cropRight: Float, cropBottom: Float,
        visible: Boolean, zOrder: Int, flipH: Boolean, flipV: Boolean
    )
    /** [types] holds one VideoFilterType.nativeId per stage; [params] holds VideoFilterChain.FLOATS_PER_STAGE floats per stage. */
    external fun setSourceFilterChain(sourceId: String, types: IntArray, params: FloatArray)
    /** Config last applied per source: the preview calls this for every source on every drag step. */
    private val appliedEffects = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun setSourceEffectsFromConfig(sourceId: String, configJson: String) {
        // Moving a source changes only its transform; re-parsing filters and LUTs each drag step was wasted work.
        // The renderer keeps each source's chain (also across re-creation), so unchanged config needs no resend.
        if (appliedEffects.put(sourceId, configJson) == configJson) return
        applyChain(sourceId, VideoFilterChain.read(configJson))
    }
    /** Applies a chain that isn't the saved config (the Filters editor's live preview): the next config apply must resend. */
    fun applySourceFilterChain(sourceId: String, stages: List<VideoFilterStage>) {
        appliedEffects.remove(sourceId)
        applyChain(sourceId, stages)
    }
    private fun applyChain(sourceId: String, stages: List<VideoFilterStage>) {
        val (types, params) = VideoFilterChain.pack(stages)
        setSourceFilterChain(sourceId, types, params)
        LutLibrary.sync(sourceId, VideoFilterChain.lutPaths(stages))
    }
    external fun setSourceLut(sourceId: String, slot: Int, key: String, size: Int, rgb: ByteArray, domainMin: FloatArray, domainMax: FloatArray)
    external fun clearSourceLut(sourceId: String, slot: Int)
    external fun getSourceLutKey(sourceId: String, slot: Int): String
    external fun updateSourceRgba(sourceId: String, rgba: ByteArray, width: Int, height: Int): Boolean
    external fun updateSourceYuvDirect(sourceId: String, frame: java.nio.ByteBuffer, width: Int, height: Int, pixelFormat: Int): Boolean
    external fun removeSourceLayer(sourceId: String)

    /** Offscreen canvas for a nested scene or group; sources owned by [key] draw into it. */
    external fun setSceneTarget(key: String, width: Int, height: Int)
    /** Frees scene canvases not listed in [keys]. */
    external fun retainSceneTargets(keys: Array<String>)
    /** Draws [sourceId] into the canvas [owner] ("" = program canvas). */
    external fun setSourceOwner(sourceId: String, owner: String)
    /** Makes [sourceId] a layer showing the scene canvas [key]. */
    external fun setSourceSceneRef(sourceId: String, key: String)
    external fun setTransition(type: Int, durationMs: Int)
    external fun setTransitionProgress(progress: Float)
    /** Freezes the outgoing scene at the next frame; [movePairs] = old id, new id, ... for the Move transition. */
    external fun beginSceneTransition(type: Int, durationMs: Int, movePairs: Array<String>)
    /** Animates from the snapshot to the live new scene. */
    external fun startSceneTransition()
    /** Rolling text slid by the compositor (8 floats, see GlCompositor::setSourceRoll); null turns it off. */
    external fun setSourceRoll(sourceId: String, params: FloatArray?)
    /** 0 none, 1 snapshot pending, 2 snapshot shown, 3 animating. */
    external fun sceneTransitionPhase(): Int

    external fun getRenderTimeMs(): Float
    external fun getDroppedFrames(): Long
    external fun getTotalFrames(): Long
    /** Frames rendered into the encoder's input surface (all time). */
    external fun getEncoderFrames(): Long
    /** Per-source Scale Filtering (OBS): 0 auto, 1 point, 2 bilinear, 3 bicubic, 4 lanczos, 5 area. */
    external fun setSourceScaleFilter(sourceId: String, mode: Int)
    /** The source's own signal: 0 SDR, 1 HDR10 (PQ), 2 HLG; converted to the output's colour when drawn. */
    external fun setSourceTransfer(sourceId: String, transfer: Int)
    /** Output colour for the next encoder surface: 0 SDR, 1 Rec. 2100 PQ, 2 Rec. 2100 HLG; 10-bit; nits. */
    external fun setOutputColor(transfer: Int, tenBit: Boolean, sdrWhiteNits: Float, hdrPeakNits: Float)
    /** What the encoder surface really is (bit depth, colour tagging), for the stream log. */
    external fun encoderSurfaceInfo(): String
    /** OBS's Paste (Reference): [sourceId] shows [targetId]'s frames with its own transform; "" removes it. */
    external fun setSourceAlias(sourceId: String, targetId: String)
    /** Color space / range chosen in Properties: [matrix] -1 as reported, 0 Rec. 601, 1 Rec. 709, 2 BT.2020; [range] -1 as reported, 0 limited, 1 full. */
    external fun setSourceYuvColor(sourceId: String, matrix: Int, range: Int)
    /**
     * What a decoder surface holds, so Color space / range can be re-applied: [kind] 1 YUV from a video decoder, 2 RGB from
     * JPEG (Rec. 601 full range), 0 other RGB; [matrix] / [range] what the decoder reports (-1 = not said).
     */
    external fun setSourceDecodedColor(sourceId: String, kind: Int, matrix: Int, range: Int)
    /** Hides a source's picture for now without touching its transform (media: "Show nothing when playback ends"). */
    external fun setSourceBlank(sourceId: String, blank: Boolean)
    /** HLG look for a source read as HLG: [strength] and [colour] 0..1 (1 = plain HLG), decoded for a [peakNits] display. */
    external fun setSourceHlgLook(sourceId: String, strength: Float, colour: Float, peakNits: Float)
    /** Where the render time goes, in one line (stream log, Stats). */
    external fun describeRender(): String
    /** Measurement: draw the 4K canvas every frame without streaming; [flags] 1 bilinear, 2 no colour conversion, 4 no filters. */
    external fun setGpuBench(enabled: Boolean, flags: Int)
    /** Saves the next streamed canvas frame (as the encoder gets it) to [path]: 8-bit RGBA, top row first. */
    external fun grabCanvas(path: String)
    /** The YUV matrix the device itself declares for its current format (UVC Color Matching descriptor); -1 = it doesn't. */
    external fun setSourceYuvReported(sourceId: String, matrix: Int)
    /** Registers a source fed by raw YUV frames (no decoder surface) before its first frame arrives. */
    external fun ensureRawSource(sourceId: String)
    /** The compositor's state for a source in one line (stream-log diagnostics). */
    external fun describeSource(sourceId: String): String
}
