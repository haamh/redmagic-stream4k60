package com.stream4k60.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.stream4k60.app.ui.settings.components.SettingsSection
import com.stream4k60.app.ui.settings.components.SettingsToggle

@Composable
fun GeneralSettingsPage(viewModel: SettingsViewModel) {
    val s by viewModel.generalSettings.collectAsState()
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // Settings backups: saved whenever the app is closed or sent to the background (if anything changed), newest 10 kept.
        SettingsSection("Backups") {
            val backups by viewModel.backups.collectAsState()
            androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshBackups() }
            var confirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.stream4k60.app.data.backup.SettingsBackups.Backup?>(null) }
            val dateFormat = androidx.compose.runtime.remember { java.text.SimpleDateFormat("d MMM yyyy, HH:mm:ss", java.util.Locale.getDefault()) }
            androidx.compose.material3.Text(
                "Everything (scenes, sources, filters, profiles with video, output and stream settings, hotkeys, layout) is saved as you change it. A backup is also kept each time the app is closed or sent to the background, if something changed; the newest ${com.stream4k60.app.data.backup.SettingsBackups.KEEP} are kept. They stay private on this tablet because they include your stream key.",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            com.stream4k60.app.ui.settings.components.SettingsButton("Back up now", { viewModel.backupNow() })
            if (backups.isEmpty()) androidx.compose.material3.Text("No backups yet.", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            backups.forEach { b ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    androidx.compose.material3.Text("${dateFormat.format(java.util.Date(b.createdMs))} · ${(b.sizeBytes + 1023) / 1024} KB", modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { confirm = b }) { androidx.compose.material3.Text("Restore") }
                }
            }
            confirm?.let { b ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirm = null },
                    title = { androidx.compose.material3.Text("Restore this backup?") },
                    text = { androidx.compose.material3.Text("All settings, scenes and sources are replaced with the ones from ${dateFormat.format(java.util.Date(b.createdMs))}. The current state is backed up first, so you can go back to it. The app restarts.") },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = null; viewModel.restoreBackup(b) }) { androidx.compose.material3.Text("Restore") } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = null }) { androidx.compose.material3.Text("Cancel") } }
                )
            }
        }
        SettingsSection("Output") {
            SettingsToggle("Show confirmation dialog when stopping streams", s.confirmStopStreaming,
                { viewModel.saveGeneralSettings(s.copy(confirmStopStreaming = it)) })
        }
        SettingsSection("Snapping") {
            SettingsToggle("Enable source snapping", s.snappingEnabled,
                { viewModel.saveGeneralSettings(s.copy(snappingEnabled = it)) },
                description = "Dragged sources snap to the canvas edges and centre.")
            SettingsToggle("Snap sources to other sources", s.snapToSources,
                { viewModel.saveGeneralSettings(s.copy(snapToSources = it)) }, enabled = s.snappingEnabled)
        }
    }
}
