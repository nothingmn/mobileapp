package com.anopticlabs.gravel.notebook.pipeline

import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.storage.EditNoteResult
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.NoteMetadata
import com.anopticlabs.gravel.notebook.storage.NoteStore
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reply append, proved without a device: a fake store stands in for the notebook's and drives every branch
 * — a note rewritten under the append, one deleted, one archived, storage that refuses to write — and the real
 * store carries the cases that are about what actually lands in a note.
 */
class NoteStoreReplySinkTest {

    private val noteId = "0123456789abcdef0123456789abcdef"
    private val at = ZonedDateTime.of(2026, 1, 2, 15, 47, 0, 0, ZoneId.systemDefault())
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    private val original = "First line\nSecond line"

    private fun listed(hash: String, folder: String = "work") =
        NoteMetadata(folder, "note.md", hash, 1_700_000_000_000L, "Old", "2026-01-02")

    /**
     * A store of one note whose listed entry, content and answer to the edit are the test's to set. Every edit is
     * recorded with the hash it was asked to match, which is what makes the retry, and its stale hash, visible.
     */
    private class FakeReplyStore(
        private val noteId: String,
        var entry: NoteMetadata?,
        var content: String?,
        var answer: (attempt: Int, written: String) -> EditNoteResult = { _, _ -> EditNoteResult.Saved("note.md") },
    ) : ReplyNoteStore {
        val hashesAskedFor = mutableListOf<String>()
        var written: String? = null
        var editedId: String? = null

        override fun listed(noteId: String): NoteMetadata? = entry.takeIf { noteId == this.noteId }

        override fun content(noteId: String): String? = content.takeIf { noteId == this.noteId }

        override suspend fun edit(noteId: String, expectedHash: String, content: String, modifiedAtMillis: Long): EditNoteResult {
            hashesAskedFor += expectedHash
            editedId = noteId
            val result = answer(hashesAskedFor.size, content)
            // Only a landed edit is a write; a refused one left the note as it was.
            if (result is EditNoteResult.Saved) written = content
            return result
        }
    }

    private fun sink(store: ReplyNoteStore) = NoteStoreReplySink(store, now = { 1_700_000_000_000L }, localNow = { at })

