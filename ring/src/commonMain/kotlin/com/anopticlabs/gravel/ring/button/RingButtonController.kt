package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.protocol.RingButtonRecord

/**
 * The cadence the ring writes one gesture's collections at, used only to date the presses a count reports.
 *
 * Why 80 ms. Measured on the operator's phone: the collections of one gesture arrived 65–90 ms apart, so a count of
 * three presses is spread back over the two steps this makes. The ring reports a count, not a time per press, so the
 * presses of a count are dated back from the last report of the gesture by this step — enough for the sequence to
 * read as one sequence and for the event to carry an honest end time. Nothing is decided by it: the gesture comes
 * from the count, not from these dates.
 */
const val RING_BURST_STEP_MILLIS = 80L

/**
 * How long the button has to stay silent before the reports read so far are taken to be one finished gesture.
 *
 * Why 150 ms. Measured on the operator's phone at 08:47:33 (PID 31321): one triple tap reached this code as three
 * collections 65 ms apart, each carrying the ring's cumulative count and each marked final. The window has to be
 * longer than the slowest gap between the presses of one gesture — 90 ms as measured — and 150 leaves 60 ms of room
 * over that, so a tap, a double and a triple all settle as the one gesture the ring counted. It is kept as short as
 * the measurement allows because the key now waits for it: a tap's key arrives one window, 150 ms, after the ring's
 * last report of it, which is inside the quarter of a second at which a button stops feeling connected. Android's
 * own double-tap timeout (300 ms, `ViewConfiguration.getDoubleTapTimeout`) is deliberately not the number copied
 * here: the app is not deciding what a double tap is — the ring counts the presses itself — it is only waiting for
 * the ring to stop reporting one.
 *
 * The cost, stated plainly: two presses of one gesture that this code happens to read more than 150 ms apart (a
 * stalled exchange between two collections) settle as two gestures and send two keys. Every report is logged with
 * its collection, its count and whether it was final, so that is visible in logcat; this is the one value to raise
 * if it is ever seen.
 */
const val RING_BUTTON_QUIET_MILLIS = 150L

/** The presses one gesture is reported as: a tap, a double, a triple. A count of more is not a gesture this app maps. */
const val RING_MAX_BURST_PRESSES = 3

/**
 * The paired ring's button, read from the ring's own report of it: record 0x53 inside a collection, decoded to a
 * [RingButtonRecord] by the parser. Feeds the [RingButtonClassifier] and sends the media key a gesture was mapped to.
 *
 * The ring reports the button as a count of presses that climbs as the taps land — 1, then 2, then 3 for a triple —
 * and **every one of those collections is marked final**, because `final` says the *collection* is complete, not the
 * gesture. Measured on the operator's phone at 08:47:33 (three collections 65 ms apart, counts 1, 2, 3, all final),
 * settling on `final` sent play/pause, then next, then previous for one triple tap: the user got a key for every
 * press of the gesture and ended on the wrong track. So a report never dispatches. It is kept, the highest count of
 * the window is the gesture, and the gesture is sent once when the reports stop:
 *
 * - A report joins the open gesture while it arrives inside [RING_BUTTON_QUIET_MILLIS] of the one before it. The
 *   highest count seen in that window is the gesture's count, because the ring's count is cumulative within the
 *   gesture it is reporting.
 * - The gesture is sent when nothing has been reported for [RING_BUTTON_QUIET_MILLIS]: by [quiet] from the sync's own
 *   clock, or by [collection] when a later report arrives and finds the window already closed. Either way it is sent
 *   once, dated from the last report of the gesture, so the wait never moves a gesture's end time.
 * - `final` is parsed and logged and decides nothing, and neither does the collection's start index: in the
 *   measurement every collection of one gesture carried its own final marker, so neither can delimit a gesture. Only
 *   the silence can.
 * - A report of the button being held joins the same window rather than dispatching on arrival. While the button is
 *   down the ring is still writing collections for the note, and the gesture ends when the button comes up — which is
 *   exactly the silence. Sending a hold on arrival would fire on its first report with none of the hold in it, and
 *   would then need a second "already sent" path to keep the next held report from firing again; with one window the
 *   press that precedes a hold is still in hand, so presses then a hold settle as [RingButtonGesture.CLICK_HOLD] and
 *   a hold alone as [RingButtonGesture.HOLD]. Neither sends a key, so the wait costs the hold nothing.
 *
 * [mapping] is read on every gesture, so switching the setting on in Settings takes effect on the next press rather
 * than at the next restart.
 */
