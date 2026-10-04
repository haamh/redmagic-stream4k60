package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.stream4k60.app.data.model.FpsCommon
import com.stream4k60.app.data.model.FpsType
import com.stream4k60.app.ui.settings.components.AdvancedSection
import com.stream4k60.app.ui.settings.components.SettingsDropdown
import com.stream4k60.app.ui.settings.components.SettingsNumberInput
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsToggle
import com.stream4k60.app.data.model.ColorFormat
import com.stream4k60.app.data.model.ColorRange
import com.stream4k60.app.data.model.ColorSpace

private data class ResolutionPreset(val label: String, val width: Int, val height: Int)

private val astraCanvasPreset = ResolutionPreset("Astra native panel (2400 × 1504, 16:10)", 2400, 1504)

private val resolutionPresets = listOf(
    ResolutionPreset("3840 × 2160 (4K UHD)", 3840, 2160),
    ResolutionPreset("2560 × 1440 (1440p)", 2560, 1440),
    ResolutionPreset("1920 × 1080 (1080p)", 1920, 1080),
    ResolutionPreset("1280 × 720 (720p)", 1280, 720),
    ResolutionPreset("854 × 480 (480p)", 854, 480),
    // Vertical video for Shorts, TikTok and Reels.
    ResolutionPreset("2160 × 3840 (9:16, 4K portrait)", 2160, 3840),
    ResolutionPreset("1440 × 2560 (9:16, 1440p portrait)", 1440, 2560),
    ResolutionPreset("1080 × 1920 (9:16, 1080p portrait)", 1080, 1920),
    ResolutionPreset("720 × 1280 (9:16, 720p portrait)", 720, 1280)
)

private fun resolutionLabel(width: Int, height: Int, presets: List<ResolutionPreset> = resolutionPresets): String =
    presets.firstOrNull { it.width == width && it.height == height }?.label ?: "Custom (${width} × $height)"

