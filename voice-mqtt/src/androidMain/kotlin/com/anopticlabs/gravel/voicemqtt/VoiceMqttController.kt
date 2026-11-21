package com.anopticlabs.gravel.voicemqtt

import android.content.Context
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.AppVisibility
import com.anopticlabs.gravel.notebook.pipeline.NoteReplySink
import com.anopticlabs.gravel.notebook.pipeline.noteReplySinkOrNull
import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureStore
import com.anopticlabs.gravel.voicemqtt.capture.VoiceCapturedMessage
import com.anopticlabs.gravel.voicemqtt.client.ConnectionPhase
import com.anopticlabs.gravel.voicemqtt.client.ConnectionState
import com.anopticlabs.gravel.voicemqtt.client.PahoVoiceTransport
import com.anopticlabs.gravel.voicemqtt.client.VoiceTurnClient
import com.anopticlabs.gravel.voicemqtt.copy.AndroidVoiceText
import com.anopticlabs.gravel.voicemqtt.copy.VoiceText
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettingsStore
import com.anopticlabs.gravel.voicemqtt.speech.AndroidSpeakingNotice
import com.anopticlabs.gravel.voicemqtt.speech.AndroidSpeechOutput
import com.anopticlabs.gravel.voicemqtt.speech.BluetoothAudioGate
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFileHistory
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFileRun
import com.anopticlabs.gravel.voicemqtt.speech.VoiceSpeechHistory
import com.anopticlabs.gravel.voicemqtt.speech.VoiceSpeechRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** The phone's own copy, so a sentence the loop says follows the phone's language. */
    private val copy: VoiceText,
    /** The record the settings screen reads for "reply filed", or the reason it was not. */
    val replyFiles: VoiceReplyFileHistory = VoiceReplyFileHistory(now = now),
    /**
     * The inventory every message on the reply filter lands in — matched, unsolicited, unreadable and duplicate
     * alike. In memory, bounded, newest first; see [VoiceCaptureStore].
     */
    private val captureStore: VoiceCaptureStore = VoiceCaptureStore(),
    /**
     * The notebook's reply sink, looked up when an answer arrives: the app binds it only with
     * `VOICE_MQTT_ENABLED` on, and with the flag off nothing is filed because there is nothing to file into.
     */
    private val noteReplySink: () -> NoteReplySink? = ::noteReplySinkOrNull,
    /**
     * Called for every turn that goes out and every message that comes back: that is what the speaking notice's
     * warm window is measured from (see [com.anopticlabs.gravel.voicemqtt.speech.SpeakingWarmTracker]), so a
     * reply that lands minutes after the phone was pocketed still finds the service up.
     */
    private val onVoiceActivity: () -> Unit = {},
) {
    val settings: StateFlow<VoiceSettings> = store.settings
    val connection: StateFlow<ConnectionState> = transport.state

    /** Every message seen on the reply filter, newest first — what the Voice Assistant page lists. */
    val captures: StateFlow<List<VoiceCapturedMessage>> = captureStore.messages

    /**
     * `requestId` → the note the capture settled as, and any answer already waiting for one. In memory only, and
     * deliberately not persisted: it exists so the answer that comes back can be appended into that exact note.
     * See [VoiceReplyFiler] for what happens when the answer arrives before its note does.
     *
     * Declared before [client] on purpose: the client is handed [VoiceReplyFiler.answer] as its reply callback.
     */
    private val filer = VoiceReplyFiler(
        scope = scope,
        sink = noteReplySink,
        history = replyFiles,
        now = now,
    )

    private val client = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { store.settings.value },
        session = VoiceSessionManager(),
        replies = VoiceReplyHandler(now),
        scope = scope,
        now = now,
        captures = captureStore,
        copy = copy,
        onAnswer = filer::answer,
    )

    private val reconnect = Mutex()

    init {
        transport.setIncoming { topic, payload ->
            // A reply is only ever spoken, never echoed back as the transcript: the client answers with the
            // server's text and nothing else. The topic rides along so the capture list can name it.
            // Anything arriving refreshes the notice's warm window: a reply means the conversation is live.
            onVoiceActivity()
            scope.launch { client.onReplyMessage(topic, payload) }
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

    /**
     * Stores a change that only affects speaking — the engine, the voice, offline speech. The broker
     * connection is left alone: none of those values is used by the transport, so reconnecting for them would
     * drop a live session for nothing.
     */
    fun saveSpeaking(next: VoiceSettings): VoiceSettings = store.update { next }

    /**
     * Stores a change that does not affect the broker connection — "Disable app reminders", "Keep the assistant
     * ready in the background". The transport never reads either, and the Notebook and the speaking notice read
     * the stored value on their next decision, so the switch takes effect at once and nothing reconnects.
     */
    fun saveWithoutReconnect(next: VoiceSettings): VoiceSettings = store.update { next }

    /** A transcribed turn; the requestId it was published under, or null when it was not sent. */
    suspend fun publishTranscript(text: String): String? {
        val current = store.settings.value
        if (!current.enabled || !current.valid) {
            logger.i { "transcript not published: the voice loop is off or its settings are incomplete" }
            return null
        }
        connectIfNeeded(current)
        val requestId = client.publishTranscript(text)
        // A turn went out: the answer is what the notice's warm window is counting down to.
        if (requestId != null) onVoiceActivity()
        return requestId
    }

    /** The settings screen's test button: publish a fixed line and speak whatever comes back. */
    suspend fun sendTestMessage(): String? {
        val current = store.settings.value
        if (!current.valid) {
            logger.i { "test message not published: the settings are incomplete" }
            return null
        }
        connectIfNeeded(current)
        val requestId = client.sendTestMessage()
        if (requestId != null) onVoiceActivity()
        return requestId
    }

    /** Holds the note a published turn settled as, so the answer that comes back can be written into it. */
    fun rememberNote(requestId: String, noteId: String, title: String) =
        filer.rememberNote(requestId, noteId, title)

    fun noteLink(requestId: String): VoiceNoteLink? = filer.noteLink(requestId)

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
 * [speechRuns] is the speaker's own record of what it did with each sentence, so a reply that was held back is
 * a visible fact on the settings screen instead of a silence. [speech] is the speaker itself, so Settings >
 * AI > Text to Speech can list the engines and voices and apply a change without an app restart.
 */
class VoiceMqttEngine(
    val controller: VoiceMqttController,
    val gate: BluetoothAudioGate,
    val speech: AndroidSpeechOutput,
    val speechRuns: StateFlow<List<VoiceSpeechRun>>,
    /** The loop's record of filing answers into notes: filed, or why not, newest first. */
    val replyFiles: StateFlow<List<VoiceReplyFileRun>>,
    /** Everything that arrived on the reply filter, newest first, spoken or not. */
    val captures: StateFlow<List<VoiceCapturedMessage>>,
)

fun buildVoiceMqttEngine(
    context: Context,
    micBusy: () -> Boolean,
    /**
     * The app's own "an activity is resumed" signal. It is what lets the speaking notice's service be started
     * while the start is permitted, and kept warm afterwards; see [com.anopticlabs.gravel.voicemqtt.speech.SpeakingWarmTracker].
     */
    visibility: AppVisibility,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): VoiceMqttEngine {
    val store = VoiceSettingsStore(context)
    val gate = BluetoothAudioGate(context)
    val text = AndroidVoiceText(context)
    val stateFlow = MutableStateFlow(ConnectionState())
    val transport = PahoVoiceTransport(clientId = store.clientId, stateFlow = stateFlow, text = text)
    val speechHistory = VoiceSpeechHistory(now = System::currentTimeMillis)
    // One notice for the whole app: the ready face while the service is warm, the speaking face while a reply
    // plays, and the same notification id for both.
    val notice = AndroidSpeakingNotice(context, visibility, store.settings, scope)
    val speaker = AndroidSpeechOutput(
        context = context,
        settings = { store.settings.value },
        gate = gate,
        micBusy = micBusy,
        history = speechHistory,
        scope = scope,
        text = text,
        notice = notice,
    )
    val controller = VoiceMqttController(
        store = store,
        transport = transport,
        speaker = speaker,
        scope = scope,
        copy = text,
        onVoiceActivity = notice::activity,
    )
    return VoiceMqttEngine(
        controller = controller,
        gate = gate,
        speech = speaker,
        speechRuns = speaker.runs,
        replyFiles = controller.replyFiles.runs,
        captures = controller.captures,
    )
}
