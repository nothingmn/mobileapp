package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger

/**
 * A press measured at or under this is the button's tap.
 *
 * Why 700 ms. A press is measured as the time between the two advertisements that carried its edges, never as the
 * time the finger was down, so the measurement is an upper bound rounded up to the ring's advertising interval. Two
 * bounds fix the window:
 *
 * - It must stay under the Notebook's own tap floor, 1000 ms (`MIN_RECORDING_MILLIS`): a ring recording shorter than
 *   that is already dropped as a tap, so a press this feature treats as a tap can never have been a note the
 *   Notebook would have kept. 700 leaves 300 ms of margin for a slow advertisement.
 * - It must be longer than a real tap as measured through those advertisements — a tap of 150 ms plus one ~500 ms
 *   advertising interval is 650 ms — or every tap would read as a hold and the feature would do nothing.
 *
 * The failure this choice is biased towards is the safe one: a press that measures too long is a
 * [RingButtonGesture.HOLD], which is what the button does today, and a recording is never mistaken for a media key
 * in a way that loses audio, because only the sync the `recording` flag alone asked for is ever held back (see
 * [RingButtonController] and the runner that uses it).
 */
const val RING_TAP_MILLIS = 700L

/**
 * Short presses closer together than this belong to one sequence, so two taps are a double-click rather than two
 * clicks.
 *
 * Why 700 ms. The gap runs from the press that closed last to the press that comes next, so it too is rounded up to
 * an advertising interval: one interval of ~500 ms plus a quick second tap has to fit, and 700 ms does. It is also
 * more than twice Android's own double-tap window (300 ms, `ViewConfiguration.getDoubleTapTimeout`), so a
 * deliberate double-click is not split, while two taps a second apart stay two clicks. A sequence that closes
 * slowly costs the user only the delay before the key is sent.
 */
const val RING_SEQUENCE_GAP_MILLIS = 700L

/**
 * One closed press sequence: the gesture it made, how many presses it took, the length of the press that closed it
 * when a source measured one, and when the sequence began and ended.
 */
data class RingButtonEvent(
    val gesture: RingButtonGesture,
    val presses: Int,
    val pressMillis: Long?,
    val sequenceStartedAtMillis: Long,
    val atMillis: Long,
)

/**
 * Turns the button's presses into the gestures the rest of the feature knows: short presses alone are a
 * [RingButtonGesture.CLICK], [RingButtonGesture.DOUBLE_CLICK] or [RingButtonGesture.TRIPLE_CLICK], a long press alone
 * is [RingButtonGesture.HOLD], and short presses followed by a long one are [RingButtonGesture.CLICK_HOLD]. Short
 * presses belong to one sequence while they follow each other by less than [gapMillis].
 *
 * Two inputs, and no clock inside: presses come from a [RingButtonPress] source, and [onTick] is called for every
 * advertisement the scan hears, which is the only clock the button has — that is what closes a sequence of short
 * presses once its gap passes with no further press. Both entry points take the millisecond they happened at, so the
 * whole matrix is tested without waiting, and neither the class nor its tests carry an Android type.
 *
 * What it cannot do: it knows only what its source saw. With [RingAdvertisementPresses] a press whose edges fall
 * between two advertisements is invisible, and a press whose edges are seen is measured rounded up to the
 * advertising interval. Every edge and every gesture is logged, so a button that seems dead on the phone can be told
 * apart from one that is never heard.
 */
class RingButtonClassifier(private val gapMillis: Long = RING_SEQUENCE_GAP_MILLIS) {
    private var shortPresses = 0
    private var lastPressAt: Long? = null
    private var sequenceStartedAt = 0L
    private var lastGesture: RingButtonEvent? = null

    /** The last gesture this classifier emitted, or null once a sync result has consumed it. */
    @Synchronized
    fun lastGesture(): RingButtonEvent? = lastGesture

    /**
     * One press from the source. The gestures it completed, which is none while a sequence of short presses waits
     * for its gap and one when it closes — and two in the one case where a press begins after the gap and so ends
     * the previous sequence before closing itself.
     */
    @Synchronized
    fun onPress(press: RingButtonPress): List<RingButtonEvent> {
        // The gap is between one press's end and the next press's start, so a press that began long before it ended
        // is dated from its start; a source that cannot measure it is taken to have just begun.
        val start = press.atMillis - (press.durationMillis ?: 0L)
        val events = onTick(start).toMutableList()
        when (press.kind) {
            RingButtonPress.Kind.SHORT -> {
                if (shortPresses == 0) sequenceStartedAt = press.atMillis
                shortPresses++
                lastPressAt = press.atMillis
                logger.d { "ring button: short press ($shortPresses in the sequence)" }
                if (shortPresses >= 3) {
                    events += close(press.atMillis, RingButtonGesture.TRIPLE_CLICK, shortPresses, press.durationMillis)
                }
            }
            RingButtonPress.Kind.LONG -> {
                events += close(
                    press.atMillis,
                    if (shortPresses > 0) RingButtonGesture.CLICK_HOLD else RingButtonGesture.HOLD,
                    shortPresses + 1,
                    press.durationMillis,
                )
            }
        }
        return events
    }

    /**
     * The clock, one call per advertisement: closes a sequence of short presses once its gap has passed. Returns the
     * click or double-click it closed, if any.
     */
    @Synchronized
    fun onTick(atMillis: Long): List<RingButtonEvent> {
        val last = lastPressAt ?: return emptyList()
        if (shortPresses == 0 || atMillis - last < gapMillis) return emptyList()
        return listOf(
            close(
                atMillis,
                if (shortPresses == 1) RingButtonGesture.CLICK else RingButtonGesture.DOUBLE_CLICK,
                shortPresses,
                null,
            ),
        )
    }

    /**
     * What a sync that produced [collections] recordings says about the last gesture: true when that gesture was a
     * media gesture whose press also left audio on the ring. Nothing is lost when it does — the advertisement's
     * count has changed, so the next sync imports it — but the log says so, because a tap that recorded is a ring
     * behaving in a way this classifier did not expect.
     */
    @Synchronized
    fun onSyncResult(collections: Int): Boolean {
        val gesture = lastGesture ?: return false
        lastGesture = null
        if (collections <= 0 || gesture.gesture.records) return false
        logger.w { "ring button: the press that produced ${gesture.gesture} also left $collections recording(s) on the ring" }
        return true
    }

    /** Forgets the sequence and the last gesture, for a ring that was unpaired or a search that restarted. */
    @Synchronized
    fun forget() {
        shortPresses = 0
        lastPressAt = null
        lastGesture = null
    }

    private fun close(
        atMillis: Long,
        gesture: RingButtonGesture,
        presses: Int,
        pressMillis: Long?,
    ): RingButtonEvent {
        val event = RingButtonEvent(gesture, presses, pressMillis, sequenceStartedAt, atMillis)
        shortPresses = 0
        lastPressAt = null
        lastGesture = event
        sequenceStartedAt = atMillis
        logger.i { "ring button: $gesture from $presses press(es), the last ${pressMillis ?: "unmeasured"} ms" }
        return event
    }

    private companion object {
        val logger = Logger.withTag("RingButton")
    }
}
