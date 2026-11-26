package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.notebook.capture.ChimeHeldReason
import com.anopticlabs.gravel.notebook.capture.ChimeRouting
import com.anopticlabs.gravel.voicemqtt.VoiceMqttEngine
import com.anopticlabs.gravel.voicemqtt.speech.chimeHeld

/**
 * The capture chime's routing, with the voice loop compiled in: the same policy a spoken reply obeys, asked at
 * the moment of the chime rather than at startup, so a headset switched off between two recordings is a silence
 * and the phone's speaker never takes its place.
 *
 * Both halves come from the engine: the settings as they stand now, and the audio outputs connected now, which
 * is the pairing this class exists for — neither the Notebook nor the ring ever sees either.
 */
internal class VoiceRoutedChime(private val engine: VoiceMqttEngine) : ChimeRouting {
    override fun held(): ChimeHeldReason? =
        chimeHeld(engine.controller.settings.value, engine.gate.connectedBluetooth())
}
