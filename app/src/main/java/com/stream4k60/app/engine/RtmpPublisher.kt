package com.stream4k60.app.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random
import com.stream4k60.app.data.model.OutputCodec
import java.util.concurrent.atomic.AtomicBoolean

/** Native RTMP/RTMPS publisher with legacy AVC and Enhanced-RTMP HEVC support. */
/** Once a message has started arriving, the rest of it must follow within this time. */
private const val MESSAGE_BODY_TIMEOUT_MS = 10_000

class RtmpPublisher(private val onState:(State,String)->Unit={_,_->}){
    /** Why the last [start] failed, worded for the user. */
    @Volatile var lastError:String?=null; private set
    /** Set by [stop]; a connect in progress checks it between steps and gives up. */
    @Volatile private var stopRequested=false
    enum class State{IDLE,CONNECTING,CONNECTED,PUBLISHING,STOPPING,ERROR}
    data class VideoCodecConfig(
        val codec: OutputCodec,
        val sps: ByteArray? = null,
        val pps: ByteArray? = null,
        val hvcC: ByteArray? = null
    )
    data class AudioCodecConfig(val asc:ByteArray)
    /** HDR stream colour for the Enhanced RTMP "colorInfo" metadata (ITU-T H.273 codes, luminance in nits). */
    data class ColorInfo(val bitDepth:Int,val primaries:Int,val transfer:Int,val matrix:Int,val maxLuminance:Int,val minLuminance:Int=0)
    /** Set for an HDR stream: sent ahead of every HEVC sequence header, as OBS does. */
    @Volatile var colorInfo:ColorInfo?=null

    @Volatile private var state=State.IDLE
    private var socket:Socket?=null
    private var videoWidth=3840
    private var videoHeight=2160
    private var videoFps=60;private var input:BufferedInputStream?=null;private var output:BufferedOutputStream?=null
    private var streamId=1;private var outChunkSize=4096;private var inChunkSize=128;private var timestampBaseUs=Long.MIN_VALUE
    private val bytesSent=AtomicLong();private val bytesReceived=AtomicLong();private val lock=Any();private var lastAck=0L;private var ackWindow=0L
    private val inStates=HashMap<Int,ChunkState>()
    private data class Outbound(val video:Boolean,val sample:HardwareVideoEncoder.Sample,val v:VideoCodecConfig?,val a:AudioCodecConfig?)
    private val outbound=ArrayBlockingQueue<Outbound>(256)
    @Volatile private var writerRunning=false
    private val reconnecting=AtomicBoolean(false)
    private var writerThread:Thread?=null
    private var readerThread:Thread?=null
    /**
     * Every connection (the first and each reconnect) must start with the decoder setup (AVC/HEVC sequence header,
     * AAC config) and then a keyframe; the encoder emits its setup only once, so the latest one is kept here.
     */
    @Volatile private var latestVideoConfig:VideoCodecConfig?=null
    @Volatile private var latestAudioConfig:AudioCodecConfig?=null
    @Volatile private var videoSequenceSent=false
    @Volatile private var audioSequenceSent=false
    @Volatile private var waitingForKeyframe=true
    @Volatile private var firstMediaTimestampLogged=false
    /** Asked after each (re)connect so the next video frame is a keyframe instead of waiting a full GOP. */
    @Volatile var onKeyframeNeeded:(()->Unit)?=null
    private var reconnectThread:Thread?=null
    private var reconnectUrl=""
    private var reconnectKey=""
    private var reconnectTls=false
    private var reconnectTimeoutMs=20_000L
    private var reconnectWidth=3840
    private var reconnectHeight=2160
    private var reconnectFps=60
    private var reconnectCodec=OutputCodec.H264
    @Volatile private var reconnectEnabled=true
    @Volatile private var reconnectDelayMs=2_000L
    @Volatile private var maxReconnectAttempts=20
    /** Settings → Advanced: retry a dropped connection [maxAttempts] times, starting [delayMs] apart. */
    fun configureReconnect(enabled:Boolean,delayMs:Long,maxAttempts:Int){reconnectEnabled=enabled&&maxAttempts>0;reconnectDelayMs=delayMs.coerceIn(250,60_000);maxReconnectAttempts=maxAttempts.coerceIn(0,1000)}
    private var videoCodec=OutputCodec.H264

    fun start(url:String,streamKey:String,tls:Boolean,width:Int=3840,height:Int=2160,fps:Int=60,codec:OutputCodec=OutputCodec.H264,timeoutMs:Long=20_000):Boolean {
        require(codec == OutputCodec.H264 || codec == OutputCodec.HEVC) { "RTMP/RTMPS currently supports H.264 and Enhanced-RTMP HEVC" }
        videoWidth=width; videoHeight=height; videoFps=fps; videoCodec=codec
        reconnectUrl=url; reconnectKey=streamKey; reconnectTls=tls; reconnectTimeoutMs=timeoutMs
        reconnectWidth=width; reconnectHeight=height; reconnectFps=fps; reconnectCodec=codec
        if(state!=State.IDLE)return state==State.PUBLISHING
        reconnecting.set(false);stopRequested=false
        lastError=null
        return runCatching{connect(url,streamKey,tls,timeoutMs);true}.onFailure{
            // Network errors are named (UnknownHostException, SSLHandshakeException…); our own checks already read as sentences.
            lastError=if(it is java.io.IOException)"${it.javaClass.simpleName}: ${it.message}" else it.message?:it.javaClass.simpleName
            state=State.ERROR;onState(state,lastError?:"RTMP connect failed")
        }.getOrDefault(false)
    }

