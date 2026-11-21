package com.anopticlabs.gravel.notebook.pipeline

import org.koin.core.context.GlobalContext

/**
 * What happened when the answer to a spoken turn was appended into the note that turn produced.
 *
 * Every outcome but [Appended] means the note was left exactly as it was: nothing was written, no note was
 * created and an archived one was not brought back.
 */
sealed interface NoteReplyResult {
    /** The answer is in the note, as a time-stamped quote block. */
    data object Appended : NoteReplyResult

    /** The store no longer lists the note — it was deleted, or its id resolves to nothing. */
    data object NotListed : NoteReplyResult

    /** The note is in the archive, so it is left as it is and stays archived. */
    data object Archived : NoteReplyResult

    /** Something else rewrote the note under the append on the first try and again on the retry. */
    data object ChangedAfterRetry : NoteReplyResult

    /** The answer carried no text at all, so nothing was appended — an empty stamp is not worth writing. */
    data object EmptyAnswer : NoteReplyResult

    /** The notebook's storage could not be read or written this time. */
    data object StorageUnavailable : NoteReplyResult
}

/**
 * Gravel: the seam the voice loop files a reply through, so the module that talks to the broker never touches
 * the note store and [com.anopticlabs.gravel.notebook.storage.NoteStore] never learns about MQTT.
 *
 * The app binds an implementation into Koin only when `VOICE_MQTT_ENABLED` is on and reads it with
 * [noteReplySinkOrNull], so with the flag off nothing files anything and a reply is only ever spoken.
 */
interface NoteReplySink {
    /**
     * Appends [reply] into note [noteId] as a time-stamped quote block, leaving the note's title, folder and
     * name alone. Never throws: a dropped append comes back as the outcome that says why, because the caller's
     * answer has already been spoken and speech must not depend on this.
     */
    suspend fun appendReply(noteId: String, reply: String): NoteReplyResult
}

/**
 * The sink the app bound, or null when the voice loop is off — the same `getOrNull` seam the capture drain uses
 * for `VoiceTurnSink`, so an unbound sink is an ordinary state rather than a startup failure.
 */
fun noteReplySinkOrNull(): NoteReplySink? = GlobalContext.getOrNull()?.let { koin -> koin.getOrNull<NoteReplySink>() }
