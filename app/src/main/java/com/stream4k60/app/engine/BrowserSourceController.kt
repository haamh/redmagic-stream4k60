package com.stream4k60.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Browser sources, with OBS's options: URL or local file, size, custom frame rate (else the canvas rate), custom CSS,
 * shutdown when not visible, refresh when the scene becomes active, page permissions (OBS's `window.obsstudio` API),
 * refresh / clear cache, and Interact (the page shown full screen to click and type into).
 */
class BrowserSourceController(private val context:android.content.Context){
    companion object {
        /** The Custom CSS a new OBS browser source starts with (OBS stores it only when it's changed). */
        const val OBS_DEFAULT_CSS="body { background-color: rgba(0, 0, 0, 0); margin: 0px auto; overflow: hidden; }"
    }
    private val main=Handler(Looper.getMainLooper());private val views=ConcurrentHashMap<String,WebView>();private val running=ConcurrentHashMap.newKeySet<String>()
    private val surfaces=ConcurrentHashMap<String,android.view.Surface>()
    private val configs=ConcurrentHashMap<String,String>();private val tickers=ConcurrentHashMap<String,Runnable>()
    private val refreshCounters=ConcurrentHashMap<String,Int>()
    private val cacheCounters=ConcurrentHashMap<String,Int>()
    private val cssConfigs=ConcurrentHashMap<String,String>()
    private val controlLevels=ConcurrentHashMap<String,Int>()
    /** Whether each running page is shown; hidden pages keep running (OBS's default) but aren't drawn. */
    private val shown=ConcurrentHashMap<String,Boolean>()
    /** Each page's WebView size in device pixels (its width × height in CSS pixels, like OBS, times the screen density). */
    private val sizes=ConcurrentHashMap<String,Pair<Int,Int>>()
    /** Pages whose last load failed: transparent like OBS (CEF shows no error page), retried every 15 s. */
    private val failed=ConcurrentHashMap.newKeySet<String>()
    private val loadError=ConcurrentHashMap.newKeySet<String>()
    private val density=context.resources.displayMetrics.density.coerceAtLeast(1f)
    /** OBS's browser (CEF on Windows) identifies as desktop Chrome plus an OBS token; pages then serve their desktop layout. */
    private val userAgent by lazy {
        val major=runCatching{android.webkit.WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')}.getOrNull()?.takeIf{it.isNotEmpty()&&it.all(Char::isDigit)}?:"127"
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36 OBS/31.1.1"
    }
    private var host:android.widget.FrameLayout?=null
    private var interacting:String?=null
    private val eventListener:(String,String)->Unit={event,detail->
        main.post{views.forEach{(id,view)->if((controlLevels[id]?:1)>=1)view.evaluateJavascript("window.dispatchEvent(new CustomEvent(${JSONObject.quote(event)},{detail:$detail}));",null)}}
    }
    init{StudioBridge.addListener(eventListener)}

    fun attachHost(parent:android.view.ViewGroup){if(host!=null)return;host=android.widget.FrameLayout(context).apply{layoutParams=android.widget.FrameLayout.LayoutParams(1,1);alpha=0f
        // Keep the default clipping: letting the pages render unclipped (clipChildren=false, or offscreenPreRaster) made
        // closing the app kernel-panic this tablet every time (2026-10-04).
        };parent.addView(host)}

    private fun settings(src:com.stream4k60.app.ui.main.SourceItem)=runCatching{JSONObject(src.configJson).optJSONObject("settings")?:JSONObject(src.configJson)}.getOrDefault(JSONObject())
    private fun shutdownWhenHidden(c:JSONObject)=c.optBoolean("shutdownWhenHidden",c.optBoolean("shutdown",false))

    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>,canvasFps:Int=60){
        val browsers=sources.filter{it.type.equals("BROWSER",true)}
        val wanted=browsers.filter{it.isVisible||!shutdownWhenHidden(settings(it))}.map{it.id}.toSet()
        views.keys.filterNot{it in wanted}.forEach(::stop)
        running.retainAll(wanted)
        browsers.filter{it.id in wanted}.forEach{src->
            val c=settings(src)
            val wasShown=shown.put(src.id,src.isVisible)
            val localFile=if(c.has("isLocalFile"))c.optBoolean("isLocalFile") else c.optBoolean("is_local_file",c.optString("url").isBlank()&&c.optString("file").isNotBlank())
            val file=c.optString("file").ifBlank{c.optString("local_file")}
            val url=c.optString("url")
            val pageUrl=if(localFile&&file.isNotBlank())file else url.ifBlank{file.ifBlank{"about:blank"}}
            val customFps=if(c.has("customFps"))c.optBoolean("customFps") else c.optBoolean("fps_custom",c.has("fps"))
            configure(src,pageUrl,displayScale(src.transformJson),c.optInt("width",1280),c.optInt("height",720),if(customFps)c.optInt("fps",30) else canvasFps,c.optBoolean("javaScript",true),c.optBoolean("hardwareAccelerated",true),c.optString("customCss",c.optString("css","")),c.optInt("refreshToken",0),c.optInt("cacheToken",0),c.optInt("pageControlLevel",c.optInt("webpage_control_level",1)).coerceIn(0,5))
            // "Refresh browser when scene becomes active": shown again after being hidden (or its scene left).
            if(wasShown==false&&src.isVisible&&c.optBoolean("refreshWhenActive",c.optBoolean("restart_when_active",false)))views[src.id]?.reload()
        }
    }

    /** How large the page is shown on the canvas, so it's drawn sharp there (up to the screen density). */
    private fun displayScale(transformJson:String):Float=runCatching{val t=JSONObject(transformJson);maxOf(kotlin.math.abs(t.optDouble("scaleX",1.0)),kotlin.math.abs(t.optDouble("scaleY",1.0))).toFloat()}.getOrDefault(1f)

    fun configure(source:com.stream4k60.app.ui.main.SourceItem,url:String,displayScale:Float=1f,width:Int=800,height:Int=600,fps:Int=30,javaScript:Boolean=true,hardwareAccelerated:Boolean=true,customCss:String="",refreshToken:Int=0,cacheToken:Int=0,controlLevel:Int=1){
        val h=host?:return
        val requestedWidth=width.coerceIn(16,8192);val requestedHeight=height.coerceIn(16,8192)
        val maxWidth=if(hardwareAccelerated)3840 else 1280
        val maxHeight=if(hardwareAccelerated)3840 else 720
        val maxFps=if(hardwareAccelerated)60 else 10
        // OBS lays a page out at width × height CSS pixels (device pixel ratio 1). Android's WebView counts CSS pixels at
        // the screen density, so the WebView is that many times larger to get the same layout: text wraps and widgets
        // fit exactly as in OBS. It's drawn into the source at the size it's shown on the canvas, and the source reports
        // width × height like OBS so imported transforms and crops stay right.
        val layoutScale=minOf(density,4608f/requestedWidth,4608f/requestedHeight)
        val lw=(requestedWidth*layoutScale).toInt();val lh=(requestedHeight*layoutScale).toInt()
        val drawScale=(kotlin.math.ceil(displayScale*4f)/4f).coerceIn(1f,layoutScale.coerceAtLeast(1f))
        val fit=minOf(drawScale,maxWidth.toFloat()/requestedWidth,maxHeight.toFloat()/requestedHeight)
        val w=(requestedWidth*fit).toInt().coerceIn(16,maxWidth);val heightSafe=(requestedHeight*fit).toInt().coerceIn(16,maxHeight);val rate=fps.coerceIn(1,maxFps)
        sizes[source.id]=lw to lh
        SourceNativeSizes.report(source.id,requestedWidth,requestedHeight)
        controlLevels[source.id]=controlLevel
        val signature="$url|$lw|$lh|$w|$heightSafe|$rate|$javaScript|$hardwareAccelerated|$customCss|$refreshToken|$cacheToken|$controlLevel"
        if(configs[source.id]==signature&&views.containsKey(source.id))return
        configs[source.id]=signature
        val previousRefresh=refreshCounters.put(source.id,refreshToken)
        val previousCache=cacheCounters.put(source.id,cacheToken)
        cssConfigs[source.id]=customCss
        tickers.remove(source.id)?.let(main::removeCallbacks)
        val currentSurface=surfaces[source.id]
        if(hardwareAccelerated&&currentSurface==null){
            NativeEngine.removeSourceLayer(source.id)
            val surface=NativeEngine.createSourceSurface(source.id)?:run{SourceRuntimeErrors.report(source.id,"Could not create the browser's GPU compositor surface.");return}
            NativeEngine.setSourceBufferSize(source.id,w,heightSafe)
            surfaces[source.id]=surface
        }else if(!hardwareAccelerated&&currentSurface!=null){
            surfaces.remove(source.id)?.release()
            NativeEngine.removeSourceLayer(source.id)
        }else if(hardwareAccelerated){
            NativeEngine.setSourceBufferSize(source.id,w,heightSafe)
        }
        val web=views[source.id]?:WebView(context).also{view->
            view.setBackgroundColor(Color.TRANSPARENT)
            view.isVerticalScrollBarEnabled=false;view.isHorizontalScrollBarEnabled=false
            view.settings.userAgentString=userAgent
            // OBS's browser keeps sign-ins (cookies, third-party ones too) for pages like YouTube Studio.
            android.webkit.CookieManager.getInstance().let{it.setAcceptCookie(true);it.setAcceptThirdPartyCookies(view,true)}
            view.layoutParams=android.widget.FrameLayout.LayoutParams(lw,lh)
            view.addJavascriptInterface(ObsStudioApi(source.id),"__stream4kObs")
            view.webViewClient=object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if(!request.isForMainFrame)return
                    SourceRuntimeErrors.report(source.id,"Browser page failed to load: ${error.description}. Retrying every 15 s.")
                    loadError.add(source.id);failed.add(source.id)
                    main.postDelayed({if(running.contains(source.id)&&failed.contains(source.id))views[source.id]?.reload()},15_000L)
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loadError.remove(source.id); injectObsApi(view) }
                override fun onPageFinished(view: WebView, url: String) {
                    if(loadError.contains(source.id))return
                    failed.remove(source.id)
                    android.webkit.CookieManager.getInstance().flush()
                    injectObsApi(view)
                    injectCustomCss(view, cssConfigs[source.id].orEmpty())
                    SourceRuntimeErrors.clear(source.id)
                    view.evaluateJavascript("innerWidth+'x'+innerHeight+' CSS px, devicePixelRatio '+devicePixelRatio"){r->
                        StreamLog.add("Browser ${source.name}: page laid out at ${r.trim('"')} (OBS: width x height at 1), WebView ${sizes[source.id]?.let{"${it.first}x${it.second}"}} device px")
                    }
                }
            }
            h.addView(view);views[source.id]=view
        }
        if(interacting!=source.id)web.layoutParams=android.widget.FrameLayout.LayoutParams(lw,lh)
        web.setLayerType(if(hardwareAccelerated)android.view.View.LAYER_TYPE_HARDWARE else android.view.View.LAYER_TYPE_SOFTWARE,null)
        web.measure(android.view.View.MeasureSpec.makeMeasureSpec(lw,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(lh,android.view.View.MeasureSpec.EXACTLY))
        web.layout(0,0,lw,lh)
        web.settings.javaScriptEnabled=javaScript
        web.settings.domStorageEnabled=true
        web.settings.mediaPlaybackRequiresUserGesture=false
        web.settings.allowFileAccess=true
        if(previousCache!=null&&previousCache!=cacheToken){web.clearCache(true);web.reload()}
        else if(web.url!=url)web.loadUrl(url)
        else if(previousRefresh!=null&&previousRefresh!=refreshToken)web.reload()
        else injectCustomCss(web,customCss)
        running.add(source.id)
        lateinit var ticker:Runnable
        ticker=Runnable {
            if(!running.contains(source.id)||tickers[source.id]!==ticker)return@Runnable
            web.post {
                if(running.contains(source.id)&&tickers[source.id]===ticker){
                    // A hidden page keeps running (timers, sockets, audio) but isn't drawn.
                    if(shown[source.id]!=false)runCatching {
                        val outputSurface=surfaces[source.id]
                        if(outputSurface!=null){
                            val canvas=outputSurface.lockHardwareCanvas()
                            try {
                                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                                if(!failed.contains(source.id)){
                                // The page is laid out at CSS size × density; draw it at the source's size.
                                if(web.width!=w||web.height!=heightSafe)canvas.scale(w.toFloat()/web.width.coerceAtLeast(1),heightSafe.toFloat()/web.height.coerceAtLeast(1))
                                web.draw(canvas)
                                }
                            } finally {
                                outputSurface.unlockCanvasAndPost(canvas)
                            }
                            NativeEngine.setSourceEffectsFromConfig(source.id,source.configJson)
                        }else{
                            val bitmap=Bitmap.createBitmap(w,heightSafe,Bitmap.Config.ARGB_8888)
                            val bitmapCanvas=Canvas(bitmap)
                            if(web.width!=w||web.height!=heightSafe)bitmapCanvas.scale(w.toFloat()/web.width.coerceAtLeast(1),heightSafe.toFloat()/web.height.coerceAtLeast(1))
                            if(!failed.contains(source.id))web.draw(bitmapCanvas)
                            val rgba=bitmap.toRgba();bitmap.recycle()
                            if(NativeEngine.updateSourceRgba(source.id,rgba.first,w,heightSafe))NativeEngine.setSourceEffectsFromConfig(source.id,source.configJson)
                            else SourceRuntimeErrors.report(source.id,"Browser frames could not be uploaded to the compositor.")
                        }
                    }.onFailure { SourceRuntimeErrors.report(source.id,"Browser frame rendering failed: ${it.message ?: "WebView capture error"}.") }
                    if(running.contains(source.id)&&tickers[source.id]===ticker)main.postDelayed(ticker,(1000L/rate).coerceAtLeast(16))
                }
            }
        }
        tickers[source.id]=ticker
        main.post(ticker)
    }

    /**
     * OBS's Interact: the page moves into [container] (a full-screen overlay) at its own size, scaled to fit, so taps and
     * the keyboard reach it; the source keeps showing it meanwhile.
     */
    fun beginInteraction(sourceId:String,container:android.widget.FrameLayout):Boolean{
        val web=views[sourceId]?:return false
        val (w,h)=sizes[sourceId]?:(web.width to web.height)
        endInteraction()
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        container.addView(web,android.widget.FrameLayout.LayoutParams(w,h))
        container.post{
            val fit=minOf(container.width.toFloat()/w,container.height.toFloat()/h).coerceAtLeast(0.05f)
            web.pivotX=0f;web.pivotY=0f;web.scaleX=fit;web.scaleY=fit
            web.translationX=(container.width-w*fit)/2f;web.translationY=(container.height-h*fit)/2f
            web.requestFocus()
        }
        interacting=sourceId
        return true
    }
    fun endInteraction(){
        val id=interacting?:return
        interacting=null
        val web=views[id]?:return
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.scaleX=1f;web.scaleY=1f;web.translationX=0f;web.translationY=0f
        val (w,h)=sizes[id]?:(web.width to web.height)
        host?.addView(web,android.widget.FrameLayout.LayoutParams(w,h))
    }

    fun stop(sourceId:String){if(interacting==sourceId)endInteraction();running.remove(sourceId);configs.remove(sourceId);refreshCounters.remove(sourceId);cacheCounters.remove(sourceId);cssConfigs.remove(sourceId);shown.remove(sourceId);failed.remove(sourceId);loadError.remove(sourceId);controlLevels.remove(sourceId);tickers.remove(sourceId)?.let(main::removeCallbacks);views.remove(sourceId)?.let{v->(v.parent as? android.view.ViewGroup)?.removeView(v);v.destroy()};surfaces.remove(sourceId)?.release();NativeEngine.removeSourceLayer(sourceId);SourceRuntimeErrors.clear(sourceId)}
    fun stopAll(){views.keys.toList().forEach(::stop);host?.let{(it.parent as? android.view.ViewGroup)?.removeView(it)};host=null}
    private fun injectCustomCss(view:WebView,css:String){
        val quoted=JSONObject.quote(css)
        view.evaluateJavascript("(function(){var s=document.getElementById('__stream4k_custom_css');if(!s){s=document.createElement('style');s.id='__stream4k_custom_css';(document.head||document.documentElement).appendChild(s);}s.textContent=$quoted;})();",null)
    }

    /** OBS's `window.obsstudio` page API, backed by [ObsStudioApi] and limited by the page's permission level. */
    private fun injectObsApi(view:WebView){
        view.evaluateJavascript("""(function(){if(window.obsstudio&&window.obsstudio.__s4k)return;var b=window.__stream4kObs;if(!b)return;
function j(s){try{return JSON.parse(s)}catch(e){return null}}
window.obsstudio={__s4k:true,pluginVersion:"2.24.0",
getControlLevel:function(cb){cb(b.controlLevel())},
getStatus:function(cb){var r=j(b.status());if(r)cb(r)},
getCurrentScene:function(cb){var r=j(b.currentScene());if(r)cb(r)},
getScenes:function(cb){var r=j(b.scenes());if(r)cb(r)},
getTransitions:function(cb){var r=j(b.transitions());if(r)cb(r)},
getCurrentTransition:function(cb){var r=b.currentTransition();if(r!==null)cb(r)},
setCurrentScene:function(n){b.setCurrentScene(String(n))},
setCurrentTransition:function(n){b.setCurrentTransition(String(n))},
startStreaming:function(){b.startStreaming()},stopStreaming:function(){b.stopStreaming()},
startRecording:function(){},stopRecording:function(){},pauseRecording:function(){},unpauseRecording:function(){},
startReplayBuffer:function(){},stopReplayBuffer:function(){},saveReplayBuffer:function(){},
startVirtualcam:function(){},stopVirtualcam:function(){}};})();""",null)
    }

    /** The calls a page may make, each checked against its permission level as OBS does. */
    private inner class ObsStudioApi(private val sourceId:String){
        private fun level()=controlLevels[sourceId]?:1
        @JavascriptInterface fun controlLevel():Int=level()
        @JavascriptInterface fun status():String?=if(level()>=1)StudioBridge.status().toString() else null
        @JavascriptInterface fun currentScene():String?=if(level()>=1)StudioBridge.currentScene().toString() else null
        @JavascriptInterface fun scenes():String?=if(level()>=2)StudioBridge.scenes().toString() else null
        @JavascriptInterface fun transitions():String?=if(level()>=2)StudioBridge.transitions().toString() else null
        @JavascriptInterface fun currentTransition():String?=if(level()>=2)StudioBridge.currentTransition() else null
        @JavascriptInterface fun setCurrentScene(name:String){if(level()>=4)main.post{StudioBridge.setCurrentScene(name)}}
        @JavascriptInterface fun setCurrentTransition(name:String){if(level()>=4)main.post{StudioBridge.setCurrentTransition(name)}}
        @JavascriptInterface fun startStreaming(){if(level()>=5)main.post{StudioBridge.startStreaming()}}
        @JavascriptInterface fun stopStreaming(){if(level()>=5)main.post{StudioBridge.stopStreaming()}}
    }
}
