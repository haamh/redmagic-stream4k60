package com.stream4k60.app.engine

import android.graphics.*
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError

/** CPU-bound only for small/overlay sources; frame is uploaded once into the GPU compositor. */
class BitmapSourceController(private val context: Context, private val scope:CoroutineScope){
    private val jobs=ConcurrentHashMap<String,Job>()
    private val sourceFingerprints=ConcurrentHashMap<String,String>()
    private val sourceStateLock=Any()
    /** Whether each source is shown, for slideshows that pause while hidden. */
    private val visibility=ConcurrentHashMap<String,Boolean>()

    /**
     * Hidden sources normally unload, as before. OBS keeps them when asked: an image unless "Unload image when not
     * showing", a slideshow whose visibility behaviour is "pause" or "always play".
     */
    private fun keepWhenHidden(src:com.stream4k60.app.ui.main.SourceItem):Boolean{
        val cfg=settings(src.configJson)
        return when(src.type.uppercase()){
            "IMAGE"->!cfg.optBoolean("unloadWhenNotShowing",cfg.optBoolean("unload",false))
            "IMAGE_SLIDESHOW"->cfg.optString("playbackBehavior",cfg.optString("playback_behavior","stop_restart")) in setOf("pause_unpause","always_play")
            "TEXT","COLOR"->true
            else->false
        }
    }

    fun sync(sources:List<com.stream4k60.app.ui.main.SourceItem>){
        val wanted=sources.filter{it.type.uppercase() in setOf("IMAGE","TEXT","COLOR","IMAGE_SLIDESHOW")&&(it.isVisible||keepWhenHidden(it))}
        val ids=wanted.map{it.id}.toSet()
        wanted.forEach{visibility[it.id]=it.isVisible}
        synchronized(sourceStateLock){
            (sourceFingerprints.keys + jobs.keys).filterNot{it in ids}.toSet().forEach{id->
                sourceFingerprints.remove(id)
                jobs.remove(id)?.cancel()
                visibility.remove(id)
                NativeEngine.removeSourceLayer(id)
                SourceRuntimeErrors.clear(id)
                SourceMediaCommands.clear(id)
            }
            wanted.forEach{src->
                // Text is drawn at the size it's shown on the canvas: a new scale draws it again.
                val fingerprint="${src.type}:${src.name}:${src.configJson}"+(if(src.type.equals("TEXT",true))":x"+textScale(src.transformJson,SourceNativeSizes.sizes.value[src.id]) else "")
                if(sourceFingerprints[src.id]!=fingerprint){
                    sourceFingerprints[src.id]=fingerprint
                    jobs.remove(src.id)?.cancel()
                    jobs[src.id]=scope.launch(Dispatchers.Default){renderOnce(src,fingerprint)}
                }
            }
        }
    }
    fun stop(){
        synchronized(sourceStateLock){
            val ids=(sourceFingerprints.keys+jobs.keys).toSet()
            jobs.values.forEach{it.cancel()};jobs.clear();sourceFingerprints.clear()
            ids.forEach(NativeEngine::removeSourceLayer)
            ids.forEach(SourceRuntimeErrors::clear)
        }
    }

    private fun settings(configJson:String)=runCatching{JSONObject(configJson).let{it.optJSONObject("settings")?:it}}.getOrElse{JSONObject()}

