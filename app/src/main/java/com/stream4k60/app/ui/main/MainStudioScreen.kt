package com.stream4k60.app.ui.main

import com.stream4k60.app.ui.common.ClosableTitle

import androidx.compose.runtime.saveable.rememberSaveable

import com.stream4k60.app.data.model.HotkeyAction
import androidx.compose.runtime.rememberUpdatedState
import com.stream4k60.app.ui.dialogs.ConfirmStopDialog
import androidx.hilt.navigation.compose.hiltViewModel
import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import com.stream4k60.app.engine.*
import com.stream4k60.app.ui.main.components.*
import com.stream4k60.app.ui.dialogs.ColorPickerDialog
import com.stream4k60.app.ui.filters.FilterEditorScreen
import com.stream4k60.app.ui.search.*
import com.stream4k60.app.ui.sources.TransformDialog
import com.stream4k60.app.ui.sources.SourcePropertiesDialog
import com.stream4k60.app.ui.youtube.YouTubeBroadcastPicker
import org.json.JSONObject

@Composable
fun MainStudioScreen(
    onOpenSettings:(String)->Unit,
    onOpenProfiles:()->Unit,
    vm:MainStudioViewModel=hiltViewModel()
){
    val streaming by vm.streamState.collectAsState();val streamButton by vm.streamButtonState.collectAsState();val liveState by vm.liveState.collectAsState();val streamStats by vm.streamStats.collectAsState();val streamError by vm.streamError.collectAsState();val recording by vm.recordState.collectAsState();val studio by vm.isStudioModeEnabled.collectAsState();val selectedTransition by vm.selectedTransition.collectAsState();val scenes by vm.scenes.collectAsState();val sceneCollections by vm.sceneCollections.collectAsState();val activeCollectionId by vm.activeSceneCollectionId.collectAsState();val active by vm.activeScene.collectAsState();val sources by vm.sources.collectAsState();val sourceErrors by SourceRuntimeErrors.errors.collectAsState();val videoConfig by vm.videoConfig.collectAsState();val importedRtmpEndpoint by vm.importedRtmpEndpoint.collectAsState();var selectedSourceId by remember{mutableStateOf<String?>(null)};val canvasFocusRequester=remember{FocusRequester()};var search by remember{mutableStateOf(false)};var yt by remember{mutableStateOf(false)};var customRtmp by remember{mutableStateOf(false)};var addSource by remember{mutableStateOf(false)};var editingSource by remember{mutableStateOf<SourceItem?>(null)};var filteringSource by remember{mutableStateOf<SourceItem?>(null)};var audioManage by remember{mutableStateOf<Pair<String,String>?>(null)};var broadcastTitle by rememberSaveable{mutableStateOf<String?>(null)}
    val general by vm.generalSettings.collectAsState()
    var confirmStop by remember { mutableStateOf<String?>(null) }
    var showStats by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    /** The browser source whose page is open full screen for clicking and typing (OBS's Interact). */
    var interactingBrowser by remember { mutableStateOf<String?>(null) }
    // Everything the program shows, including the contents of nested scenes and groups.
    val renderSources by vm.renderSources.collectAsState()
    val renderItems = remember(renderSources) { renderSources.map { it.item } }
    // Other scenes' sources kept running hidden: originals of references shown here, and capture devices.
    val backingItems by vm.backingSources.collectAsState()
    // Scene sources plus the global Desktop Audio / Mic sources from Settings → Audio.
    val audioItems by vm.audioItems.collectAsState()
    val groupChildren by vm.groupChildren.collectAsState()
    // Sources that can be opened for editing: the active scene's items and the contents of its groups.
    fun editable(id: String?): SourceItem? = id?.let { key -> sources.firstOrNull { it.id == key } ?: groupChildren.values.flatten().firstOrNull { it.id == key } }
    /** Canvas a source is positioned on: its group's canvas for group contents, otherwise the program canvas. */
    fun canvasOf(id: String): Pair<Int, Int> {
        val group = groupChildren.entries.firstOrNull { (_, items) -> items.any { it.id == id } }?.key?.let { gid -> sources.firstOrNull { it.id == gid } }
        return group?.let { sourceSettings(it.configJson).let { c -> c.optInt("width", videoConfig.baseResWidth) to c.optInt("height", videoConfig.baseResHeight) } }
            ?: (videoConfig.baseResWidth to videoConfig.baseResHeight)
    }
    var pickingNestedScene by remember { mutableStateOf(false) }
    val ctx=androidx.compose.ui.platform.LocalContext.current
    // Studio state kept across restarts (with the rest of the layout): the selected source of each scene, the stats
    // panel, Studio Mode and the scene transition. Sources' positions, crops, visibility and locks live in the database.
    val studioPrefs = remember { ctx.getSharedPreferences("studio_layout", android.content.Context.MODE_PRIVATE) }
    LaunchedEffect(active?.id) { selectedSourceId = active?.id?.let { studioPrefs.getString("selected_source_$it", null) } }
    LaunchedEffect(selectedSourceId) { active?.id?.let { studioPrefs.edit().putString("selected_source_$it", selectedSourceId).apply() } }
    LaunchedEffect(Unit) {
        showStats = studioPrefs.getBoolean("show_stats", false)
        studioPrefs.getString("transition", null)?.let(vm::selectTransition)
        if (studioPrefs.getBoolean("studio_mode", false) != vm.isStudioModeEnabled.value) vm.toggleStudioMode()
    }
    LaunchedEffect(showStats) { studioPrefs.edit().putBoolean("show_stats", showStats).apply() }
    val studioModeNow by vm.isStudioModeEnabled.collectAsState()
    val transitionNow by vm.selectedTransition.collectAsState()
    LaunchedEffect(studioModeNow, transitionNow) { studioPrefs.edit().putBoolean("studio_mode", studioModeNow).putString("transition", transitionNow).apply() }
    val thermalStatus by AstraDeviceMonitor.thermalStatus.collectAsState()
    var projectionRequested by remember{mutableStateOf(false)}
    var projectionGrantedInSession by remember{mutableStateOf(false)}
    var projectionDeniedForSources by remember{mutableStateOf(false)}
    var showProjectionGuideDialog by remember{mutableStateOf(false)}
    var showProjectionRetryDialog by remember{mutableStateOf(false)}
    val screenIds=renderItems.filter{it.type.equals("SCREEN_CAPTURE",true)&&it.isVisible}.map{it.id}
    val playbackIds=audioItems.filter{it.type.equals("PLAYBACK_AUDIO",true)}.map{it.id}
    val playback=playbackIds.isNotEmpty()
    val projectionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
        projectionRequested=false
        if(result.resultCode==Activity.RESULT_OK&&result.data!=null){
            projectionGrantedInSession=true
            projectionDeniedForSources=false
            showProjectionRetryDialog=false
            (screenIds + playbackIds).forEach(SourceRuntimeErrors::clear)
            com.stream4k60.app.service.ProjectionCaptureService.start(ctx,result.resultCode,result.data!!,screenIds,playback,playbackIds.firstOrNull())
        } else {
            projectionGrantedInSession=false
            projectionDeniedForSources=true
            showProjectionRetryDialog=true
            screenIds.forEach { SourceRuntimeErrors.report(it,"Grant Android screen-capture permission to use this source.") }
            playbackIds.forEach { SourceRuntimeErrors.report(it,"Grant Android playback-capture permission to use this source.") }
        }
    }
    fun launchProjectionConsent() {
        val pm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            pm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            pm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
    fun requestProjectionPermission() {
        if (!projectionRequested && (screenIds.isNotEmpty() || playback)) {
            projectionRequested = true
            projectionDeniedForSources = false
            showProjectionGuideDialog = true
        }
    }
    var transformingSource by remember { mutableStateOf<SourceItem?>(null) }
    val scope=rememberCoroutineScope();val camera=remember{CameraSourceController(ctx)};val bitmap=remember{BitmapSourceController(ctx,scope)};val media=remember{MediaSourceController(ctx,scope)};val browser=remember{BrowserSourceController(ctx)};val usb=remember{com.stream4k60.app.engine.NativeUsbManager(ctx.applicationContext)};val usbDevices by usb.connectedDevices.collectAsState()
    // Transform-only edits should update the compositor without restarting capture sources.
    val captureSources = (renderItems + backingItems).map { it.copy(transformJson = "{}") }
    DisposableEffect(Unit){usb.initialize();browser.attachHost((ctx as Activity).findViewById(android.R.id.content) as ViewGroup);onDispose{camera.stopAll();bitmap.stop();media.stopAll();browser.stopAll();usb.shutdown();NativeEngine.setPreviewSurface(null);com.stream4k60.app.engine.StreamLog.add("Studio closed: cameras, USB devices and browser pages released")}}
    LaunchedEffect(captureSources,videoConfig,usbDevices,playbackIds){
        // References show another source's frames: no player / capture of their own.
        val renderItems=(renderItems+backingItems).filter{com.stream4k60.app.engine.SourceReferences.targetOf(it.configJson)==null}
        // USB cameras that Android's own camera driver can read go through it; the rest use direct USB capture.
        val externalCameras=com.stream4k60.app.engine.UsbCameraRouting.externalCameraIds(ctx)
        val viaAndroid={it:SourceItem->com.stream4k60.app.engine.UsbCameraRouting.usesAndroidDriver(it,externalCameras)}
        camera.sync(renderItems.map{if(viaAndroid(it))com.stream4k60.app.engine.UsbCameraRouting.asAndroidCamera(it,externalCameras) else it});bitmap.sync(renderItems);media.sync(renderItems);browser.sync(renderItems)
        if(screenIds.isEmpty() && !playback){
            com.stream4k60.app.service.ProjectionCaptureService.stop(ctx)
            projectionGrantedInSession=false
            projectionDeniedForSources=false
        } else if(com.stream4k60.app.service.ProjectionCaptureService.isActive()) {
            com.stream4k60.app.service.ProjectionCaptureService.sync(ctx,screenIds,playback,playbackIds.firstOrNull())
        } else if(!projectionGrantedInSession && !projectionDeniedForSources && !projectionRequested){
            requestProjectionPermission()
        }
        val wantedUsb=renderItems.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible&&!viaAndroid(it)}.mapNotNull{sourceSettings(it.configJson).optInt("deviceId",-1).takeIf{v->v>=0}}.toSet()
        usbDevices.filter{it.isCapturing&&it.deviceId !in wantedUsb}.forEach{usb.stopCapture(it.deviceId)}
        val visibleUsbSources=renderItems.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible&&!viaAndroid(it)}
        val duplicateUsbIds=visibleUsbSources.map{sourceSettings(it.configJson).optInt("deviceId",-1)}.filter{it>=0}.groupingBy{it}.eachCount().filterValues{it>1}.keys
        duplicateUsbIds.filter{id->usbDevices.any{it.deviceId==id&&it.isCapturing}}.forEach{usb.stopCapture(it)}
        visibleUsbSources.filter{sourceSettings(it.configJson).optInt("deviceId",-1) in duplicateUsbIds}.forEach{src->
            SourceRuntimeErrors.report(src.id,"This USB camera is assigned to more than one visible source. Each UVC device can run one capture session; select a different device for each source.")
        }
        visibleUsbSources.filter{sourceSettings(it.configJson).optInt("deviceId",-1) !in duplicateUsbIds}.forEach{src->
            val c=sourceSettings(src.configJson);val deviceId=c.optInt("deviceId",-1);val device=usbDevices.firstOrNull{it.deviceId==deviceId}
            if(deviceId<0 || device==null) SourceRuntimeErrors.report(src.id,"Connect and select an Android USB video device in source properties.")
            else {
                val w=c.optInt("width",3840);val h=c.optInt("height",2160);val fps=c.optInt("fps",60);val fmt=c.optString("format","MJPEG")
                if(usb.startCapture(deviceId,w,h,fps,fmt,src.id,src.configJson))SourceRuntimeErrors.clear(src.id)
                else SourceRuntimeErrors.report(src.id,"USB capture did not start: ${usb.lastError(deviceId)?:"check the selected format, USB permission and bandwidth"}"+
                    if(externalCameras.isEmpty())" (Android doesn't list this camera in its camera service, so only direct USB capture is possible.)" else " Try Driver: Android camera in Properties.")
            }
        }
    }
    // Every capture source finds its own device again (by identity, as OBS does) after a replug or app start.
    LaunchedEffect((renderItems + backingItems).map { it.id to it.configJson }, usbDevices.map { it.deviceId }) {
        val bindable = renderItems + backingItems
        com.stream4k60.app.engine.UsbDeviceBinding.resolve(bindable, usbDevices).forEach { b ->
            val src = bindable.firstOrNull { it.id == b.sourceId } ?: return@forEach
            if (!com.stream4k60.app.engine.UsbDeviceBinding.isBound(src.configJson, b.device)) {
                com.stream4k60.app.engine.StreamLog.add("USB source ${src.name} → ${b.device.displayName} (device ${b.device.deviceId})")
                vm.rebindUsbSource(src.id, com.stream4k60.app.engine.UsbDeviceBinding.bind(src.configJson, b.device))
            }
        }
    }
    // Direct USB audio: capture cards' and webcams' own sound and USB microphones, read over USB by the app itself, so
    // several can record at once (Android on the Astra records from one USB microphone at a time).
    val audioItemsForUsb by vm.audioItems.collectAsState()
    LaunchedEffect(audioItemsForUsb, usbDevices) {
        val targets = audioItemsForUsb.mapNotNull { src ->
            val mixerId = com.stream4k60.app.engine.UsbAudioSources.mixerInputId(src) ?: return@mapNotNull null
            val device = com.stream4k60.app.engine.UsbAudioSources.deviceFor(src, usbDevices)
            if (device == null) { SourceRuntimeErrors.report(src.id, "USB audio: connect the device and allow its USB permission (tap Rescan in Properties)."); null }
            else device.deviceId to (src.id to mixerId)
        }.toMap()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            usbDevices.filter { it.audioSourceId != null && it.deviceId !in targets }.forEach { usb.stopAudioCapture(it.deviceId) }
            targets.forEach { (deviceId, ids) ->
                usb.startAudioCapture(deviceId, ids.second)?.let { SourceRuntimeErrors.report(ids.first, "USB audio didn't start: $it") }
            }
        }
    }
    // References (Duplicate (reference)) draw the original's frames; registered before transforms are applied below.
    val references = remember(renderItems) { renderItems.mapNotNull { src -> com.stream4k60.app.engine.SourceReferences.targetOf(src.configJson)?.let { src.id to it } } }
    var registeredAliases by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(references) {
        val ids = references.map { it.first }.toSet()
        (registeredAliases - ids).forEach { NativeEngine.setSourceAlias(it, "") }
        references.forEach { (id, target) -> NativeEngine.setSourceAlias(id, target) }
        registeredAliases = ids
    }
    // In the background and idle (not streaming / recording), pause media: Android killed the studio for CPU use with
    // three videos decoding there. Streaming and recording run as foreground services and keep everything going.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val streaming = vm.streamState.value in setOf(StudioStreamState.CONNECTING, StudioStreamState.LIVE, StudioStreamState.RECONNECTING, StudioStreamState.STOPPING)
                    val recording = vm.recordState.value in setOf(StudioRecordState.RECORDING, StudioRecordState.PAUSED, StudioRecordState.STOPPING)
                    if (!streaming && !recording) media.setPaused(true)
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> media.setPaused(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var shownContainerIds by remember { mutableStateOf(emptySet<String>()) }
    // Blanked from the start too, so a backing camera never flashes up full size before its transform arrives.
    var blankedBacking by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(backingItems) {
        val ids = backingItems.filter { !it.type.equals("MEDIA", true) }.map { it.id }.toSet()
        (blankedBacking - ids).forEach { NativeEngine.setSourceBlank(it, false) }
        (ids - blankedBacking).forEach { NativeEngine.setSourceBlank(it, true) }
        blankedBacking = ids
    }
    val nativeSizes by com.stream4k60.app.engine.SourceNativeSizes.sizes.collectAsState()
    LaunchedEffect(renderSources,backingItems,videoConfig,nativeSizes){
        val base=videoConfig.baseResWidth to videoConfig.baseResHeight
        // Nested scenes use the program canvas size; groups have their own canvas.
        val canvasFor=renderSources.mapNotNull{rs->rs.containerKey?.let{key->key to if(rs.item.type.equals("GROUP",true)){
            val c=sourceSettings(rs.item.configJson);c.optInt("width",base.first).coerceAtLeast(16) to c.optInt("height",base.second).coerceAtLeast(16)
        }else base}}.toMap()
        renderSources.forEach{rs->
            NativeEngine.setSourceOwner(rs.item.id,rs.owner)
            rs.containerKey?.let{key->val (w,h)=canvasFor.getValue(key);NativeEngine.setSceneTarget(key,w,h);NativeEngine.setSourceSceneRef(rs.item.id,key)}
            val (cw,ch)=if(rs.owner.isEmpty())base else canvasFor[rs.owner]?:base
            applySourceTransformToNative(rs.item,rs.z,cw,ch)
        }
        // Backing sources update their textures for references but never draw.
        backingItems.forEach{NativeEngine.setSourceOwner(it.id,"");applySourceTransformToNative(it.copy(isVisible=false),0,base.first,base.second)}
        NativeEngine.retainSceneTargets(canvasFor.keys.toTypedArray())
        val containerIds=renderSources.filter{it.containerKey!=null}.map{it.item.id}.toSet()
        (shownContainerIds-containerIds).forEach(NativeEngine::removeSourceLayer)
        shownContainerIds=containerIds
    }
    val currentScenes = rememberUpdatedState(scenes)
    DisposableEffect(Unit) {
        HotkeyDispatcher.attach { action, pressed ->
            when (action) {
                HotkeyAction.START_STREAM -> vm.startStreaming()
                HotkeyAction.STOP_STREAM -> vm.stopStreaming()
                HotkeyAction.TOGGLE_STREAM -> if (streaming == StudioStreamState.IDLE || streaming == StudioStreamState.ERROR) vm.startStreaming() else vm.stopStreaming()
                HotkeyAction.STUDIO_MODE -> vm.toggleStudioMode()
                HotkeyAction.MUTE_MIC -> vm.togglePrimaryMicMute()
                HotkeyAction.PUSH_TO_TALK -> vm.setPushToTalk(pressed)
                HotkeyAction.UNDO -> vm.undo()
                HotkeyAction.REDO -> vm.redo()
                else -> {
                    // SCENE_1..SCENE_9 switch to the scene at that position in the Scenes dock.
                    val index = action.name.removePrefix("SCENE_").toIntOrNull()?.minus(1)
                    index?.let { currentScenes.value.getOrNull(it) }?.let { vm.setActiveScene(it.id) }
                }
            }
        }
        onDispose { HotkeyDispatcher.detach() }
    }

    val entries = FeatureCatalog.build(
        openYouTube = { yt = true },
        openCustomStream = { customRtmp = true },
        openProfiles = onOpenProfiles,
        openSettings = onOpenSettings,
        addSource = { type -> vm.addSource(type) { created -> selectedSourceId = created.id; editingSource = created } },
        startReplay = vm::startReplay,
        toggleStudio = vm::toggleStudioMode
    )

    val undoState by vm.undoState.collectAsState()
    Column(Modifier.fillMaxSize()){
        TopBar(
            scenes = scenes,
            sceneCollections = sceneCollections,
            activeCollectionId = activeCollectionId,
            activeSceneId = active?.id,
            sources = sources,
            isStreaming = (streaming == StudioStreamState.LIVE || streaming == StudioStreamState.RECONNECTING),
            isRecording = recording == StudioRecordState.RECORDING,
            isStudioMode = studio,
            thermalStatus = thermalStatus,
            onSelectScene = vm::setActiveScene,
            onSelectCollection = vm::selectSceneCollection,
            onAddScene = { vm.addScene("Scene ${scenes.size + 1}") },
            onAddSource = { addSource = true },
            onToggleSourceVisibility = vm::toggleSourceVisibility,
            onToggleStudioMode = vm::toggleStudioMode,
            onReplayBuffer = vm::startReplay,
            onSearch = { search = true },
            onYouTube = { yt = true },
            onCustomStream = { customRtmp = true },
            onProfiles = onOpenProfiles,
            onSettings = { onOpenSettings("General") },
            statsOpen = showStats,
            onToggleStats = { showStats = !showStats },
            undoLabel = undoState.undoLabel,
            redoLabel = undoState.redoLabel,
            onUndo = vm::undo,
            onRedo = vm::redo
        )
        // OBS layout: Scenes/Sources docks down the left; preview, source toolbar and the
        // Audio Mixer / Scene Transitions / Controls docks on the right; status bar below.
        val dock = rememberDockSizes()
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val m = studioLayoutMetrics(maxWidth, maxHeight)
            val areaWidth = maxWidth.value
            val areaHeight = maxHeight.value
            val leftWidth = dock.leftWidth.orDefault(m.leftWidth)
            val bottomHeight = dock.bottomHeight.orDefault(m.bottomHeight)
            val transitionsWidth = dock.transitionsWidth.orDefault(m.transitionsWidth)
            val controlsWidth = dock.controlsWidth.orDefault(m.controlsWidth)
            // Dock contents grow and shrink with the dock (relative to its default size).
            val leftScale = leftWidth / m.leftWidth
            val bottomScale = bottomHeight / m.bottomHeight
            val transitionsScale = minOf(bottomScale, transitionsWidth / m.transitionsWidth)
            val controlsScale = minOf(bottomScale, controlsWidth / m.controlsWidth)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(leftWidth).fillMaxHeight()) {
                    val scenesFraction = dock.scenesFraction.coerceIn(0.15f, 0.85f)
                    Dock("Scenes", Modifier.weight(scenesFraction).fillMaxWidth(), leftScale) {
                        ScenePanel(scenes,active?.id,{vm.setActiveScene(it)},{vm.addScene("Scene ${scenes.size+1}")},{vm.removeScene()},Modifier.fillMaxSize(),showHeader=false)
                    }
                    DockSplitter(false, { d -> dock.scenesFraction = (dock.scenesFraction + d / areaHeight).coerceIn(0.15f, 0.85f) }, dock::save) {
                        dock.scenesFraction = 0.5f; dock.save()
                    }
                    Dock("Sources", Modifier.weight(1f - scenesFraction).fillMaxWidth(), leftScale) {
                        SourcePanel(
                            sources = sources,
                            sourceErrors = sourceErrors,
                            onToggleVisibility = vm::toggleSourceVisibility,
                            onToggleLock = vm::toggleSourceLock,
                            onAdd = { addSource = true },
                            onRemove = { selectedSourceId?.let(vm::removeSource) },
                            onProperties = { editable(selectedSourceId)?.let { editingSource = it } },
                            onFilters = { id -> editable(id)?.let { filteringSource = it } },
                            onTransform = { id -> editable(id)?.takeIf { !it.isLocked && !it.isAudioOnly() }?.let { transformingSource = it } },
                            onOpenProperties = { id -> editable(id)?.let { editingSource = it } },
                            onRequestCapturePermission = { requestProjectionPermission() },
                            onRenameSource = vm::renameSource,
                            onDuplicateSource = vm::duplicateSource,
                            onDuplicateReference = vm::duplicateSourceAsReference,
                            onDeleteSource = vm::removeSource,
                            onResetTransform = vm::resetSourceTransform,
                            onPasteTransform = vm::updateSourceTransform,
                            selectedSourceId = selectedSourceId,
                            onSelectSource = { selectedSourceId = it; canvasFocusRequester.requestFocus() },
                            onMoveSource = vm::moveSourceInStack,
                            onScaleFilter = { id, mode -> vm.setSourceScaleFilter(id, mode) },
                            onMoveSourceToIndex = vm::moveSourceToDisplayIndex,
                            groupChildren = groupChildren,
                            onMoveIntoGroup = vm::moveSourceIntoGroup,
                            onMoveOutOfGroup = vm::moveSourceOutOfGroup,
                            modifier = Modifier.fillMaxSize(),
                            showHeader = false
                        )
                    }
                }
                DockSplitter(true, { d -> dock.leftWidth = (leftWidth.value + d).coerceIn(150f, areaWidth * 0.45f) }, dock::save) {
                    dock.leftWidth = 0f; dock.save()
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.background).padding(6.dp)) {
                        PreviewViewport(
                            videoConfig.baseResWidth, videoConfig.baseResHeight,
                            videoConfig.outputResWidth, videoConfig.outputResHeight,
                            Modifier.weight(1f).fillMaxHeight()
                        ) { canvasRect ->
                            // The editor covers the whole preview area, so sources can be moved and resized past the
                            // canvas edges (as in OBS); the picture itself is the canvas rectangle.
                            EditablePreview(
                                sources,
                                selectedSourceId,
                                videoConfig.baseResWidth,
                                videoConfig.baseResHeight,
                                { selectedSourceId = it },
                                { id, transform -> vm.updateSourceTransform(id, transform) },
                                vm::moveSourceInStack,
                                canvasFocusRequester,
                                Modifier.fillMaxSize(),
                                snapping = general.snappingEnabled,
                                snapToSources = general.snapToSources,
                                canvasRect = canvasRect
                            )
                        }
                        if (studio) {
                            Spacer(Modifier.width(8.dp))
                            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                val aspect = videoConfig.baseResWidth.toFloat() / videoConfig.baseResHeight.coerceAtLeast(1)
                                val canvasWidth = minOf(maxWidth, maxHeight * aspect)
                                val canvasHeight = canvasWidth / aspect
                                Box(Modifier.size(canvasWidth, canvasHeight).background(Color.Black)) {
                                    NativePreviewSurface(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    SourceToolbar(
                        selectedName = editable(selectedSourceId)?.name,
                        onProperties = { editable(selectedSourceId)?.let { editingSource = it } },
                        onFilters = { editable(selectedSourceId)?.let { filteringSource = it } }
                    )
                    DockSplitter(false, { d -> dock.bottomHeight = (bottomHeight.value - d).coerceIn(110f, areaHeight * 0.7f) }, dock::save) {
                        dock.bottomHeight = 0f; dock.save()
                    }
                    Row(Modifier.fillMaxWidth().height(bottomHeight)) {
                        Dock("Audio Mixer", Modifier.weight(1f).fillMaxHeight(), bottomScale) {
                            AudioMixerPanel(audioItems, { src, cfg -> vm.updateSourceConfig(src.id, cfg) }, vm::audioPeak, Modifier.fillMaxSize(), showHeader = false, onFilters = { audioManage = "filters" to it.id },
                                onProperties = { audioManage = "properties" to it.id },
                                onRename = { src, name -> vm.renameSource(src.id, name) },
                                onManageAll = { mode -> audioManage = mode to (audioManage?.second ?: audioItems.firstOrNull(::isAudioSource)?.id.orEmpty()) })
                        }
                        DockSplitter(true, { d -> dock.transitionsWidth = (transitionsWidth.value - d).coerceIn(120f, areaWidth * 0.35f) }, dock::save) {
                            dock.transitionsWidth = 0f; dock.save()
                        }
                        Dock("Scene Transitions", Modifier.width(transitionsWidth).fillMaxHeight(), transitionsScale) {
                            TransitionsDockContent(selectedTransition, vm::selectTransition, studio) { active?.id?.let { vm.setActiveScene(it) } }
                        }
                        DockSplitter(true, { d -> dock.controlsWidth = (controlsWidth.value - d).coerceIn(140f, areaWidth * 0.35f) }, dock::save) {
                            dock.controlsWidth = 0f; dock.save()
                        }
                        Dock("Controls", Modifier.width(controlsWidth).fillMaxHeight(), controlsScale) {
                            ControlsDockContent(
                                streamState = streamButton,
                                liveState = liveState,
                                onGoLive = { vm.goLive() },
                                onEndLive = { confirmStop = "live" },
                                isStudioMode = studio,
                                onStartStreaming = { vm.startStreaming() },
                                onStopStreaming = { if (general.confirmStopStreaming && (streamButton == StudioStreamState.LIVE || streamButton == StudioStreamState.RECONNECTING)) confirmStop = "streaming" else vm.stopStreaming() },
                                onToggleStudio = { vm.toggleStudioMode() },
                                onSettings = { onOpenSettings("General") },
                                onManageBroadcast = { yt = true },
                                broadcastTitle = broadcastTitle
                            )
                        }
                    }
                }
            }
            if (showStats) StatsPanel(streamStats, streaming, videoConfig.frameRate, AstraDeviceMonitor.label(thermalStatus), { showStats = false },
                maxWidth, maxHeight)
        }
        StudioStatusBar(
            isStreaming = streaming == StudioStreamState.LIVE,
            targetFps = videoConfig.frameRate,
            thermal = AstraDeviceMonitor.label(thermalStatus),
            reconnecting = streaming == StudioStreamState.RECONNECTING,
            bitrateBps = streamStats.bitrate,
            networkDropped = streamStats.droppedFrames,
            statsOpen = showStats,
            onToggleStats = { showStats = !showStats }
        )
    }
    confirmStop?.let { what ->
        ConfirmStopDialog(
            actionType = if (what == "live") "the YouTube broadcast (it ends for viewers and can't be restarted)" else what,
            onDismiss = { confirmStop = null },
            onConfirm = { if (what == "live") vm.endLive() else vm.stopStreaming(); confirmStop = null }
        )
    }
    if(search)FeatureSearchSheet(entries){search=false}
    if(showProjectionGuideDialog) AlertDialog(
        onDismissRequest = {
            showProjectionGuideDialog = false
            projectionRequested = false
            projectionDeniedForSources = true
        },
        title = { ClosableTitle("Choose what Stream4k can capture", { showProjectionGuideDialog = false; projectionRequested = false; projectionDeniedForSources = true }) },
        text = {
            Text(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "Android will show its screen-sharing prompt next. To capture one app, choose “A single app” or “Share an app”, select the app, then tap Start. Choose the entire display if you need the Astra screen. Android may not offer app sharing on older system updates."
                } else {
                    "Android will ask you to share the display. This Astra software version offers full-display capture only; the app cannot select another app window silently."
                }
            )
        },
        confirmButton = {
            Button(onClick = {
                showProjectionGuideDialog = false
                launchProjectionConsent()
            }) { Text("Continue to Android") }
        },
        dismissButton = {
            TextButton(onClick = {
                showProjectionGuideDialog = false
                projectionRequested = false
                projectionDeniedForSources = true
            }) { Text("Cancel") }
        }
    )
    if(showProjectionRetryDialog) AlertDialog(
        onDismissRequest = { showProjectionRetryDialog = false },
        title = { ClosableTitle("Capture permission needed", { showProjectionRetryDialog = false }) },
        text = { Text("Android did not grant screen or playback capture. These sources stay visible in the scene list, but they cannot capture until permission is granted.") },
        confirmButton = { Button(onClick = { requestProjectionPermission() }) { Text("Try again") } },
        dismissButton = { TextButton(onClick = { showProjectionRetryDialog = false }) { Text("Later") } }
    )
    if(yt)YouTubeBroadcastPicker({cfg,title->vm.setStreamConfig(cfg);broadcastTitle=title;yt=false},{yt=false})
    if(customRtmp)CustomRtmpDialog(videoConfig,importedRtmpEndpoint,{vm.setStreamConfig(it);customRtmp=false},{customRtmp=false})
    streamError?.let { message ->
        AlertDialog(onDismissRequest = vm::dismissStreamError, title = { ClosableTitle("Streaming could not start", vm::dismissStreamError) }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissStreamError) { Text("OK") } })
    }
    // New sources open their properties straight away so the device/file/URL can be chosen, like OBS.
    if(addSource)SourceTypePicker(onAdd={type->addSource=false;if(type=="SCENE")pickingNestedScene=true else vm.addSource(type){created->selectedSourceId=created.id;editingSource=created}},onDismiss={addSource=false})
    if(pickingNestedScene)NestedScenePicker(vm,onDismiss={pickingNestedScene=false})
    // One window for every audio source: tabs per source, switch between Properties and Filters.
    audioManage?.let { (mode, wantedId) ->
        val list = audioItems.filter(::isAudioSource)
        val src = list.firstOrNull { it.id == wantedId } ?: list.firstOrNull()
        if (src == null) { audioManage = null } else key(src.id, mode) {
            val tabs: @Composable () -> Unit = { AudioSourceTabs(list, src.id, mode, { audioManage = mode to it }, { audioManage = it to src.id }) }
            if (mode == "filters") FilterEditorScreen(
                source = src,
                onApply = { config -> vm.updateSourceConfig(src.id, config); audioManage = null },
                onCancel = { audioManage = null },
                header = tabs
            ) else SourcePropertiesDialog(
                source = src,
                usbManager = usb,
                onSave = { vm.updateSourceConfig(it.id, it.configJson); audioManage = null },
                runtimeError = sourceErrors[src.id],
                peakProvider = vm::audioPeak,
                header = tabs,
                onLiveChange = { vm.previewSourceConfig(src.id, it) },
                onDismiss = { vm.previewSourceConfig(src.id, src.configJson); audioManage = null }
            )
        }
    }
    filteringSource?.let { source ->
        FilterEditorScreen(
            source = source,
            onApply = { config -> vm.updateSourceConfig(source.id, config); filteringSource = null },
            onCancel = { filteringSource = null }
        )
    }
    editingSource?.let { source ->
        if (source.type.equals("COLOR", true)) {
            val initialColor = remember(source.id, source.configJson) {
                val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
                val settings = root.optJSONObject("settings") ?: root
                runCatching { android.graphics.Color.parseColor(settings.optString("color", "#FF000000")) }
                    .getOrDefault(android.graphics.Color.BLACK)
            }
            ColorPickerDialog(
                initialColor = initialColor,
                onDismiss = { editingSource = null },
                onColorSelected = { color ->
                    val root = runCatching { JSONObject(source.configJson) }.getOrDefault(JSONObject())
                    val settings = root.optJSONObject("settings")
                    val colorValue = String.format(java.util.Locale.US, "#%08X", color)
                    if (settings != null) settings.put("color", colorValue) else root.put("color", colorValue)
                    vm.updateSourceConfig(source.id, root.toString())
                    editingSource = null
                }
            )
        } else {
            SourcePropertiesDialog(
                source = source,
                usbManager = usb,
                onSave = { vm.updateSourceConfig(it.id, it.configJson); editingSource = null },
                onRemapSource = { item, targetType -> vm.remapImportedSource(item.id, targetType); editingSource = null },
                runtimeError = sourceErrors[source.id],
                peakProvider = vm::audioPeak,
                onLiveChange = { vm.previewSourceConfig(source.id, it) },
                onInteract = { id -> interactingBrowser = id },
                // Cancel / outside tap: put back the saved settings that live editing replaced.
                onDismiss = { vm.previewSourceConfig(source.id, source.configJson); editingSource = null }
            )
        }
    }
    interactingBrowser?.let { id ->
        // OBS's Interact: the page itself, full screen, to click and type into; the source keeps showing it.
        androidx.compose.ui.window.Dialog(onDismissRequest = { interactingBrowser = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                com.stream4k60.app.ui.common.ClosableTitle("Interact: ${sources.firstOrNull { it.id == id }?.name ?: "browser"}", { interactingBrowser = null })
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c -> android.widget.FrameLayout(c).also { frame -> frame.post { browser.beginInteraction(id, frame) } } },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        DisposableEffect(id) { onDispose { browser.endInteraction() } }
    }
    transformingSource?.let { source ->
        TransformDialog(
            source = source,
            canvasWidth = canvasOf(source.id).first,
            canvasHeight = canvasOf(source.id).second,
            onApply = { transform -> vm.updateSourceTransform(source.id, transform); transformingSource = null },
            onDismiss = { transformingSource = null }
        )
    }
}

