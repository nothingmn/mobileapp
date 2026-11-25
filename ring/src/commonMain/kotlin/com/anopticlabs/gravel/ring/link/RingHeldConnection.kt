package com.anopticlabs.gravel.ring.link

import com.anopticlabs.gravel.ring.sync.RingConnection
import kotlinx.coroutines.flow.StateFlow

/**
 * A link to the ring that stays connected until [close], so a press that arrives while it is held can use it
 * instead of connecting again. The per-press path (`withRingConnection`) and the warm path (`RingLinkKeeper`) share
 * one implementation: the difference is only who decides to close it, and when.
 *
 * [ended] is the ring's own answer, not the keeper's: it becomes non-null when the link went down without anybody
 * asking, and the words in it say what Android reported. A link that was closed deliberately does not report
 * anything here. The keeper watches it so a dropped link is noticed and released while nothing is using it, rather
 * than being found dead by the next press.
 */
interface RingHeldConnection : RingConnection {
    /**
     * Null while the link is up; the failure it went down with once the ring or Android took it away — the same
     * exception a session waiting on [notifications] is given.
     */
    val ended: StateFlow<RingLinkException?>

    /**
     * Empties what the ring sent while no session was reading — the count is logged, not acted on.
     *
     * A session's exchange refuses bytes that arrived between its requests (a real protocol fault, which must
     * still fail loudly), so a link that has been idle since the last press is drained before it is handed over.
     * On a link that has just been subscribed this is always empty.
     */
    fun drain(): Int

    /** Hangs up and closes. Idempotent, and safe from the lost-link path. */
    suspend fun close()
}
