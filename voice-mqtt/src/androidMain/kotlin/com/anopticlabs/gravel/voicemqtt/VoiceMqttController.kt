package com.anopticlabs.gravel.voicemqtt

import android.content.Context
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.client.ConnectionPhase
import com.anopticlabs.gravel.voicemqtt.client.ConnectionState
import com.anopticlabs.gravel.voicemqtt.client.PahoVoiceTransport
import com.anopticlabs.gravel.voicemqtt.client.VoiceTurnClient
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettingsStore
import com.anopticlabs.gravel.voicemqtt.speech.AndroidSpeechOutput
import com.anopticlabs.gravel.voicemqtt.speech.BluetoothAudioGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The link between a turn that was published and the note it settled as, kept in memory for the next
 * increment. Nothing reads it yet.
 */
data class VoiceNoteLink(val requestId: String, val noteId: String, val title: String)

/**
 * Owns the loop for the app's lifetime: the settings, the connection, the turn client and the speaker.
 *
 * A settings change reconnects with the new values on the spot, so nothing about the broker needs an app
 * restart. With the loop switched off the transport is closed rather than left idle, and a transcript is not
 * published at all.
 */
class VoiceMqttController(
    private val store: VoiceSettingsStore,
    private val transport: PahoVoiceTransport,
    speaker: AndroidSpeechOutput,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val settings: StateFlow<VoiceSettings> = store.settings
    val connection: StateFlow<ConnectionState> = transport.state

    private val client = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { store.settings.value },
        session = VoiceSessionManager(),
        replies = VoiceReplyHandler(now),
        scope = scope,
        now = now,
    )

    private val reconnect = Mutex()

    /**
     * requestId → the note the capture settled as. In memory only, and deliberately not persisted: it exists
     * so the next increment can append the reply into that exact note.
     */
    private val noteLinks = mutableMapOf<String, VoiceNoteLink>()

    init {
        transport.setIncoming { payload ->
            // A reply is only ever spoken, never echoed back as the transcript: the client answers with the
            // server's text and nothing else.
            scope.launch { client.onReplyMessage(payload) }
        }
    }

    /** Connects if the loop is enabled and the settings are usable; safe to call more than once. */
    fun start() {
        scope.launch { reconcile() }
    }

    /** Stores new settings and reconnects with them immediately. */
    fun save(next: VoiceSettings): VoiceSettings {
        val stored = store.update { next }
        scope.launch { reconcile() }
        return stored
    }

    /** A transcribed turn; the requestId it was published under, or null when it was not sent. */
    suspend fun publishTranscript(text: String): String? {
        val current = store.settings.value
        if (!current.enabled || !current.valid) {
            logger.i { "transcript not published: the voice loop is off or its settings are incomplete" }
            return null
        }
        connectIfNeeded(current)
        return client.publishTranscript(text)
    }

    /** The settings screen's test button: publish a fixed line and speak whatever comes back. */
    suspend fun sendTestMessage(): String? {
        val current = store.settings.value
        if (!current.valid) {
            logger.i { "test message not published: the settings are incomplete" }
            return null
        }
        connectIfNeeded(current)
        return client.sendTestMessage()
    }

    /** Holds the note a published turn settled as, so a later increment can write the reply into it. */
    fun rememberNote(requestId: String, noteId: String, title: String) {
        noteLinks[requestId] = VoiceNoteLink(requestId, noteId, title)
        // TODO(next increment): when the final reply for this requestId arrives, append its text to the note
        //  with this noteId (Notebook NoteStore) instead of only speaking it. The association is carried here
        //  already so that increment needs no change at the capture drain.
    }

    fun noteLink(requestId: String): VoiceNoteLink? = noteLinks[requestId]

    private suspend fun connectIfNeeded(current: VoiceSettings) {
        if (transport.state.value.phase == ConnectionPhase.CONNECTED) return
        if (!current.valid) return
        transport.connect(current)
    }

    private suspend fun reconcile() {
        reconnect.withLock {
            val current = store.settings.value
            if (!current.enabled || !current.valid) {
                if (transport.state.value.phase != ConnectionPhase.DISCONNECTED) transport.disconnect()
                return
            }
            transport.connect(current)
        }
    }

    private companion object {
        val logger = Logger.withTag("VoiceMqttController")
    }
}

/**
 * What the app builds once, when the VOICE_MQTT_ENABLED flag is on. [micBusy] is the app's own answer to
 * "is the microphone recording right now", which is what keeps a reply from bleeding into a recording.
 */
class VoiceMqttEngine(
    val controller: VoiceMqttController,
    val gate: BluetoothAudioGate,
)

fun buildVoiceMqttEngine(
    context: Context,
    micBusy: () -> Boolean,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): VoiceMqttEngine {
    val store = VoiceSettingsStore(context)
    val gate = BluetoothAudioGate(context)
    val stateFlow = MutableStateFlow(ConnectionState())
    val transport = PahoVoiceTransport(clientId = store.clientId, stateFlow = stateFlow)
    val speaker = AndroidSpeechOutput(
        context = context,
        settings = { store.settings.value },
        gate = gate,
        micBusy = micBusy,
        scope = scope,
    )
    return VoiceMqttEngine(
        controller = VoiceMqttController(store = store, transport = transport, speaker = speaker, scope = scope),
        gate = gate,
    )
}
