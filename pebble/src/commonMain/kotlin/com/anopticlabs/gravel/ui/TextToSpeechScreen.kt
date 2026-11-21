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

/**
 * Settings > AI > Text to Speech: which engine speaks the replies, which voice inside it, and whether the
 * replies may leave the phone at all.
 *
 * The screen is built from [TextToSpeechFacts] only — no engine, no Android type — so the same layout works
 * wherever the settings are drawn, and the honesty it carries is testable as plain values: the "Offline
 * speech" switch says what it does, the cloud sentence appears in full whenever the switch is off, and a phone
 * with no usable voice says so instead of going quiet.
 *
 * Every word comes from this feature's own Compose resources (see [TextToSpeechKeys]), in the phone's language;
 * the rest of this app's settings screens are English-only and deliberately out of scope.
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
    val title = stringResourceFor(TextToSpeechKeys.TTS_TITLE)
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title(title)
        topBarParams.actions { }
    }
    LaunchedEffect(facts.read) { if (!facts.read) onRefresh() }

    var enginePickerOpen by remember { mutableStateOf(false) }
    var voicePickerOpen by remember { mutableStateOf(false) }
    // The two pickers' "let the phone choose" choices.
    val phoneDefaultEngine = stringResourceFor(TextToSpeechKeys.ENGINE_DEFAULT)
    val bestVoice = stringResourceFor(TextToSpeechKeys.BEST_VOICE)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResourceFor(TextToSpeechKeys.SECTION_SPEAKING), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(OFFLINE_SPEECH_TITLE.text())
                Text(OFFLINE_SPEECH_SUBTITLE.text(), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = facts.offlineOnly, onCheckedChange = onOfflineOnly)
        }
        cloudSpeechNote(facts.offlineOnly)?.let { note ->
            Text(note.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Text(stringResourceFor(TextToSpeechKeys.SECTION_ENGINE), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResourceFor(TextToSpeechKeys.ENGINE_INTRO),
            style = MaterialTheme.typography.bodySmall,
        )
        Column {
            TextButton(
                onClick = { enginePickerOpen = true },
                enabled = facts.engines.isNotEmpty(),
            ) {
                Text(facts.engineLabel.takeIf { it.isNotBlank() } ?: phoneDefaultEngine)
            }
            DropdownMenu(expanded = enginePickerOpen, onDismissRequest = { enginePickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(phoneDefaultEngine) },
                    onClick = {
                        enginePickerOpen = false
                        onEngine(null)
                    },
                )
                facts.engines.forEach { engine ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (engine.isSystemDefault) {
                                    stringResourceFor(TextToSpeechKeys.ENGINE_DEFAULT_MARKED, engine.label)
                                } else {
                                    engine.label
                                },
                            )
                        },
                        onClick = {
                            enginePickerOpen = false
                            onEngine(engine.name)
                        },
                    )
                }
            }
            if (facts.read && facts.engines.isEmpty()) {
                Text(
                    stringResourceFor(TextToSpeechKeys.NO_ENGINE_INSTALLED),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(stringResourceFor(TextToSpeechKeys.SECTION_VOICE), style = MaterialTheme.typography.titleMedium)
        Column {
            TextButton(
                onClick = { voicePickerOpen = true },
                enabled = facts.selectableVoices.isNotEmpty(),
            ) {
                Text(facts.voice ?: bestVoice)
            }
            DropdownMenu(expanded = voicePickerOpen, onDismissRequest = { voicePickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(bestVoice) },
                    onClick = {
                        voicePickerOpen = false
                        onVoice(null)
                    },
                )
                facts.selectableVoices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (voice.offline) {
                                    stringResourceFor(TextToSpeechKeys.VOICE_ON_PHONE, voice.label)
                                } else {
                                    stringResourceFor(TextToSpeechKeys.VOICE_CLOUD, voice.label)
                                },
                            )
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
            Text(line.text(), style = MaterialTheme.typography.bodySmall)
        }
        facts.problem?.let { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        pinnedVoiceNote(facts)?.let { note ->
            Text(note.text(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
