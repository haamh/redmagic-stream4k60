package com.stream4k60.app.ui.main

import com.stream4k60.app.engine.HotkeyDispatcher
import com.stream4k60.app.engine.AudioFilterChain
import com.stream4k60.app.profile.ImportSelection
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stream4k60.app.data.local.entity.SceneEntity
import com.stream4k60.app.data.local.entity.SceneCollectionEntity
import com.stream4k60.app.data.local.entity.SourceEntity
import com.stream4k60.app.data.model.*
import com.stream4k60.app.data.repository.SceneRepository
import com.stream4k60.app.engine.UsbAudioSources
import com.stream4k60.app.data.repository.SettingsRepository
import com.stream4k60.app.engine.StreamEngine
import com.stream4k60.app.engine.NativeEngine
import com.stream4k60.app.engine.NativeAudioGraph
import com.stream4k60.app.engine.NativeAudioBridge
import com.stream4k60.app.engine.SourceRuntimeErrors
import com.stream4k60.app.engine.StreamState
import com.stream4k60.app.engine.RecordState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class SceneItem(val id:String,val name:String,val isActive:Boolean)
data class SourceItem(val id:String,val name:String,val type:String,val isVisible:Boolean,val isLocked:Boolean,val configJson:String,val transformJson:String="{}")
/**
 * A source as the compositor draws it. [owner] is "" for the program canvas, or the key of the nested scene /
 * group canvas it draws into. [containerKey] is set for SCENE and GROUP sources: the canvas they show.
 */
data class RenderSource(val item:SourceItem,val owner:String,val z:Int,val containerKey:String?)
object NestedSources {
 const val GROUP_PREFIX="group:"
 const val MAX_DEPTH=6
 val CONTAINER_TYPES=setOf("SCENE","GROUP")
 /** Canvas key a SCENE/GROUP source shows; group children are stored under this key as their sceneId. */
 fun containerKey(item:SourceItem):String? = when(item.type.uppercase()){
  "GROUP"->GROUP_PREFIX+item.id
  "SCENE"->runCatching{org.json.JSONObject(item.configJson).let{it.optJSONObject("settings")?:it}.optString("sceneId")}.getOrNull()?.takeIf{it.isNotBlank()}
  else->null
 }
}
enum class StudioStreamState{IDLE,CONNECTING,LIVE,RECONNECTING,STOPPING,ERROR}
/** The YouTube broadcast itself (Go Live / End Live), separate from sending video. UNAVAILABLE: no broadcast picked. */
enum class StudioLiveState{UNAVAILABLE,READY,GOING_LIVE,LIVE,ENDING,ENDED}
enum class StudioRecordState{IDLE,RECORDING,PAUSED,STOPPING,ERROR}

@HiltViewModel class MainStudioViewModel @Inject constructor(private val repo:SceneRepository,private val engine:StreamEngine,private val settingsRepository:SettingsRepository,@ApplicationContext private val context:Context):ViewModel(){
 private val sourceTransformMutex=Mutex()
 /** OBS's Undo / Redo over scene and source edits. */
 private val history=UndoHistory(repo)
 val undoState=history.state
 private var historyCollection:String?=null
 private suspend fun cp(label:String,coalesceKey:String?=null){_activeSceneCollectionId.value?.let{runCatching{history.checkpoint(it,label,coalesceKey)}}}
 fun undo(){viewModelScope.launch{if(history.undo())load()}}
 fun redo(){viewModelScope.launch{if(history.redo())load()}}
 private val audioSettingsState=settingsRepository.audioSettings.stateIn(viewModelScope,SharingStarted.Eagerly,AudioSettings())
 val advancedSettings=settingsRepository.advancedSettings.stateIn(viewModelScope,SharingStarted.Eagerly,AdvancedSettings())
 @Volatile private var pushToTalkHeld=false
 private val _audioItems=MutableStateFlow<List<SourceItem>>(emptyList())
 /** Everything with audio the program uses: scene sources, nested content and the global Desktop/Mic sources. */
 val audioItems=_audioItems.asStateFlow()