    fun stop(){
        synchronized(lock){
            if(state==State.IDLE)return
            stopRequested=true
            state=State.STOPPING
            onState(state,"Stopping")
            reconnecting.set(false)
            writerRunning=false
        }
        runCatching{writerThread?.join(1500)}
        writerThread=null
        runCatching{socket?.close()}
        runCatching{readerThread?.join(1500)}
        readerThread=null
        runCatching{reconnectThread?.join(1500)}
        reconnectThread=null
        outbound.clear()
        synchronized(lock){
            runCatching{socket?.close()}
            socket=null;input=null;output=null
            state=State.IDLE
            onState(state,"Stopped")
        }
    }

    fun isPublishing()=state==State.PUBLISHING

    fun sendVideo(sample:HardwareVideoEncoder.Sample,config:VideoCodecConfig?) {
        if(config!=null)latestVideoConfig=config
        if(state!=State.PUBLISHING)return
        val setup=config?:latestVideoConfig
        if(sample.codecConfig){if(setup!=null){enqueue(Outbound(true,sample,setup,null));videoSequenceSent=true};return}
        if(!videoSequenceSent){
            setup?:return // no decoder setup known yet: frames would be undecodable
            enqueue(Outbound(true,HardwareVideoEncoder.Sample(ByteArray(0),sample.ptsUs,false,true),setup,null));videoSequenceSent=true
        }
        if(waitingForKeyframe){if(!sample.keyframe){if(droppingForNetwork)droppedVideoFrames++;return};waitingForKeyframe=false;droppingForNetwork=false}
        enqueue(Outbound(true,sample,null,null))
    }
    fun sendAudio(sample:HardwareVideoEncoder.Sample,config:AudioCodecConfig?) {
        if(config!=null)latestAudioConfig=config
        if(state!=State.PUBLISHING)return
        val setup=config?:latestAudioConfig
        if(sample.codecConfig){if(setup!=null){enqueue(Outbound(false,sample,null,setup));audioSequenceSent=true};return}
        if(!audioSequenceSent){
            setup?:return
            enqueue(Outbound(false,HardwareVideoEncoder.Sample(ByteArray(0),sample.ptsUs,false,true),null,setup));audioSequenceSent=true
        }
        enqueue(Outbound(false,sample,null,null))
    }

    /** Video frames dropped because the upload couldn't keep up (OBS's "dropped frames (network)"). */
    @Volatile var droppedVideoFrames=0L; private set
    /** Video frames written to the server. */
    @Volatile var sentVideoFrames=0L; private set
    /** Waiting for a keyframe because of a network drop (not the normal first keyframe after connecting). */
    @Volatile private var droppingForNetwork=false
    /** Bytes written to the server so far, and packets waiting to be sent. */
    fun sentBytes()=bytesSent.get()
    fun queuedPackets()=outbound.size

    private fun enqueue(item:Outbound) {
        if(outbound.offer(item)) return
        // The upload can't keep up. Like OBS: drop the video still waiting to be sent and resume at the next keyframe
        // (requested now), so viewers see a short freeze rather than smeared frames. Pulling single frames from the
        // middle broke every later frame until a keyframe, and audio overflow used to drop whatever was oldest,
        // keyframes and setup included. Audio and decoder setup are kept.
        var dropped=0L
        val it=outbound.iterator()
        while(it.hasNext()){val old=it.next();if(old.video&&!old.sample.codecConfig){it.remove();dropped++}}
        if(item.video&&!item.sample.codecConfig)dropped++
        else if(!outbound.offer(item)){outbound.poll();outbound.offer(item)}
        if(dropped>0){droppedVideoFrames+=dropped;droppingForNetwork=true;waitingForKeyframe=true;onKeyframeNeeded?.invoke()}
    }

