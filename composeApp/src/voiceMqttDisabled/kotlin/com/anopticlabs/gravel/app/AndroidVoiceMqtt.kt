package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.capture.ChimeRouting
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Gravel, with `VOICE_MQTT_ENABLED` off — the shipped default.
 *
 * This is the whole of the voice loop in such a build: a module that binds only the capture chime's routing and a
 * function that does nothing else. The MQTT module is not even a dependency of the app graph
 * (`composeApp/build.gradle.kts` adds it only when the flag is on), so no client, no transport, no speech engine and
 * no settings screen from it is compiled into the APK, and nothing can reach them.
 */
val voiceMqttModule: Module = module {
    // Nothing here consults VoiceMqttSettings or VoiceTurnSink, which is what keeps the Settings entry out and the
    // capture drain's callbacks no-ops. The chime still needs an answer, so it gets the conservative one.
    single<ChimeRouting> { HeadsetOnlyChimeRouting(androidContext()) }
}

/** No-op: there is no loop to start. */
fun startVoiceMqtt() = Unit
