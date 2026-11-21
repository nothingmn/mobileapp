package com.anopticlabs.gravel.app

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Gravel, with `VOICE_MQTT_ENABLED` off — the shipped default.
 *
 * This is the whole of the voice loop in such a build: an empty module and a function that does nothing. The
 * MQTT module is not even a dependency of the app graph (`composeApp/build.gradle.kts` adds it only when the
 * flag is on), so no client, no transport, no speech engine and no settings screen from it is compiled into the
 * APK, and nothing can reach them.
 */
val voiceMqttModule: Module = module {
    // Intentionally empty: nothing binds VoiceMqttSettings or VoiceTurnSink, which is what keeps the Settings
    // entry out and the capture drain's callbacks no-ops.
}

/** No-op: there is no loop to start. */
fun startVoiceMqtt() = Unit
