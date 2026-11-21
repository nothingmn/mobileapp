package com.anopticlabs.gravel.voicemqtt

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.pipeline.NoteReplyResult
import com.anopticlabs.gravel.notebook.pipeline.NoteReplySink
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFileHistory
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFilingOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Puts a spoken answer into the note its recording produced, whenever that note becomes known.
 *
 * Everything here happens after the sentence has gone to the speaker and is never awaited by it: the answer is
 * spoken whether or not a note exists, can be written, or is touched by anyone else meanwhile. This class is
 * the whole filing path, separated from [VoiceMqttController] so the host tests can drive it with a fake sink
 * and a fake clock — the reconciliation this feature was missing is a rule about time, and a rule about time
 * that is only proved by hand is not proved at all.
 *
 * Every decision — filed, held, or not filed and why — leaves one line at info level in logcat with the
 * requestId, and one entry in [history] for the settings screen. The one exception is a repeated answer for a
 * turn that has already been filed: that is logged, not put on the screen, because the line the screen shows is
 * still the true one.
 */
class VoiceReplyFiler(
    private val scope: CoroutineScope,
    /** The notebook's reply sink, looked up when an answer is filed; null with the loop's flag off. */
    private val sink: () -> NoteReplySink?,
    private val history: VoiceReplyFileHistory,
    private val windowMillis: Long = ANSWER_LINK_WINDOW_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val reconciler = VoiceReplyReconciler(windowMillis = windowMillis, now = now)

    /** The note a published turn settled as; the answer is filed now if it is already here, or when it comes. */
    fun rememberNote(requestId: String, noteId: String, title: String) {
        when (val decision = reconciler.remember(requestId, noteId, title)) {
            is VoiceLinkDecision.File -> {
                logger.i { "note $noteId linked to $requestId after its answer was spoken; filing it now" }
                file(decision.link, decision.text)
            }
            VoiceLinkDecision.Remembered -> logger.i { "note $noteId linked to $requestId" }
            VoiceLinkDecision.TooLate ->
                logger.i { "note $noteId linked to $requestId too late: its answer is already settled" }
        }
    }

    fun noteLink(requestId: String): VoiceNoteLink? = reconciler.noteLink(requestId)

    /** An answer that has just been spoken. Filed at once when its note is known, held when it is not. */
    fun answer(requestId: String, text: String) {
        when (val decision = reconciler.answer(requestId, text)) {
            is VoiceAnswerDecision.File -> file(decision.link, decision.text)
            is VoiceAnswerDecision.Hold -> {
                logger.i {
                    "answer $requestId held: no note is linked to it yet; waiting up to " +
                        "${windowMillis / 1000} s for one"
                }
                scope.launch {
                    delay(windowMillis)
                    // A sweep rather than a per-answer timer: it returns every answer that has waited out its
                    // window, so whichever timer runs first also takes the ones beside it.
                    reconciler.expiredAt().forEach { lost ->
                        conclude(lost.requestId, noteId = null, outcome = VoiceReplyFilingOutcome.NoNoteLinked)
                    }
                }
            }
            VoiceAnswerDecision.Duplicate ->
                // Logged, not put on the settings screen: the note that matters is the one this turn already
                // reached, and that line is still the last thing the screen shows.
                logger.i { "answer $requestId not filed again: this turn's answer was already offered to a note" }
        }
    }

    private fun file(link: VoiceNoteLink, text: String) {
        scope.launch {
            val bound = sink()
            if (bound == null) {
                conclude(link.requestId, link.noteId, VoiceReplyFilingOutcome.NoNoteSink)
                return@launch
            }
            val result = try {
                bound.appendReply(link.noteId, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NoteReplyResult.StorageUnavailable
            }
            conclude(link.requestId, link.noteId, VoiceReplyFilingOutcome.NoteSaid(link.title, result))
        }
    }

    private fun conclude(requestId: String, noteId: String?, outcome: VoiceReplyFilingOutcome) {
        history.recorded(outcome)
        logger.i { replyFilingLine(requestId, noteId, outcome, windowMillis) }
    }

    private companion object {
        val logger = Logger.withTag("VoiceReplyFiler")
    }
}

/**
 * The one line logcat gets for a filing decision.
 *
 * It always names the turn, so a log from a phone can be read back against one recording; it always says what
 * happened, so "no note was linked" and "the note is gone" are told apart rather than both being silence. The
 * reason is English like every other log line in this app, and it is not user-facing copy: the settings screen
 * has its own sentences, translated.
 */
internal fun replyFilingLine(
    requestId: String,
    noteId: String?,
    outcome: VoiceReplyFilingOutcome,
    windowMillis: Long = ANSWER_LINK_WINDOW_MILLIS,
): String = when (outcome) {
    is VoiceReplyFilingOutcome.NoteSaid -> when (outcome.result) {
        NoteReplyResult.Appended ->
            "answer $requestId filed into note $noteId as a quote block (Appended)"
        NoteReplyResult.NotListed ->
            "answer $requestId not filed into note $noteId: the note is gone (NotListed)"
        NoteReplyResult.Archived ->
            "answer $requestId not filed into note $noteId: the note is in the archive (Archived)"
        NoteReplyResult.ChangedAfterRetry ->
            "answer $requestId not filed into note $noteId: the note changed twice under the append " +
                "(ChangedAfterRetry)"
        NoteReplyResult.EmptyAnswer ->
            "answer $requestId not filed into note $noteId: the answer had no text (EmptyAnswer)"
        NoteReplyResult.StorageUnavailable ->
            "answer $requestId not filed into note $noteId: the notebook's storage was unavailable " +
                "(StorageUnavailable)"
    }
    VoiceReplyFilingOutcome.NoNoteLinked ->
        "answer $requestId not filed: no note was linked for this reply within ${windowMillis / 1000} s"
    VoiceReplyFilingOutcome.NoNoteSink ->
        "answer $requestId not filed: no note sink is bound in this build"
}