    private fun connect(url:String,key:String,tls:Boolean,timeoutMs:Long){
        state=State.CONNECTING;onState(state,"Connecting to ingest server")
        // A new connection starts from RTMP defaults; a reconnect must not inherit the old one's chunking or acks.
        inStates.clear();inChunkSize=128;bytesReceived.set(0);lastAck=0L;ackWindow=0L
        val uri=URI(if(url.startsWith("rtmps://")||url.startsWith("rtmp://"))url else error("Invalid RTMP(S) URL"))
        val host=uri.host?:error("Invalid RTMP host");val port=if(uri.port>0)uri.port else if(uri.scheme.equals("rtmps",true)||tls)443 else 1935
        val s=if(uri.scheme.equals("rtmps",true)||tls){
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket().also{it.connect(InetSocketAddress(host,port),timeoutMs.toInt().coerceAtLeast(1000));(it as SSLSocket).soTimeout=timeoutMs.toInt().coerceAtLeast(1000);it.startHandshake()}
        }else Socket().also{it.soTimeout=timeoutMs.toInt().coerceAtLeast(1000);it.connect(InetSocketAddress(host,port),timeoutMs.toInt().coerceAtLeast(1000))}
        socket=s;input=BufferedInputStream(s.getInputStream(),64*1024);output=BufferedOutputStream(s.getOutputStream(),64*1024)
        if(stopRequested){runCatching{s.close()};error("Stopped while connecting")}
        handshake()
        writeMessage(1,2,0,byteArrayOf(0,0x00,0x10,0x00)) // 4096 chunk size
        outChunkSize=4096
        val app=uri.path.trim('/').substringBefore('/').ifBlank{"live2"};val tcUrl="${if(uri.scheme.equals("rtmps",true)||tls)"rtmps" else "rtmp"}://$host:$port/$app"
        val props=linkedMapOf<String,Any?>(
            "app" to app,
            "type" to "nonprivate",
            "tcUrl" to tcUrl,
            "fpad" to false,
            "capabilities" to 15.0,
            "audioCodecs" to 4071.0,
            "videoCodecs" to 252.0,
            "videoFunction" to 1.0,
            "objectEncoding" to 0.0
        ).apply {
            if (videoCodec == OutputCodec.HEVC) put("fourCcList", listOf("hvc1"))
        }
        // The properties are connect's command object; sending them after a null left the server without an app name.
        sendCommand(0,"connect",1,emptyList(),commandObject=props);waitFor("connect",1.0){it.command=="_result"&&it.transaction==1.0}
        state=State.CONNECTED;onState(state,"Connected")
        sendCommand(0,"releaseStream",2,listOf(key));drainUntil(800)
        sendCommand(0,"FCPublish",3,listOf(key));drainUntil(800)
        sendCommand(0,"createStream",4,emptyList());val created=waitFor("createStream",4.0){it.command=="_result"&&it.transaction==4.0};streamId=created.values.lastOrNull{it is Double}?.let{(it as Double).toInt()}?:error("RTMP server did not return a stream id")
        // publish belongs to the stream createStream returned (chunk stream 8 carries that message stream id), not stream 0.
        sendCommand(8,"publish",5,listOf(key,"live"));waitFor("publish",5.0){it.command=="onStatus"&&it.info["code"]=="NetStream.Publish.Start"}
        sendMetadata();timestampBaseUs=Long.MIN_VALUE;firstMediaTimestampLogged=false
        videoSequenceSent=false;audioSequenceSent=false;waitingForKeyframe=true
        if(stopRequested)error("Stopped while connecting")
        state=State.PUBLISHING;onState(state,"Publishing")
        writerRunning=true;writerThread=Thread(::writeLoop,"Stream4k-RTMP-Writer").apply{priority=Thread.MAX_PRIORITY;start()}
        readerThread=Thread(::readLoop,"Stream4k-RTMP-Reader").apply{start()}
        onKeyframeNeeded?.invoke()
    }

