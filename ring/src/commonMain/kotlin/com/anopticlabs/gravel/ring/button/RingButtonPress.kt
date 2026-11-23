package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger

/**
 * A press of the ring's button, as a source measured it: a short or a long press that ended at [atMillis], with its
 * length when the source could measure one.
 *
 * This type is the seam every source meets at. The source this build ships,
 * [RingAdvertisementPresses], reads the advertisements' `recording` bit. A source that found the presses in a
 * collection's own records would report the same values, and [RingButtonClassifier] and everything above it would
 * not change: which source is in use is the caller's decision, not the classifier's.
 */
data class RingButtonPress(
    val kind: Kind,
    /** When the press ended, on the caller's clock. */
    val atMillis: Long,
    /** How long the press was, when the source measured it; null when it could not. */
    val durationMillis: Long? = null,
) {
    enum class Kind { SHORT, LONG }
}

/**
 * The ring's button as its advertisements already report it: the `recording` bit is set while the button is held
 * ([com.anopticlabs.gravel.ring.protocol.RingAdvertisement]), so a false-to-true edge opens a press and a
 * true-to-false edge closes it. A press measured at or under [tapMillis] is short, a longer one long.
 *
 * The measurement is an upper bound: it is the distance between the two advertisements that carried the edges, so
 * it is never shorter than the press and is rounded up to the ring's advertising interval. A press whose two edges
 * fall between the same pair of advertisements is not seen at all. Both facts are logged, not hidden.
 */
class RingAdvertisementPresses(private val tapMillis: Long = RING_TAP_MILLIS) {
    private var down = false
    private var pressedAt: Long? = null

    /**
     * One advertisement at [atMillis]: the press this advertisement closed, or null when its flag did not change
     * or when it opened a press.
     */
    @Synchronized
    fun onAdvertisement(atMillis: Long, recording: Boolean): RingButtonPress? {
        if (recording == down) return null
        if (recording) {
            down = true
            pressedAt = atMillis
            logger.d { "ring button: pressed at $atMillis" }
            return null
        }
        down = false
        val start = pressedAt ?: atMillis
        pressedAt = null
        val held = atMillis - start
        logger.d { "ring button: released at $atMillis after $held ms, ${if (held <= tapMillis) "short" else "long"}" }
        return RingButtonPress(
            if (held <= tapMillis) RingButtonPress.Kind.SHORT else RingButtonPress.Kind.LONG,
            atMillis,
            held,
        )
    }

    /** Whether a press is open at [atMillis] and still short enough to be a tap. */
    @Synchronized
    fun tapInProgress(atMillis: Long): Boolean {
        val start = pressedAt ?: return false
        return down && atMillis - start <= tapMillis
    }

    /** Forgets a press the scan will never see closed, for a ring that was unpaired or a search that restarted. */
    @Synchronized
    fun forget() {
        down = false
        pressedAt = null
    }

    private companion object {
        val logger = Logger.withTag("RingButton")
    }
}
