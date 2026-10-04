package com.stream4k60.app.youtube

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class YouTubeAccountManager : ViewModel(){
    // Starts from the process-wide session so a sign-in done earlier in this run still counts.
    private val _connected=MutableStateFlow(!YouTubeAuthSession.accessToken.isNullOrBlank());val connected:StateFlow<Boolean> = _connected.asStateFlow();private val _token=MutableStateFlow(YouTubeAuthSession.accessToken);val token:StateFlow<String?> = _token.asStateFlow()
    private val requested= listOf(Scope("https://www.googleapis.com/auth/youtube.force-ssl"))
    /** Authorizes [account] (chosen in Android's account picker), so the user decides which Google account is used. */
    suspend fun authorize(activity:Activity,account:Account?,onResolution:(IntentSenderRequest)->Unit,onDone:(Boolean,String?)->Unit){runCatching{setSignedOut(activity,false);val req=AuthorizationRequest.builder().setRequestedScopes(requested).apply{account?.let(::setAccount)}.build();val result=Identity.getAuthorizationClient(activity).authorize(req).await();if(result.hasResolution())onResolution(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build())else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}}.onFailure{onDone(false,explain(it))}}
    fun handleAuthorizationResult(activity:Activity,intent:Intent?,onDone:(Boolean,String?)->Unit){runCatching{val result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(intent);_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();onDone(_connected.value,null)}.onFailure{onDone(false,explain(it))}}
    /**
     * Google remembers an account that already granted access: this gets a fresh token without showing anything.
     * Returns false when the user has to sign in (first time, access revoked, or they pressed Disconnect).
     */
    suspend fun restore(activity:Activity):Boolean{
        if(isSignedOut(activity))return false
        return runCatching{
            val result=Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest.builder().setRequestedScopes(requested).build()).await()
            if(result.hasResolution())false else{_token.value=result.accessToken;YouTubeAuthSession.accessToken=result.accessToken;_connected.value=!result.accessToken.isNullOrBlank();_connected.value}
        }.getOrDefault(false)
    }
    /**
     * Really signs out: revokes this app's access at Google (so the next Connect asks again) and forgets the token.
     * The signed-out mark also stops [restore] from quietly reconnecting if the revoke could not reach Google.
     */
    suspend fun disconnect(context:Context){
        val token=_token.value
        _token.value=null;YouTubeAuthSession.accessToken=null;_connected.value=false
        setSignedOut(context,true)
        if(token.isNullOrBlank())return
        withContext(Dispatchers.IO){
            runCatching{
                val c=URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
                c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10_000;c.readTimeout=10_000
                c.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                c.outputStream.use{it.write("token=${URLEncoder.encode(token,"UTF-8")}".toByteArray())}
                c.responseCode;c.disconnect()
            }
            runCatching{GoogleAuthUtil.clearToken(context,token)}
        }
    }
    fun service()=YouTubeService{suspendToken()}
    private suspend fun suspendToken()=_token.value
    private fun prefs(context:Context)=context.getSharedPreferences("youtube_account",Context.MODE_PRIVATE)
    private fun isSignedOut(context:Context)=prefs(context).getBoolean("signed_out",false)
    private fun setSignedOut(context:Context,value:Boolean)=prefs(context).edit().putBoolean("signed_out",value).apply()
    /** Google's errors are codes; say what to do instead. */
    private fun explain(t:Throwable):String{
        val m=t.message.orEmpty()
        return when{
            m.contains("UNREGISTERED_ON_API_CONSOLE",true)||m.startsWith("10:")->
                "This app isn't registered with Google yet. In Google Cloud Console create an Android OAuth client for package com.stream4k60.app with this APK's SHA-1, enable YouTube Data API v3, and add your Google account as a test user."
            m.contains("CANCELED",true)||m.startsWith("16:")->"Sign-in was cancelled."
            m.startsWith("7:")->"No internet connection."
            else->m.ifBlank{"YouTube sign-in failed."}
        }
    }
}
