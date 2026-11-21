package com.anopticlabs.gravel.voicemqtt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.pipeline.NoteReplyResult
import com.anopticlabs.gravel.voicemqtt.R
import com.anopticlabs.gravel.voicemqtt.client.ConnectionPhase
import com.anopticlabs.gravel.voicemqtt.client.ConnectionState
import com.anopticlabs.gravel.voicemqtt.copy.AndroidVoiceText
import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText
import com.anopticlabs.gravel.voicemqtt.settings.PreferredDevice
import com.anopticlabs.gravel.voicemqtt.settings.VoiceConnectionDefaults
import com.anopticlabs.gravel.voicemqtt.settings.VoiceField
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.BluetoothAudioGate
import com.anopticlabs.gravel.voicemqtt.speech.PlaybackPolicy
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFileRun
import com.anopticlabs.gravel.voicemqtt.speech.VoiceSpeechRun

/**
 * Settings > Voice Assistant: everything the client needs at connect time, all of it editable.
 *
 * A wrong broker is diagnosable from here — the state line names the address it tried and the failure's own
 * reason — and saving reconnects with the new values immediately. Field errors are inline, checked before the
 * save button is offered, so a typo in a topic never becomes a client that silently receives nothing.
 *
 * Every word on this screen comes from the feature's string resources, in the phone's language: the labels
 * through `stringResource`, and the sentences assembled from a rule (a refusal's reason, what became of the last
 * answer) through the same resources via [AndroidVoiceText], which is also what the speaker uses.
 */