 private val _renderSources=MutableStateFlow<List<RenderSource>>(emptyList());val renderSources=_renderSources.asStateFlow()
 /**
  * Sources of other scenes that stay running while hidden, as OBS keeps a shared source alive: the originals of
  * references shown by this scene, and every capture device (so switching back to a scene doesn't restart cameras).
  * They're never drawn themselves; references read their frames.
  */
 private val _backingSources=MutableStateFlow<List<SourceItem>>(emptyList());val backingSources=_backingSources.asStateFlow()
 private var backingScenes=emptyMap<String,String>()
 /** Items inside each group of the active scene (keyed by group source id), bottom layer first. */
 private val _groupChildren=MutableStateFlow<Map<String,List<SourceItem>>>(emptyMap());val groupChildren=_groupChildren.asStateFlow()
 @Volatile private var syncAudioGraphErrors: Set<String> = emptySet()
 private val collectionPrefs=context.getSharedPreferences("stream4k_studio",Context.MODE_PRIVATE)
 private val _currentSceneCollection=MutableStateFlow("Default");val currentSceneCollection=_currentSceneCollection.asStateFlow()
 private val _sceneCollections=MutableStateFlow<List<SceneCollectionEntity>>(emptyList());val sceneCollections=_sceneCollections.asStateFlow()
 private val _activeSceneCollectionId=MutableStateFlow<String?>(null);val activeSceneCollectionId=_activeSceneCollectionId.asStateFlow()
 private val _activeScene=MutableStateFlow<SceneItem?>(null);val activeScene=_activeScene.asStateFlow();private val _scenes=MutableStateFlow<List<SceneItem>>(emptyList());val scenes=_scenes.asStateFlow();private val _sources=MutableStateFlow<List<SourceItem>>(emptyList());val sources=_sources.asStateFlow();private val _isStudio=MutableStateFlow(false);val isStudioModeEnabled=_isStudio.asStateFlow();private val _transition=MutableStateFlow(collectionPrefs.getString("transitionName","Fade")?:"Fade");val selectedTransition=_transition.asStateFlow();private val _transitionDuration=MutableStateFlow(collectionPrefs.getInt("transitionDurationMs",400).coerceIn(SceneTransitions.MIN_MS,SceneTransitions.MAX_MS));val transitionDuration=_transitionDuration.asStateFlow();private var transitionJob:kotlinx.coroutines.Job?=null;private val _streamConfig=MutableStateFlow(StreamConfig());val streamConfig=_streamConfig.asStateFlow();private val _recordConfig=MutableStateFlow(RecordingConfig());val recordConfig=_recordConfig.asStateFlow();private val _streamError=MutableStateFlow<String?>(null);val streamError=_streamError.asStateFlow()
 val streamState=engine.streamState.map{when(it){StreamState.IDLE->StudioStreamState.IDLE;StreamState.CONNECTING->StudioStreamState.CONNECTING;StreamState.LIVE->StudioStreamState.LIVE;StreamState.RECONNECTING->StudioStreamState.RECONNECTING;StreamState.STOPPING->StudioStreamState.STOPPING;StreamState.ERROR->StudioStreamState.ERROR}}.stateIn(viewModelScope,SharingStarted.Eagerly,StudioStreamState.IDLE)
 val recordState=engine.recordState.map{when(it){RecordState.IDLE->StudioRecordState.IDLE;RecordState.RECORDING->StudioRecordState.RECORDING;RecordState.PAUSED->StudioRecordState.PAUSED;RecordState.STOPPING->StudioRecordState.STOPPING;RecordState.ERROR->StudioRecordState.ERROR}}.stateIn(viewModelScope,SharingStarted.Eagerly,StudioRecordState.IDLE)
 val videoConfig=settingsRepository.videoConfig.stateIn(viewModelScope,SharingStarted.Eagerly,VideoConfig())
 val importedRtmpEndpoint=settingsRepository.importedRtmpEndpoint.stateIn(viewModelScope,SharingStarted.Eagerly,null)
 init {
  // Global audio devices/levels and push-to-talk changes re-sync the native mixer.
  viewModelScope.launch { audioSettingsState.drop(1).collect { syncAudioGraph() } }
  // While nothing is live the preview follows Settings → Video → Colour / HDR (a stream or recording sets it when it starts).
  viewModelScope.launch { kotlinx.coroutines.flow.combine(videoConfig,streamState,recordState){v,s,r->if((s==StudioStreamState.IDLE||s==StudioStreamState.ERROR)&&(r==StudioRecordState.IDLE||r==StudioRecordState.ERROR))v.outputColor else null}.collect{c->if(c!=null)NativeEngine.setOutputColor(c.transfer,c.tenBit,c.sdrWhiteLevel.toFloat(),c.hdrNominalPeak.toFloat())} }
  // A live stream that ends on its own (reconnect gave up) explains why instead of silently going idle.
  viewModelScope.launch { var prev=StreamState.IDLE; engine.streamState.collect { st ->
   if(st==StreamState.ERROR&&(prev==StreamState.LIVE||prev==StreamState.RECONNECTING)) _streamError.value="Connection to the streaming server was lost and reconnecting failed. Check your internet, then start streaming again."
   prev=st } }
  // OBS's window.obsstudio API for browser sources (what each page may use depends on its Page permissions).
  com.stream4k60.app.engine.StudioBridge.apply {
   fun sceneJson()=org.json.JSONObject().put("name",_activeScene.value?.name.orEmpty()).put("width",videoConfig.value.baseResWidth).put("height",videoConfig.value.baseResHeight)
   currentScene={sceneJson()}
   scenes={org.json.JSONArray(_scenes.value.map{it.name})}
   transitions={org.json.JSONArray(SceneTransitions.names)}
   currentTransition={_transition.value}
   setCurrentScene={name->_scenes.value.firstOrNull{it.name==name}?.let{setActiveScene(it.id)}}
   setCurrentTransition={name->selectTransition(name)}
   status={org.json.JSONObject().put("streaming",streamState.value in setOf(StudioStreamState.LIVE,StudioStreamState.RECONNECTING)).put("recording",recordState.value==StudioRecordState.RECORDING).put("recordingPaused",recordState.value==StudioRecordState.PAUSED).put("replaybuffer",false).put("virtualcam",false)}
   startStreaming={this@MainStudioViewModel.startStreaming()}
   stopStreaming={this@MainStudioViewModel.stopStreaming()}
   viewModelScope.launch{_activeScene.map{it?.name}.distinctUntilChanged().drop(1).collect{emit("obsSceneChanged",sceneJson())}}
   viewModelScope.launch{streamState.drop(1).collect{st->when(st){StudioStreamState.CONNECTING->emit("obsStreamingStarting");StudioStreamState.LIVE->emit("obsStreamingStarted");StudioStreamState.STOPPING->emit("obsStreamingStopping");StudioStreamState.IDLE->emit("obsStreamingStopped");else->Unit}}}
  }
  // Every 30 s: where the render time goes (also when not streaming), the monitor output (format, path, level) and the
  // direct USB inputs' levels.
  viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default){
   while(true){
    kotlinx.coroutines.delay(30_000)
    if(streamState.value!=StudioStreamState.LIVE)runCatching{com.stream4k60.app.engine.StreamLog.add("Renderer: ${NativeEngine.describeRender()}")}
    val m=NativeAudioGraph.monitorInfo().takeIf{it.isNotBlank()}?:continue
    val inputs=renderedItems().mapNotNull{src->UsbAudioSources.mixerInputId(src)?.let{id->val p=NativeAudioGraph.peak(id);"${src.name} ${if(p>1e-6f)"%.1f".format(20*kotlin.math.log10(p)) else "-inf"} dBFS"}}
    com.stream4k60.app.engine.StreamLog.add("Monitor: $m${if(inputs.isNotEmpty())"; USB inputs: "+inputs.joinToString() else ""}")
   }
  }
  viewModelScope.launch { settingsRepository.hotkeys.collect { HotkeyDispatcher.setBindings(HotkeyDispatcher.defaultBindings + (it ?: emptyMap())) /* actions added later (Undo / Redo) get their default keys */ } }
  viewModelScope.launch {
   ImportSelection.requestedCollectionId.filterNotNull().collect { id ->
    // The collections flow can lag the import's database writes.
    withTimeoutOrNull(5_000) { _sceneCollections.first { list -> list.any { it.id == id } } }
    selectSceneCollection(id)
    ImportSelection.requestedCollectionId.compareAndSet(id, null)
   }
  }
  viewModelScope.launch {
   if(repo.collections().first().isEmpty()) {
    val cid=UUID.randomUUID().toString()
    repo.saveCollection(SceneCollectionEntity(cid,"Default"))
    val sid=UUID.randomUUID().toString()
    repo.saveScene(SceneEntity(sid,cid,"Scene 1",0,true))
   }
   val collections=repo.collections().first()
   val preferredId=collectionPrefs.getString("activeCollectionId",null)
   val initial=collections.firstOrNull{it.id==preferredId} ?: collections.firstOrNull{it.name=="Default"} ?: collections.firstOrNull()
   initial?.let { _activeSceneCollectionId.value=it.id;_currentSceneCollection.value=it.name;collectionPrefs.edit().putString("activeCollectionId",it.id).apply();load(it) }
   repo.collections().collect { latest ->
    _sceneCollections.value=latest
    val selected=latest.firstOrNull{it.id==_activeSceneCollectionId.value} ?: latest.firstOrNull{it.name=="Default"} ?: latest.firstOrNull()
    if(selected!=null && selected.id!=_activeSceneCollectionId.value) {
     _activeSceneCollectionId.value=selected.id
     _currentSceneCollection.value=selected.name
     collectionPrefs.edit().putString("activeCollectionId",selected.id).apply()
     load(selected)
    }
   }
  }
 }
 private suspend fun load(collection:SceneCollectionEntity?=null){
  val all=repo.collections().first()
  _sceneCollections.value=all
  val c=collection ?: all.firstOrNull{it.id==_activeSceneCollectionId.value} ?: all.firstOrNull{it.name=="Default"} ?: all.firstOrNull() ?: return
  _activeSceneCollectionId.value=c.id;_currentSceneCollection.value=c.name
  // Undo history belongs to one scene collection.
  if(historyCollection!=c.id){if(historyCollection!=null)history.clear();historyCollection=c.id}
  val ss=repo.loadScenes(c.id);_scenes.value=ss.map{SceneItem(it.id,it.name,it.active)}
  val selectedScene=ss.firstOrNull{it.id==c.activeSceneId} ?: ss.firstOrNull{it.active} ?: ss.firstOrNull()
  _activeScene.value=selectedScene?.let{SceneItem(it.id,it.name,true)}
  val active=_activeScene.value;_sources.value=if(active!=null)repo.loadSources(active.id).map(::toItem)else emptyList()
  _renderSources.value=if(active!=null)expandRenderSources(_sources.value,active.id)else emptyList()
  loadBackingSources(ss.map{it.id},active?.id)
  _groupChildren.value=_sources.value.filter{it.type.equals("GROUP",true)}.associate{g->g.id to repo.loadSources(NestedSources.GROUP_PREFIX+g.id).map(::toItem)}
  syncAudioGraph()
 }
 private suspend fun loadBackingSources(sceneIds:List<String>,activeId:String?){
  val shown=_renderSources.value.map{it.item}
  val shownIds=shown.map{it.id}.toSet()
  val wanted=shown.mapNotNull{com.stream4k60.app.engine.SourceReferences.targetOf(it.configJson)}.filter{it !in shownIds}.toMutableSet()
  val shownDevices=shown.filter{it.type.equals("USB_CAPTURE",true)&&it.isVisible}.map{usbDeviceKey(it.configJson)}.toSet()
  val out=mutableListOf<SourceItem>();val scenesOf=mutableMapOf<String,String>()
  // Keep only reference originals and the already-supported background USB captures alive. The transition work must not
  // implicitly start every inactive scene media/browser/camera source: doing that can consume hardware decoder/GPU/USB
  // resources before the stream encoder is created, and it was not part of the last known-good streaming path.
  for(sceneId in sceneIds){
   if(sceneId==activeId)continue
   for(row in repo.loadSources(sceneId)){
    if(row.id in shownIds||row.id in scenesOf)continue
    val item=toItem(row)
    val device=item.type.equals("USB_CAPTURE",true)&&item.isVisible&&com.stream4k60.app.engine.SourceReferences.targetOf(item.configJson)==null&&usbDeviceKey(item.configJson) !in shownDevices
    if(row.id in wanted||device){out+=item.copy(isVisible=true);scenesOf[row.id]=sceneId}
   }
  }
  backingScenes=scenesOf
  _backingSources.value=out
 }
 private fun toItem(it:SourceEntity)=SourceItem(it.id,it.name,it.type,it.visible,it.locked,it.configJson,it.transformJson)
 /** Flattens the active scene plus every visible nested scene/group it shows, each nested canvas once. */
 private suspend fun expandRenderSources(top:List<SourceItem>,rootSceneId:String):List<RenderSource>{
  val out=mutableListOf<RenderSource>()
  val expanded=mutableSetOf<String>()
  suspend fun walk(items:List<SourceItem>,owner:String,depth:Int,path:Set<String>){
   items.forEachIndexed{z,item->
    val key=NestedSources.containerKey(item)
    out+=RenderSource(item,owner,z,key)
    if(key==null||!item.isVisible||depth>=NestedSources.MAX_DEPTH||key in path||!expanded.add(key))return@forEachIndexed
    walk(repo.loadSources(key).map(::toItem),key,depth+1,path+key)
   }
  }
  walk(top,"",0,setOf(rootSceneId))
  return out
 }
 /** Containers whose rows can be edited from the studio: the active scene and the groups it shows. */
 private fun editableContainers():List<String> = (listOfNotNull(_activeScene.value?.id)+
  _renderSources.value.mapNotNull{it.containerKey}+
  _groupChildren.value.keys.map{NestedSources.GROUP_PREFIX+it}).distinct()
 private suspend fun rowFor(id:String):SourceEntity?{
  for(container in editableContainers())repo.loadSources(container).firstOrNull{it.id==id}?.let{return it}
  return null
 }
 /** Deletes a source and, for groups, everything inside them. */
 private suspend fun deleteDeep(row:SourceEntity){
  if(row.type.equals("GROUP",true))repo.loadSources(NestedSources.GROUP_PREFIX+row.id).forEach{deleteDeep(it)}
  repo.deleteSource(row.id)
 }
 /** Copies a source into [container]; groups are copied with their contents. */
 private suspend fun copyDeep(row:SourceEntity,container:String,sortOrder:Int,name:String):SourceEntity{
  val copy=row.copy(id=UUID.randomUUID().toString(),sceneId=container,name=name,sortOrder=sortOrder,visible=true,locked=false)
  repo.saveSources(listOf(copy))
  if(row.type.equals("GROUP",true))repo.loadSources(NestedSources.GROUP_PREFIX+row.id).forEach{child->copyDeep(child,NestedSources.GROUP_PREFIX+copy.id,child.sortOrder,child.name)}
  return copy
 }
 /** Scenes that [sceneId] can show without creating a loop. */
 suspend fun nestableScenes():List<SceneItem>{
  val active=_activeScene.value?:return emptyList()
  val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return emptyList()
  val all=repo.loadScenes(c.id)
  suspend fun reaches(from:String,target:String,seen:MutableSet<String>):Boolean{
   if(from==target)return true
   if(!seen.add(from))return false
   return repo.loadSources(from).any{row->NestedSources.containerKey(toItem(row))?.let{reaches(it,target,seen)}==true}
  }
  return all.filter{it.id!=active.id&&!reaches(it.id,active.id,mutableSetOf())}.map{SceneItem(it.id,it.name,false)}
 }
 fun addSceneSource(sceneId:String){viewModelScope.launch{cp("Add scene source");
  val s=_activeScene.value?:return@launch
  val name=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?.let{c->repo.loadScenes(c.id).firstOrNull{it.id==sceneId}?.name}?:"Scene"
  val rows=repo.loadSources(s.id)
  repo.saveSources(listOf(SourceEntity(UUID.randomUUID().toString(),s.id,name,"SCENE",rows.size,true,false,"{\"sceneId\":\"$sceneId\"}","{}","{}")))
  load()
 }}
 /** Moves a source into a group (keeping its canvas position when the group sits at the origin unscaled). */
 fun moveSourceIntoGroup(id:String,groupId:String){viewModelScope.launch{cp("Move into group");
  val row=rowFor(id)?:return@launch
  if(row.id==groupId)return@launch
  val container=NestedSources.GROUP_PREFIX+groupId
  // A group cannot contain itself or its ancestors.
  if(row.type.equals("GROUP",true)&&(container==NestedSources.GROUP_PREFIX+row.id||isInside(groupId,NestedSources.GROUP_PREFIX+row.id)))return@launch
  repo.saveSources(listOf(row.copy(sceneId=container,sortOrder=repo.loadSources(container).size)))
  load()
 }}
 fun moveSourceOutOfGroup(id:String){viewModelScope.launch{cp("Move out of group");
  val row=rowFor(id)?:return@launch
  if(!row.sceneId.startsWith(NestedSources.GROUP_PREFIX))return@launch
  val s=_activeScene.value?:return@launch
  repo.saveSources(listOf(row.copy(sceneId=s.id,sortOrder=repo.loadSources(s.id).size)))
  load()
 }}
 private suspend fun isInside(groupId:String,container:String):Boolean{
  for(row in repo.loadSources(container)){
   if(row.id==groupId)return true
   if(row.type.equals("GROUP",true)&&isInside(groupId,NestedSources.GROUP_PREFIX+row.id))return true
  }
  return false
 }
 fun selectSceneCollection(id:String){
  val collection=_sceneCollections.value.firstOrNull{it.id==id}?:return
  _activeSceneCollectionId.value=id;_currentSceneCollection.value=collection.name
  collectionPrefs.edit().putString("activeCollectionId",id).apply()
  viewModelScope.launch{load(collection)}
 }
 fun setStreamConfig(c:StreamConfig){_streamConfig.value=c;_liveState.value=StudioLiveState.READY;watchBroadcast(c.broadcastId)}
 private var broadcastWatch:kotlinx.coroutines.Job?=null
 /**
  * Follows the picked broadcast's real state on YouTube: it can start by itself (auto-start once video arrives) or be
  * started/ended in YouTube Studio, and the Go Live button should say so. YouTube offers no push for this (OBS polls
  * too), so it is checked once when picked, every 15 s only while streaming (when it can change), and once when
  * streaming stops; never while idle. Each check is 1 unit of the 10,000-a-day API quota.
  */
 private fun watchBroadcast(id:String?){
  broadcastWatch?.cancel()
  if(id.isNullOrBlank())return
  suspend fun check(){
   if(_liveState.value==StudioLiveState.GOING_LIVE||_liveState.value==StudioLiveState.ENDING)return
   if(com.stream4k60.app.youtube.YouTubeAuthSession.accessToken==null)return
   runCatching{youTube().broadcastState(id).first}.onSuccess{life->
    // A Go Live / End Live started meanwhile owns the state until it finishes.
    if(_liveState.value!=StudioLiveState.GOING_LIVE&&_liveState.value!=StudioLiveState.ENDING)
     _liveState.value=when(life){"live","liveStarting"->StudioLiveState.LIVE;"complete","revoked"->StudioLiveState.ENDED;else->StudioLiveState.READY}
   }
  }
  broadcastWatch=viewModelScope.launch{
   streamState.map{it==StudioStreamState.LIVE||it==StudioStreamState.RECONNECTING}.distinctUntilChanged().collectLatest{streaming->
    check()
    while(streaming){kotlinx.coroutines.delay(15_000L);check()}
   }
  }
 }
 private val _liveState=MutableStateFlow(StudioLiveState.READY)
 /** Go Live needs a broadcast picked in Manage Broadcast: its id is what the YouTube API takes live. */
 val liveState=combine(_liveState,_streamConfig){s,c->if(c.broadcastId.isNullOrBlank())StudioLiveState.UNAVAILABLE else s}
  .stateIn(viewModelScope,SharingStarted.Eagerly,StudioLiveState.UNAVAILABLE)
 private fun youTube()=com.stream4k60.app.youtube.YouTubeService{com.stream4k60.app.youtube.YouTubeAuthSession.accessToken}
 private suspend fun waitForYouTubeIngest(config:StreamConfig){
  if(config.service!=StreamService.YOUTUBE||config.broadcastId.isNullOrBlank())return
  // A saved manual RTMPS destination may not have a YouTube OAuth session; without OAuth we cannot verify
  // provider-side ingest, so the generic publisher readiness check remains the source of truth.
  if(com.stream4k60.app.youtube.YouTubeAuthSession.accessToken==null)return
  val (_,streamId)=youTube().broadcastState(config.broadcastId)
  streamId?:error("YouTube broadcast has no bound ingest stream; open that broadcast in YouTube Studio and pick it again.")
  var lastStatus:String?=null
  repeat(30){
   if(startCancelled.value)error("Stopped while connecting")
   lastStatus=youTube().streamStatus(streamId)
   if(lastStatus.equals("active",true))return
   kotlinx.coroutines.delay(1000)
  }
  error("Video was sent to YouTube, but YouTube did not report the bound stream as receiving it"+
    (lastStatus?.let{" (status: $it)"}.orEmpty())+".")
 }
 fun goLive()=viewModelScope.launch{
  val id=_streamConfig.value.broadcastId?:return@launch
  if(com.stream4k60.app.youtube.YouTubeAuthSession.accessToken==null){_streamError.value="Connect YouTube in Manage Broadcast to go live from here.";return@launch}
  if(streamState.value!=StudioStreamState.LIVE&&streamState.value!=StudioStreamState.RECONNECTING){_streamError.value="Start streaming first: YouTube can only go live while it receives your video.";return@launch}
  _liveState.value=StudioLiveState.GOING_LIVE
  runCatching{youTube().goLive(id)}
   .onSuccess{_liveState.value=StudioLiveState.LIVE;com.stream4k60.app.engine.StreamLog.add("YouTube broadcast is live")}
   .onFailure{_liveState.value=StudioLiveState.READY;_streamError.value="Couldn't go live: ${it.message}";com.stream4k60.app.engine.StreamLog.add("Go Live failed: ${it.message}")}
 }
 fun endLive()=viewModelScope.launch{
  val id=_streamConfig.value.broadcastId?:return@launch
  _liveState.value=StudioLiveState.ENDING
  runCatching{youTube().transitionBroadcast(id,"complete")}
   .onSuccess{_liveState.value=StudioLiveState.ENDED;com.stream4k60.app.engine.StreamLog.add("YouTube broadcast ended")}
   .onFailure{_liveState.value=StudioLiveState.LIVE;_streamError.value="Couldn't end the broadcast: ${it.message}"}
 }
 fun dismissStreamError(){_streamError.value=null}
 val streamStats=engine.streamStats
 fun setRecordConfig(c:RecordingConfig){_recordConfig.value=c}
  fun addScene(name:String){viewModelScope.launch{cp("Add scene");val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch;val current=repo.loadScenes(c.id);val id=UUID.randomUUID().toString();repo.saveScene(SceneEntity(id,c.id,name.ifBlank{"Scene ${current.size+1}"},current.size,false));load(c)}}
  fun removeScene(){viewModelScope.launch{cp("Remove scene");val a=_activeScene.value?:return@launch;val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch;val all=repo.loadScenes(c.id);if(all.size>1){val next=all.first{it.id!=a.id};repo.loadScenes(c.id).forEach{row->if(row.id==next.id)repo.saveScene(row.copy(active=true)) else if(row.active)repo.saveScene(row.copy(active=false))};repo.saveCollection(c.copy(activeSceneId=next.id));load(c.copy(activeSceneId=next.id))}}}
 fun setActiveScene(id:String){viewModelScope.launch{
   val c=repo.collections().first().firstOrNull{it.id==_activeSceneCollectionId.value}?:return@launch
  val type=SceneTransitions.code(_transition.value)
  if(type==0){activateScene(c.id,id);return@launch}
  // Move: sources in both scenes (the same source or a synced copy of it) glide from the old layout to the new one.
  val pairs=if(type==SceneTransitions.MOVE)movePairs(_sources.value,repo.loadSources(id).map(::toItem)) else emptyList()
  // The compositor freezes the outgoing scene at its next frame; the new scene is switched in underneath the snapshot,
  // and the animation starts once its sources are on the canvas, timed per frame on the render thread.
  NativeEngine.beginSceneTransition(type,_transitionDuration.value,pairs.toTypedArray())
  var waited=0
  while(NativeEngine.sceneTransitionPhase()==1&&waited<250){kotlinx.coroutines.delay(4);waited+=4}
  activateScene(c.id,id)
  kotlinx.coroutines.delay(80)
  NativeEngine.startSceneTransition()
}.also{job->transitionJob?.cancel();transitionJob=job}}
/** (old id, new id) pairs of sources both scenes show: the same source, or synced copies of one source, in order. */
private fun movePairs(old:List<SourceItem>,new:List<SourceItem>):List<String>{
  fun identity(s:SourceItem)=com.stream4k60.app.engine.SourceReferences.targetOf(s.configJson)?:s.id
  val pool=old.filter{it.isVisible}.groupBy(::identity).mapValues{it.value.toMutableList()}
  val out=mutableListOf<String>()
  for(n in new.filter{it.isVisible}){val o=pool[identity(n)]?.removeFirstOrNull()?:continue;out+=o.id;out+=n.id}
  return out
}
private suspend fun activateScene(collectionId:String,id:String){repo.loadScenes(collectionId).forEach{repo.saveScene(it.copy(active=it.id==id))};repo.saveCollection(repo.collections().first().firstOrNull{it.id==collectionId}?.copy(activeSceneId=id)?:return);load()}
 /** Adds a source; [onCreated] receives it so the studio can open its properties, like OBS. */
 fun addSource(type:String,onCreated:(SourceItem)->Unit={}){viewModelScope.launch{cp("Add source");val s=_activeScene.value?:return@launch;val rows=repo.loadSources(s.id);val id=UUID.randomUUID().toString()
  // A new group's canvas matches the program canvas, so items moved into it keep their positions.
  val config=if(type.equals("GROUP",true))settingsRepository.videoConfig.first().let{"{\"width\":${it.baseResWidth},\"height\":${it.baseResHeight}}"}else defaultConfigFor(type)
  repo.saveSources(listOf(SourceEntity(id,s.id,displayNameFor(type,rows.size),type,rows.size,true,false,config,"{}","{}")));load()
  _sources.value.firstOrNull{it.id==id}?.let(onCreated)}}
 fun removeSource(id:String?=null){viewModelScope.launch{cp("Remove source");val s=_activeScene.value?:return@launch;val target=id?.let{rowFor(it)}?:repo.loadSources(s.id).lastOrNull();target?.let{deleteDeep(it)};load()}}
 fun renameSource(id:String,name:String){viewModelScope.launch{cp("Rename source");val clean=name.trim().take(80);if(clean.isEmpty())return@launch;rowFor(id)?.let{repo.saveSources(listOf(it.copy(name=clean)))};load()}}
 fun duplicateSource(id:String){viewModelScope.launch{cp("Duplicate source");
  val original=rowFor(id)?:return@launch
  val rows=repo.loadSources(original.sceneId).sortedBy{it.sortOrder}
  val insertion=original.sortOrder+1
  repo.saveSources(rows.filter{it.sortOrder>=insertion}.map{it.copy(sortOrder=it.sortOrder+1)})
  copyDeep(original,original.sceneId,insertion,"${original.name} copy".take(80))
  load()
 }}
 /**
  * OBS's Paste (Reference): the same source shown again — same frames, always in sync, no second decoder — with its
  * own transform. A reference of a reference shows the original. Groups are copied instead.
  */
 fun duplicateSourceAsReference(id:String){viewModelScope.launch{cp("Duplicate source (reference)");
  val original=rowFor(id)?:return@launch
  if(original.type.equals("GROUP",true)){duplicateSource(id);return@launch}
  val target=com.stream4k60.app.engine.SourceReferences.targetOf(original.configJson)?:original.id
  val rows=repo.loadSources(original.sceneId).sortedBy{it.sortOrder}
  val insertion=original.sortOrder+1
  repo.saveSources(rows.filter{it.sortOrder>=insertion}.map{it.copy(sortOrder=it.sortOrder+1)})
  val root=runCatching{org.json.JSONObject(original.configJson)}.getOrDefault(org.json.JSONObject())
  (root.optJSONObject("settings")?:root).put("referenceOf",target)
  val baseName=original.name.removeSuffix(" (reference)")
  repo.saveSources(listOf(original.copy(id=UUID.randomUUID().toString(),name="$baseName (reference)".take(80),sortOrder=insertion,visible=true,locked=false,configJson=root.toString())))
  load()
 }}
 /** Adds [id] to another scene as a synced copy (a reference): same source, same frames, its own position there. */
 fun copySourceToScene(id:String,sceneId:String){viewModelScope.launch{cp("Copy source to scene")
  val original=rowFor(id)?:return@launch
  if(original.type.equals("GROUP",true)||original.sceneId==sceneId)return@launch
  val target=com.stream4k60.app.engine.SourceReferences.targetOf(original.configJson)?:original.id
  val top=(repo.loadSources(sceneId).maxOfOrNull{it.sortOrder}?:-1)+1
  val root=runCatching{org.json.JSONObject(original.configJson)}.getOrDefault(org.json.JSONObject())
  (root.optJSONObject("settings")?:root).put("referenceOf",target)
  val baseName=original.name.removeSuffix(" (reference)")
  repo.saveSources(listOf(original.copy(id=UUID.randomUUID().toString(),sceneId=sceneId,name="$baseName (reference)".take(80),sortOrder=top,visible=true,locked=false,configJson=root.toString())))
  load()
 }}
 fun resetSourceTransform(id:String){updateSourceTransform(id,"{}")}
 fun moveSourceInStack(id:String,displayDelta:Int){viewModelScope.launch{cp("Reorder sources");
  val container=rowFor(id)?.sceneId?:return@launch
  val displayed=repo.loadSources(container).sortedBy{it.sortOrder}.asReversed().toMutableList()
  val from=displayed.indexOfFirst{it.id==id};if(from<0)return@launch
  val to=(from+displayDelta).coerceIn(0,displayed.lastIndex);if(to==from)return@launch
  val moved=displayed.removeAt(from);displayed.add(to,moved)
  repo.saveSources(displayed.asReversed().mapIndexed{index,row->row.copy(sortOrder=index)})
  load()
 }}
 fun moveSourceToDisplayIndex(id:String,targetIndex:Int){viewModelScope.launch{cp("Reorder sources");
  val container=rowFor(id)?.sceneId?:return@launch
  val displayed=repo.loadSources(container).sortedBy{it.sortOrder}.asReversed().toMutableList()
  val from=displayed.indexOfFirst{it.id==id};if(from<0)return@launch
  val to=targetIndex.coerceIn(0,displayed.lastIndex);if(to==from)return@launch
  val moved=displayed.removeAt(from);displayed.add(to,moved)
  repo.saveSources(displayed.asReversed().mapIndexed{index,row->row.copy(sortOrder=index)})
  load()
 }}
 fun toggleSourceVisibility(id:String){viewModelScope.launch{cp("Show / hide source");rowFor(id)?.let{repo.saveSources(listOf(it.copy(visible=!it.visible)))};load()}}
 fun toggleSourceLock(id:String){viewModelScope.launch{cp("Lock / unlock source");rowFor(id)?.let{repo.saveSources(listOf(it.copy(locked=!it.locked)))};load()}}
 private fun defaultConfigFor(type:String)=when(type.uppercase()){
  "CAMERA"->"{\"facing\":\"BACK\",\"width\":1920,\"height\":1080,\"fps\":30}"
  "USB_CAPTURE"->"{\"deviceId\":-1,\"width\":1920,\"height\":1080,\"fps\":30,\"format\":\"MJPEG\"}"
  "BROWSER"->"{\"url\":\"about:blank\",\"width\":1280,\"height\":720,\"fps\":30,\"hardwareAccelerated\":true,\"javaScript\":true,\"customCss\":\"body { background-color: rgba(0, 0, 0, 0); margin: 0px auto; overflow: hidden; }\",\"refreshToken\":0}"
  // No width/height: like OBS, a media source is the size of its video (reported once it plays).
  "MEDIA"->"{\"url\":\"\",\"file\":\"\",\"loop\":true,\"audioEnabled\":true,\"decoderPreference\":\"hardware\",\"playbackSpeed\":1.0,\"startPositionMs\":0,\"volume\":1.0,\"balance\":0.0,\"muted\":false,\"monitoring\":\"OUTPUT_ONLY\",\"syncOffsetMs\":0,\"solo\":false}"
  "IMAGE"->"{\"file\":\"\"}"
  "IMAGE_SLIDESHOW"->"{\"files\":[],\"slideIntervalSeconds\":5,\"loop\":true,\"randomize\":false}"
  "TEXT"->"{\"text\":\"Text Source\",\"fontSize\":64,\"textColor\":\"#FFFFFFFF\",\"backgroundColor\":\"#00000000\",\"bold\":false,\"italic\":false,\"alignment\":\"LEFT\"}"
  "COLOR"->"{\"color\":\"#FF000000\",\"width\":1280,\"height\":720}"
  "AUDIO_INPUT"->{
   val am=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   val id=am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull()?.id ?: -1
   "{\"deviceId\":$id,\"volume\":1.0,\"balance\":0.0,\"muted\":false,\"monitoring\":\"MONITOR_AND_OUTPUT\",\"syncOffsetMs\":0,\"solo\":false}"
  }
  "AUDIO_OUTPUT"->{
   val am=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
   val id=am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()?.id ?: -1
   "{\"deviceId\":$id}"
  }
  "PLAYBACK_AUDIO"->"{\"enabled\":true,\"sourceId\":\"android_playback_audio\",\"volume\":1.0,\"monitoring\":\"OUTPUT_ONLY\"}"
  else->"{}"
 }
 private fun displayNameFor(type:String,n:Int)=when(type.uppercase()){"GROUP"->"Group ${n+1}";
  "CAMERA"->"Camera ${n+1}";"USB_CAPTURE"->"USB Capture ${n+1}";"SCREEN_CAPTURE"->"Screen Capture";"BROWSER"->"Browser ${n+1}";"IMAGE"->"Image ${n+1}";"IMAGE_SLIDESHOW"->"Image Slideshow ${n+1}";"MEDIA"->"Media ${n+1}";"TEXT"->"Text ${n+1}";"COLOR"->"Color ${n+1}";"AUDIO_INPUT"->"Audio Input ${n+1}";"AUDIO_OUTPUT"->"Monitor Output ${n+1}";"PLAYBACK_AUDIO"->"Android Playback Audio";else->"$type ${n+1}"
 }
 /** Every source the program actually shows, including the contents of nested scenes and groups. */
 /** What the audio mix and capture use: a reference stands for its original (one source in OBS terms), counted once. */
 private fun renderedItems():List<SourceItem>{
  val shown=_renderSources.value.map{it.item}
  val originals=(shown+_backingSources.value).associateBy{it.id}
  val out=LinkedHashMap<String,SourceItem>()
  for(src in shown){
   val item=com.stream4k60.app.engine.SourceReferences.targetOf(src.configJson)?.let{t->originals[t]?.copy(isVisible=src.isVisible)}?:src
   out[item.id]=out[item.id]?.takeIf{it.isVisible}?:item
  }
  return out.values.toList()+globalAudioItems()
 }
 /** OBS global audio (Settings → Audio): present in every scene, shown in the mixer, filterable. */
 private fun globalAudioItems():List<SourceItem>{
  val a=audioSettingsState.value
  return buildList{
   if(a.desktopAudioEnabled)add(SourceItem(GlobalAudio.DESKTOP,"Desktop Audio","PLAYBACK_AUDIO",true,false,a.desktopConfig))
   if(a.micDeviceId!=AudioSettings.MIC_DISABLED){
    val cfg=runCatching{org.json.JSONObject(a.micConfig)}.getOrDefault(org.json.JSONObject()).put("deviceId",if(a.micDeviceId==AudioSettings.MIC_DEFAULT)-1 else a.micDeviceId)
    // Push-to-talk keeps the mic muted until the key is held; push-to-mute mutes while held.
    if(a.pushToTalk&&!pushToTalkHeld||a.pushToMute&&pushToTalkHeld)cfg.put("muted",true)
    add(SourceItem(GlobalAudio.MIC,"Mic/Aux","AUDIO_INPUT",true,false,cfg.toString()))
   }
  }
 }
 private fun refreshAudioItems(){_audioItems.value=renderedItems().filter{com.stream4k60.app.engine.SourceReferences.targetOf(it.configJson)==null}}
 private fun syncAudioGraph() {
  refreshAudioItems()
  val routes=audioRoutes()
  val playbackSource=renderedItems().firstOrNull{it.type.equals("PLAYBACK_AUDIO",true)}
  val playbackRoute=playbackSource?.let(::parsePlaybackRoute)
  val monitor=monitorDeviceId()
  val audioSources=renderedItems().filter{(it.type.uppercase() in setOf("AUDIO_INPUT","AUDIO_OUTPUT","PLAYBACK_AUDIO") || it.type.equals("USB_CAPTURE",true)&&(sourceAudioSettings(it.configJson).optInt("audioDeviceId",-1)>=0||UsbAudioSources.mixerInputId(it)!=null))}
  val reportIds=audioSources.map{it.id}.toSet()
  val missingDeviceIds=audioSources.filter{source->
   // Mic/Aux on "Default" uses Android's current input (-1); that is a valid choice, not a missing device.
   source.type.equals("AUDIO_INPUT",true)&&source.id!=GlobalAudio.MIC&&UsbAudioSources.mixerInputId(source)==null&&sourceAudioSettings(source.configJson).optInt("deviceId",-1)<0 ||
    source.type.equals("AUDIO_OUTPUT",true)&&sourceSettings(source.configJson).optInt("deviceId",-1)<0
  }.map{it.id}.toSet()
  val configuredReportIds=reportIds-missingDeviceIds
  val sourceNames=audioSources.associate{it.id to it.name}
  val lossless=audioSettingsState.value.losslessMonitoring
  val monitorFormat=if(lossless)4 else 2
  viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
    runCatching { NativeAudioGraph.configure(routes,playbackRoute,monitor,monitor!=null,mediaAudioRoutes()+usbAudioRoutes(),monitorFormat,lossless) }
     .onSuccess { failures ->
      // Direct USB microphones push into whichever mixer is current (it is replaced when the monitor device changes).
      com.stream4k60.app.engine.NativeUsbAudio.setMixer(NativeAudioGraph.currentHandle())
      syncAudioGraphErrors.forEach(SourceRuntimeErrors::clear); syncAudioGraphErrors=failures.keys
      (configuredReportIds-failures.keys).forEach(SourceRuntimeErrors::clear)
      // Only the inputs that failed show an error; the rest of the mix keeps running.
      failures.forEach{(id,reason)->
       // The one-USB-mic limit only explains a failure to open a specific device, not Android's default input.
       val specificDevice=routes.firstOrNull{it.sourceId==id}?.deviceId?.let{it>=0}==true
       SourceRuntimeErrors.report(id,if(specificDevice)"$reason. Android on this tablet records from one USB microphone at a time, and another source has it (Mic/Aux on Default uses Android's current input, which can be a USB hub's audio chip). In Settings → Audio, set Mic/Aux Audio to this microphone, or to Disabled." else "$reason. Another app may be using the microphone, or the input changed while it opened; it is tried again on the next change.")
      }
      missingDeviceIds.forEach{id->SourceRuntimeErrors.report(id,"Select a connected Android audio device for ${sourceNames[id] ?: "this source"}.")}
     }
     .onFailure { error ->
      syncAudioGraphErrors.filterNot{it in configuredReportIds}.forEach(SourceRuntimeErrors::clear)
      configuredReportIds.forEach { id -> SourceRuntimeErrors.report(id,"Audio device or mixer could not start: ${error.message ?: "Android audio error"}.") }
      missingDeviceIds.forEach{id->SourceRuntimeErrors.report(id,"Select a connected Android audio device for ${sourceNames[id] ?: "this source"}.")}
      syncAudioGraphErrors=configuredReportIds
     }
  }
 }
 /**
  * Properties edits applied live, in memory only, as OBS's properties window does: the studio and the dialog's
  * preview show them at once (a picked video starts playing). Apply saves them; Cancel restores the saved config.
  */
 fun previewSourceConfig(id:String,config:String){
  if(id==GlobalAudio.DESKTOP||id==GlobalAudio.MIC)return
  _sources.value=_sources.value.map{if(it.id==id)it.copy(configJson=config)else it}
  _renderSources.value=_renderSources.value.map{if(it.item.id==id)it.copy(item=it.item.copy(configJson=config))else it}
  _groupChildren.value=_groupChildren.value.mapValues{(_,items)->items.map{if(it.id==id)it.copy(configJson=config)else it}}
  syncAudioGraph()
 }
 /** OBS's per-source Scale Filtering, stored with the source and applied by the renderer at once. */
 fun setSourceScaleFilter(id:String,mode:String){
  val source=_sources.value.firstOrNull{it.id==id}?:_groupChildren.value.values.flatten().firstOrNull{it.id==id}?:return
  val root=runCatching{org.json.JSONObject(source.configJson)}.getOrDefault(org.json.JSONObject())
  (root.optJSONObject("settings")?:root).put("scaleFilter",mode)
  updateSourceConfig(id,root.toString())
 }
 fun updateSourceConfig(id:String,config:String){
  if(id==GlobalAudio.DESKTOP||id==GlobalAudio.MIC){
   viewModelScope.launch{
    val a=audioSettingsState.value
    // The device and push-to-talk mute are derived, not stored.
    val json=runCatching{org.json.JSONObject(config)}.getOrNull()
    // Picking a device in Mic/Aux properties is the same as Settings → Audio → Mic/Aux device.
    val pickedDevice=json?.let{(it.optJSONObject("settings")?:it).optInt("deviceId",Int.MIN_VALUE)}?.takeIf{it!=Int.MIN_VALUE}
    val clean=json?.apply{remove("deviceId");optJSONObject("settings")?.remove("deviceId");if(id==GlobalAudio.MIC&&(a.pushToTalk||a.pushToMute))remove("muted")}?.toString()?:config
    val micDevice=if(id==GlobalAudio.MIC&&pickedDevice!=null)(if(pickedDevice<0)AudioSettings.MIC_DEFAULT else pickedDevice)else a.micDeviceId
    settingsRepository.saveAudioSettings(if(id==GlobalAudio.DESKTOP)a.copy(desktopConfig=clean)else a.copy(micConfig=clean,micDeviceId=micDevice))
   }
   return
  }
  viewModelScope.launch{cp("Properties","config:$id");rowFor(id)?.let{row->
  repo.saveSources(listOf(row.copy(configJson=config)))
  if(row.type.equals("AUDIO_INPUT",true)) runCatching { engine.updateAudioRoute(parseAudioRoute(SourceItem(row.id,row.name,row.type,row.visible,row.locked,config,row.transformJson))) }
   .onSuccess { SourceRuntimeErrors.clear(id) }
   .onFailure { SourceRuntimeErrors.report(id,"Android could not update this audio input: ${it.message ?: "audio route error"}.") }
 };load()}}
 /**
  * A capture source found its device again (after a replug or app start): saves Android's new number with the device's
  * lasting identity. Not an edit of the user's, so no undo step.
  */
 fun rebindUsbSource(id:String,config:String){viewModelScope.launch{(rowFor(id)?:backingScenes[id]?.let{scene->repo.loadSources(scene).firstOrNull{it.id==id}})?.let{row->if(row.configJson!=config){repo.saveSources(listOf(row.copy(configJson=config)));load()}}}}
 fun remapImportedSource(id:String,targetType:String){
  val supported=setOf("USB_CAPTURE","SCREEN_CAPTURE","PLAYBACK_AUDIO","AUDIO_INPUT","AUDIO_OUTPUT","BROWSER","MEDIA","IMAGE","IMAGE_SLIDESHOW","TEXT","COLOR")
  val type=targetType.uppercase();if(type !in supported)return
  val sceneId=_activeScene.value?.id?:return
  viewModelScope.launch {
   val row=repo.loadSources(sceneId).firstOrNull{it.id==id}?:return@launch
   if(!row.type.startsWith("OBS:")||row.type=="OBS:GROUP")return@launch
   cp("Remap source")
   val root=runCatching{org.json.JSONObject(row.configJson)}.getOrDefault(org.json.JSONObject())
   val oldSettings=runCatching{org.json.JSONObject(root.optJSONObject("settings")?.toString()?:"{}")}.getOrDefault(org.json.JSONObject())
   root.optJSONObject("audio")?.let{audio->listOf("volume","balance","muted","monitoring","syncOffsetMs","solo").forEach{key->if(!oldSettings.has(key)&&audio.has(key))oldSettings.put(key,audio.opt(key))}}
   val merged=runCatching{org.json.JSONObject(defaultConfigFor(type))}.getOrDefault(org.json.JSONObject())
   oldSettings.keys().asSequence().toList().forEach{key->merged.put(key,oldSettings.opt(key))}
   root.put("settings",merged)
   root.put("remappedFromType",row.type)
   repo.saveSources(listOf(row.copy(type=type,configJson=root.toString())))
   load()
  }
 }
 private fun sourceSettings(configJson:String):org.json.JSONObject {
  val root=runCatching{org.json.JSONObject(configJson)}.getOrDefault(org.json.JSONObject())
  return root.optJSONObject("settings")?:root
 }
 private fun sourceAudioSettings(configJson:String):org.json.JSONObject {
  val root=runCatching{org.json.JSONObject(configJson)}.getOrDefault(org.json.JSONObject())
  val settings=org.json.JSONObject((root.optJSONObject("settings")?:root).toString())
  val audio=root.optJSONObject("audio")?:org.json.JSONObject()
  listOf("volume","balance","muted","monitoring","syncOffsetMs","solo").forEach{key->if(!settings.has(key)&&audio.has(key))settings.put(key,audio.opt(key))}
  return settings
 }
 fun updateSourceTransform(id:String,transform:String){
  val current=_renderSources.value.firstOrNull{it.item.id==id}?.item?:_groupChildren.value.values.flatten().firstOrNull{it.id==id}?:return
  if(current.isLocked)return
  _sources.value=_sources.value.map{if(it.id==id)it.copy(transformJson=transform)else it}
  _renderSources.value=_renderSources.value.map{if(it.item.id==id)it.copy(item=it.item.copy(transformJson=transform))else it}
  viewModelScope.launch{cp("Transform","transform:$id");sourceTransformMutex.withLock{rowFor(id)?.let{row->if(!row.locked)repo.saveSources(listOf(row.copy(transformJson=transform)))}}}
 }
 private fun parseAudioRoute(src:SourceItem):AudioInputRoute{
  val j=sourceAudioSettings(src.configJson)
  return AudioInputRoute(src.id,j.optInt("deviceId",-1),j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",false),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","MONITOR_AND_OUTPUT"))}.getOrDefault(AudioMonitoring.MONITOR_AND_OUTPUT),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson),AudioFilterChain.gain(src.configJson))
 }
 private fun parsePlaybackRoute(src:SourceItem):AudioInputRoute{
  val j=sourceAudioSettings(src.configJson)
  return AudioInputRoute(NativeAudioBridge.PLAYBACK_SOURCE_ID,-1,j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",false),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","OUTPUT_ONLY"))}.getOrDefault(AudioMonitoring.OUTPUT_ONLY),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson),AudioFilterChain.gain(src.configJson))
 }
 /** Sources read straight from USB (each its own mixer input), outside Android's one-USB-microphone limit. */
 private fun usbAudioRoutes():List<AudioInputRoute> = renderedItems().mapNotNull{src->
  val id=UsbAudioSources.mixerInputId(src)?:return@mapNotNull null
  runCatching{
   val j=sourceAudioSettings(src.configJson)
   AudioInputRoute(id,-1,j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",false),
    runCatching{AudioMonitoring.valueOf(j.optString("monitoring","MONITOR_AND_OUTPUT"))}.getOrDefault(AudioMonitoring.MONITOR_AND_OUTPUT),
    j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson),AudioFilterChain.gain(src.configJson))
  }.getOrNull()
 }
 private fun audioRoutes(): List<AudioInputRoute> = renderedItems().filter { (it.type.equals("AUDIO_INPUT", true) || it.type.equals("USB_CAPTURE", true)) && com.stream4k60.app.engine.SourceReferences.targetOf(it.configJson) == null && UsbAudioSources.mixerInputId(it) == null }.mapNotNull { src ->
  runCatching {
   val isCaptureCard=src.type.equals("USB_CAPTURE",true)
   val j=sourceAudioSettings(src.configJson); val id=j.optInt(if(isCaptureCard)"audioDeviceId" else "deviceId",-1)
   // -1 opens Android's default input; only the global Mic/Aux asks for that.
   if(id<0&&src.id!=GlobalAudio.MIC) null else AudioInputRoute(
    sourceId=if(isCaptureCard)"usb_audio_${src.id}" else src.id, deviceId=id, volume=j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f), balance=j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f), muted=j.optBoolean("muted",false),
    monitoring=runCatching{AudioMonitoring.valueOf(j.optString("monitoring","MONITOR_AND_OUTPUT"))}.getOrDefault(AudioMonitoring.MONITOR_AND_OUTPUT),
    syncOffsetMs=j.optInt("syncOffsetMs",0).coerceIn(-2000,2000), solo=j.optBoolean("solo",false),
    noiseGate=AudioFilterChain.noiseGate(src.configJson),gain=AudioFilterChain.gain(src.configJson)
   )
  }.getOrNull()
 }
 private fun mediaAudioRoutes():List<AudioInputRoute> = renderedItems().filter{it.type.equals("MEDIA",true)&&com.stream4k60.app.engine.SourceReferences.targetOf(it.configJson)==null}.mapNotNull{src->
  runCatching{
   val j=sourceSettings(src.configJson);val audioId="media_audio_${src.id}"
   AudioInputRoute(audioId,-1,j.optDouble("volume",1.0).toFloat().coerceIn(0f,2f),j.optDouble("balance",0.0).toFloat().coerceIn(-1f,1f),j.optBoolean("muted",!j.optBoolean("audioEnabled",true)),runCatching{AudioMonitoring.valueOf(j.optString("monitoring","OUTPUT_ONLY"))}.getOrDefault(AudioMonitoring.OUTPUT_ONLY),j.optInt("syncOffsetMs",0).coerceIn(-2000,2000),j.optBoolean("solo",false),AudioFilterChain.noiseGate(src.configJson),AudioFilterChain.gain(src.configJson))
  }.getOrNull()
 }
 /** An Audio Output source wins; otherwise the monitoring device from Settings → Audio (-1 = Android default). */
 private fun monitorDeviceId(): Int? = renderedItems().firstOrNull { it.type.equals("AUDIO_OUTPUT",true) }?.let { runCatching { sourceSettings(it.configJson).optInt("deviceId",-1).takeIf { id -> id>=0 } }.getOrNull() }
  ?: audioSettingsState.value.monitorDeviceId
 fun audioPeak(sourceId:String):Float = engine.audioPeak(sourceId)
 fun togglePrimaryMicMute(){
  val src=renderedItems().firstOrNull{it.id==GlobalAudio.MIC} ?: _sources.value.firstOrNull{it.type.equals("AUDIO_INPUT",true)} ?: return
  val root=runCatching{org.json.JSONObject(src.configJson)}.getOrDefault(org.json.JSONObject());val j=root.optJSONObject("settings")?:root
  val next=!j.optBoolean("muted",false)
  j.put("muted",next)
  updateSourceConfig(src.id,j.toString())
 }
 fun setPushToTalk(pressed:Boolean){
  val a=audioSettingsState.value
  if(a.pushToTalk||a.pushToMute){
   // Release waits for the configured delay so word endings are not clipped.
   viewModelScope.launch{if(!pressed)kotlinx.coroutines.delay(a.pushDelayMs.toLong());pushToTalkHeld=pressed;syncAudioGraph()}
   return
  }
  val src=_sources.value.firstOrNull{it.type.equals("AUDIO_INPUT",true)} ?: return
  val root=runCatching{org.json.JSONObject(src.configJson)}.getOrDefault(org.json.JSONObject());val j=root.optJSONObject("settings")?:root
  j.put("muted",!pressed)
  updateSourceConfig(src.id,j.toString())
 }
 val generalSettings=settingsRepository.generalSettings.stateIn(viewModelScope,SharingStarted.Eagerly,GeneralSettings())
 /** Destination saved in Settings → Stream, with the current Output/Video settings. */
 private suspend fun savedDestination():StreamConfig?{
  val s=settingsRepository.streamSettings.first()
  if(s.server.isBlank()||s.streamKey.isBlank())return null
  val v=settingsRepository.videoConfig.first()
  return StreamConfig(
   service=s.service,
   protocol=if(s.server.startsWith("rtmps",true))StreamProtocol.RTMPS else StreamProtocol.RTMP,
   ingestionUrl=s.server,streamName=s.streamKey,
   outputCodec=v.outputCodec,outputWidth=v.outputResWidth,outputHeight=v.outputResHeight,
   // YouTube, Twitch, Facebook and Kick ingest accept at most 60 FPS; only a custom server gets 120.
   fps=if(s.service!=StreamService.CUSTOM)v.frameRate.coerceAtMost(60)else v.frameRate,
   bitrate=v.videoBitrateKbps*1_000,
   audioBitrate=v.audioBitrateKbps*1_000,
   rateControl=v.rateControl,
   dynamicBitrate=v.dynamicBitrate,
   color=v.outputColor
  )
 }
 /**
  * A destination picked earlier (YouTube picker, custom RTMP) says only where to send. Size, codec, frame rate and
  * bitrates are read again at Start, so Output/Video changes made after picking it apply (they used to be ignored).
  */
 private suspend fun withCurrentOutput(d:StreamConfig):StreamConfig{
  val v=settingsRepository.videoConfig.first()
  return d.copy(outputCodec=v.outputCodec,outputWidth=v.outputResWidth,outputHeight=v.outputResHeight,
   fps=if(d.service!=StreamService.CUSTOM)v.frameRate.coerceAtMost(60)else v.frameRate,
   bitrate=v.videoBitrateKbps*1_000,audioBitrate=v.audioBitrateKbps*1_000,rateControl=v.rateControl,dynamicBitrate=v.dynamicBitrate,color=v.outputColor)
 }
 private fun withReconnect(c:StreamConfig):StreamConfig{val a=advancedSettings.value;return c.copy(autoReconnect=a.autoReconnect,reconnectDelayMs=a.reconnectDelaySec*1_000L,maxReconnectAttempts=if(a.autoReconnect)a.maxRetries else 0)}
 /** Set from the tap on Start until the engine has connected or failed, so the button reacts at once. */
 private val startRequested=MutableStateFlow(false)
 private val startCancelled=MutableStateFlow(false)
 /**
  * What the Start/Stop button shows: Connecting from the moment Start is tapped (before the engine reports it), and
  * Stopping from a Stop during connecting until that start has unwound.
  */
 val streamButtonState=combine(streamState,startRequested,startCancelled){st,requested,cancelled->
  when{
   requested&&cancelled->StudioStreamState.STOPPING
   requested->StudioStreamState.CONNECTING
   else->st
  }
 }.stateIn(viewModelScope,SharingStarted.Eagerly,StudioStreamState.IDLE)
 fun startStreaming()=viewModelScope.launch{
  if(startRequested.value)return@launch
  startRequested.value=true;startCancelled.value=false
  try{
   val routes=audioRoutes(); val monitor=monitorDeviceId(); val playback=renderedItems().any{it.type.equals("PLAYBACK_AUDIO",true)}
   _streamError.value=null
   // A destination picked this session (YouTube picker / custom dialog) wins; otherwise use Settings → Stream.
   val destination=_streamConfig.value.takeIf{it.ingestionUrl.isNotBlank()}?.let{withCurrentOutput(it)}?:savedDestination()
   if(destination==null){_streamError.value="No stream destination yet. Open Settings → Stream, choose a service and paste your stream key.";return@launch}
   try{
    engine.startStreaming(withReconnect(destination).copy(audioDeviceIds=routes.map{it.deviceId},audioInputs=routes,monitorDeviceId=monitor,monitorEnabled=monitor!=null,audioPlaybackCaptureEnabled=playback))
    // Do not finish the start operation merely because RTMPS/HLS accepted the connection. Confirm YouTube own status.
    waitForYouTubeIngest(destination)
   }catch(t:Throwable){
    // Provider-side verification can fail after the local publisher is already LIVE; tear it down rather than
    // leaving a stream running behind an error message.
    if(!startCancelled.value&&streamState.value in setOf(StudioStreamState.LIVE,StudioStreamState.RECONNECTING))runCatching{engine.stopStreaming()}
    if(!startCancelled.value)_streamError.value=t.message ?: "Streaming could not start."
   }
  }finally{startRequested.value=false}
 }
 fun stopStreaming()=viewModelScope.launch{if(startRequested.value)startCancelled.value=true;engine.stopStreaming()}
 fun startRecording()=viewModelScope.launch{
  val routes=audioRoutes(); val monitor=monitorDeviceId(); val playback=renderedItems().any{it.type.equals("PLAYBACK_AUDIO",true)}
  runCatching{engine.startRecording(_recordConfig.value.copy(color=videoConfig.value.outputColor,audioDeviceIds=routes.map{it.deviceId},audioInputs=routes,monitorDeviceId=monitor,monitorEnabled=monitor!=null,audioPlaybackCaptureEnabled=playback))}
 }
 fun stopRecording()=viewModelScope.launch{engine.stopRecording()}
 override fun onCleared(){NativeAudioGraph.stop();super.onCleared()}
 fun pauseRecording()=viewModelScope.launch{engine.pauseRecording()};fun toggleStudioMode(){_isStudio.value=!_isStudio.value};fun selectTransition(v:String){
  // OBS-era names become Fade at their old speed.
  when(v){"Fast Fade"->setTransitionDuration(180);"Slow Fade"->setTransitionDuration(600)}
  val name=if(v=="Fast Fade"||v=="Slow Fade")"Fade" else v
  _transition.value=name;collectionPrefs.edit().putString("transitionName",name).apply()
 }
 fun setTransitionDuration(ms:Int){val v=ms.coerceIn(SceneTransitions.MIN_MS,SceneTransitions.MAX_MS);_transitionDuration.value=v;collectionPrefs.edit().putInt("transitionDurationMs",v).apply()};fun saveReplay()=viewModelScope.launch{runCatching{engine.saveReplayBuffer()}};fun startReplay()=viewModelScope.launch{runCatching{engine.startReplayBuffer(30,256)}}
}

/** Ids of the OBS-style global audio sources (not stored as scene rows). */
object GlobalAudio {
 const val DESKTOP="global:desktop"
 const val MIC="global:mic"
}
