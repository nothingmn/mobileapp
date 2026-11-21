package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys

/** Where a line goes when the loop has something to say. Implementations decide whether it may be spoken at all. */
interface SpeechOutput {
    suspend fun speak(text: String)
}

/**
 * Every sentence the loop can say, kept in one place so that the failure paths are auditable: the protocol's
 * rule is that no path ends in silence, and an opaque server code is never one of these.
 *
 * Each one is a resource key rather than an English sentence, because the phone speaks the user's language: the
 * caller resolves it through [com.anopticlabs.gravel.voicemqtt.copy.VoiceText] at the moment of speaking.
 * [TEST_MESSAGE] is the one that is sent rather than said — the settings screen's test publishes it — and it is
 * copy for the same reason: the answer to it is read aloud by someone who is reading the screen in that language.
 */
object VoiceLoopSentences {
    val FAILURE = VoiceCopy(VoiceKeys.SPOKEN_FAILURE)
    val TIMEOUT = VoiceCopy(VoiceKeys.SPOKEN_TIMEOUT)
    val NOT_CONNECTED = VoiceCopy(VoiceKeys.SPOKEN_NOT_CONNECTED)
    val BUSY = VoiceCopy(VoiceKeys.SPOKEN_BUSY)
    val EMPTY_TRANSCRIPT = VoiceCopy(VoiceKeys.SPOKEN_EMPTY_TRANSCRIPT)
    val TOO_LONG = VoiceCopy(VoiceKeys.SPOKEN_TOO_LONG)
    val TOO_LARGE = VoiceCopy(VoiceKeys.SPOKEN_TOO_LARGE)
    val BROKEN_IDS = VoiceCopy(VoiceKeys.SPOKEN_BROKEN_REQUEST)
    val TEST_MESSAGE = VoiceCopy(VoiceKeys.SPOKEN_TEST_MESSAGE)
}
