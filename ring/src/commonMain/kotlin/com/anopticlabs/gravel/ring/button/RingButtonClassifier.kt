package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger

/**
 * Short presses closer together than this belong to one sequence, so two taps are a double-click rather than two
 * clicks.
 *
 * Why 700 ms. The gap runs from the press that closed last to the press that comes next, and the presses of one
 * gesture arrive from the ring as the parts of one collection group, tens of milliseconds apart (70–90 ms
 * measured), so any two presses of one gesture are far inside this window while two deliberate taps a second apart
 * are outside it. It is also more than twice Android's own double-tap window (300 ms,
 * `ViewConfiguration.getDoubleTapTimeout`), so a deliberate double-click is not split. A sequence that closes
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
 * Two inputs, and no clock inside: presses come from a [RingButtonPress] source, and [onTick] is called by that
 * source when it knows no further press of the sequence is coming — for the ring's own button record, the final
 * collection of the gesture. Both entry points take the millisecond they happened at, so the whole matrix is tested
 * without waiting, and neither the class nor its tests carry an Android type.
 *
 * What it cannot do: it knows only what its source saw. A source that reports fewer presses than the user made — a
 * tap the ring never reported — makes a shorter gesture or none at all, never a different key. Every press and every
 * gesture is logged, so a button that seems dead on the phone can be told apart from one that is never heard.
 */
class RingButtonClassifier(private val gapMillis: Long = RING_SEQUENCE_GAP_MILLIS) {
    private var shortPresses = 0
    private var lastPressAt: Long? = null
    private var sequenceStartedAt = 0L

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
     * The clock, called by the source once the sequence is over: closes a sequence of short presses at
     * [atMillis], which the source puts past the gap. Returns the click or double-click it closed, if any.
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

    /** Forgets the sequence, for a ring that was unpaired or a search that restarted. */
    @Synchronized
    fun forget() {
        shortPresses = 0
        lastPressAt = null
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
        sequenceStartedAt = atMillis
        logger.i { "ring button: $gesture from $presses press(es), the last ${pressMillis ?: "unmeasured"} ms" }
        return event
    }

    private companion object {
        val logger = Logger.withTag("RingButton")
    }
}
