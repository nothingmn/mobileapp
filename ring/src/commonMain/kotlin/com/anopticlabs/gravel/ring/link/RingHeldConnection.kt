package com.anopticlabs.gravel.ring.link

import com.anopticlabs.gravel.ring.sync.RingConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * A link to the ring that stays up until [close] — one press's link, so the block that opened it is the only thing
 * that can still be using it. `withRingConnection` opens one, hands it to the session and closes it before it
 * returns; nothing else in the app may keep one alive past the press that opened it, because a ring with a link
 * open does not advertise, and an advertisement is the only way a press is heard (see DESIGN_NOTES, "Why the ring's
 * link is not held between presses").
 *
 * [ended] is the ring's own answer, not the caller's: it becomes non-null when the link went down without anybody
 * asking, and the words in it say what Android reported. A link that was closed deliberately does not report
 * anything here. It is what ends a session at once, rather than at the next request's timeout.
 */
interface RingHeldConnection : RingConnection {
    /**
     * Null while the link is up; the failure it went down with once the ring or Android took it away — the same
     * exception a session waiting on [notifications] is given.
     */
    val ended: StateFlow<RingLinkException?>

    /**
     * When each step of this link's open happened, for the one line a press logs about it (see
     * [RingConnectMarks.line]). Empty for a link that was not marked, which is what a test's fake is.
     */
    val connect: RingConnectMarks get() = RingConnectMarks()

    /** Hangs up and closes. Idempotent, and safe from the lost-link path. */
    suspend fun close()
}
