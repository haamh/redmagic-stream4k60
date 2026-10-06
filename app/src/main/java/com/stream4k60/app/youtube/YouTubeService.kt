package com.stream4k60.app.youtube

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import timber.log.Timber
import java.net.URL

data class YouTubeStreamIssue(
    val type:String,
    val severity:String,
    val reason:String,
    val description:String
)

data class YouTubeStreamHealth(
    val streamStatus:String?,
    val healthStatus:String?,
    val lastUpdateTimeSeconds:Long?,
    val issues:List<YouTubeStreamIssue>
){
    val fatal:Boolean get()=issues.any{it.severity.equals("error",true)}
    fun summary():String = buildList {
        streamStatus?.let{add("streamStatus=${it}")}
        healthStatus?.let{add("health=${it}")}
        issues.forEach { add("${it.severity}:${it.type}: ${it.description}") }
    }.joinToString(" | ").ifBlank{"no health data"}
}

@kotlinx.serialization.Serializable
data class YouTubeBroadcast(val id:String,val title:String,val scheduledStart:String?,val lifeCycle:String?,val streamId:String?,val streamStatus:String?,val ingestionType:String?,val ingestionUrl:String?,val rtmpsUrl:String?,val backupUrl:String?,val streamName:String?,val thumbnail:String?,val latency:String?=null)

