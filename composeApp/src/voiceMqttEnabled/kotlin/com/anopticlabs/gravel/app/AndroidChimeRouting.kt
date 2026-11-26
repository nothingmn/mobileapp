package com.anopticlabs.gravel.app

import android.content.Context
import com.anopticlabs.gravel.notebook.capture.ChimeHeldReason
import com.anopticlabs.gravel.notebook.capture.ChimeRouting
import com.anopticlabs.gravel.voicemqtt.VoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.speech.BluetoothAudioGate
import com.anopticlabs.gravel.voicemqtt.speech.chimeHeld

/**
 * The capture chime's routing, with the voice loop compiled in: the same policy a spoken reply obeys, asked at
 * the moment of the chime rather than at startup, so a headset switched off between two recordings is a silence
 * and the phone's speaker never takes its place.
 *
 * The settings are the engine's, because they are the switches the user edits. The connected outputs are **not**
 * the engine's: a ring capture arrives on the ring path, which can run while the voice loop is idle, never
 * connected, or not yet started, and a gate asked in that state answers with no outputs — which reads as "no
 * Bluetooth headset" and holds the chime back for a reason the operator never chose. So the outputs are read
 * here, from the platform's own device list at this instant, exactly the way the flag-off build reads them; only
 * the source of "what is connected right now" differs from a reply's, never the rule applied to it.
 */
internal class VoiceRoutedChime(
    private val engine: VoiceMqttEngine,
    context: Context,
) : ChimeRouting {

    /** The platform read, independent of whether the voice loop has been built, started or connected. */
    private val gate = BluetoothAudioGate(context.applicationContext)

    override fun held(): ChimeHeldReason? =
        chimeHeld(engine.controller.settings.value, gate.connectedBluetooth())
}
