package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings

/**
 * One audio output connected at the moment of speaking, in the terms the policy needs: whether it is a
 * Bluetooth headset and, where the platform can say, which device it is.
 */
data class ConnectedOutput(val bluetooth: Boolean, val address: String? = null)

/** Why a sentence was not spoken. Every one of them is a fact to record, not an error to say aloud. */
enum class SpeechSuppression {
    /** The user turned spoken replies off; the silence is their choice. */
    REPLIES_OFF,

    /** Headset only is on and no Bluetooth output is connected right now. */
    NO_BLUETOOTH_OUTPUT,

    /** Headset only is on, Bluetooth outputs are connected, but not the one the user pinned. */
    PREFERRED_DEVICE_NOT_CONNECTED,
}

/** What the loop does with a sentence, decided at the moment of speaking. */
sealed interface PlaybackDecision {
    data object Speak : PlaybackDecision
    data class Silent(val reason: SpeechSuppression) : PlaybackDecision
}

/**
 * The two switches, applied in the order the operator described them, and nothing else. Pure and in common
 * code on purpose: the matrix is the behaviour, so it is decided here where a host test can prove every cell
 * without a phone, an audio stack or a speech engine.
 *
 * - replies off: silent, whatever the outputs are. The reply is still received; it is just not spoken.
 * - headset only on: a Bluetooth output must be connected *now*. A paired but switched-off headset is not an
 *   output, and the phone's speaker must not take its place. A pinned device narrows this further to that one
 *   address when the platform can name its outputs.
 * - headset only off: whatever output is active, the phone's speaker included.
 */
object PlaybackPolicy {

    fun decide(settings: VoiceSettings, connected: List<ConnectedOutput>): PlaybackDecision {
        if (!settings.speakReplies) return PlaybackDecision.Silent(SpeechSuppression.REPLIES_OFF)
        if (!settings.headsetOnly) return PlaybackDecision.Speak
        val bluetooth = connected.filter { it.bluetooth }
        if (bluetooth.isEmpty()) return PlaybackDecision.Silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT)
        val wanted = settings.preferredDevice?.address ?: return PlaybackDecision.Speak
        // Before Android P an output cannot report its address, so the pin cannot be checked; the connected
        // Bluetooth output stands, rather than the answer being refused for a platform's shortcoming.
        val addresses = bluetooth.mapNotNull { it.address }
        if (addresses.isEmpty()) return PlaybackDecision.Speak
        return if (addresses.any { it.equals(wanted, ignoreCase = true) }) {
            PlaybackDecision.Speak
        } else {
            PlaybackDecision.Silent(SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED)
        }
    }

    /** The line written to the log when a sentence is held back. */
    fun logLine(reason: SpeechSuppression): String = when (reason) {
        SpeechSuppression.REPLIES_OFF -> "reply not spoken: spoken replies are switched off"
        SpeechSuppression.NO_BLUETOOTH_OUTPUT -> "reply not spoken: no Bluetooth audio output is connected"
        SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED ->
            "reply not spoken: the chosen Bluetooth device is not connected"
    }

    /** The sentence the settings screen shows under the switches, in plain language. */
    fun explanation(reason: SpeechSuppression): String = when (reason) {
        SpeechSuppression.REPLIES_OFF -> "Spoken replies are off, so nothing was played."
        SpeechSuppression.NO_BLUETOOTH_OUTPUT -> "No Bluetooth output was connected, so the reply was not played."
        SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED ->
            "Your chosen Bluetooth device was not connected, so the reply was not played."
    }
}