@Composable
fun VoiceMqttSettingsContent(
    settings: VoiceSettings,
    connection: ConnectionState,
    gate: BluetoothAudioGate,
    onSave: (VoiceSettings) -> Unit,
    onSendTest: () -> Unit,
    /**
     * Stores a change that only affects what the Notebook does with a recording ("Disable app reminders"), so the
     * switch takes effect at once and the broker connection is left alone. Defaults to [onSave] for a caller that
     * has only the one save path.
     */
    onSaveCaptureBehaviour: (VoiceSettings) -> Unit = onSave,
    speechRuns: List<VoiceSpeechRun> = emptyList(),
    replyFiles: List<VoiceReplyFileRun> = emptyList(),
) {
    val context = LocalContext.current
    val text: VoiceText = remember(context) { AndroidVoiceText(context) }
    var draft by remember(settings) { mutableStateOf(settings) }
    // A port of zero means "none": the field starts empty rather than showing a number the build never supplied.
    var portText by remember(settings) {
        mutableStateOf(settings.port.takeIf { it != VoiceConnectionDefaults.UNSET_PORT }?.toString().orEmpty())
    }
    var showPassword by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf<String?>(null) }
    val paired = remember(gate) { gate.pairedDevices() }
    val canListDevices = remember(gate) { gate.canListDevices() }
    val errors = draft.errors()
    val anyDevice = stringResource(R.string.voice_picker_any_device)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.voice_section_connection), style = MaterialTheme.typography.titleMedium)
        Text(
            when (connection.phase) {
                ConnectionPhase.CONNECTED ->
                    stringResource(R.string.voice_status_connected, settings.serverUri)
                ConnectionPhase.CONNECTING ->
                    stringResource(R.string.voice_status_connecting, settings.serverUri)
                ConnectionPhase.DISCONNECTED -> stringResource(R.string.voice_status_disconnected)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val lastError = connection.lastError
        if (lastError != null && connection.phase != ConnectionPhase.CONNECTED) {
            Text(lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // Nothing is baked into this build by default, so an empty address is a state the screen states plainly:
        // it is what has to be filled in before the loop can be turned on, not a value the app will pick instead.
        if (draft.host.isBlank()) {
            Text(
                stringResource(R.string.voice_hint_connection_unset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_toggle_loop_title))
                Text(
                    stringResource(R.string.voice_toggle_loop_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Turning the loop on needs a broker the app can actually reach; turning it off is always allowed,
            // so an address cleared while it was on can never strand the switch.
            Switch(
                checked = draft.enabled,
                enabled = draft.enabled || errors.isEmpty(),
                onCheckedChange = { onSave(draft.copy(enabled = it)) },
            )
        }

        OutlinedTextField(
            value = draft.host,
            onValueChange = { draft = draft.copy(host = it) },
            label = { Text(stringResource(R.string.voice_field_broker_address)) },
            singleLine = true,
            isError = errors.containsKey(VoiceField.HOST),
            supportingText = { errors[VoiceField.HOST]?.let { Text(text.of(it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { typed ->
                portText = typed.filter { it.isDigit() }.take(5)
                draft = draft.copy(port = portText.toIntOrNull() ?: VoiceConnectionDefaults.UNSET_PORT)
            },
            label = { Text(stringResource(R.string.voice_field_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errors.containsKey(VoiceField.PORT),
            supportingText = { errors[VoiceField.PORT]?.let { Text(text.of(it)) } },
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = stringResource(R.string.voice_toggle_tls_title),
            description = stringResource(R.string.voice_toggle_tls_description),
            checked = draft.tls,
            onCheckedChange = { draft = draft.copy(tls = it) },
        )
        if (draft.tls) {
            ToggleRow(
                title = stringResource(R.string.voice_toggle_self_signed_title),
                description = stringResource(R.string.voice_toggle_self_signed_description),
                checked = draft.trustSelfSigned,
                onCheckedChange = { draft = draft.copy(trustSelfSigned = it) },
            )
        }

        Text(stringResource(R.string.voice_section_credentials), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.username,
            onValueChange = { draft = draft.copy(username = it) },
            label = { Text(stringResource(R.string.voice_field_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.password,
            onValueChange = { draft = draft.copy(password = it) },
            label = { Text(stringResource(R.string.voice_field_password)) },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(
                        if (showPassword) {
                            stringResource(R.string.voice_button_hide)
                        } else {
                            stringResource(R.string.voice_button_show)
                        },
                    )
                }
            },
            supportingText = { Text(stringResource(R.string.voice_field_password_note)) },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.voice_section_topics), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.ingressTopic,
            onValueChange = { draft = draft.copy(ingressTopic = it) },
            label = { Text(stringResource(R.string.voice_field_ingress_topic)) },
            singleLine = true,
            isError = errors.containsKey(VoiceField.INGRESS_TOPIC),
            supportingText = {
                Text(
                    errors[VoiceField.INGRESS_TOPIC]?.let { text.of(it) }
                        ?: stringResource(R.string.voice_hint_ingress_topic),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.egressTopic,
            onValueChange = { draft = draft.copy(egressTopic = it) },
            label = { Text(stringResource(R.string.voice_field_egress_topic)) },
            singleLine = true,
            isError = errors.containsKey(VoiceField.EGRESS_TOPIC),
            supportingText = {
                Text(
                    errors[VoiceField.EGRESS_TOPIC]?.let { text.of(it) }
                        ?: stringResource(R.string.voice_hint_egress_topic),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text(stringResource(R.string.voice_section_speaking), style = MaterialTheme.typography.titleMedium)
        ToggleRow(
            title = stringResource(R.string.voice_toggle_speak_replies_title),
            description = stringResource(R.string.voice_toggle_speak_replies_description),
            checked = draft.speakReplies,
            onCheckedChange = { draft = draft.copy(speakReplies = it) },
        )
        ToggleRow(
            title = stringResource(R.string.voice_toggle_headset_only_title),
            description = stringResource(R.string.voice_toggle_headset_only_description),
            checked = draft.headsetOnly,
            onCheckedChange = { draft = draft.copy(headsetOnly = it) },
        )
        Column {
            TextButton(
                onClick = { pickerOpen = true },
                enabled = canListDevices && draft.headsetOnly,
            ) {
                Text(stringResource(R.string.voice_picker_device_line, draft.preferredDevice?.name ?: anyDevice))
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(anyDevice) },
                    onClick = {
                        draft = draft.copy(preferredDevice = null)
                        pickerOpen = false
                    },
                )
                paired.forEach { device ->
                    DropdownMenuItem(
                        text = { Text(device.name) },
                        onClick = {
                            draft = draft.copy(preferredDevice = PreferredDevice(device.address, device.name))
                            pickerOpen = false
                        },
                    )
                }
            }
            if (!draft.headsetOnly) {
                Text(
                    stringResource(R.string.voice_hint_device_headset_only),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!canListDevices) {
                Text(
                    stringResource(R.string.voice_hint_devices_permission),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // The speaker's own record, so a reply that was held back is a fact on this screen and not a silence.
        speechRuns.firstOrNull()?.suppression?.let { reason ->
            Text(
                stringResource(
                    R.string.voice_speech_last_not_played,
                    text.of(PlaybackPolicy.explanation(reason)),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // And where the last answer went: into the note its recording produced, or why it did not get there.
        replyFiles.firstOrNull()?.let { run ->
            Text(filingSentence(run, text), style = MaterialTheme.typography.bodySmall)
        }

        // What a recording becomes. Kept apart from Speaking because it is not about the reply: it is the one
        // switch here that changes what the Notebook stores, and it takes effect the moment it is flipped.
        Text(stringResource(R.string.voice_section_recordings), style = MaterialTheme.typography.titleMedium)
        ToggleRow(
            title = stringResource(R.string.voice_toggle_disable_app_reminders_title),
            description = stringResource(R.string.voice_toggle_disable_app_reminders_description),
            checked = draft.disableAppReminders,
            onCheckedChange = { onSaveCaptureBehaviour(draft.copy(disableAppReminders = it)) },
        )

        Button(
            onClick = { onSave(draft) },
            enabled = errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.voice_button_save_and_reconnect)) }

        Button(
            onClick = {
                onSave(draft)
                onSendTest()
                lastAction = text.of(VoiceCopy(VoiceKeys.ACTION_TEST_SENT))
            },
            enabled = errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.voice_button_send_test)) }

        lastAction?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** What became of the last answer: filed into the note its recording produced, or the reason it was not. */
private fun filingSentence(run: VoiceReplyFileRun, text: VoiceText): String = text.of(
    when (run.outcome) {
        NoteReplyResult.Appended -> VoiceCopy(VoiceKeys.FILING_APPENDED, run.title)
        NoteReplyResult.NotListed -> VoiceCopy(VoiceKeys.FILING_NOT_LISTED)
        NoteReplyResult.Archived -> VoiceCopy(VoiceKeys.FILING_ARCHIVED)
        NoteReplyResult.ChangedAfterRetry -> VoiceCopy(VoiceKeys.FILING_CHANGED_AFTER_RETRY)
        NoteReplyResult.EmptyAnswer -> VoiceCopy(VoiceKeys.FILING_EMPTY_ANSWER)
        NoteReplyResult.StorageUnavailable -> VoiceCopy(VoiceKeys.FILING_STORAGE_UNAVAILABLE)
    },
)

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