    private suspend fun renderOnce(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String){
        val root=runCatching{JSONObject(src.configJson)}.getOrElse{JSONObject()}
        val cfg=root.optJSONObject("settings")?:root
        when(src.type.uppercase()){
            "COLOR"->{
                val color=runCatching{Color.parseColor(cfg.optString("color","#FF000000"))}.getOrNull()
                if(color==null){SourceRuntimeErrors.report(src.id,"The color source contains an invalid color. Edit its properties and choose a valid color.");removeLayerIfCurrent(src.id,fingerprint);return}
                val w=cfg.optInt("width",1280).coerceIn(1,7680);val h=cfg.optInt("height",720).coerceIn(1,4320)
                SourceNativeSizes.report(src.id,w,h)
                // A flat colour needs no full-size bitmap: a small one is stretched to the source's size.
                val frame=rgbaBitmap(16,16){canvas->canvas.drawColor(color,PorterDuff.Mode.SRC)}
                uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,16,16)
            }
            "TEXT"->renderText(src,fingerprint,cfg,root.has("obsId"))
            "IMAGE_SLIDESHOW"->renderSlideshow(src,fingerprint,cfg)
            "IMAGE"->{
                val path=cfg.optString("url").ifBlank{cfg.optString("file").ifBlank{cfg.optString("path")}}
                if(path.isBlank()){SourceRuntimeErrors.report(src.id,"Choose an image file in source properties.");removeLayerIfCurrent(src.id,fingerprint);return}
                val bitmap=decodeBitmap(path)
                if(bitmap==null){SourceRuntimeErrors.report(src.id,"The image file could not be opened. Re-select it or relink the asset.");removeLayerIfCurrent(src.id,fingerprint);return}
                // The image's own size, as OBS: the source used to default to 1280 × 720 whatever the picture was.
                SourceNativeSizes.report(src.id,bitmap.width,bitmap.height)
                val frame=bitmap.toRgba();bitmap.recycle()
                uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,frame.second.first,frame.second.second)
            }
        }
    }

    /** Text plus optional compact rolling-text ticker. Rolling mode keeps the rounded viewport as part of the source itself. */
    private suspend fun renderText(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String,cfg:JSONObject,imported:Boolean){
        val style=TextSourceRenderer.style(cfg,src.name,imported)
        if(!style.rolling){
            var last:String?=null
            while(kotlinx.coroutines.currentCoroutineContext().isActive&&sourceFingerprints[src.id]==fingerprint){
                val text=if(style.readFromFile){
                    if(style.file.isBlank()){SourceRuntimeErrors.report(src.id,"Read from file is on: choose a text file in source properties.");""}
                    else withContext(Dispatchers.IO){TextSourceRenderer.readTextFile(context,style.file)}?:run{SourceRuntimeErrors.report(src.id,"The text file could not be read. Choose it again.");""}
                }else style.text
                if(text!=last){
                    last=text
                    val base=runCatching{TextSourceRenderer.render(context,style,text)}.getOrElse{SourceRuntimeErrors.report(src.id,"The text could not be drawn: ${it.message}");return}
                    val logicalW=base.width
                    val logicalH=base.height
                    val k=minOf(textScale(src.transformJson,logicalW to logicalH),TextSourceRenderer.MAX_SIZE.toFloat()/logicalW,TextSourceRenderer.MAX_SIZE.toFloat()/logicalH).coerceAtLeast(1f)
                    val bitmap=if(k>1.01f)runCatching{TextSourceRenderer.render(context,TextSourceRenderer.scaled(style,k),text)}.getOrNull()?.also{base.recycle()}?:base else base
                    SourceNativeSizes.report(src.id,logicalW,logicalH)
                    val drawnSuffix=if(bitmap!==base) " (drawn " + bitmap.width + "x" + bitmap.height + ")" else ""
                    StreamLog.add("Text source " + src.name + ": " + logicalW + "x" + logicalH + drawnSuffix)
                    val frame=bitmap.toRgba();bitmap.recycle()
                    uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,frame.second.first,frame.second.second)
                }
                if(!style.readFromFile)return
                kotlinx.coroutines.delay(1000)
            }
            return
        }

        var textBitmap:android.graphics.Bitmap?=null
        var currentText:String?=null
        var nextFilePoll=0L
        var offset=0f
        var previous=android.os.SystemClock.uptimeMillis()
        try{
            while(kotlinx.coroutines.currentCoroutineContext().isActive&&sourceFingerprints[src.id]==fingerprint){
                val now=android.os.SystemClock.uptimeMillis()
                if(currentText==null||style.readFromFile&&now>=nextFilePoll){
                    val nextText=if(style.readFromFile){
                        nextFilePoll=now+1000L
                        if(style.file.isBlank()){
                            SourceRuntimeErrors.report(src.id,"Read from file is on: choose a text file in source properties.")
                            ""
                        }else withContext(Dispatchers.IO){
                            TextSourceRenderer.readTextFile(context,style.file)
                        }?:run{
                            SourceRuntimeErrors.report(src.id,"The text file could not be read. Choose it again.")
                            ""
                        }
                    }else style.text
                    if(nextText!=currentText){
                        currentText=nextText
                        textBitmap?.recycle()
                        textBitmap=runCatching{
                            TextSourceRenderer.render(context,style.copy(background=0,backgroundMode=0,extents=false),nextText.ifEmpty{" "})
                        }.getOrElse{
                            SourceRuntimeErrors.report(src.id,"The rolling text could not be drawn: ${it.message}")
                            return
                        }
                        val w=TextSourceRenderer.rollingWidth(style,textBitmap!!.width)
                        val h=TextSourceRenderer.rollingHeight(style,textBitmap!!.height)
                        SourceNativeSizes.report(src.id,w,h)
                        StreamLog.add("Rolling text ${src.name}: window ${w}x${h}, text ${textBitmap!!.width}x${textBitmap!!.height}")
                        offset=0f
                        previous=now
                    }
                }
                val bitmap=textBitmap?:continue
                val dt=((now-previous).coerceAtMost(100L)).coerceAtLeast(0L)/1000f
                previous=now
                offset+=style.rollingSpeed*dt
                val w=TextSourceRenderer.rollingWidth(style,bitmap.width)
                val h=TextSourceRenderer.rollingHeight(style,bitmap.height)
                val frame=TextSourceRenderer.renderRollingFrame(style,bitmap,offset,w,h)
                val rgba=frame.toRgba()
                frame.recycle()
                uploadIfCurrent(src.id,fingerprint,src.configJson,rgba.first,rgba.second.first,rgba.second.second)
                kotlinx.coroutines.delay(16)
            }
        }finally{textBitmap?.recycle()}
    }

    /** How many times larger than its own size a text source is shown on the canvas (from its transform), in quarter steps, 1-4. */
    private fun textScale(transformJson:String,native:Pair<Int,Int>?):Float{
        val t=runCatching{JSONObject(transformJson)}.getOrDefault(JSONObject())
        var scale=maxOf(kotlin.math.abs(t.optDouble("scaleX",1.0)),kotlin.math.abs(t.optDouble("scaleY",1.0)))
        val bw=t.optDouble("boundsWidth",0.0);val bh=t.optDouble("boundsHeight",0.0)
        if(t.optInt("boundsType",0)>0&&bw>0&&bh>0&&native!=null&&native.first>0&&native.second>0)scale=maxOf(bw/native.first,bh/native.second)
        return ((kotlin.math.round(scale*4)/4).coerceIn(1.0,4.0)).toFloat()
    }

    private fun uploadIfCurrent(id:String,fingerprint:String,configJson:String,rgba:ByteArray,width:Int,height:Int){
        synchronized(sourceStateLock){
            if(sourceFingerprints[id]==fingerprint){
                if(NativeEngine.updateSourceRgba(id,rgba,width,height)) {
                    NativeEngine.setSourceEffectsFromConfig(id,configJson)
                    SourceRuntimeErrors.clear(id)
                } else SourceRuntimeErrors.report(id,"The image or overlay could not be uploaded to the compositor.")
            }
        }
    }

    private fun removeLayerIfCurrent(id:String,fingerprint:String){
        synchronized(sourceStateLock){if(sourceFingerprints[id]==fingerprint)NativeEngine.removeSourceLayer(id)}
    }

    /**
     * OBS's Image Slide Show: slide time and transition (cut, fade, swipe, slide) with its speed, loop, randomize, hide
     * when done, automatic or manual (media controls / hotkeys) advance, visibility behaviour and the bounding size every
     * image is fitted into, so the source keeps one size.
     */
    private suspend fun renderSlideshow(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String,cfg:JSONObject){
        val configured=cfg.optJSONArray("files")
        val paths=if(configured!=null)(0 until configured.length()).mapNotNull{i->configured.optString(i).takeIf(String::isNotBlank)?:configured.optJSONObject(i)?.optString("value")?.takeIf(String::isNotBlank)} else emptyList()
        if(paths.isEmpty()){
            SourceRuntimeErrors.report(src.id,"Add at least one slideshow image in source properties.")
            removeLayerIfCurrent(src.id,fingerprint)
            return
        }
        val slideMs=when{cfg.has("slideTimeMs")->cfg.optLong("slideTimeMs");cfg.has("slide_time")->cfg.optLong("slide_time");else->cfg.optLong("slideIntervalSeconds",8L)*1000L}.coerceIn(50L,3_600_000L)
        val transition=cfg.optString("transition","cut").lowercase()
        val transitionMs=cfg.optLong("transitionSpeedMs",cfg.optLong("transition_speed",700L)).coerceIn(0L,10_000L)
        val loop=cfg.optBoolean("loop",true)
        val randomize=cfg.optBoolean("randomize",false)
        val hideWhenDone=cfg.optBoolean("hideWhenDone",cfg.optBoolean("hide",false))
        val manual=cfg.optString("slideMode",cfg.optString("slide_mode","mode_auto"))=="mode_manual"
        val pauseHidden=cfg.optString("playbackBehavior",cfg.optString("playback_behavior","stop_restart"))=="pause_unpause"
        val size=withContext(Dispatchers.IO){slideshowSize(paths,cfg.optString("customSize",cfg.optString("use_custom_size","Automatic")))}
        SourceNativeSizes.report(src.id,size.first,size.second)
        val order=paths.indices.toMutableList().also{if(randomize)it.shuffle()}
        val commands=kotlinx.coroutines.channels.Channel<SourceMediaCommands.Command>(kotlinx.coroutines.channels.Channel.CONFLATED)
        val listener=scope.launch{SourceMediaCommands.requests.collect{if(it.sourceId==src.id)commands.trySend(it.command)}}
        var position=0
        var playing=true
        var current:Bitmap?=null
        fun report()=SourceMediaCommands.report(src.id,SourceMediaCommands.State(playing,!playing&&position==0,slide=position+1,slides=order.size))
        try{
            suspend fun show(index:Int,animate:Boolean){
                val next=withContext(Dispatchers.IO){decodeBitmap(paths[order[index]])}?.let{fitInto(it,size.first,size.second)}
                if(next==null){SourceRuntimeErrors.report(src.id,"A slideshow image could not be opened. Re-select or relink the file.");return}
                val prev=current
                if(animate&&prev!=null&&transition!="cut"&&transitionMs>0)animateTransition(src,fingerprint,prev,next,transition,transitionMs)
                uploadBitmap(src,fingerprint,next)
                prev?.recycle()
                current=next
                report()
            }
            show(position,false)
            while(kotlinx.coroutines.currentCoroutineContext().isActive&&sourceFingerprints[src.id]==fingerprint){
                val command=if(manual||!playing)commands.receive() else kotlinx.coroutines.withTimeoutOrNull(slideMs){commands.receive()}
                if(pauseHidden&&visibility[src.id]==false&&command==null){
                    // Paused while hidden: wait until shown again, then give the slide its full time.
                    while(visibility[src.id]==false&&kotlinx.coroutines.currentCoroutineContext().isActive)kotlinx.coroutines.delay(100)
                    continue
                }
                when(command){
                    null,SourceMediaCommands.Command.NEXT->{
                        if(position+1>=order.size){
                            if(!loop&&command==null){
                                if(hideWhenDone)removeLayerIfCurrent(src.id,fingerprint)
                                playing=false;report();continue
                            }
                            position=0;if(randomize)order.shuffle()
                        }else position++
                        show(position,true)
                    }
                    SourceMediaCommands.Command.PREVIOUS->{position=if(position==0)order.size-1 else position-1;show(position,true)}
                    SourceMediaCommands.Command.PLAY_PAUSE->{playing=!playing;report()}
                    SourceMediaCommands.Command.RESTART->{playing=true;position=0;show(position,false)}
                    SourceMediaCommands.Command.STOP->{playing=false;position=0;show(position,false);report()}
                    else->Unit
                }
            }
        }finally{listener.cancel();current?.recycle()}
    }

    /** The slideshow's size: "WxH", an aspect ratio "W:H" (at the largest image's height) or automatic (the largest image). */
    private fun slideshowSize(paths:List<String>,custom:String):Pair<Int,Int>{
        Regex("(\\d+)\\s*[xX×]\\s*(\\d+)").matchEntire(custom.trim())?.let{return it.groupValues[1].toInt().coerceIn(1,8192) to it.groupValues[2].toInt().coerceIn(1,8192)}
        var w=0;var h=0
        for(p in paths.take(64)){val b=BitmapFactory.Options().apply{inJustDecodeBounds=true};openBitmapInput(p)?.use{BitmapFactory.decodeStream(it,null,b)};w=maxOf(w,b.outWidth);h=maxOf(h,b.outHeight)}
        if(w<=0||h<=0){w=1920;h=1080}
        // Large photos are capped like any decoded image.
        while(w.toLong()*h>MAX_DECODE_PIXELS){w/=2;h/=2}
        Regex("(\\d+)\\s*:\\s*(\\d+)").matchEntire(custom.trim())?.let{val aw=it.groupValues[1].toInt();val ah=it.groupValues[2].toInt();if(aw>0&&ah>0)return (h*aw/ah).coerceIn(1,8192) to h}
        return w to h
    }

    /** [image] scaled to fit inside w×h, centered, on transparency (OBS fits each slide into the bounding size). */
    private fun fitInto(image:Bitmap,w:Int,h:Int):Bitmap{
        if(image.width==w&&image.height==h)return image
        val out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val scale=minOf(w.toFloat()/image.width,h.toFloat()/image.height)
        val dw=image.width*scale;val dh=image.height*scale
        Canvas(out).drawBitmap(image,null,RectF((w-dw)/2f,(h-dh)/2f,(w+dw)/2f,(h+dh)/2f),Paint(Paint.FILTER_BITMAP_FLAG))
        image.recycle()
        return out
    }

    private suspend fun animateTransition(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String,from:Bitmap,to:Bitmap,kind:String,durationMs:Long){
        val w=to.width;val h=to.height
        val frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(frame);val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        val start=android.os.SystemClock.uptimeMillis()
        try{
            while(sourceFingerprints[src.id]==fingerprint){
                val t=((android.os.SystemClock.uptimeMillis()-start).toFloat()/durationMs).coerceIn(0f,1f)
                val e=t*t*(3-2*t) // smoothstep, as OBS's swipe / slide ease
                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR)
                when(kind){
                    "fade"->{paint.alpha=255;canvas.drawBitmap(from,0f,0f,paint);paint.alpha=(e*255).toInt();canvas.drawBitmap(to,0f,0f,paint);paint.alpha=255}
                    "swipe"->{canvas.drawBitmap(from,0f,0f,paint);canvas.drawBitmap(to,w*(1-e),0f,paint)}
                    else->{canvas.drawBitmap(from,-w*e,0f,paint);canvas.drawBitmap(to,w*(1-e),0f,paint)} // slide
                }
                val rgba=frame.toRgba()
                uploadIfCurrent(src.id,fingerprint,src.configJson,rgba.first,w,h)
                if(t>=1f)break
                kotlinx.coroutines.delay(33)
            }
        }finally{frame.recycle()}
    }

    private fun uploadBitmap(src:com.stream4k60.app.ui.main.SourceItem,fingerprint:String,bitmap:Bitmap){
        val frame=bitmap.toRgba()
        uploadIfCurrent(src.id,fingerprint,src.configJson,frame.first,frame.second.first,frame.second.second)
    }

    private suspend fun decodeBitmap(path:String):Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri=Uri.parse(path)
            if (uri.scheme?.lowercase() in setOf("http","https")) {
                val connection=(URL(path).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=10_000
                    readTimeout=15_000
                    instanceFollowRedirects=true
                    requestMethod="GET"
                    setRequestProperty("User-Agent","Stream4K60/1.0")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val bytes=connection.inputStream.use { it.readBytes() }
                    val boundsCheck=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,boundsCheck)
                    if(boundsCheck.outWidth<=0||boundsCheck.outHeight<=0)return@runCatching null
                    var sample=1
                    while((boundsCheck.outWidth.toLong()/(sample*2L))*(boundsCheck.outHeight.toLong()/(sample*2L))>MAX_DECODE_PIXELS)sample*=2
                    val options=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
                    BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                } finally { connection.disconnect() }
            } else {
                val localPath = when (uri.scheme?.lowercase()) {
                    "file" -> uri.path
                    "content" -> null
                    else -> path
                }
                if (localPath != null) {
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    BitmapFactory.decodeFile(localPath,bounds)
                    if(bounds.outWidth<=0||bounds.outHeight<=0)return@runCatching null
                    var sample=1
                    while((bounds.outWidth.toLong()/(sample*2L))*(bounds.outHeight.toLong()/(sample*2L))>MAX_DECODE_PIXELS)sample*=2
                    val options=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
                    BitmapFactory.decodeFile(localPath,options)
                } else {
                    val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                    openBitmapInput(path)?.use{BitmapFactory.decodeStream(it,null,bounds)}
                        ?: return@runCatching null
                    if(bounds.outWidth<=0||bounds.outHeight<=0)return@runCatching null
                    var sample=1
                    while((bounds.outWidth.toLong()/(sample*2L))*(bounds.outHeight.toLong()/(sample*2L))>MAX_DECODE_PIXELS)sample*=2
                    val options=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
                    openBitmapInput(path)?.use{BitmapFactory.decodeStream(it,null,options)}
                }
            }
        }.getOrNull()
    }

    private fun openBitmapInput(path:String):InputStream? {
        val uri=Uri.parse(path)
        return when(uri.scheme?.lowercase()){
            "content"->context.contentResolver.openInputStream(uri)
            "file"->uri.path?.let(::File)?.takeIf(File::isFile)?.let(::FileInputStream)
            else->File(path).absoluteFile.takeIf(File::isFile)?.let(::FileInputStream)
        }
    }

    private companion object { const val MAX_DECODE_PIXELS=3_145_728L }

    private fun rgbaBitmap(w:Int,h:Int,draw:(Canvas)->Unit):Pair<ByteArray,Pair<Int,Int>>{val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b);draw(c);return b.toRgba().also{b.recycle()}}
}

/**
 * Pixels in the byte order the GPU upload reads (R, G, B, A). They were written A, R, G, B, so alpha came from the blue
 * byte: black (and every colour without blue) turned transparent and other colours shifted.
 */
internal fun Bitmap.toRgba():Pair<ByteArray,Pair<Int,Int>>{
    val px=IntArray(width*height);getPixels(px,0,width,0,0,width,height)
    val out=ByteArray(px.size*4);var o=0
    for(v in px){
        val a=v ushr 24
        out[o++]=(v ushr 16).toByte();out[o++]=(v ushr 8).toByte();out[o++]=v.toByte()
        out[o++]=a.toByte()
    }
    return out to (width to height)
}
