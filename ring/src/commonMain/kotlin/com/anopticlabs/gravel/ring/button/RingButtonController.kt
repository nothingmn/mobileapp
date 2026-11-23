package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.protocol.RingButtonRecord

/**
 * The cadence the ring writes one gesture's collections at, used only to date the presses a count reports.
 *
 * Why 80 ms. Measured on the operator's phone: the collections of one gesture arrived 70–90 ms apart. The ring
 * reports a count, not a time per press, so the presses of a count are dated back from the moment the gesture
 * settled by this step — enough for the sequence to read as one sequence and for the event to carry an honest end
 * time. Nothing is decided by it: the gesture comes from the count, not from these dates.
 */
const val RING_BURST_STEP_MILLIS = 80L

/**
 * How long a gesture's collections may stop arriving before the gesture is taken to be over.
 *
 * Why 500 ms. A gesture's collections arrive tens of milliseconds apart and it closes well inside this, so a
 * silence this long means the ring is not going to report another press for it. It is also under
 * [RING_SEQUENCE_GAP_MILLIS], so settling a burst on the silence can never split one gesture into two media
 * commands. This is the fallback: the ring marks the last collection of a gesture itself, and that marker settles it
 * without waiting for any time to pass.
 */
const val RING_BURST_QUIET_MILLIS = 500L

/** The presses one gesture is reported as: a tap, a double, a triple. A count of more is not a gesture this app maps. */
const val RING_MAX_BURST_PRESSES = 3

/**
 * The paired ring's button, read from the ring's own report of it: record 0x53 inside a collection, decoded to a
 * [RingButtonRecord] by the parser. Feeds the [RingButtonClassifier] and sends the media key a gesture was mapped to.
 *
 * A press is reported as the *parts of one collection group*: collections sharing a start index, the last one marked
 * final, each carrying a count that climbs as the taps land (1, then 2, then 3). So the burst — not the individual
 * record — is what a gesture is:
 *
 * - The final part of a group settles the burst, and its count is the gesture: 1 is a click, 2 a double, 3 a triple,
 *   and a record whose first word says the button is held is a hold (count 2 held being a click then a hold). The
 *   ring marks that part itself, so the normal path needs no timer and cannot be early.
 * - A silence longer than [RING_BURST_QUIET_MILLIS] settles a burst whose final part never arrived, with the last
 *   count it reported. The sync calls [quiet] while it waits for the ring; without it, a ring that lost its last
 *   part would lose the press.
 * - A new group while one is open (a different start index) drops the open one: its count was never settled, and a
 *   key sent for a gesture nobody finished is worse than a tap that does nothing.
 *
 * [mapping] is read on every gesture, so switching the setting on in Settings takes effect on the next press rather
 * than at the next restart.
 */
class RingButtonController(
    private val mapping: () -> RingButtonMapping,
    private val keys: RingMediaKeys = RingMediaKeys.None,
    private val classifier: RingButtonClassifier = RingButtonClassifier(),
) {
    private var start: Long? = null
    private var count = 0
    private var held = false
    private var firstAt = 0L
    private var lastAt = 0L

    /** One collection's button record at [atMillis], with the part index it carries and whether it closes the group. */
    fun collection(record: RingButtonRecord, startIndex: Long, final: Boolean, atMillis: Long) {
        val open = start
        if (open != null && open != startIndex) {
            logger.w {
                "ring button: report for the group at $startIndex replaced one at $open that never closed " +
                    "($count press(es) dropped, nothing sent)"
            }
            clear()
        }
        if (start == null) {
            start = startIndex
            firstAt = atMillis
        }
        count = record.count
        // Any part of the gesture saying the button is held makes it a hold: the ring reports the press as it is
        // happening, so the part that says so may not be the last one written.
        held = held || record.held
        lastAt = atMillis
        logger.d {
            "ring button: report $count press(es), ${if (held) "held" else "tapped"}, " +
                "group at $startIndex${if (final) " closed" else " open"}"
        }
        if (final) settle(atMillis, onGap = false)
    }

    /**
     * The clock, called while a gesture may still be arriving: settles one whose collections stopped more than
     * [RING_BURST_QUIET_MILLIS] ago.
     */
    fun quiet(atMillis: Long) {
        val open = start ?: return
        if (atMillis - lastAt < RING_BURST_QUIET_MILLIS) return
        logger.w { "ring button: the group at $open never closed; settling on the last report it made" }
        settle(atMillis, onGap = true)
    }

    /** Forgets a half-read gesture, for a ring that was unpaired or a search that restarted it. */
    fun forget() {
        clear()
        classifier.forget()
    }

    private fun clear() {
        start = null
        count = 0
        held = false
        firstAt = 0L
        lastAt = 0L
    }

    /**
     * Turns the burst's settled count into presses and lets the classifier close them. A tap is reported as its
     * presses and then the sequence is closed, because the ring has already said the gesture is over; a hold is the
     * presses before it and then one long press, which the classifier closes itself.
     */
    private fun settle(atMillis: Long, onGap: Boolean) {
        val presses = count
        val isHeld = held
        val began = firstAt
        clear()
        if (presses !in 1..RING_MAX_BURST_PRESSES) {
            logger.w { "ring button: a report of $presses press(es) is not a gesture; nothing sent" }
            return
        }
        val reported = mutableListOf<RingButtonPress>()
        for (i in 0 until presses) {
            val at = atMillis - (presses - 1 - i) * RING_BURST_STEP_MILLIS
            // The part that says the button is held is the press the hold belongs to, and the ring counts it with
            // the taps that came before it.
            if (isHeld && i == presses - 1) {
                reported += RingButtonPress(RingButtonPress.Kind.LONG, at, atMillis - began)
            } else {
                reported += RingButtonPress(RingButtonPress.Kind.SHORT, at)
            }
        }
        val events = reported.flatMap { classifier.onPress(it) }.toMutableList()
        if (!isHeld) {
            // The ring said the gesture is over with the final part, so the sequence closes now rather than after a
            // real 700 ms of silence.
            events += classifier.onTick(atMillis + RING_SEQUENCE_GAP_MILLIS)
        }
        logger.d {
            "ring button: $presses press(es)${if (isHeld) " held" else ""} " +
                "(${if (onGap) "settled on the quiet gap" else "settled on the final part"}); " +
                "${events.map { it.gesture }}"
        }
        events.forEach { dispatch(it, mapping()) }
    }

    private fun dispatch(event: RingButtonEvent, mapping: RingButtonMapping) {
        if (!mapping.consumes(event.gesture)) {
            logger.i { "ring button: ${event.gesture} left to the ring (recording)" }
            return
        }
        val action = mapping.actionFor(event.gesture)
        if (action == RingButtonAction.NOTHING) {
            logger.i { "ring button: ${event.gesture} does nothing (not mapped)" }
            return
        }
        // A phone whose media session refuses the key must not end the scan or the sync, so the send is contained here.
        try {
            keys.send(action)
            logger.d { "ring button: sent $action for ${event.gesture}" }
            logger.i { "ring button: ${event.gesture} -> $action" }
        } catch (e: Exception) {
            logger.e(e) { "ring button: ${event.gesture} -> $action was not sent" }
        }
    }

    private companion object {
        val logger = Logger.withTag("RingButton")
    }
}
