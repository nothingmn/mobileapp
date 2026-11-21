package com.anopticlabs.gravel.voicemqtt

import com.anopticlabs.gravel.notebook.pipeline.NoteReplyResult
import com.anopticlabs.gravel.notebook.pipeline.NoteReplySink
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFileHistory
import com.anopticlabs.gravel.voicemqtt.speech.VoiceReplyFilingOutcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The filing path, driven the way a phone drives it: an answer arrives on one coroutine, the note it belongs to
 * is registered on another, and the two are rarely in that order.
 *
 * The defect these tests exist for: an answer whose note was not linked **yet** was dropped by a bare `return` —
 * spoken, never kept, and nothing anywhere said so. Each test below is one of the ways that used to happen, with
 * the sink counting what it was actually asked to write, and the history holding what the settings screen shows.
 */
class VoiceReplyFilerTest {

    /** Counts every append it is asked to do, and can answer the way the real notebook does. */
    private class FakeSink(
        private val result: NoteReplyResult = NoteReplyResult.Appended,
        private val boom: Exception? = null,
    ) : NoteReplySink {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun appendReply(noteId: String, reply: String): NoteReplyResult {
            boom?.let { throw it }
            calls += noteId to reply
            return result
        }
    }

    private fun TestScope.history() = VoiceReplyFileHistory(now = { testScheduler.currentTime })

    /** The production window, not a shortened one: the TTL the phone actually uses is what is being proved. */
    private fun TestScope.filer(sink: NoteReplySink?, history: VoiceReplyFileHistory) = VoiceReplyFiler(
        scope = backgroundScope,
        sink = { sink },
        history = history,
        now = { testScheduler.currentTime },
    )

    @Test
    fun anAnswerWithItsNoteAlreadyKnownIsFiledAtOnce() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.rememberNote("turn-1", "note-1", "Garage")
        filer.answer("turn-1", "The garage lights are on.")
        runCurrent()