    @Test
    fun anAppendLandsAsTheWholeQuoteBlockOnTheNotesOwnId() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original)
        assertEquals(NoteReplyResult.Appended, sink(store).appendReply(noteId, "The garage lights are on."))

        assertEquals(noteId, store.editedId, "the answer goes into the note the recording produced, and no other")
        assertEquals(listOf("h1"), store.hashesAskedFor, "the append is hash-guarded: one write against the listed hash")
        assertEquals(
            "First line\nSecond line\n\n> $REPLY_QUOTE_LABEL $time\n> The garage lights are on.",
            store.written,
        )
    }

    @Test
    fun aNoteRewrittenUnderTheAppendIsRereadAndTheRetryLands() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original)
        store.answer = { attempt, _ ->
            if (attempt == 1) {
                // Something else edits the note between our read and our write, so the hash we read is stale.
                store.entry = listed("h2")
                store.content = "Rewritten"
                EditNoteResult.Changed
            } else {
                EditNoteResult.Saved("note.md")
            }
        }

        assertEquals(NoteReplyResult.Appended, sink(store).appendReply(noteId, "Sure."))
        assertEquals(listOf("h1", "h2"), store.hashesAskedFor, "the retry matches the hash it re-read, not the stale one")
        assertEquals("Rewritten\n\n> $REPLY_QUOTE_LABEL $time\n> Sure.", store.written, "the retry appends to the re-read content")
    }

    @Test
    fun aNoteRewrittenTwiceIsLeftAloneAfterExactlyOneRetry() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original, answer = { _, _ -> EditNoteResult.Changed })
        assertEquals(NoteReplyResult.ChangedAfterRetry, sink(store).appendReply(noteId, "Sure."))
        assertEquals(2, store.hashesAskedFor.size, "one try and exactly one retry, then it gives up")
    }

    @Test
    fun aDeletedNoteIsDroppedWithoutAnyWrite() = runBlocking {
        val store = FakeReplyStore(noteId, entry = null, content = null)
        assertEquals(NoteReplyResult.NotListed, sink(store).appendReply(noteId, "Sure."))
        assertTrue(store.hashesAskedFor.isEmpty(), "a note the store no longer lists is never written to and never recreated")
        assertNull(store.written)
    }

    @Test
    fun anArchivedNoteIsDroppedWithoutAnyWrite() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1", ARCHIVE_FOLDER), original)
        assertEquals(NoteReplyResult.Archived, sink(store).appendReply(noteId, "Sure."))
        assertTrue(store.hashesAskedFor.isEmpty(), "an archived note is not resurrected and not written to")
    }

    @Test
    fun storageThatRefusesTheWriteIsRecordedAndWritesNothing() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original, answer = { _, _ -> throw IOException("full") })
        assertEquals(NoteReplyResult.StorageUnavailable, sink(store).appendReply(noteId, "Sure."))
        assertNull(store.written)
    }

    @Test
    fun aNoteWhoseContentWillNotReadIsRecordedAsStorageUnavailable() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), content = null)
        assertEquals(NoteReplyResult.StorageUnavailable, sink(store).appendReply(noteId, "Sure."))
        assertTrue(store.hashesAskedFor.isEmpty())
    }

    @Test
    fun aMultiLineAnswerIsQuotedLineByLine() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original)
        sink(store).appendReply(noteId, "Lights: on.\nThermostat: 21.\n\nDoor: locked.")
        assertEquals(
            "First line\nSecond line\n\n> $REPLY_QUOTE_LABEL $time\n> Lights: on.\n> Thermostat: 21.\n>\n> Door: locked.",
            store.written,
        )
    }

    @Test
    fun anAnswerThatAlreadyEndsInNewlinesLeavesNothingAfterItsLastWord() = runBlocking {
        val store = FakeReplyStore(noteId, listed("h1"), original)
        sink(store).appendReply(noteId, "Sure.\n\n")
        assertEquals("First line\nSecond line\n\n> $REPLY_QUOTE_LABEL $time\n> Sure.", store.written, "the block ends at the full stop")
        assertTrue(store.written!!.endsWith("> Sure."), "no quote marker, fence or newline trails the answer")
    }

    @Test
    fun aBlankAnswerIsNotAppendedAtAll() = runBlocking {
        for (blank in listOf("", "   ", "\n", " \n\t\n ")) {
            val store = FakeReplyStore(noteId, listed("h1"), original)
            assertEquals(NoteReplyResult.EmptyAnswer, sink(store).appendReply(noteId, blank), "for answer [$blank]")
            assertTrue(store.hashesAskedFor.isEmpty(), "an empty answer writes nothing and stamps nothing")
            assertNull(store.written)
        }
    }

    // The real store: what actually lands in a note, end to end.

    private val root = Files.createTempDirectory("notebook-reply").toFile()
    private var clock = 1_700_000_000_000L
    private val crypto = FakeCryptographer(softwareCrypto())
    private val real = NoteStore(root, crypto) { clock }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun realSink() = NoteStoreReplySink(NoteStoreReplyNotes(real), now = { clock }, localNow = { at })

    @Test
    fun anAppendIntoTheRealStoreKeepsTheTitleTheFolderAndTheName() = runBlocking {
        val id = real.createNote("work", "garage_01.02.26_1547.md", original, title = "Garage", date = "2026-01-02")
        val before = real.readMetadata(strict = true).getValue(id)
        clock += 60_000

        assertEquals(NoteReplyResult.Appended, realSink().appendReply(id, "The garage lights are on."))

        val after = real.readMetadata(strict = true).getValue(id)
        assertEquals(before.folder, after.folder, "an append changes no folder")
        assertEquals(before.name, after.name, "an append renames nothing")
        assertEquals(before.title, after.title, "an append keeps the note's title")
        assertEquals(before.date, after.date)
        assertTrue(after.modifiedAtMillis > before.modifiedAtMillis, "the appended note becomes the newest in its folder")
        assertEquals(
            "$original\n\n> $REPLY_QUOTE_LABEL $time\n> The garage lights are on.",
            real.readNote(id, strict = true),
        )
    }

    @Test
    fun anArchivedNoteInTheRealStoreStaysArchivedAndUnwritten() = runBlocking {
        val id = real.createNote("work", "garage_01.02.26_1547.md", original, title = "Garage")
        real.moveNote(id, ARCHIVE_FOLDER)

        assertEquals(NoteReplyResult.Archived, realSink().appendReply(id, "The garage lights are on."))
        assertEquals(original, real.readNote(id, strict = true), "nothing was appended and the note was not brought back")
        assertEquals(ARCHIVE_FOLDER, real.readMetadata(strict = true).getValue(id).folder)
    }

    @Test
    fun aSecondAppendAddsOneMoreBlockAndNoStrayBlankLine() = runBlocking {
        val id = real.createNote("work", "garage_01.02.26_1547.md", original, title = "Garage")
        realSink().appendReply(id, "First answer.")
        clock += 60_000
        assertEquals(NoteReplyResult.Appended, realSink().appendReply(id, "Second answer."))

        val content = real.readNote(id, strict = true)!!
        assertEquals(2, content.split("> $REPLY_QUOTE_LABEL $time").size - 1, "both answers are in the note")
        assertTrue(!content.contains("\n\n\n"), "one blank line separates each block, never two")
    }

    @Test
    fun aNoteDeletedBeforeTheAnswerArrivesIsNeitherWrittenNorRecreated() = runBlocking {
        val id = real.createNote("work", "garage_01.02.26_1547.md", original, title = "Garage")
        real.deleteNote(id)
        val filesBefore = real.noteFileIds()

        assertEquals(NoteReplyResult.NotListed, realSink().appendReply(id, "The garage lights are on."))
        assertEquals(filesBefore, real.noteFileIds(), "no note file was written for a note that is gone")
        assertEquals(0, real.folderCount("work"))
    }

    @Test
    fun aBodyWithNoTrailingNewlineGetsExactlyOneBlankLineBeforeTheBlock() = runBlocking {
        val bare = real.createNote("work", "bare_01.02.26_1547.md", "Body without a newline")
        val closed = real.createNote("work", "closed_01.02.26_1547.md", "Body with a newline\n", modifiedAtMillis = clock + 1)

        assertEquals(NoteReplyResult.Appended, realSink().appendReply(bare, "Sure."))
        assertEquals(NoteReplyResult.Appended, realSink().appendReply(closed, "Sure."))

        val bareContent = real.readNote(bare, strict = true)!!
        assertEquals("Body without a newline\n\n> $REPLY_QUOTE_LABEL $time\n> Sure.", bareContent)
        assertTrue(!bareContent.contains("\n\n\n"), "one blank line, not two")
        // A body that already ends in a newline gets one blank line as well, not two more.
        assertEquals("Body with a newline\n\n> $REPLY_QUOTE_LABEL $time\n> Sure.", real.readNote(closed, strict = true))
    }

    @Test
    fun aStoreThatRefusesTheWriteReturnsStorageUnavailableAndLeavesTheNoteAsItWas() = runBlocking {
        val id = real.createNote("work", "garage_01.02.26_1547.md", original, title = "Garage")
        // Every seal of a note file fails from here: the edit cannot take, whatever it was asked to write.
        crypto.failSealWhenAadContains = "notes/"

        assertEquals(NoteReplyResult.StorageUnavailable, realSink().appendReply(id, "The garage lights are on."))

        crypto.failSealWhenAadContains = null
        assertEquals(original, real.readNote(id, strict = true), "a write that was refused changed nothing")
        assertEquals(NoteStore.sha256(original), real.readMetadata(strict = true).getValue(id).hash)
    }
}
