package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.anopticlabs.gravel.notebook.pipeline.VoiceTurnSink
import com.anopticlabs.gravel.notebook.sources.PhoneRecorder
import com.anopticlabs.gravel.notebook.sources.RecorderState
import com.anopticlabs.gravel.ui.VoiceMqttSettings
import com.anopticlabs.gravel.voicemqtt.VoiceMqttBuildKonfig
import com.anopticlabs.gravel.voicemqtt.VoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.buildVoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.ui.VoiceMqttSettingsContent
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Gravel: binds the Agentry voice loop into the app, and binds nothing at all when VOICE_MQTT_ENABLED is off.
 *
 * This file is the only place the app graph touches the :voice-mqtt module. The two interfaces it implements
 * live in :pebble (common code, no iOS problem) and :notebook (the capture drain's seam), so neither of those
 * modules depends on the MQTT client and a build with the flag off carries no reachable path into it.
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
        single<VoiceMqttSettings> { AndroidVoiceMqttSettings(get()) }
    }
}

/** The drain's seam: every transcribed capture goes in, the requestId it went out under comes back. */
private class AndroidVoiceTurnSink(private val controller: com.anopticlabs.gravel.voicemqtt.VoiceMqttController) : VoiceTurnSink {
    override suspend fun publishTranscript(text: String): String? = controller.publishTranscript(text)

    override fun rememberNote(requestId: String, noteId: String, title: String) =
        controller.rememberNote(requestId, noteId, title)
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
        )
    }
}

/** Where the app starts the loop once, after the app graph exists. No-op with the flag off. */
fun startVoiceMqtt() {
    if (!VoiceMqttBuildKonfig.VOICE_MQTT_ENABLED) return
    // Bound by this module whenever the flag is on, which is the only way this line is reached.
    GlobalContext.get().get<VoiceMqttEngine>().controller.start()
}
