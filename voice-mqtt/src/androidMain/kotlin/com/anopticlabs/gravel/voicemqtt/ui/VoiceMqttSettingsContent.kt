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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.voicemqtt.client.ConnectionPhase
import com.anopticlabs.gravel.voicemqtt.client.ConnectionState
import com.anopticlabs.gravel.voicemqtt.settings.PreferredDevice
import com.anopticlabs.gravel.voicemqtt.settings.VoiceField
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.BluetoothAudioGate
import com.anopticlabs.gravel.voicemqtt.speech.PlaybackPolicy
import com.anopticlabs.gravel.voicemqtt.speech.VoiceSpeechRun

/** The picker's "no device in particular" choice. */
private const val ANY_DEVICE = "Any Bluetooth device"

/**
 * Settings > Voice Assistant: everything the client needs at connect time, all of it editable.
 *
 * A wrong broker is diagnosable from here — the state line names the address it tried and the failure's own
 * reason — and saving reconnects with the new values immediately. Field errors are inline, checked before the
 * save button is offered, so a typo in a topic never becomes a client that silently receives nothing.
 */
@Composable
fun VoiceMqttSettingsContent(
    settings: VoiceSettings,
    connection: ConnectionState,
    gate: BluetoothAudioGate,
    onSave: (VoiceSettings) -> Unit,
    onSendTest: () -> Unit,
    speechRuns: List<VoiceSpeechRun> = emptyList(),
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var portText by remember(settings) { mutableStateOf(settings.port.toString()) }
    var showPassword by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var lastAction by remember { mutableStateOf<String?>(null) }
    val paired = remember(gate) { gate.pairedDevices() }
    val canListDevices = remember(gate) { gate.canListDevices() }
    val errors = draft.errors()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.titleMedium)
        Text(
            when (connection.phase) {
                ConnectionPhase.CONNECTED -> "Connected to ${settings.serverUri}"
                ConnectionPhase.CONNECTING -> "Connecting to ${settings.serverUri}…"
                ConnectionPhase.DISCONNECTED -> "Not connected"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        val lastError = connection.lastError
        if (lastError != null && connection.phase != ConnectionPhase.CONNECTED) {
            Text(lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice assistant")
                Text(
                    "Publish every transcribed recording and speak the answer",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = draft.enabled, onCheckedChange = { onSave(draft.copy(enabled = it)) })
        }

        OutlinedTextField(
            value = draft.host,
            onValueChange = { draft = draft.copy(host = it) },
            label = { Text("Broker address") },
            singleLine = true,
            isError = errors.containsKey(VoiceField.HOST),
            supportingText = { errors[VoiceField.HOST]?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { text ->
                portText = text.filter { it.isDigit() }.take(5)
                draft = draft.copy(port = portText.toIntOrNull() ?: -1)
            },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = errors.containsKey(VoiceField.PORT),
            supportingText = { errors[VoiceField.PORT]?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        ToggleRow(
            title = "Use TLS",
            description = "Connect with ssl:// instead of tcp://",
            checked = draft.tls,
            onCheckedChange = { draft = draft.copy(tls = it) },
        )
        if (draft.tls) {
            ToggleRow(
                title = "Trust a self-signed certificate",
                description = "Off by default. On accepts any certificate the broker presents, so only use it for a broker you run yourself.",
                checked = draft.trustSelfSigned,
                onCheckedChange = { draft = draft.copy(trustSelfSigned = it) },
            )
        }

        Text("Credentials", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.username,
            onValueChange = { draft = draft.copy(username = it) },
            label = { Text("Username (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.password,
            onValueChange = { draft = draft.copy(password = it) },
            label = { Text("Password (optional)") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
            },
            supportingText = { Text("Kept encrypted in this phone's Keystore-backed storage.") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Topics", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = draft.ingressTopic,
            onValueChange = { draft = draft.copy(ingressTopic = it) },
            label = { Text("Request topic") },
            singleLine = true,
            isError = errors.containsKey(VoiceField.INGRESS_TOPIC),
            supportingText = {
                Text(errors[VoiceField.INGRESS_TOPIC] ?: "One exact topic; the server takes the client's identity from it.")
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.egressTopic,
            onValueChange = { draft = draft.copy(egressTopic = it) },
            label = { Text("Reply topic filter") },
            singleLine = true,
            isError = errors.containsKey(VoiceField.EGRESS_TOPIC),
            supportingText = {
                Text(errors[VoiceField.EGRESS_TOPIC] ?: "A wildcard is expected here: the reply topic's last part is the server's.")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Speaking", style = MaterialTheme.typography.titleMedium)
        ToggleRow(
            title = "Speak the replies",
            description = "Off means nothing is played aloud. The answer still arrives and is still kept with the note.",
            checked = draft.speakReplies,
            onCheckedChange = { draft = draft.copy(speakReplies = it) },
        )
        ToggleRow(
            title = "Only play through a Bluetooth headset",
            description = "On means a headset must be connected at the moment the answer arrives. Off plays through " +
                "whatever the phone is using, its own speaker included.",
            checked = draft.headsetOnly,
            onCheckedChange = { draft = draft.copy(headsetOnly = it) },
        )
        Column {
            TextButton(
                onClick = { pickerOpen = true },
                enabled = canListDevices && draft.headsetOnly,
            ) {
                Text("Replies play on: ${draft.preferredDevice?.name ?: ANY_DEVICE}")
            }
            DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                DropdownMenuItem(
                    text = { Text(ANY_DEVICE) },
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
                    "Only used when replies are restricted to Bluetooth.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!canListDevices) {
                Text(
                    "Allow the Nearby devices permission to choose one paired headset.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        // The speaker's own record, so a reply that was held back is a fact on this screen and not a silence.
        speechRuns.firstOrNull()?.suppression?.let { reason ->
            Text(
                "Last reply was not played: ${PlaybackPolicy.explanation(reason)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { onSave(draft) },
            enabled = errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save and reconnect") }

        Button(
            onClick = {
                onSave(draft)
                onSendTest()
                lastAction = "Test message sent; the answer is spoken when it arrives."
            },
            enabled = errors.isEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send test message") }

        lastAction?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

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