private fun sourceSettings(configJson:String):JSONObject {
    val root=runCatching{JSONObject(configJson)}.getOrDefault(JSONObject())
    return root.optJSONObject("settings")?:root
}

@Composable private fun SourceTypePicker(onAdd:(String)->Unit,onDismiss:()->Unit){
    val types=listOf("USB_CAPTURE" to "USB camera / capture card","BROWSER" to "Browser","MEDIA" to "Media","IMAGE" to "Image","IMAGE_SLIDESHOW" to "Image slideshow","TEXT" to "Text","COLOR" to "Color","AUDIO_INPUT" to "Audio input","PLAYBACK_AUDIO" to "Android app audio","AUDIO_OUTPUT" to "Audio monitor output","SCENE" to "Scene (show another scene)","GROUP" to "Group")
    AlertDialog(onDismissRequest=onDismiss,title={ClosableTitle("Add source",onDismiss)},text={Column{types.forEach{(id,name)->TextButton(onClick={onAdd(id)},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=4.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(name);Text("+")}}}}},confirmButton={})
}

/** Chooses a scene to show inside the active one; scenes that would create a loop are not offered. */
@Composable private fun NestedScenePicker(vm: MainStudioViewModel, onDismiss: () -> Unit) {
    var choices by remember { mutableStateOf<List<SceneItem>?>(null) }
    LaunchedEffect(Unit) { choices = vm.nestableScenes() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle("Add scene", onDismiss) },
        text = {
            Column {
                when {
                    choices == null -> Text("Loading scenes…")
                    choices!!.isEmpty() -> Text("No other scene can be added here. Create another scene first; a scene that already shows this one cannot be added, because that would loop.")
                    else -> choices!!.forEach { scene ->
                        TextButton(onClick = { vm.addSceneSource(scene.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(scene.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
