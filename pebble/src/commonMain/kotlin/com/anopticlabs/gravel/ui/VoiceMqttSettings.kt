package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coredevices.pebble.ui.TopBarParams
import org.koin.compose.getKoin

/**
 * Gravel: the Agentry voice loop as common code sees it.
 *
 * The loop's client, its settings screen and its speech engine are Android-only (the MQTT clients a phone can
 * carry are JVM libraries), so the module cannot be a dependency of common code — :pebble has iOS targets.
 * Common code therefore looks the implementation up and treats a missing binding as "this build has no voice
 * loop", which is exactly what a build with VOICE_MQTT_ENABLED off produces: no binding, no Settings entry,
 * no route to the screen.
 */
interface VoiceMqttSettings {
    /** False leaves the Settings entry out entirely; true only where the Android side bound the loop. */
    val available: Boolean

    /** The broker, topic, credential and playback settings; the screen saves and reconnects from here. */
    @Composable
    fun Screen(topBarParams: TopBarParams)
}

/** Null in a build without the loop, because nothing binds [VoiceMqttSettings] then. */
@Composable
fun rememberVoiceMqttSettings(): VoiceMqttSettings? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<VoiceMqttSettings>() }
}