    private fun writeLoop(){
        try{
            while(writerRunning){
                val item=outbound.take()
                if(!writerRunning)break
                if(item.video){
                    val s=item.sample;val ts=timestamp(s.ptsUs,s.codecConfig)
                    if(!s.codecConfig&&!firstMediaTimestampLogged){firstMediaTimestampLogged=true;StreamLog.add("RTMP first video PTS ${s.ptsUs} -> ${ts} ms")}
                    if(s.codecConfig&&item.v?.codec==OutputCodec.HEVC)colorInfo?.let{writeMessage(0x09,6,ts,FlvMetadata.colorInfo(it))}
                    val body=if(s.codecConfig)flvVideoSequence(item.v?:return)else flvVideoFrame(s.data,s.keyframe, videoCodec)
                    writeMessage(0x09,6,ts,body)
                    if(!s.codecConfig)sentVideoFrames++
                }else{
                    val s=item.sample;val ts=timestamp(s.ptsUs,s.codecConfig)
                    if(!s.codecConfig&&!firstMediaTimestampLogged){firstMediaTimestampLogged=true;StreamLog.add("RTMP first audio PTS ${s.ptsUs} -> ${ts} ms")}
                    val body=if(s.codecConfig)flvAudioSequence(item.a?:return)else flvAudioRaw(s.data)
                    writeMessage(0x08,4,ts,body)
                }
            }
        }catch(t:Throwable){
            if(writerRunning && state!=State.STOPPING && state!=State.IDLE){
                writerRunning=false
                runCatching{socket?.close()}
                scheduleReconnect("${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * While publishing, reads what the server sends: answers its keep-alive pings, and reports status or error
     * messages (often the reason a server is about to drop the connection) instead of leaving them unread.
     */
    private fun readLoop(){
        try{
            while(writerRunning){
                val m=readMessage(1000)?:continue
                handleControl(m)
                when(m.type){
                    // User control event 6 = ping request: answer 7 with the same timestamp.
                    4->if(m.body.size>=6&&m.body[0].toInt()==0&&m.body[1].toInt()==6)synchronized(lock){writeMessageRaw(4,2,0L,byteArrayOf(0,7)+m.body.copyOfRange(2,6));output?.flush()}
                    20,17->Amf.decodeCommand(m.body)?.let{c->
                        if(c.command=="onStatus"||c.command=="_error"||c.command=="close")
                            onState(state,"Server says ${c.info["code"]?:c.command}${c.info["description"]?.let{" ($it)"}.orEmpty()}")
                    }
                }
            }
        }catch(t:Throwable){
            if(writerRunning && state!=State.STOPPING && state!=State.IDLE){
                writerRunning=false
                runCatching{socket?.close()}
                scheduleReconnect("server side: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun scheduleReconnect(reason:String){
        if(!reconnectEnabled){state=State.ERROR;onState(state,"Connection lost: $reason");return}
        if(!reconnecting.compareAndSet(false,true))return
        state=State.CONNECTING
        onState(state,"Connection lost ($reason); reconnecting")
        reconnectThread=Thread({
            var delay=reconnectDelayMs
            repeat(maxReconnectAttempts){
                if(!reconnecting.get())return@Thread
                Thread.sleep(delay)
                if(!reconnecting.get())return@Thread
                runCatching{
                    socket?.close()
                    readerThread?.let{if(it!=Thread.currentThread())it.join(2000)};readerThread=null
                    socket=null;input=null;output=null
                    outbound.clear()
                    connect(reconnectUrl,reconnectKey,reconnectTls,reconnectTimeoutMs)
                }.onSuccess{
                    reconnecting.set(false)
                    return@Thread
                }.onFailure{
                    delay=(delay*2).coerceAtMost(maxOf(reconnectDelayMs,30_000L))
                }
            }
            reconnecting.set(false)
            state=State.ERROR
            onState(state,"RTMP reconnect failed: $reason")
        },"Stream4k-RTMP-Reconnect").apply{priority=Thread.NORM_PRIORITY+1;start()}
    }

    private fun sendMetadata(){
        val videoCodeId: Any = if(videoCodec == OutputCodec.HEVC) fourCcNumber("hvc1").toDouble() else "avc1"
        val meta=linkedMapOf<String,Any?>(
            "width" to videoWidth.toDouble(),
            "height" to videoHeight.toDouble(),
            "framerate" to videoFps.toDouble(),
            "videocodecid" to videoCodeId,
            "audiocodecid" to "mp4a",
            "stereo" to true,
            "2.0" to 2.0
        )
        val body=Amf.encode(listOf("@setDataFrame","onMetaData",meta));writeMessage(0x12,5,0,body)
    }

    private fun timestamp(ptsUs:Long,setup:Boolean=false):Long{
        // Codec-config buffers are not media-time anchors: some MediaCodec encoders report their config PTS as 0.
        // Anchor RTMP time on the first actual media sample so absolute CLOCK_MONOTONIC values never become stream time.
        if(!setup&&timestampBaseUs==Long.MIN_VALUE)timestampBaseUs=ptsUs
        if(timestampBaseUs==Long.MIN_VALUE)return 0L
        return ((ptsUs-timestampBaseUs)/1000L).coerceIn(0L,0xFFFFFFFFL)
    }

    private fun writeMessage(type:Int,chunkStreamId:Int,ts:Long,body:ByteArray){synchronized(lock){val o=output?:return;var off=0;var first=true;val msgStream=when(chunkStreamId){4,5,6,8->streamId else->0};while(first||off<body.size){val n=minOf(outChunkSize,body.size-off);val useFmt=if(first)0 else 3;writeBasicHeader(o,useFmt,chunkStreamId);if(first){val ext=if(ts>=0xFFFFFFL)0xFFFFFF else ts.toInt();writeU24(o,ext);writeU24(o,body.size);o.write(type);writeU32LE(o,msgStream);if(ts>=0xFFFFFFL)writeU32BE(o,ts and 0xffffffffL)}else if(ts>=0xFFFFFFL){writeU32BE(o,ts and 0xffffffffL)};o.write(body,off,n);off+=n;first=false};o.flush();bytesSent.addAndGet(body.size.toLong())}}

    private fun writeBasicHeader(o:BufferedOutputStream,fmt:Int,csid:Int){require(csid in 2..65599);val f=fmt shl 6;when{csid<64->o.write(f or csid);csid<320-> {o.write(f);o.write(csid-64)};else->{o.write(f or 1);val n=csid-64;o.write(n and 0xff);o.write(n ushr 8)}}}

    /** [commandObject] fills the slot after the transaction id: connect's properties (app, tcUrl…); null for other commands. */
    private fun sendCommand(csid:Int,name:String,tx:Int,args:List<Any?>,commandObject:Any?=null){val stream=if(csid<2)3 else csid;writeMessage(0x14,stream,0,Amf.encode(listOf(name,tx.toDouble(),commandObject)+args))}

    private data class Command(val command:String,val transaction:Double,val values:List<Any?>,val info:Map<String,String>)
    /** Waits for the answer to [step]. A refusal (`_error` for [transaction], or an error onStatus such as a bad stream key) fails at once with the server's reason. */
    private fun waitFor(step:String,transaction:Double,timeoutMs:Long=15_000,predicate:(Command)->Boolean):Command{
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while(System.nanoTime()<deadline){
            val c=readCommand(remainingTimeout(deadline))?:continue
            if(c.command=="_error"&&c.transaction==transaction||c.command=="onStatus"&&c.info["level"]=="error")
                error("The RTMP server refused $step: ${c.info["code"]?:c.command}${c.info["description"]?.let{" ($it)"}.orEmpty()}")
            if(predicate(c))return c
        }
        error("The RTMP server did not answer $step within ${timeoutMs/1000} s")
    }
    private fun drainUntil(ms:Long){val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(ms);while(System.nanoTime()<deadline){val m=readMessage(remainingTimeout(deadline))?:break;handleControl(m)}}
    private fun remainingTimeout(deadline:Long)=((deadline-System.nanoTime())/1_000_000).toInt().coerceIn(1,1000)

    private fun readCommand(timeoutMs:Int):Command?{val m=readMessage(timeoutMs)?:return null;handleControl(m);if(m.type!=20&&m.type!=17)return null;return Amf.decodeCommand(m.body)}

    private data class InMessage(val type:Int,val streamId:Int,val timestamp:Int,val body:ByteArray)
    private data class ChunkState(var timestamp:Int=0,var delta:Int=0,var length:Int=0,var type:Int=0,var streamId:Int=0,var remaining:Int=0,var buffer:ByteArrayOutputStream=ByteArrayOutputStream(),var extended:Boolean=false)
    private fun readMessage(timeoutMs:Int):InMessage?{
        val i=input?:return null;socket?.soTimeout=timeoutMs
        // No next message within the wait just means the server has nothing more to say yet. This used to throw,
        // which aborted every connect at the first quiet moment (after releaseStream/FCPublish).
        val first=try{i.read()}catch(_:SocketTimeoutException){return null}
        require(first>=0){"The RTMP server closed the connection"}
        socket?.soTimeout=MESSAGE_BODY_TIMEOUT_MS
        val (fmt,csid)=readBasic(i,first);val st=inStates.getOrPut(csid){ChunkState()};var timestamp=st.timestamp
        if(fmt==0){timestamp=readU24(i);st.length=readU24(i);st.type=i.read();st.streamId=readU32LE(i).toInt();st.delta=0;st.buffer.reset();st.remaining=st.length;st.extended=timestamp==0xFFFFFF;if(st.extended)timestamp=readU32BE(i).toInt();st.timestamp=timestamp}
        else if(fmt==1){st.delta=readU24(i);st.length=readU24(i);st.type=i.read();st.remaining=st.length;st.buffer.reset();timestamp=st.timestamp+st.delta;st.timestamp=timestamp;if(st.delta==0xFFFFFF)readU32BE(i)}
        else if(fmt==2){st.delta=readU24(i);timestamp=st.timestamp+st.delta;st.timestamp=timestamp;st.remaining=st.length;st.buffer.reset();if(st.delta==0xFFFFFF)readU32BE(i)}
        else {if(st.remaining==0){st.remaining=st.length;st.buffer.reset();};if(st.extended)readU32BE(i)}
        val take=minOf(inChunkSize,st.remaining);val buf=ByteArray(take);readFully(i,buf);st.buffer.write(buf);st.remaining-=take;if(st.remaining>0){while(st.remaining>0){val (cf,cc)=readBasic(i);require(cc==csid){"Unexpected RTMP continuation chunk"};if(cf==3&&st.extended)readU32BE(i);val n=minOf(inChunkSize,st.remaining);val b=ByteArray(n);readFully(i,b);st.buffer.write(b);st.remaining-=n}}
        val body=st.buffer.toByteArray();bytesReceived.addAndGet(body.size.toLong());val msg=InMessage(st.type,st.streamId,st.timestamp,body);st.buffer.reset();st.remaining=0;return msg
    }

    private fun handleControl(m:InMessage){when(m.type){1->if(m.body.size>=4){inChunkSize=readU32BE(m.body).toInt().coerceIn(128,1024*1024)};5->if(m.body.size>=4){ackWindow=readU32BE(m.body);};6->{if(m.body.size>=8)ackWindow=readU32BE(m.body);};3->{/* acknowledgement */};8,9,18->{}};if(ackWindow>0&&bytesReceived.get()-lastAck>=ackWindow){val b=ByteArray(4);writeU32BE(b,bytesReceived.get() and 0xffffffffL);writeMessageRaw(3,2,0L,b);lastAck=bytesReceived.get()}}

    // Locked like writeMessage: the reader thread's acks/pings must not interleave with media chunks.
    private fun writeMessageRaw(type:Int,chunkStreamId:Int,ts:Long,body:ByteArray){synchronized(lock){val o=output?:return;writeBasicHeader(o,0,chunkStreamId);writeU24(o,ts.coerceAtMost(0xFFFFFFL).toInt());writeU24(o,body.size);o.write(type);writeU32LE(o,0);o.write(body);o.flush()}}

    private fun handshake(){val o=output?:error("No RTMP output");val i=input?:error("No RTMP input");o.write(3);val time=(System.currentTimeMillis()/1000).toInt();writeU32BE(o,time.toLong());writeU32BE(o,0);val c1=ByteArray(1528);Random.nextBytes(c1);o.write(c1);o.flush();require(i.read()==3){"Invalid RTMP S0"};val s1=ByteArray(1536);readFully(i,s1);val s2=ByteArray(1536);readFully(i,s2);o.write(s1);o.flush();/* C2 sent */}

    private fun writeU24(o:OutputStream,v:Int){o.write(v ushr 16);o.write(v ushr 8);o.write(v)}
    private fun writeU32LE(o:OutputStream,v:Int){o.write(v);o.write(v ushr 8);o.write(v ushr 16);o.write(v ushr 24)}
    private fun writeU32BE(o:OutputStream,v:Long){o.write((v ushr 24).toInt());o.write((v ushr 16).toInt());o.write((v ushr 8).toInt());o.write(v.toInt())}
    private fun writeU32BE(b:ByteArray,v:Long){b[0]=(v ushr 24).toByte();b[1]=(v ushr 16).toByte();b[2]=(v ushr 8).toByte();b[3]=v.toByte()}
    private fun readBasic(i:BufferedInputStream,first:Int=i.read()):Pair<Int,Int>{val b=first;require(b>=0){"The RTMP server closed the connection"};val fmt=b ushr 6;var csid=b and 63;when(csid){0->csid=64+i.read();1->{val a=i.read();val b2=i.read();csid=64+a+(b2 shl 8)}};return fmt to csid}
    private fun readU24(i:BufferedInputStream)=readU24(byteArrayOf(i.read().toByte(),i.read().toByte(),i.read().toByte()))
    private fun readU24(b:ByteArray)=((b[0].toInt() and 255) shl 16) or ((b[1].toInt() and 255) shl 8) or (b[2].toInt() and 255)
    private fun readU32LE(i:BufferedInputStream)=((i.read() and 255)) or ((i.read() and 255) shl 8) or ((i.read() and 255) shl 16) or ((i.read() and 255) shl 24)
    private fun readU32BE(i:BufferedInputStream)=((i.read() and 255).toLong() shl 24) or ((i.read() and 255).toLong() shl 16) or ((i.read() and 255).toLong() shl 8) or (i.read() and 255).toLong()
    private fun readU32BE(b:ByteArray)=((b[0].toLong() and 255) shl 24) or ((b[1].toLong() and 255) shl 16) or ((b[2].toLong() and 255) shl 8) or (b[3].toLong() and 255)
    private fun readFully(i:BufferedInputStream,b:ByteArray){var o=0;while(o<b.size){val n=i.read(b,o,b.size-o);require(n>0){"RTMP socket closed"};o+=n}}

    object AnnexB{
        fun nalus(data:ByteArray):List<ByteArray>{val out=ArrayList<ByteArray>();var start=findStart(data,0);if(start<0)return lengthPrefixed(data);while(start>=0){val prefix=if(data[start+2].toInt()==1)3 else 4;val nstart=start+prefix;val next=findStart(data,nstart);val end=if(next<0)data.size else next;if(end>nstart)out.add(data.copyOfRange(nstart,end));start=next};return if(out.isEmpty())listOf(data)else out}
        private fun findStart(d:ByteArray,from:Int):Int{var i=from;while(i+3<d.size){if(d[i].toInt()==0&&d[i+1].toInt()==0&&(d[i+2].toInt()==1||(d[i+2].toInt()==0&&d[i+3].toInt()==1)))return i;i++};return -1}
        private fun lengthPrefixed(d:ByteArray):List<ByteArray>{val out=ArrayList<ByteArray>();var p=0;while(p+4<=d.size){val n=((d[p].toInt() and 255) shl 24) or ((d[p+1].toInt() and 255) shl 16) or ((d[p+2].toInt() and 255) shl 8) or (d[p+3].toInt() and 255);p+=4;if(n<=0||p+n>d.size)return listOf(d);out.add(d.copyOfRange(p,p+n));p+=n};return if(out.isEmpty())listOf(d)else out}
    }

    private fun flvVideoSequence(c:VideoCodecConfig):ByteArray {
        return when(c.codec) {
            OutputCodec.H264 -> {
                // Bare NAL units: a start code left in front made the record unreadable (YouTube dropped the stream).
                val sps=AnnexB.nalus(c.sps ?: error("H.264 SPS missing")).first()
                val pps=AnnexB.nalus(c.pps ?: error("H.264 PPS missing")).first()
                // AVCDecoderConfigurationRecord: version 1, profile, compatibility, level (from the SPS), 0xFF = 4-byte
                // NAL lengths, 0xE1 = one SPS, then one PPS. The level byte was missing, shifting everything after it.
                val o=ByteArrayOutputStream();o.write(0x17);o.write(0);writeU24(o,0);o.write(1)
                o.write(sps.getOrElse(1){0}.toInt() and 255);o.write(sps.getOrElse(2){0}.toInt() and 255);o.write(sps.getOrElse(3){0}.toInt() and 255);o.write(0xFF)
                o.write(0xE1);writeU16(o,sps.size);o.write(sps);o.write(1);writeU16(o,pps.size);o.write(pps);o.toByteArray()
            }
            OutputCodec.HEVC -> {
                val cfg=c.hvcC ?: error("HEVC configuration record missing")
                ByteArrayOutputStream().apply {
                    // Enhanced RTMP: IsExHeader (0x80) | FrameType 1 keyframe (0x10) | PacketType 0 SequenceStart.
                    // This was 0x08 (no IsExHeader bit): YouTube read a legacy tag with a bogus codec and hung up.
                    write(0x90)
                    writeFourCc(this, "hvc1")
                    write(cfg)
                }.toByteArray()
            }
            else -> error("Unsupported RTMP video codec: ${c.codec}")
        }
    }

    private fun flvVideoFrame(data:ByteArray,key:Boolean,codec:OutputCodec):ByteArray{
        return when(codec) {
            OutputCodec.H264 -> {
                val o=ByteArrayOutputStream();o.write(if(key)0x17 else 0x27);o.write(1);writeU24(o,0);for(n in AnnexB.nalus(data)){writeU32(o,n.size);o.write(n)};o.toByteArray()
            }
            OutputCodec.HEVC -> {
                // IsExHeader | FrameType (1 key, 2 inter) | PacketType 1 CodedFrames (with a 24-bit composition time).
                val o=ByteArrayOutputStream();o.write(if(key)0x91 else 0xA1);writeFourCc(o,"hvc1");writeU24(o,0);for(n in AnnexB.nalus(data)){writeU32(o,n.size);o.write(n)};o.toByteArray()
            }
            else -> error("Unsupported RTMP video codec: $codec")
        }
    }
    private fun writeFourCc(o:ByteArrayOutputStream, value:String){
        require(value.length==4)
        value.forEach{ o.write(it.code) }
    }

    private fun fourCcNumber(value:String):Long {
        require(value.length==4)
        return value.fold(0L){acc,c->(acc shl 8) or c.code.toLong()}
    }

    object FlvMetadata {
        /**
         * OBS's flv_packet_metadata: IsExHeader | PacketType 4 Metadata (0x84), 'hvc1', then AMF0 "colorInfo" with
         * colorConfig {bitDepth, colorPrimaries, transferCharacteristics, matrixCoefficients} and hdrMdcv {max/min luminance}.
         */
        fun colorInfo(c:ColorInfo):ByteArray{
            val o=ByteArrayOutputStream();o.write(0x84);o.write("hvc1".toByteArray())
            val info=linkedMapOf<String,Any?>("colorConfig" to linkedMapOf("bitDepth" to c.bitDepth.toDouble(),"colorPrimaries" to c.primaries.toDouble(),"transferCharacteristics" to c.transfer.toDouble(),"matrixCoefficients" to c.matrix.toDouble()))
            if(c.maxLuminance!=0)info["hdrMdcv"]=linkedMapOf("maxLuminance" to c.maxLuminance.toDouble(),"minLuminance" to c.minLuminance.toDouble())
            o.write(Amf.encode(listOf("colorInfo",info)));return o.toByteArray()
        }
    }

    object HevcConfiguration {
        fun fromCsd(csd:ByteArray, profileIdc:Int = 1, levelIdc:Int = 153):ByteArray {
            // Android exposes HEVC CSD as VPS+SPS+PPS, each beginning with 00 00 00 01.
            // Enhanced-RTMP requires ISO/IEC 14496-15 HEVCDecoderConfigurationRecord (hvcC).
            val nalus = AnnexB.nalus(csd)
            val vps = nalus.filter { nalType(it)==32 }
            val sps = nalus.filter { nalType(it)==33 }
            val pps = nalus.filter { nalType(it)==34 }
            require(vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()) { "HEVC CSD lacks VPS/SPS/PPS" }
            val arrays = listOf(32 to vps, 33 to sps, 34 to pps)
            // The record's profile, compatibility, constraint and level fields are the SPS's profile_tier_level, copied
            // as-is (they were zeros plus a guessed profile/level); the MediaFormat values are only a fallback.
            val ptl = spsHeader(sps.first())
            val profile = ptl?.let { it[1].toInt() and 0x1F } ?: (profileIdc and 0x1F)
            val subLayers = ptl?.let { ((it[0].toInt() ushr 1) and 0x07) + 1 } ?: 1
            val nested = ptl?.let { it[0].toInt() and 0x01 } ?: 1
            val bitDepthMinus8 = if (profile == 2) 2 else 0 // Main 10
            return ByteArrayOutputStream().apply {
                write(1) // configurationVersion
                if (ptl != null) write(ptl, 1, 12)
                else { write(profile); repeat(4){write(0)}; repeat(6){write(0)}; write(levelIdc and 0xFF) }
                write(0xF0); write(0x00) // reserved + min_spatial_segmentation_idc=0
                write(0xFC or 0) // reserved + parallelismType=0
                write(0xFD) // reserved + chromaFormat=1 (4:2:0)
                write(0xF8 or bitDepthMinus8) // reserved + bitDepthLumaMinus8
                write(0xF8 or bitDepthMinus8) // reserved + bitDepthChromaMinus8
                write(0); write(0) // avgFrameRate=0
                write((subLayers shl 3) or (nested shl 2) or 3) // constantFrameRate=0, numTemporalLayers, temporalIdNested, lengthSizeMinusOne=3
                write(arrays.size)
                for((type,list) in arrays){
                    write(0x80 or (type and 0x3F))
                    writeU16(this,list.size)
                    for(nal in list){ writeU16(this,nal.size); write(nal) }
                }
            }.toByteArray()
        }
        private fun nalType(nal:ByteArray)=if(nal.size>=2)((nal[0].toInt() ushr 1) and 0x3F) else -1
        /**
         * The SPS's first 13 payload bytes with emulation prevention removed: the sub-layer byte, then the 12-byte
         * general profile_tier_level. Null when the SPS is too short.
         */
        internal fun spsHeader(sps:ByteArray):ByteArray?{
            val out=ByteArrayOutputStream();var zeros=0
            for(i in 2 until sps.size){
                val b=sps[i].toInt() and 0xFF
                if(zeros>=2&&b==3){zeros=0;continue}
                out.write(b);zeros=if(b==0)zeros+1 else 0
                if(out.size()>=13)break
            }
            return out.toByteArray().takeIf{it.size>=13}
        }
        private fun writeU16(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)}
    }

    private fun flvAudioSequence(c:AudioCodecConfig)=byteArrayOf(0xAF.toByte(),0,c.asc.getOrElse(0){0},c.asc.getOrElse(1){0})
    private fun flvAudioRaw(d:ByteArray)=byteArrayOf(0xAF.toByte(),1)+d
    private fun writeU16(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)}
    private fun writeU32(o:ByteArrayOutputStream,v:Int){o.write(v ushr 24);o.write(v ushr 16);o.write(v ushr 8);o.write(v)}

    private object Amf{
        fun encode(v:List<Any?>):ByteArray{val o=ByteArrayOutputStream();v.forEach{write(o,it)};return o.toByteArray()}
        private fun write(o:ByteArrayOutputStream,v:Any?){when(v){null->o.write(5);is String->{o.write(2);writeUtf(o,v)};is Double->{o.write(0);writeLong(o,java.lang.Double.doubleToRawLongBits(v))};is Float->{o.write(0);writeLong(o,java.lang.Double.doubleToRawLongBits(v.toDouble()))};is Boolean->{o.write(1);o.write(if(v)1 else 0)};is List<*>->{o.write(10);writeInt32(o,v.size);v.forEach{write(o,it)}};is Map<*,*>->{o.write(3);for((k,x)in v){writeShort(o,k.toString().length);o.write(k.toString().toByteArray());write(o,x)};o.write(0);o.write(0);o.write(9)};else->write(o,v.toString())}}
        private fun writeUtf(o:ByteArrayOutputStream,s:String){val b=s.toByteArray();writeShort(o,b.size);o.write(b)};private fun writeShort(o:ByteArrayOutputStream,v:Int){o.write(v ushr 8);o.write(v)};private fun writeInt32(o:ByteArrayOutputStream,v:Int){o.write(v ushr 24);o.write(v ushr 16);o.write(v ushr 8);o.write(v)};private fun writeLong(o:ByteArrayOutputStream,v:Long){for(s in 56 downTo 0 step 8)o.write((v ushr s).toInt())}
        data class Decoded(val command:String,val transaction:Double,val values:List<Any?>,val info:Map<String,String>)
        fun decodeCommand(b:ByteArray):Command?{val r=Reader(b);val command=r.read() as? String?:return null;val tx=(r.read() as? Double)?:0.0;val values=mutableListOf<Any?>();try{while(r.remaining()>0)values.add(r.read())}catch(_:IndexOutOfBoundsException){};val info=values.flatMap{(it as? Map<*,*>)?.entries?:emptySet()}.associate{it.key.toString() to it.value.toString()};return Command(command,tx,values,info)}
        class Reader(private val b:ByteArray){var p=0;fun remaining()=b.size-p;fun read():Any?{if(p>=b.size)return null;return when(val t=b[p++].toInt() and 255){0->{val x=readLong();java.lang.Double.longBitsToDouble(x)};1->(b[p++].toInt()!=0);2->{val n=readShort();String(b,p,n).also{p+=n}};3->properties();5->null;6->null;8->{p+=4;properties()};12->{val n=readInt32();String(b,p,n).also{p+=n}};10->{val n=readInt32();List(n){read()}};11->{val time=readLong();val tz=readShort();Pair(time,tz)};else->null}}
            /** Object/ECMA-array properties up to the object-end marker (00 00 09). */
            private fun properties():Map<String,Any?>{val m=linkedMapOf<String,Any?>();while(p+3<=b.size){val n=readShort();if(n==0&&(b[p].toInt() and 255)==9){p++;return m};val k=String(b,p,n);p+=n;m[k]=read()};return m}
            private fun readShort()=((b[p++].toInt() and 255) shl 8) or (b[p++].toInt() and 255);private fun readInt32():Int{val v=((b[p++].toInt() and 255) shl 24) or ((b[p++].toInt() and 255) shl 16) or ((b[p++].toInt() and 255) shl 8) or (b[p++].toInt() and 255);return v};private fun readLong():Long{var v=0L;repeat(8){v=(v shl 8) or (b[p++].toLong() and 255)};return v}
        }
    }
}
