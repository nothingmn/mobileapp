package com.anopticlabs.gravel.voicemqtt

import com.anopticlabs.gravel.voicemqtt.session.SESSION_IDLE_MILLIS

/**
 * How long an answer waits for the note its recording settled as.
 *
 * A recording is published as a turn the moment it has been transcribed, and settled as a note only after the
 * text model has run — so on a fast local broker the answer can be back tens of seconds before there is a note
 * id to put it in. The wait is capped at the conversation itself, [SESSION_IDLE_MILLIS]: that is the point at
 * which the server has already reset the session and this turn is history, so an answer still without a note by
 * then is not going to get one. Nothing longer would keep a stale answer alive past its turn; nothing shorter
 * would outlast a slow text model on a full queue.
 */
const val ANSWER_LINK_WINDOW_MILLIS: Long = SESSION_IDLE_MILLIS

/** requestId → the note its recording settled as. */
data class VoiceNoteLink(val requestId: String, val noteId: String, val title: String)

/** What the loop decided to do with an answer the moment it arrived. */
sealed interface VoiceAnswerDecision {
    /** There is a note: write [text] into [link]. The turn is spent and will not be offered again. */
    data class File(val link: VoiceNoteLink, val text: String) : VoiceAnswerDecision

    /** No note is linked yet: the answer waits, at most until [expiresAtMillis]. */
    data class Hold(val requestId: String, val expiresAtMillis: Long) : VoiceAnswerDecision

    /** This turn's answer was already offered to a note, or is already waiting for one. */
    data object Duplicate : VoiceAnswerDecision
}

/** What the loop decided to do with a note link the moment it was registered. */
sealed interface VoiceLinkDecision {
    /** An answer was waiting for this turn: write [text] into [link] now. */
    data class File(val link: VoiceNoteLink, val text: String) : VoiceLinkDecision

    /** Nothing was waiting: the link is kept for the answer that is still to come. */
    data object Remembered : VoiceLinkDecision

    /**
     * The answer came and went before this link existed, so there is nothing left to file. What became of that
     * answer was already logged and recorded when it happened, against the same requestId.
     */
    data object TooLate : VoiceLinkDecision
}

/** An answer that waited out its window without a note ever being linked to it. */
data class VoiceHeldAnswerLost(val requestId: String, val expiresAtMillis: Long)

/**
 * The book of `requestId → note`, and the answers waiting for a page that has not been written yet.
 *
 * The defect this exists for: an answer was filed only if its link was already there, and an answer that came
 * first was dropped by a bare `return` — silently, with nothing in the log and nothing on the settings screen.
 *
 * The rules, in one place because they are decisions rather than side effects:
 * - an answer with a link is handed over at once — the common path costs nothing extra;
 * - an answer without a link is **held**, and handed over the moment the link is registered;
 * - a held answer is **dropped** when [ANSWER_LINK_WINDOW_MILLIS] closes, and the drop is returned to the caller
 *   so it can be recorded and logged. Nothing is dropped by silence any more;
 * - a turn is handed over **once**. A second answer for it, or a link that arrives after its answer was already
 *   spent, decides [VoiceAnswerDecision.Duplicate] or [VoiceLinkDecision.TooLate] instead of filing again.
 *
 * Every method is safe to call from any thread: answers arrive on the transport's coroutine while links arrive
 * on the capture drain's.
 */
class VoiceReplyReconciler(
    private val windowMillis: Long = ANSWER_LINK_WINDOW_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val links = LinkedHashMap<String, Linked>()
    private val held = LinkedHashMap<String, Held>()

    /** Turns whose answer has been handed to a note, and when — kept only long enough to refuse a duplicate. */
    private val filed = LinkedHashMap<String, Long>()

    /** Turns whose answer waited out the window, and when — so a link that arrives later says why it is idle. */
    private val dropped = LinkedHashMap<String, Long>()

    private data class Linked(val link: VoiceNoteLink, val atMillis: Long)
    private data class Held(val text: String, val expiresAtMillis: Long)

    fun answer(requestId: String, text: String): VoiceAnswerDecision = synchronized(lock) {
        val at = now()
        prune(at)
        if (filed.containsKey(requestId) || held.containsKey(requestId)) {
            return@synchronized VoiceAnswerDecision.Duplicate
        }
        val waiting = links.remove(requestId)
        if (waiting != null) {
            filed[requestId] = at
            VoiceAnswerDecision.File(waiting.link, text)
        } else {
            val expiresAtMillis = at + windowMillis
            held[requestId] = Held(text, expiresAtMillis)
            VoiceAnswerDecision.Hold(requestId, expiresAtMillis)
        }
    }

    fun remember(requestId: String, noteId: String, title: String): VoiceLinkDecision = synchronized(lock) {
        val at = now()
        prune(at)
        val waiting = held.remove(requestId)
        if (waiting != null) {
            filed[requestId] = at
            return@synchronized VoiceLinkDecision.File(VoiceNoteLink(requestId, noteId, title), waiting.text)
        }
        if (filed.containsKey(requestId) || dropped.containsKey(requestId)) {
            return@synchronized VoiceLinkDecision.TooLate
        }
        links[requestId] = Linked(VoiceNoteLink(requestId, noteId, title), at)
        VoiceLinkDecision.Remembered
    }

    fun noteLink(requestId: String): VoiceNoteLink? = synchronized(lock) { links[requestId]?.link }

    /** The held answers whose window has closed, removed as they are returned. */
    fun expiredAt(atMillis: Long = now()): List<VoiceHeldAnswerLost> = synchronized(lock) {
        val due = held.filterValues { atMillis >= it.expiresAtMillis }
            .map { (requestId, waiting) -> VoiceHeldAnswerLost(requestId, waiting.expiresAtMillis) }
        due.forEach {
            dropped[it.requestId] = atMillis
            held.remove(it.requestId)
        }
        prune(atMillis)
        due
    }

    /**
     * Forgets everything older than one window: a turn id kept to refuse duplicates, and a link whose turn
     * never got answered at all. Without this the book would grow for the life of the process.
     */
    private fun prune(atMillis: Long) {
        val cutoff = atMillis - windowMillis
        filed.entries.removeAll { it.value <= cutoff }
        dropped.entries.removeAll { it.value <= cutoff }
        links.entries.removeAll { it.value.atMillis <= cutoff }
    }
}
