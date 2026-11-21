package com.anopticlabs.gravel.voicemqtt.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One decision the loop made about one sentence: spoken, or held back and why, and when. */
data class VoiceSpeechRun(val atMillis: Long, val spoken: Boolean, val suppression: SpeechSuppression? = null)

/**
 * The loop's own run history for speaking: what it did with each sentence, newest first, kept short.
 *
 * It exists so that a suppressed reply is a recorded fact rather than a silence with no trace: the operator
 * can see on the settings screen that nothing was played and why, and the log line names the same reason. A
 * suppression is never an error to speak aloud — there would be no output to speak it on.
 */
class VoiceSpeechHistory(
    private val now: () -> Long,
    private val limit: Int = 20,
) {
    private val _runs = MutableStateFlow<List<VoiceSpeechRun>>(emptyList())

    /** Newest first. */
    val runs: StateFlow<List<VoiceSpeechRun>> = _runs.asStateFlow()

    fun spoken() {
        record(VoiceSpeechRun(now(), spoken = true))
    }

    fun suppressed(reason: SpeechSuppression) {
        record(VoiceSpeechRun(now(), spoken = false, suppression = reason))
    }

    private fun record(run: VoiceSpeechRun) {
        _runs.value = (listOf(run) + _runs.value).take(limit)
    }
}
