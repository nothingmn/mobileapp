package com.anopticlabs.gravel.ring.button

/**
 * A press of the ring's button, as a source measured it: a short or a long press that ended at [atMillis], with its
 * length when the source could measure one.
 *
 * This type is the seam every source meets at. The source this build ships is the ring's own record of the button,
 * 0x53, which a sync reads out of a collection ([com.anopticlabs.gravel.ring.protocol.RingButtonRecord]); it reports
 * one press per press the record counts and a long one when the record says the button is held. Another source that
 * saw presses anywhere else would report the same values, and [RingButtonClassifier] and everything above it would
 * not change: which source is in use is the caller's decision, not the classifier's.
 *
 * The advertisement's `recording` bit was the first source tried and is gone: measured on a real phone it never
 * closed a press, because the only advertisements the app sees are the ones that start a sync, and the press itself
 * is what starts it. See `RingButtonController` for the source that replaced it.
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
