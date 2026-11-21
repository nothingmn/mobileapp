package com.anopticlabs.gravel.notebook.pipeline

/**
 * Gravel: the seam the voice loop attaches to, so :notebook keeps no dependency on the MQTT module and a build
 * without the loop changes nothing here.
 *
 * The app binds an implementation into Koin only when `VOICE_MQTT_ENABLED` is on; the drain reads it with
 * `getOrNull`, so with the flag off every callback below is absent and a capture runs exactly as it did
 * before the feature existed.
 */
interface VoiceTurnSink {
    /**
     * Publishes one transcribed turn, unchanged, and returns the requestId it went out under, or null when
     * nothing was published (the loop is off, the settings are incomplete, or the envelope was refused —
     * a refusal the user already heard about).
     */
    suspend fun publishTranscript(text: String): String?

    /**
     * Holds the note a published turn settled as, keyed by that turn's requestId, in memory. The next
     * increment appends the answer into that note; nothing reads it yet.
     */
    fun rememberNote(requestId: String, noteId: String, title: String)
}
