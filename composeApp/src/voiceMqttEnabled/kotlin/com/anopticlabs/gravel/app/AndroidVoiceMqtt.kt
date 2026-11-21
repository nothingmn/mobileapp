package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.anopticlabs.gravel.notebook.pipeline.AppReminderGate
import com.anopticlabs.gravel.notebook.pipeline.NoteReplySink
import com.anopticlabs.gravel.notebook.pipeline.NoteStoreReplyNotes
import com.anopticlabs.gravel.notebook.pipeline.NoteStoreReplySink
import com.anopticlabs.gravel.notebook.pipeline.VoiceTurnSink
import com.anopticlabs.gravel.notebook.sources.PhoneRecorder
import com.anopticlabs.gravel.notebook.sources.RecorderState
import com.anopticlabs.gravel.ui.SpeechEngine
import com.anopticlabs.gravel.ui.SpeechVoice
import com.anopticlabs.gravel.ui.TextToSpeechFacts
import com.anopticlabs.gravel.ui.TextToSpeechScreen
import com.anopticlabs.gravel.ui.TextToSpeechSettings
import com.anopticlabs.gravel.ui.VoiceMqttSettings
import com.anopticlabs.gravel.voicemqtt.VoiceMqttBuildKonfig
import com.anopticlabs.gravel.voicemqtt.VoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.buildVoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.ui.VoiceMqttSettingsContent
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Gravel: binds the Agentry voice loop into the app, and binds nothing at all when VOICE_MQTT_ENABLED is off.
 *
 * This file is the only place the app graph touches the :voice-mqtt module. The interfaces it implements live in
 * :pebble (common code, no iOS problem) and :notebook (the capture drain's seam), so neither of those modules
 * depends on the MQTT client or on the Android speech engine, and a build with the flag off carries no reachable
 * path into either.
 */
val voiceMqttModule: Module = module {
    if (VoiceMqttBuildKonfig.VOICE_MQTT_ENABLED) {
        single {
            buildVoiceMqttEngine(
                androidContext(),
                // The phone is recording: a reply waits rather than bleeding into the microphone.
                micBusy = { get<PhoneRecorder>().state.value is RecorderState.Recording },
            )
        }
        single<VoiceTurnSink> { AndroidVoiceTurnSink(get<VoiceMqttEngine>().controller) }
        // The answer is filed into the note the recording produced, through the notebook's own store. Bound
        // only here and only with the flag on, which is what leaves the append out of a build without the loop.
        single<NoteReplySink> { NoteStoreReplySink(NoteStoreReplyNotes(get())) }
        // "Disable app reminders": the Notebook asks this at the moment a capture is published, so with the flag
        // off nothing is bound and the answer is "reminders are allowed" — today's behaviour.
        single<AppReminderGate> { AndroidAppReminderGate(get<VoiceMqttEngine>().controller) }
        single<VoiceMqttSettings> { AndroidVoiceMqttSettings(get()) }
        single<TextToSpeechSettings> { AndroidTextToSpeechSettings(get()) }
    }
}

/** The drain's seam: every transcribed capture goes in, the requestId it went out under comes back. */
private class AndroidVoiceTurnSink(private val controller: com.anopticlabs.gravel.voicemqtt.VoiceMqttController) : VoiceTurnSink {
    override suspend fun publishTranscript(text: String): String? = controller.publishTranscript(text)

    override fun rememberNote(requestId: String, noteId: String, title: String) =
        controller.rememberNote(requestId, noteId, title)
}

/**
 * The Notebook's gate on its own reminders, closed by the switch on the Voice Assistant page.
 *
 * Read when a capture is published, so a flip takes effect on the next recording with no restart and no
 * reconnect — and it keeps its position whether the loop is on or off, because "do not make reminders in this
 * app" is not a statement about the broker.
 */
private class AndroidAppReminderGate(private val controller: com.anopticlabs.gravel.voicemqtt.VoiceMqttController) : AppReminderGate {
    override val remindersAllowed: Boolean get() = !controller.settings.value.disableAppReminders
}

/** Settings > Voice Assistant, drawn by the voice module's own Compose screen. */
private class AndroidVoiceMqttSettings(private val engine: VoiceMqttEngine) : VoiceMqttSettings {

    override val available: Boolean = true

    @Composable
    override fun Screen(topBarParams: TopBarParams) {
        val controller = engine.controller
        val settings by controller.settings.collectAsState()
        val connection by controller.connection.collectAsState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            topBarParams.searchAvailable(null)
            topBarParams.title("Voice Assistant")
            topBarParams.actions { }
        }
        VoiceMqttSettingsContent(
            settings = settings,
            connection = connection,
            gate = engine.gate,
            onSave = { controller.save(it) },
            onSendTest = { scope.launch { controller.sendTestMessage() } },
            onSaveCaptureBehaviour = { controller.saveCaptureBehaviour(it) },
            speechRuns = engine.speechRuns.collectAsState().value,
            replyFiles = engine.replyFiles.collectAsState().value,
        )
    }
}

