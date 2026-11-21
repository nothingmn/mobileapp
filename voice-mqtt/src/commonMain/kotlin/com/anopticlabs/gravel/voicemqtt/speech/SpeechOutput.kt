package com.anopticlabs.gravel.voicemqtt.speech

/** Where a line goes when the loop has something to say. Implementations decide whether it may be spoken at all. */
interface SpeechOutput {
    suspend fun speak(text: String)
}

/**
 * Every sentence the loop can say, kept in one place so that the failure paths are auditable: the protocol's
 * rule is that no path ends in silence, and an opaque server code is never one of these.
 */
object VoiceLoopSentences {
    const val FAILURE = "I couldn't reach the assistant. Please try again in a moment."
    const val TIMEOUT = "The assistant didn't answer in time. Please try again."
    const val NOT_CONNECTED = "I can't reach the assistant's server right now. Please try again when it is back."
    const val BUSY = "The assistant is busy. Please try again in a moment."
    const val EMPTY_TRANSCRIPT = "I couldn't hear anything in that recording, so nothing was sent."
    const val TOO_LONG = "That recording was too long to send."
    const val TOO_LARGE = "That recording was too large to send."
    const val BROKEN_IDS = "Something went wrong building that request, so nothing was sent."
    const val TEST_MESSAGE = "This is a test message from the Gravel voice assistant."
}
