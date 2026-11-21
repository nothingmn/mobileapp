package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.notebook.pipeline.NoteReplyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What became of a spoken answer: the note it went into and what the notebook said about it, or the reason it
 * never reached one at all.
 *
 * [NoNoteLinked] is the one this feature used to be silent about: the answer came back before the recording had
 * settled as a note, and it was dropped without a trace. It is a recorded reason now, with its own sentence on
 * the settings screen.
 */
sealed interface VoiceReplyFilingOutcome {
    /** A note was found and offered the answer; [result] is the notebook's own word for what happened. */
    data class NoteSaid(val title: String, val result: NoteReplyResult) : VoiceReplyFilingOutcome

    /** Nothing was filed: no note was ever linked to this turn inside the loop's window. */
    data object NoNoteLinked : VoiceReplyFilingOutcome

    /** Nothing was filed: this build has no reply sink bound, so there is nothing to file into. */
    data object NoNoteSink : VoiceReplyFilingOutcome
}

/** What the loop did with the note a spoken answer belonged to: filed into it, or why not, and when. */
data class VoiceReplyFileRun(val atMillis: Long, val outcome: VoiceReplyFilingOutcome)

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

    fun recorded(outcome: VoiceReplyFilingOutcome) {
        val run = VoiceReplyFileRun(now(), outcome)
        _runs.value = (listOf(run) + _runs.value).take(limit)
    }
}