class YouTubeService(private val accessTokenProvider:suspend ()->String?) {
    suspend fun listBroadcasts():List<YouTubeBroadcast> = withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        val uri="https://www.googleapis.com/youtube/v3/liveBroadcasts?part=id,snippet,contentDetails,status&mine=true&maxResults=50"
        val root=get(uri,token)
        val items=root["items"]?.jsonArray ?: JsonArray(emptyList())
        items.map{element->
            val b=element.jsonObject
            val id=b["id"]?.jsonPrimitive?.content.orEmpty();val sn=b["snippet"]?.jsonObject;val cd=b["contentDetails"]?.jsonObject;val st=b["status"]?.jsonObject;val streamId=cd?.get("boundStreamId")?.jsonPrimitive?.contentOrNull
            val stream=streamId?.let{get("https://www.googleapis.com/youtube/v3/liveStreams?part=id,snippet,cdn,status&id=$it",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject}
            val cdn=stream?.get("cdn")?.jsonObject;val ii=cdn?.get("ingestionInfo")?.jsonObject
            YouTubeBroadcast(id,sn?.get("title")?.jsonPrimitive?.content.orEmpty(),sn?.get("scheduledStartTime")?.jsonPrimitive?.contentOrNull,st?.get("lifeCycleStatus")?.jsonPrimitive?.contentOrNull,streamId,stream?.get("status")?.jsonObject?.get("streamStatus")?.jsonPrimitive?.contentOrNull,cdn?.get("ingestionType")?.jsonPrimitive?.contentOrNull,ii?.get("ingestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("rtmpsIngestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("rtmpsBackupIngestionAddress")?.jsonPrimitive?.contentOrNull,ii?.get("streamName")?.jsonPrimitive?.contentOrNull,sn?.get("thumbnails")?.jsonObject?.get("default")?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull,cd?.get("latencyPreference")?.jsonPrimitive?.contentOrNull)
        }
    }
    /** Title of the channel this sign-in reaches, or null when the Google account has no channel of its own. */
    suspend fun channelTitle():String? = withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        get("https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject?.get("snippet")?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
    }
    suspend fun transitionBroadcast(id:String,status:String){withContext(Dispatchers.IO){val token=accessTokenProvider()?:error("YouTube not connected");post("https://www.googleapis.com/youtube/v3/liveBroadcasts/transition?id=$id&broadcastStatus=$status&part=id,status",token,"POST", "")}}
    suspend fun streamIsActive(streamId:String):Boolean = streamStatus(streamId).equals("active", true)
    /** lifeCycleStatus (ready, testing, live, complete…) and bound stream id of one broadcast: one request, not the whole list. */
    suspend fun broadcastState(id:String):Pair<String?,String?> = withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        val b=get("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=status,contentDetails&id=$id",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject?:error("YouTube can't find this broadcast any more")
        b["status"]?.jsonObject?.get("lifeCycleStatus")?.jsonPrimitive?.contentOrNull to b["contentDetails"]?.jsonObject?.get("boundStreamId")?.jsonPrimitive?.contentOrNull
    }
    /** Full ingest/health status returned by YouTube. RTMP itself does not reliably carry these media-validation errors. */
    suspend fun streamHealth(streamId:String):YouTubeStreamHealth = withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        val stream=get("https://www.googleapis.com/youtube/v3/liveStreams?part=status&id=${streamId}",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error("YouTube can't find the bound live stream $streamId")
        val status=stream["status"]?.jsonObject ?: JsonObject(emptyMap())
        val health=status["healthStatus"]?.jsonObject
        val issues=health?.get("configurationIssues")?.jsonArray?.mapNotNull { element ->
            val x=element.jsonObject
            val type=x["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            YouTubeStreamIssue(
                type=type,
                severity=x["severity"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reason=x["reason"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                description=x["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
        YouTubeStreamHealth(
            streamStatus=status["streamStatus"]?.jsonPrimitive?.contentOrNull,
            healthStatus=health?.get("status")?.jsonPrimitive?.contentOrNull,
            lastUpdateTimeSeconds=health?.get("lastUpdateTimeSeconds")?.jsonPrimitive?.longOrNull,
            issues=issues
        )
    }

    /** streamStatus of one stream: "active" once YouTube is receiving data via the bound ingest stream. */
    suspend fun streamStatus(streamId:String):String? = streamHealth(streamId).streamStatus

    private suspend fun ingestHealthSummary(streamId:String):String =
        runCatching { streamHealth(streamId).summary() }.getOrElse { it.message ?: "health status unavailable" }
    /**
     * Takes a broadcast live (OBS's "Start broadcast"), separate from sending video: waits until YouTube receives the
     * stream, passes through "testing" when the broadcast has a preview (monitor) stream, then goes "live". A broadcast
     * that is already live counts as done.
     */
    suspend fun goLive(id:String){
        val (life,streamId)=broadcastState(id)
        if(life=="live")return
        if(life=="complete"||life=="revoked")error("this broadcast has ended; pick or schedule another one in Manage Broadcast")
        streamId?:error("no stream is attached to this broadcast yet: open it once in YouTube Studio")
        var receiving=false
        var lastSummary=""
        for(n in 0 until 30){
            val health=runCatching{streamHealth(streamId)}.getOrNull()
            if(health!=null){
                val summary=health.summary()
                if(summary!=lastSummary){Timber.d("YouTube ingest: %s",summary);lastSummary=summary}
                if(health.streamStatus.equals("active",true)){receiving=true;break}
            }
            kotlinx.coroutines.delay(1000)
        }
        check(receiving) {
            "YouTube isn't receiving your video yet. ${ingestHealthSummary(streamId)}"
        }
        if(runCatching{transitionBroadcast(id,"live")}.isFailure){
            // A broadcast with a preview must be "testing" before it can go "live".
            if(broadcastState(id).first!="testing")runCatching{transitionBroadcast(id,"testing")}
            for(n in 0 until 30){if(broadcastState(id).first=="testing")break;kotlinx.coroutines.delay(1000)}
            transitionBroadcast(id,"live")
        }
        for(n in 0 until 30){if(broadcastState(id).first=="live")return;kotlinx.coroutines.delay(1000)}
        error("YouTube accepted Go Live but hasn't shown the broadcast as live yet; check YouTube Studio")
    }
    /**
     * The broadcast's latency (YouTube Studio's Stream latency): "normal" (about 20–60 s for 4K / HDR), "low" (about
     * 5–10 s) or "ultraLow" (2–5 s, up to 1080p). Only a broadcast that isn't live yet can change it. Updating
     * contentDetails replaces it, so the current values are read and sent back with the new latency.
     */
    suspend fun setLatency(id:String,preference:String){withContext(Dispatchers.IO){
        val token=accessTokenProvider()?:error("YouTube account is not connected")
        val b=get("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=contentDetails&id=$id",token)["items"]?.jsonArray?.firstOrNull()?.jsonObject?:error("YouTube can't find this broadcast any more")
        val details=b["contentDetails"]?.jsonObject?:JsonObject(emptyMap())
        val readOnly=setOf("boundStreamId","boundStreamLastUpdateTimeMs","enableLowLatency")
        val body=buildJsonObject{put("id",id);put("contentDetails",buildJsonObject{for((k,v) in details)if(k !in readOnly)put(k,v);put("latencyPreference",preference)})}
        post("https://www.googleapis.com/youtube/v3/liveBroadcasts?part=contentDetails",token,"PUT",body.toString())
    }}
    private fun get(url:String,token:String):JsonObject{val c=open(url,token,"GET");return Json.parseToJsonElement(c.inputStream.bufferedReader().use{it.readText()}).jsonObject.also{c.disconnect()}}
    private fun post(url:String,token:String,method:String,body:String):JsonObject{val c=open(url,token,method);c.doOutput=true;c.setRequestProperty("Content-Type","application/json");if(body.isNotEmpty())c.outputStream.use{it.write(body.toByteArray())};val text=(if(c.responseCode in 200..299)c.inputStream else c.errorStream).bufferedReader().use{it.readText()};val code=c.responseCode;c.disconnect();if(code !in 200..299)error("YouTube API $code: $text");return if(text.isBlank()) buildJsonObject{ } else Json.parseToJsonElement(text).jsonObject}
    private fun open(url:String,token:String,method:String):HttpURLConnection{val c=URL(url).openConnection() as HttpURLConnection;c.requestMethod=method;c.connectTimeout=15_000;c.readTimeout=20_000;c.setRequestProperty("Authorization","Bearer $token");c.setRequestProperty("Accept","application/json");return c}
}
