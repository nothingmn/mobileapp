package com.anopticlabs.gravel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.ui.TopBarParams

/** The pickers' "let the phone choose" choices. */
private const val PHONE_DEFAULT_ENGINE = "The phone's default engine"
private const val BEST_VOICE = "Best voice for the phone's language"

/**
 * Settings > AI > Text to Speech: which engine speaks the replies, which voice inside it, and whether the
 * replies may leave the phone at all.
 *
 * The screen is built from [TextToSpeechFacts] only — no engine, no Android type — so the same layout works
 * wherever the settings are drawn, and the honesty it carries is testable as plain values: the "Offline
 * speech" switch says what it does, the cloud sentence appears in full whenever the switch is off, and a phone
 * with no usable voice says so instead of going quiet.
 */
@Composable
fun TextToSpeechScreen(
    topBarParams: TopBarParams,
    facts: TextToSpeechFacts,
    onEngine: (String?) -> Unit,
    onVoice: (String?) -> Unit,
    onOfflineOnly: (Boolean) -> Unit,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Text to Speech")
        topBarParams.actions { }
    }
    LaunchedEffect(facts.read) { if (!facts.read) onRefresh() }

    var enginePickerOpen by remember { mutableStateOf(false) }
    var voicePickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Speaking", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(OFFLINE_SPEECH_TITLE)
                Text(OFFLINE_SPEECH_SUBTITLE, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = facts.offlineOnly, onCheckedChange = onOfflineOnly)
        }
        cloudSpeechNote(facts.offlineOnly)?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Text("Engine", style = MaterialTheme.typography.titleMedium)
        Text(
            "Installed engines on this phone. Changing one takes effect without restarting the app.",
            style = MaterialTheme.typography.bodySmall,
        )
        Column {
            TextButton(
                onClick = { enginePickerOpen = true },
                enabled = facts.engines.isNotEmpty(),
            ) {
                Text(facts.engineLabel.takeIf { it.isNotBlank() } ?: PHONE_DEFAULT_ENGINE)
            }
            DropdownMenu(expanded = enginePickerOpen, onDismissRequest = { enginePickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(PHONE_DEFAULT_ENGINE) },
                    onClick = {
                        enginePickerOpen = false
                        onEngine(null)
                    },
                )
                facts.engines.forEach { engine ->
                    DropdownMenuItem(
                        text = { Text(if (engine.isSystemDefault) "${engine.label} (phone default)" else engine.label) },
                        onClick = {
                            enginePickerOpen = false
                            onEngine(engine.name)
                        },
                    )
                }
            }
            if (facts.read && facts.engines.isEmpty()) {
                Text(
                    "No text-to-speech engine is installed on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text("Voice", style = MaterialTheme.typography.titleMedium)
        Column {
            TextButton(
                onClick = { voicePickerOpen = true },
                enabled = facts.selectableVoices.isNotEmpty(),
            ) {
                Text(facts.voice ?: BEST_VOICE)
            }
            DropdownMenu(expanded = voicePickerOpen, onDismissRequest = { voicePickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(BEST_VOICE) },
                    onClick = {
                        voicePickerOpen = false
                        onVoice(null)
                    },
                )
                facts.selectableVoices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(if (voice.offline) "${voice.label} — on this phone" else "${voice.label} — cloud")
                        },
                        onClick = {
                            voicePickerOpen = false
                            onVoice(voice.name)
                        },
                    )
                }
            }
        }

        speechInUseLine(facts)?.let { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
        facts.problem?.let { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        pinnedVoiceNote(facts)?.let { note ->
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