/**
 * Settings > AI > Text to Speech: the phone's engines and voices and the offline-only rule, read from the
 * speaker and written through the store.
 *
 * A change is stored and the engine rebuilt on the spot ([AndroidSpeechOutput.reload]), so the next reply is
 * spoken by the new engine with no app restart. A change that only affects speaking never reconnects the
 * broker, because none of these values is used by the transport.
 */
private class AndroidTextToSpeechSettings(private val engine: VoiceMqttEngine) : TextToSpeechSettings {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val supported: Boolean = true

    override val facts: StateFlow<TextToSpeechFacts> =
        combine(engine.controller.settings, engine.speech.speechState) { settings, speech ->
            TextToSpeechFacts(
                read = speech.read,
                engines = speech.engines.map { SpeechEngine(it.name, it.label, it.isSystemDefault) },
                engine = settings.ttsEngine,
                engineLabel = speech.engineLabel,
                voice = settings.preferredVoice,
                offlineOnly = settings.offlineOnly,
                voices = speech.voices.map { SpeechVoice(it.name, it.locale, offline = !it.networkRequired, it.quality) },
                inUse = speech.chosenVoice?.let {
                    SpeechVoice(it.name, it.locale, offline = !it.networkRequired, it.quality)
                },
                problem = speech.problem,
                languageLabel = speech.languageLabel,
            )
        }.stateIn(scope, SharingStarted.Eagerly, TextToSpeechFacts())

    override fun refresh() {
        scope.launch { engine.speech.reload() }
    }

    override fun chooseEngine(name: String?) {
        store(engine.controller.settings.value.copy(ttsEngine = name, preferredVoice = null))
    }

    override fun chooseVoice(name: String?) {
        store(engine.controller.settings.value.copy(preferredVoice = name))
    }

    override fun setOfflineOnly(enabled: Boolean) {
        store(engine.controller.settings.value.copy(offlineOnly = enabled))
    }

    /** Stores the change and rebuilds the engine, so the next reply uses it and nothing needs a restart. */
    private fun store(next: VoiceSettings) {
        engine.controller.saveSpeaking(next)
        scope.launch { engine.speech.reload() }
    }

    @Composable
    override fun Screen(topBarParams: TopBarParams) {
        val current by facts.collectAsState()
        TextToSpeechScreen(
            topBarParams = topBarParams,
            facts = current,
            onEngine = ::chooseEngine,
            onVoice = ::chooseVoice,
            onOfflineOnly = ::setOfflineOnly,
            onRefresh = ::refresh,
        )
    }
}

/** Where the app starts the loop once, after the app graph exists. No-op with the flag off. */
fun startVoiceMqtt() {
    if (!VoiceMqttBuildKonfig.VOICE_MQTT_ENABLED) return
    // Bound by this module whenever the flag is on, which is the only way this line is reached.
    GlobalContext.get().get<VoiceMqttEngine>().controller.start()
}