class RingButtonController(
    private val mapping: () -> RingButtonMapping,
    private val keys: RingMediaKeys = RingMediaKeys.None,
    private val classifier: RingButtonClassifier = RingButtonClassifier(),
) {
    private var open = false
    private var count = 0
    private var held = false
    private var firstAt = 0L
    private var lastAt = 0L

    /**
     * One collection's button record at [atMillis]. [startIndex] and [final] are logged and decide nothing; the class
     * comment says what the measurement showed about them.
     */
    fun collection(record: RingButtonRecord, startIndex: Long, final: Boolean, atMillis: Long) {
        // A report that arrives after the window closed begins a new gesture, and the one before it is sent first:
        // two taps a second apart are two clicks, not one double tap, and the earlier gesture is not sent again.
        if (open && atMillis - lastAt >= RING_BUTTON_QUIET_MILLIS) settle()
        if (!open) {
            open = true
            firstAt = atMillis
            count = 0
            held = false
        }
        // The highest count of the window is the gesture: the ring's count climbs across the collections of one
        // gesture, so the last count it reports is the whole of it.
        count = maxOf(count, record.count)
        // Any report of the gesture saying the button is held makes it a hold: the ring reports the press as it
        // happens, so the report that says so may not be the last one written.
        held = held || record.held
        lastAt = atMillis
        logger.d {
            "ring button: report ${record.count} press(es), ${if (record.held) "held" else "tapped"}, " +
                "collection group at $startIndex${if (final) " closed" else " open"}: the gesture stands at " +
                "$count press(es)${if (held) ", held" else ""}"
        }
    }

    /**
     * The clock, called while the button may still be reporting and once the ring has gone quiet: sends the open
     * gesture once nothing has been reported for [RING_BUTTON_QUIET_MILLIS].
     */
    fun quiet(atMillis: Long) {
        if (open && atMillis - lastAt >= RING_BUTTON_QUIET_MILLIS) settle()
    }

    /**
     * How long the open gesture has left before [quiet] settles it, in milliseconds; 0 when nothing is open or its
     * window has already passed, in which case [quiet] settles it now. The sync waits on this rather than polling.
     */
    fun settlesIn(atMillis: Long): Long =
        if (!open) 0L else (lastAt + RING_BUTTON_QUIET_MILLIS - atMillis).coerceAtLeast(0L)

    /** Forgets a gesture that was still open, for a ring that was unpaired or a search that restarted it. */
    fun forget() {
        clear()
        classifier.forget()
    }

    private fun clear() {
        open = false
        count = 0
        held = false
        firstAt = 0L
        lastAt = 0L
    }

    /**
     * Sends the gesture the window settled on, once, and forgets it. Only [collection] and [quiet] call this, and
     * both first prove the window has closed, so one gesture cannot be sent twice.
     *
     * A tap is reported as its presses and then the sequence is closed, because the ring has already counted them; a
     * hold is the presses before it and then one long press, which the classifier closes itself.
     */
    private fun settle() {
        val presses = count
        val isHeld = held
        val began = firstAt
        val ended = lastAt
        clear()
        if (presses !in 1..RING_MAX_BURST_PRESSES) {
            logger.w { "ring button: a report of $presses press(es) is not a gesture; nothing sent" }
            return
        }
        val reported = (0 until presses).map { i ->
            val at = ended - (presses - 1 - i) * RING_BURST_STEP_MILLIS
            // The press the hold belongs to is the last one, and the ring counts it with the taps that came before it.
            if (isHeld && i == presses - 1) {
                RingButtonPress(RingButtonPress.Kind.LONG, at, ended - began)
            } else {
                RingButtonPress(RingButtonPress.Kind.SHORT, at)
            }
        }
        val events = reported.flatMap { classifier.onPress(it) }.toMutableList()
        if (!isHeld) {
            // The count is the whole gesture, so the sequence closes now rather than after a real 700 ms of silence.
            events += classifier.onTick(ended + RING_SEQUENCE_GAP_MILLIS)
        }
        logger.i {
            "ring button: $presses press(es)${if (isHeld) " held" else ""} settled on " +
                "$RING_BUTTON_QUIET_MILLIS ms of quiet: ${events.map { it.gesture }}"
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
