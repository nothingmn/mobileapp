package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.notebook.pipeline.NoteReplyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the loop did with the note a spoken answer belonged to: filed into [title], or why not, and when. */
data class VoiceReplyFileRun(val atMillis: Long, val title: String, val outcome: NoteReplyResult)

/**
 * The loop's own record of filing answers into notes, newest first, kept short — the sibling of the speech
 * history, and for the same reason: an answer that did not reach its note is a fact on the settings screen
 * rather than a silence. It is never read aloud; the answer has already been spoken by the time this runs.
 */
class VoiceReplyFileHistory(
    private val now: () -> Long,
    private val limit: Int = 20,
) {
    private val _runs = MutableStateFlow<List<VoiceReplyFileRun>>(emptyList())

    /** Newest first. */
    val runs: StateFlow<List<VoiceReplyFileRun>> = _runs.asStateFlow()

    fun recorded(title: String, outcome: NoteReplyResult) {
        val run = VoiceReplyFileRun(now(), title, outcome)
        _runs.value = (listOf(run) + _runs.value).take(limit)
    }
}