@Composable
fun VideoSettingsPage(viewModel: SettingsViewModel) {
    val config by viewModel.videoConfig.collectAsState()
    val scrollState = rememberScrollState()
    val baseCanvasPresets = listOf(astraCanvasPreset) + resolutionPresets
    val outputPresets = resolutionPresets

    Column(modifier = Modifier.verticalScroll(scrollState)) {
        SettingsSection("Video") {
            SettingsDropdown(
                label = "Base (Canvas) Resolution",
                options = baseCanvasPresets.map { it.label },
                selected = resolutionLabel(config.baseResWidth, config.baseResHeight, baseCanvasPresets),
                description = "Sets the scene coordinate space and preview layout. Use the Astra native panel for a full-screen 16:10 layout, a 16:9 canvas for common streaming output, or a 9:16 canvas for vertical video. Other sizes: Custom dimensions below.",
                onSelect = { selected ->
                    baseCanvasPresets.firstOrNull { it.label == selected }?.let {
                        viewModel.setVideoConfig(config.copy(baseResWidth = it.width, baseResHeight = it.height))
                    }
                }
            )
            SettingsDropdown(
                label = "Output (Scaled) Resolution",
                options = outputPresets.map { it.label },
                selected = resolutionLabel(config.outputResWidth, config.outputResHeight),
                description = "Sets the encoded stream/recording size independently of the canvas. Example: 1920 × 1080 output from a 4K canvas. Keep the canvas and output the same shape (16:9 or 9:16), or the picture is stretched.",
                onSelect = { selected ->
                    resolutionPresets.firstOrNull { it.label == selected }?.let {
                        viewModel.setVideoConfig(config.copy(outputResWidth = it.width, outputResHeight = it.height))
                    }
                }
            )
            SettingsDropdown(
                label = "Common FPS Values",
                options = listOf("30 FPS", "60 FPS", "120 FPS"),
                selected = "${config.frameRate} FPS",
                description = "Controls compositor pacing and the requested encoder frame rate. 120 FPS requires an Astra hardware encoder and an ingest service that accepts 120 FPS; YouTube Live is limited to 60 FPS.",
                onSelect = { selected ->
                    val fps = selected.substringBefore(' ').toIntOrNull() ?: return@SettingsDropdown
                    val common = FpsCommon.entries.firstOrNull { it.value == fps } ?: FpsCommon.FPS_60
                    viewModel.setVideoConfig(config.copy(fpsType = FpsType.COMMON, fpsCommon = common, fpsInt = fps))
                }
            )
        }

        AdvancedSection("Custom dimensions and frame rate") {
            SettingsNumberInput(
                label = "Canvas width",
                value = config.baseResWidth,
                onValueChange = { viewModel.setVideoConfig(config.copy(baseResWidth = it)) },
                min = 320,
                max = 3840,
                unit = "px",
                description = "Scene canvas width. Example: 3840 px, or 1080 px for a 1080 × 1920 portrait canvas. The long side can be up to 3840 px and the short side up to 2160 px."
            )
            SettingsNumberInput(
                label = "Canvas height",
                value = config.baseResHeight,
                onValueChange = { viewModel.setVideoConfig(config.copy(baseResHeight = it)) },
                min = 240,
                max = 3840,
                unit = "px",
                description = "Scene canvas height. Example: 2160 px, or 1920 px for a portrait canvas."
            )
            SettingsNumberInput(
                label = "Output width",
                value = config.outputResWidth,
                onValueChange = { viewModel.setVideoConfig(config.copy(outputResWidth = it)) },
                min = 320,
                max = 3840,
                unit = "px",
                description = "Final encoded width. The long side can be up to 3840 px and the short side up to 2160 px. Encoder and ingest service must support the selected size and FPS."
            )
            SettingsNumberInput(
                label = "Output height",
                value = config.outputResHeight,
                onValueChange = { viewModel.setVideoConfig(config.copy(outputResHeight = it)) },
                min = 240,
                max = 3840,
                unit = "px",
                description = "Final encoded height. Example: 1080 px, or 1920 px for 9:16 portrait output."
            )
            SettingsNumberInput(
                label = "Integer FPS",
                value = config.frameRate,
                onValueChange = { viewModel.setVideoConfig(config.copy(fpsType = FpsType.INTEGER, fpsInt = it)) },
                min = 1,
                max = 120,
                unit = "FPS",
                description = "Integer compositor/encoder rate up to 120 FPS. The exact Astra codec mode and the ingest service must support it."
            )
        }

        // OBS: Settings → Advanced → Video. Every source keeps its own colour (see its Properties); this is what the
        // stream and recordings are sent as, and each source is converted to it.
        SettingsSection("Colour / HDR") {
            SettingsToggle(
                "HDR output",
                config.hdrOutput,
                { viewModel.setHdrOutput(it) },
                description = "On: the stream is HDR (Rec. 2100, 10-bit HEVC). HDR videos go out as they are; SDR sources (camera, SDR videos, images) are mapped into HDR at the SDR white level. YouTube shows HDR on HDR screens and makes the SDR version for everyone else. Off: an SDR stream, with HDR videos tone-mapped to SDR. Needs HEVC (Settings → Output). Applies when streaming or recording starts."
            )
            SettingsDropdown(
                label = "Color space",
                options = if (config.hdrOutput) listOf("Rec. 2100 (PQ)", "Rec. 2100 (HLG)") else listOf("sRGB", "Rec. 709"),
                selected = when (config.colorSpace) {
                    ColorSpace.REC2100PQ -> "Rec. 2100 (PQ)"
                    ColorSpace.REC2100HLG -> "Rec. 2100 (HLG)"
                    ColorSpace.REC709 -> "Rec. 709"
                    ColorSpace.SRGB -> "sRGB"
                },
                onSelect = {
                    viewModel.setColorSpace(when (it) {
                        "Rec. 2100 (PQ)" -> ColorSpace.REC2100PQ
                        "Rec. 2100 (HLG)" -> ColorSpace.REC2100HLG
                        "Rec. 709" -> ColorSpace.REC709
                        else -> ColorSpace.SRGB
                    })
                },
                description = if (config.hdrOutput) "PQ is HDR10, what YouTube and most HDR screens use. HLG is broadcast HDR and also looks right on SDR TVs."
                else "SDR. sRGB is OBS's default; both are sent as Rec. 709."
            )
            SettingsDropdown(
                label = "Color format",
                options = listOf("NV12 (8-bit, 4:2:0)", "P010 (10-bit, 4:2:0)"),
                selected = if (config.outputColor.tenBit) "P010 (10-bit, 4:2:0)" else "NV12 (8-bit, 4:2:0)",
                onSelect = { viewModel.setColorFormat(if (it.startsWith("P010")) ColorFormat.P010 else ColorFormat.NV12) },
                enabled = !config.hdrOutput,
                description = if (config.hdrOutput) "HDR is always P010 (10-bit)." else "P010 with SDR gives 10-bit SDR: less banding in skies and gradients (HEVC only)."
            )
            SettingsDropdown(
                label = "Color range",
                options = listOf("Limited", "Full"),
                selected = if (config.colorRange == ColorRange.FULL) "Full" else "Limited",
                onSelect = { viewModel.setColorRange(if (it == "Full") ColorRange.FULL else ColorRange.PARTIAL) },
                description = "Limited is what YouTube and video players expect. Keep it unless you know you need Full."
            )
            SettingsNumberInput(
                label = "SDR white level",
                value = config.sdrWhiteLevel,
                onValueChange = { viewModel.setSdrWhiteLevel(it) },
                min = 80,
                max = 480,
                unit = "nits",
                description = "How bright white in SDR sources is inside the HDR stream (OBS default 300). Also the white HDR videos are tone-mapped to in SDR."
            )
            SettingsNumberInput(
                label = "HDR nominal peak level",
                value = config.hdrNominalPeak,
                onValueChange = { viewModel.setHdrNominalPeak(it) },
                min = 400,
                max = 10_000,
                unit = "nits",
                description = "The brightest highlight the HDR stream is mastered for (OBS default 1000). Sent to YouTube as HDR10 metadata."
            )
            if (config.hdrOutput) SettingsToggle(
                "HDR preview",
                config.hdrPreview,
                { viewModel.setHdrPreview(it) },
                description = "On (HDR screens such as the Astra's): the preview is the HDR stream itself, 10-bit, exactly what the encoder sends, shown in HDR the way the YouTube app on this tablet shows the stream. Off: the preview is an SDR version (tone-mapped), which is not what HDR viewers see. YouTube's SDR version for SDR viewers is made by YouTube and can look flatter than either."
            )
        }

    }
}
