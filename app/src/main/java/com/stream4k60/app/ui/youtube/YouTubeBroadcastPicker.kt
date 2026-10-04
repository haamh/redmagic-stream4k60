package com.stream4k60.app.ui.youtube

import com.stream4k60.app.ui.common.ClosableTitle

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.stream4k60.app.data.model.*
import com.stream4k60.app.youtube.*

/** OBS's "Manage Broadcast": sign in once, pick a scheduled YouTube broadcast, and its server and key are filled in. */
@Composable
fun YouTubeBroadcastPicker(onSelected: (StreamConfig, String) -> Unit, onDismiss: () -> Unit, vm: YouTubeBroadcastPickerViewModel = hiltViewModel()) {
    val ctx = LocalContext.current as Activity
    val connected by vm.connected.collectAsState()
    val broadcasts by vm.broadcasts.collectAsState()
    val loading by vm.loading.collectAsState()
    val loadError by vm.loadError.collectAsState()
    val video by vm.videoConfig.collectAsState()
    val settingsLoaded by vm.settingsLoaded.collectAsState()
    val channel by vm.channel.collectAsState()
    val channelChecked by vm.channelChecked.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.handleResult(ctx, r.data) { ok, msg -> error = msg; if (ok) vm.load() }
    }
    // Connect always shows Android's account chooser, so a different Google account can be picked each time.
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val name = r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
        val type = r.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_TYPE) ?: "com.google"
        if (name != null) vm.authorize(ctx, android.accounts.Account(name, type), { launcher.launch(it) }) { ok, msg -> error = msg; if (ok) vm.load() }
    }
    LaunchedEffect(Unit) { vm.restoreAndLoad(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ClosableTitle("Manage Broadcast", onDismiss) },
        text = {
            Column {
                if (!connected) {
                    Text("Connect your YouTube account to stream to a scheduled broadcast without copying a stream key.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        error = null
                        @Suppress("DEPRECATION") // this overload is the one that always shows the chooser
                        chooser.launch(android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), true, null, null, null, null))
                    }) {
                        Text("Connect Google / YouTube")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                channel != null -> "✓ Connected to the YouTube channel “$channel”"
                                channelChecked -> "✓ Signed in, but this Google account has no YouTube channel of its own"
                                else -> "✓ YouTube account connected"
                            },
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { vm.load() }, enabled = !loading) { Text("Refresh") }
                        TextButton(onClick = { vm.disconnect(ctx) }) { Text("Disconnect") }
                    }
                    Text(
                        if (settingsLoaded) "Output: ${video.outputResWidth} × ${video.outputResHeight} at ${video.frameRate} FPS" else "Loading video settings…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(6.dp))
                    when {
                        loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Loading broadcasts…")
                        }
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        broadcasts.isEmpty() -> Text(
                            "No upcoming broadcasts on ${channel?.let { "“$it”" } ?: "this channel"}. Schedule one in YouTube Studio (Create → Go live → Schedule stream), then tap Refresh.\n\n" +
                                "If you scheduled it on another channel of the same Gmail (a brand account), this sign-in doesn't reach it: Disconnect and connect again choosing that channel if Google offers it, " +
                                "or copy that channel's stream URL and key from YouTube Studio into Custom RTMP."
                        )
                        else -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(broadcasts) { b ->
                                val hasKey = !b.streamName.isNullOrBlank()
                                ListItem(
                                    headlineContent = { Text(b.title.ifBlank { "Untitled broadcast" }) },
                                    supportingContent = {
                                        Column {
                                            Text(listOfNotNull(
                                                b.lifeCycle?.replaceFirstChar { it.uppercase() },
                                                b.scheduledStart?.take(16)?.replace('T', ' '),
                                                if (hasKey) null else "no stream key attached yet: open it in YouTube Studio once"
                                            ).joinToString(" · "))
                                            // YouTube's Normal latency on a 4K / HDR stream is 30 s to a minute behind.
                                            if (b.lifeCycle != "live") Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("Latency:", style = MaterialTheme.typography.bodySmall)
                                                listOf("normal" to "Normal", "low" to "Low (≈5–10 s)", "ultraLow" to "Ultra-low (≈2–5 s, ≤1080p)").forEach { (key, label) ->
                                                    FilterChip(selected = (b.latency ?: "normal") == key, onClick = { vm.setLatency(b, key) { error = it } }, label = { Text(label, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.padding(start = 4.dp))
                                                }
                                            } else Text("Latency: ${b.latency ?: "normal"} (can't change while live)", style = MaterialTheme.typography.bodySmall)
                                        }
                                    },
                                    trailingContent = {
                                        Button(enabled = settingsLoaded && hasKey, onClick = {
                                            runCatching { vm.toConfig(b) }.onSuccess { onSelected(it, b.title) }.onFailure { error = it.message }
                                        }) { Text("Use") }
                                    }
                                )
                            }
                        }
                    }
                }
                error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
