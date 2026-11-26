package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.notebook.capture.ChimeHeldReason
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings

/**
 * A reply's decision, said in the capture chime's terms: null when the chime may be heard, the reason when it may
 * not. The chime follows the voice output policy wholesale — the replies switch, headset only, and the pinned
 * device — so switching spoken replies off also silences the acknowledgement, and headset only keeps it off the
 * phone's speaker. That is the operator's rule, not an oversight in this mapping.
 *
 * It lives in the voice module's Android sources because that is where [PlaybackPolicy] and this module's
 * `:notebook` dependency already meet; the decision itself stays pure and is proved on the host.
 */
fun chimeHeld(settings: VoiceSettings, connected: List<ConnectedOutput>): ChimeHeldReason? =
    when (val decision = PlaybackPolicy.decide(settings, connected)) {
        PlaybackDecision.Speak -> null
        is PlaybackDecision.Silent -> when (decision.reason) {
            SpeechSuppression.REPLIES_OFF -> ChimeHeldReason.REPLIES_OFF
            SpeechSuppression.NO_BLUETOOTH_OUTPUT -> ChimeHeldReason.NO_BLUETOOTH_OUTPUT
            SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED -> ChimeHeldReason.PINNED_DEVICE_NOT_CONNECTED
            // About whether a voice exists for the language, which has nothing to do with where a sound goes: a
            // chime needs no voice at all, so it is still played.
            SpeechSuppression.NO_OFFLINE_VOICE, SpeechSuppression.NO_USABLE_VOICE -> null
        }
    }