        assertEquals(listOf("note-1" to "The garage lights are on."), sink.calls, "the common path files on the spot")
        val run = assertIs<VoiceReplyFilingOutcome.NoteSaid>(history.runs.value.single().outcome)
        assertEquals(NoteReplyResult.Appended, run.result)
        assertEquals("Garage", run.title, "the screen can still name the note it went into")
    }

    @Test
    fun anAnswerThatArrivesBeforeItsNoteIsFiledWhenTheNoteArrives() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.answer("turn-1", "The garage lights are on.")
        runCurrent()
        assertTrue(sink.calls.isEmpty(), "nothing is written while the note id is still unknown")
        assertTrue(history.runs.value.isEmpty(), "and nothing is reported as a failure either")

        filer.rememberNote("turn-1", "note-1", "Garage")
        runCurrent()

        assertEquals(
            listOf("note-1" to "The garage lights are on."),
            sink.calls,
            "the answer was kept and went into the note the moment it existed",
        )
        assertIs<VoiceReplyFilingOutcome.NoteSaid>(history.runs.value.single().outcome)
    }

    @Test
    fun aHeldAnswerIsFiledOnlyOnceEvenIfItsNoteIsRegisteredTwice() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.answer("turn-1", "The garage lights are on.")
        filer.rememberNote("turn-1", "note-1", "Garage")
        filer.rememberNote("turn-1", "note-2", "Garage again")
        runCurrent()

        assertEquals(listOf("note-1" to "The garage lights are on."), sink.calls, "one note, one append")
        assertEquals(1, history.runs.value.size)
    }

    @Test
    fun aNoteRegisteredAfterTheAnswerWasDroppedFilesNothing() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.answer("turn-1", "The garage lights are on.")
        advanceTimeBy(ANSWER_LINK_WINDOW_MILLIS + 1)
        runCurrent()
        assertEquals(1, history.runs.value.size, "the window closing is recorded")

        filer.rememberNote("turn-1", "note-1", "Garage")
        runCurrent()

        assertTrue(sink.calls.isEmpty(), "an answer whose turn is over is not resurrected by a late note")
        assertEquals(1, history.runs.value.size, "and the second decision adds no second screen line")
    }

    @Test
    fun anAnswerWhoseNoteNeverArrivesIsDroppedWhenTheWindowCloses() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.answer("turn-1", "The garage lights are on.")
        advanceTimeBy(ANSWER_LINK_WINDOW_MILLIS - 1)
        runCurrent()
        assertTrue(history.runs.value.isEmpty(), "the answer is still being held, one millisecond short")

        advanceTimeBy(2)
        runCurrent()

        assertTrue(sink.calls.isEmpty(), "nothing was ever written")
        val outcome = history.runs.value.single().outcome
        assertEquals(VoiceReplyFilingOutcome.NoNoteLinked, outcome, "the drop is recorded with its reason")

        val line = replyFilingLine("turn-1", noteId = null, outcome = outcome)
        assertTrue(line.contains("turn-1"), "the log line names the turn: $line")
        assertTrue(line.contains("no note was linked"), "and says why it was not filed: $line")
    }

    @Test
    fun twoAnswersForTheSameTurnAreFiledOnce() = runTest {
        val sink = FakeSink()
        val history = history()
        val filer = filer(sink, history)

        filer.rememberNote("turn-1", "note-1", "Garage")
        filer.answer("turn-1", "The garage lights are on.")
        filer.answer("turn-1", "The garage lights are on.")
        runCurrent()

        assertEquals(listOf("note-1" to "The garage lights are on."), sink.calls, "a second answer is not a second append")
        assertEquals(1, history.runs.value.size)
    }

    @Test
    fun theReasonsForNotFilingAreToldApartFromEachOther() = runTest {
        // No note was ever linked.
        val noLinkHistory = history()
        val noLinkFiler = filer(sink = FakeSink(), history = noLinkHistory)
        noLinkFiler.answer("turn-1", "Sure.")
        advanceTimeBy(ANSWER_LINK_WINDOW_MILLIS + 1)
        runCurrent()
        val noLink = replyFilingLine("turn-1", null, noLinkHistory.runs.value.single().outcome)

        // The note is gone.
        val goneHistory = history()
        val goneFiler = filer(FakeSink(result = NoteReplyResult.NotListed), goneHistory)
        goneFiler.rememberNote("turn-2", "note-2", "Garage")
        goneFiler.answer("turn-2", "Sure.")
        runCurrent()
        val goneOutcome = assertIs<VoiceReplyFilingOutcome.NoteSaid>(goneHistory.runs.value.single().outcome)
        assertEquals(NoteReplyResult.NotListed, goneOutcome.result)
        val gone = replyFilingLine("turn-2", "note-2", goneOutcome)

        // The notebook's storage failed under the append.
        val storageHistory = history()
        val storageFiler = filer(FakeSink(boom = IOException("the note store is not there")), storageHistory)
        storageFiler.rememberNote("turn-3", "note-3", "Garage")
        storageFiler.answer("turn-3", "Sure.")
        runCurrent()
        val storageOutcome = assertIs<VoiceReplyFilingOutcome.NoteSaid>(storageHistory.runs.value.single().outcome)
        assertEquals(NoteReplyResult.StorageUnavailable, storageOutcome.result)
        val storage = replyFilingLine("turn-3", "note-3", storageOutcome)

        // Nothing at all is bound to file into.
        val noSinkHistory = history()
        val noSinkFiler = filer(sink = null, history = noSinkHistory)
        noSinkFiler.rememberNote("turn-4", "note-4", "Garage")
        noSinkFiler.answer("turn-4", "Sure.")
        runCurrent()
        val noSinkOutcome = noSinkHistory.runs.value.single().outcome
        assertEquals(VoiceReplyFilingOutcome.NoNoteSink, noSinkOutcome)
        val noSink = replyFilingLine("turn-4", "note-4", noSinkOutcome)

        assertEquals(4, setOf(noLink, gone, storage, noSink).size, "four reasons, four different lines")
        assertTrue(noLink.contains("no note was linked"), noLink)
        assertTrue(gone.contains("the note is gone"), gone)
        assertTrue(storage.contains("storage was unavailable"), storage)
        assertTrue(noSink.contains("no note sink is bound"), noSink)
        for (line in listOf(noLink, gone, storage, noSink)) {
            assertTrue(line.startsWith("answer turn-"), "every line names the turn first: $line")
            assertTrue(line.contains("not filed"), "every one of these says the answer was not filed: $line")
        }
    }

    @Test
    fun aFiledAnswerReadsAsFiledInTheSameLogLine() = runTest {
        val outcome = VoiceReplyFilingOutcome.NoteSaid("Garage", NoteReplyResult.Appended)
        val line = replyFilingLine("turn-1", "note-1", outcome)

        assertEquals("answer turn-1 filed into note note-1 as a quote block (Appended)", line)
    }
}
