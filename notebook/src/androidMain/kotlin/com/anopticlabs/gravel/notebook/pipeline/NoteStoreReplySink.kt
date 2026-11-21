package com.anopticlabs.gravel.notebook.pipeline

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.storage.EditNoteResult
import com.anopticlabs.gravel.notebook.storage.NoteMetadata
import com.anopticlabs.gravel.notebook.storage.NoteStore
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The one word the reply block adds. A localisation pass has this single place to move it; the time in the
 * block is the device's own short-time format, so it needs no word of its own.
 */
internal const val REPLY_QUOTE_LABEL = "Reply"

/** The attempts one append makes: the read-and-write, then one retry after the note was rewritten under it. */
private const val APPEND_ATTEMPTS = 2

/**
 * What [NoteStoreReplySink] needs from the notebook's store, narrow enough that a fake can drive every branch
 * of the append — a note rewritten between the read and the write, one unlisted, one archived — without a
 * device. [NoteStoreReplyNotes] binds the real store to it.
 */
interface ReplyNoteStore {
    /** The note as the store lists it, or null when it no longer lists that id. */
    fun listed(noteId: String): NoteMetadata?

    /** The note's current content, or null when its file cannot be read this time. */
    fun content(noteId: String): String?

    /** [NoteStore.editNote], which writes only when the note still carries [expectedHash]; the title is untouched. */
    suspend fun edit(noteId: String, expectedHash: String, content: String, modifiedAtMillis: Long): EditNoteResult
}

/** The real notebook store, as the reply sink sees it. */
class NoteStoreReplyNotes(private val store: NoteStore) : ReplyNoteStore {
    override fun listed(noteId: String): NoteMetadata? = store.readMetadata(strict = true)[noteId]

    override fun content(noteId: String): String? = store.readNote(noteId, strict = true)

    override suspend fun edit(noteId: String, expectedHash: String, content: String, modifiedAtMillis: Long): EditNoteResult =
        store.editNote(noteId, expectedHash, content, title = null, modifiedAtMillis = modifiedAtMillis)
}

/**
 * Files a spoken answer into the note its recording produced, as a quote block the note's reader can see was an
 * answer and when it arrived.
 *
 * The write is hash-guarded on purpose: `updateNote` would overwrite whatever the note has become in the
 * meantime — including nothing, if it was deleted — so the append reads the note's listed hash first and writes
 * with `editNote`. A note rewritten between that read and that write is re-read and the append retried once;
 * after that the reply is dropped rather than guessed at. A note that is gone or archived is never resurrected,
 * and no note is ever created here: the answer belongs to the note the recording made, or to nothing.
 */
class NoteStoreReplySink(
    private val notes: ReplyNoteStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val localNow: () -> ZonedDateTime = { ZonedDateTime.now() },
) : NoteReplySink {

    override suspend fun appendReply(noteId: String, reply: String): NoteReplyResult {
        // An answer with nothing in it would leave a stamp with no words under it: the note is left alone.
        if (reply.isBlank()) return NoteReplyResult.EmptyAnswer
        repeat(APPEND_ATTEMPTS) {
            val entry = try {
                notes.listed(noteId)
            } catch (e: Exception) {
                return unavailable(e)
            } ?: return NoteReplyResult.NotListed
            if (entry.folder == ARCHIVE_FOLDER) return NoteReplyResult.Archived
            val current = try {
                notes.content(noteId)
            } catch (e: Exception) {
                return unavailable(e)
            } ?: run {
                // Listed, but its file will not open this time: there is nothing safe to append to.
                logger.w { "reply not filed: the note could not be read" }
                return NoteReplyResult.StorageUnavailable
            }
            val appended = current + separatorFor(current) + replyBlock(reply, localNow())
            val written = try {
                notes.edit(noteId, entry.hash, appended, now())
            } catch (e: Exception) {
                return unavailable(e)
            }
            when (written) {
                is EditNoteResult.Saved -> return NoteReplyResult.Appended
                EditNoteResult.NotListed -> return NoteReplyResult.NotListed
                EditNoteResult.Archived -> return NoteReplyResult.Archived
                // Rewritten under us: the next pass re-reads the hash and appends to the content it finds.
                EditNoteResult.Changed -> Unit
            }
        }
        return NoteReplyResult.ChangedAfterRetry
    }

    /** Nothing was written; the log line says which failure it was, without a note's content or the answer's text. */
    private fun unavailable(cause: Exception): NoteReplyResult.StorageUnavailable {
        logger.w { "reply not filed: ${cause::class.simpleName}" }
        return NoteReplyResult.StorageUnavailable
    }

    private companion object {
        val logger = Logger.withTag("NotebookStorage")
    }
}

/**
 * The block appended to a note: a blank line, then a quoted line carrying the word and the local time, then one
 * quoted line per line of the answer. Every line begins with `> `, so the markdown reader renders the whole
 * thing as the quotation it is.
 */
internal fun replyBlock(reply: String, at: ZonedDateTime): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
    val quoted = reply.trim().lines().joinToString("\n") { line ->
        if (line.isBlank()) ">" else "> $line"
    }
    return "> $REPLY_QUOTE_LABEL $time\n$quoted"
}

/**
 * What goes between the note's own text and the block: enough newlines for exactly one blank line, whether the
 * note ends in none, one, or a blank line already.
 */
private fun separatorFor(content: String): String = when {
    content.isEmpty() -> ""
    content.endsWith("\n\n") -> ""
    content.endsWith("\n") -> "\n"
    else -> "\n\n"
}
