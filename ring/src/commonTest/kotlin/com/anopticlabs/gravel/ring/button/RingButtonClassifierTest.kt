package com.anopticlabs.gravel.ring.button

import com.anopticlabs.gravel.ring.protocol.RingButtonRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The button's matrix, at the boundaries. Every timing here is a press the classifier's source placed on its own
 * clock; the source that ships, the ring's button record, reads the count out of the record and reports one press
 * for each (`RingButtonControllerTest` below drives the whole path).
 */
class RingButtonClassifierTest {

    private val click = RingButtonGesture.CLICK
    private val doubleClick = RingButtonGesture.DOUBLE_CLICK
    private val tripleClick = RingButtonGesture.TRIPLE_CLICK
    private val hold = RingButtonGesture.HOLD
    private val clickHold = RingButtonGesture.CLICK_HOLD

    private fun short(at: Long, millis: Long? = 150) = RingButtonPress(RingButtonPress.Kind.SHORT, at, millis)
    private fun long(at: Long, millis: Long? = 900) = RingButtonPress(RingButtonPress.Kind.LONG, at, millis)

    private fun RingButtonClassifier.gestures(at: Long): List<RingButtonGesture> = onTick(at).map { it.gesture }

    @Test
    fun aSingleShortPressClosesIntoAClickOnceTheGapPasses() {
        val classifier = RingButtonClassifier()

        assertEquals(emptyList(), gestureOrEmpty(classifier.onPress(short(1_200))), "the sequence waits for its gap")
        assertEquals(emptyList(), classifier.gestures(1_800), "600 ms is inside the gap")

        val event = classifier.onTick(1_900).single()

        assertEquals(click, event.gesture)
        assertEquals(1, event.presses)
        assertEquals(1_200, event.sequenceStartedAtMillis)
        assertEquals(1_900, event.atMillis)
    }

    @Test
    fun twoShortPressesInsideTheGapAreADoubleClick() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_100))
        classifier.onPress(short(1_400))

        val event = classifier.onTick(2_100).single()

        assertEquals(doubleClick, event.gesture)
        assertEquals(2, event.presses)
        assertEquals(1_100, event.sequenceStartedAtMillis, "the sequence is dated from its first press")
    }

    @Test
    fun threeShortPressesAreATripleClickAtTheThirdWithoutWaitingForTheGap() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_100))
        classifier.onPress(short(1_200))

        val event = classifier.onPress(short(1_400)).single()

        assertEquals(tripleClick, event.gesture)
        assertEquals(3, event.presses)
        assertEquals(emptyList(), classifier.gestures(5_000), "the sequence is closed")
    }

    @Test
    fun aFourthPressStartsASequenceOfItsOwn() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_050))
        classifier.onPress(short(1_150))
        assertEquals(tripleClick, classifier.onPress(short(1_250)).single().gesture)

        classifier.onPress(short(1_350))

        val event = classifier.onTick(2_050).single()
        assertEquals(click, event.gesture)
        assertEquals(1, event.presses, "the fourth press is a click, not a fourth part of the triple")
    }

    @Test
    fun aLongPressIsAHoldAtItsEnd() {
        val classifier = RingButtonClassifier()

        val event = classifier.onPress(long(1_750, 750)).single()

        assertEquals(hold, event.gesture)
        assertEquals(1, event.presses)
        assertEquals(750, event.pressMillis)
    }

    @Test
    fun aShortPressThenALongOneIsAClickHold() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_100))

        val event = classifier.onPress(long(2_000, 800)).single()

        assertEquals(clickHold, event.gesture)
        assertEquals(2, event.presses)
        assertEquals(800, event.pressMillis, "the long press is the one that closes the sequence")
    }

    @Test
    fun twoShortPressesThenALongOneAreStillAClickHold() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_050))
        classifier.onPress(short(1_150))

        val event = classifier.onPress(long(2_100, 900)).single()

        assertEquals(clickHold, event.gesture)
        assertEquals(3, event.presses)
    }

    @Test
    fun aTickThatClosesNothingEmitsNothing() {
        val classifier = RingButtonClassifier()

        assertEquals(emptyList(), classifier.gestures(1_000))
        classifier.onPress(short(1_100))
        assertEquals(emptyList(), classifier.onTick(1_500), "still inside the gap")
    }

    @Test
    fun pressesSeparatedByLessThanTheGapAreOneSequenceAndAtTheGapTwo() {
        // The gap runs from one press's end to the next press's start, which is where a source's measurement puts
        // it: a short press that begins a moment before it ends began just before its edge.
        val joined = RingButtonClassifier()
        joined.onPress(short(1_100))
        joined.onPress(short(1_100 + RING_SEQUENCE_GAP_MILLIS - 1 + 150, 150))
        assertEquals(doubleClick, joined.onTick(3_000).single().gesture)

        val split = RingButtonClassifier()
        split.onPress(short(1_100))
        val closing = split.onPress(short(1_100 + RING_SEQUENCE_GAP_MILLIS + 150, 150))
        assertEquals(listOf(click), gestureOrEmpty(closing), "a press at the gap ends the previous click")
        assertEquals(click, split.onTick(3_000).single().gesture, "and the new press is a click of its own")
    }

    @Test
    fun aPressThatBeginsAfterTheGapEndsThePreviousSequenceBeforeClosingItself() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_100))

        // Ends at 3_000 having lasted 800 ms, so it began at 2_200: well after the 700 ms gap.
        val events = classifier.onPress(long(3_000, 800))

        assertEquals(listOf(click, hold), events.map { it.gesture })
    }

    @Test
    fun forgettingClearsTheSequence() {
        val classifier = RingButtonClassifier()
        classifier.onPress(short(1_100))
        classifier.forget()

        assertEquals(emptyList(), classifier.gestures(5_000))
        classifier.onPress(short(5_100))
        assertEquals(click, classifier.onTick(5_800).single().gesture)
    }

    @Test
    fun aPressWithNoMeasuredLengthStillMakesItsGesture() {
        val classifier = RingButtonClassifier()

        val event = classifier.onPress(RingButtonPress(RingButtonPress.Kind.LONG, 1_500)).single()

        assertEquals(hold, event.gesture)
        assertNull(event.pressMillis, "a source that cannot measure a press reports none")
    }

    private fun gestureOrEmpty(events: List<RingButtonEvent>) = events.map { it.gesture }
}

/** The mapping: which gesture reaches which destination, and what a saved mapping reads back as. */
class RingButtonMappingTest {

    @Test
    fun everyMediaGestureReachesItsOwnDestinationAndTheSwitchGatesAllOfThem() {
        val mapping = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.PLAY_PAUSE,
            doubleClick = RingButtonAction.NEXT,
            tripleClick = RingButtonAction.PREVIOUS,
        )

        assertEquals(RingButtonAction.PLAY_PAUSE, mapping.actionFor(RingButtonGesture.CLICK))
        assertEquals(RingButtonAction.NEXT, mapping.actionFor(RingButtonGesture.DOUBLE_CLICK))
        assertEquals(RingButtonAction.PREVIOUS, mapping.actionFor(RingButtonGesture.TRIPLE_CLICK))
        for (gesture in RingButtonGesture.entries) {
            assertEquals(
                RingButtonAction.NOTHING,
                mapping.copy(enabled = false).actionFor(gesture),
                "with the switch off nothing is sent, $gesture included",
            )
        }
    }

    @Test
    fun aGestureMappedToNothingReachesNothing() {
        val mapping = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.NOTHING,
            doubleClick = RingButtonAction.NOTHING,
            tripleClick = RingButtonAction.NOTHING,
        )

        for (gesture in RingButtonGesture.entries) {
            assertEquals(RingButtonAction.NOTHING, mapping.actionFor(gesture))
        }
    }

    @Test
    fun theRecordingGesturesAndNoneAreNeverRemapped() {
        val mapped = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.NEXT,
            doubleClick = RingButtonAction.NEXT,
            tripleClick = RingButtonAction.NEXT,
        )

        assertEquals(RingButtonAction.NOTHING, mapped.actionFor(RingButtonGesture.HOLD))
        assertEquals(RingButtonAction.NOTHING, mapped.actionFor(RingButtonGesture.CLICK_HOLD))
        assertEquals(RingButtonAction.NOTHING, mapped.actionFor(RingButtonGesture.NONE))
        assertTrue(RingButtonGesture.HOLD.records)
        assertTrue(RingButtonGesture.CLICK_HOLD.records)
        assertFalse(RingButtonGesture.CLICK.records)
    }

    @Test
    fun onlyTheMediaGesturesAreTheButtonsOwnWhileTheSwitchIsOn() {
        val on = RingButtonMapping(enabled = true)
        val off = RingButtonMapping(enabled = false)

        assertTrue(on.consumes(RingButtonGesture.CLICK))
        assertTrue(on.consumes(RingButtonGesture.DOUBLE_CLICK))
        assertTrue(on.consumes(RingButtonGesture.TRIPLE_CLICK))
        assertTrue(
            on.copy(click = RingButtonAction.NOTHING).consumes(RingButtonGesture.CLICK),
            "a tap mapped to nothing is still the user's tap, not a recording",
        )

        assertFalse(on.consumes(RingButtonGesture.HOLD))
        assertFalse(on.consumes(RingButtonGesture.CLICK_HOLD))
        assertFalse(on.consumes(RingButtonGesture.NONE))
        for (gesture in RingButtonGesture.entries) assertFalse(off.consumes(gesture), "with the switch off $gesture records")
    }

    @Test
    fun aSavedMappingReadsBackAndAnythingElseIsTheDefault() {
        val mapping = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.NEXT,
            doubleClick = RingButtonAction.NOTHING,
            tripleClick = RingButtonAction.PLAY_PAUSE,
        )

        assertEquals(mapping, RingButtonMapping.decode(mapping.encode()))
        assertTrue(RingButtonMapping.decode(mapping.encode()).enabled)
        assertEquals(RingButtonMapping(), RingButtonMapping.decode(null))
        assertEquals(RingButtonMapping(), RingButtonMapping.decode(""))
        assertEquals(RingButtonMapping(), RingButtonMapping.decode("1,2,3"))
        assertEquals(RingButtonMapping(), RingButtonMapping.decode("1,9,0,0"))
        assertEquals(RingButtonMapping(), RingButtonMapping.decode("yes,0,0,0"))
        assertTrue(mapping.encode().length <= 48, "the file holds at most 48 characters")
    }
}

/**
 * The whole path the app runs: the ring's own report of its button, record 0x53 in a collection, out as the media key
 * the user mapped.
 *
 * Every report here is what the ring actually sent on the operator's phone at 08:47:33: one collection per press,
 * **65 ms apart**, the ring's count climbing 1, 2, 3 across the collections of one gesture, and **every one of them
 * marked final**. Settling on `final` sent a key per press — play/pause, then next, then previous for one triple tap
 * — which is the defect these tests hold shut: nothing is sent until the reports stop, and the highest count of the
 * window is the one gesture that is sent.
 */class RingButtonControllerTest {

    private class Keys : RingMediaKeys {
        val sent = mutableListOf<RingButtonAction>()
        override fun send(action: RingButtonAction) {
            sent += action
        }
    }

    /**
     * One controller and what it did. [dispatched] counts the gestures that reached the mapping: a hold sends no key,
     * so the keys alone cannot show that a hold was recognised exactly once.
     */
    private class Buttons(mapping: RingButtonMapping) {
        val keys = Keys()
        var dispatched = 0
            private set
        val controller = RingButtonController({ dispatched++; mapping }, keys)
    }

    /** The measured gap between the presses of one gesture. */
    private val measuredGap = 65L

    private fun buttons(mapping: RingButtonMapping) = Buttons(mapping)

    private val allMapped = RingButtonMapping(
        enabled = true,
        click = RingButtonAction.PLAY_PAUSE,
        doubleClick = RingButtonAction.NEXT,
        tripleClick = RingButtonAction.PREVIOUS,
    )

    private fun record(count: Int, held: Boolean = false) = RingButtonRecord(held = held, count = count)

    /**
     * One gesture's reports as the ring made them: [counts] 65 ms apart, a distinct collection each time, every one
     * final. The ring closes no gesture with a marker this code can trust, so nothing here settles anything.
     */
    private fun RingButtonController.reports(
        vararg counts: Int,
        held: Boolean = false,
        start: Long = 5_003,
        at: Long = 10_000,
    ) {
        counts.forEachIndexed { i, count ->
            collection(
                record(count, held = held && i == counts.lastIndex),
                startIndex = start + i,
                final = true,
                atMillis = at + i * measuredGap,
            )
        }
    }

    /** When the ring has said nothing more about a gesture whose last report was at [lastReport]. */
    private fun lastReportOf(count: Int, at: Long = 10_000): Long = at + (count - 1) * measuredGap

    @Test
    fun aSingleTapSendsExactlyOneKeyOneWindowAfterIt() {
        val buttons = buttons(RingButtonMapping(enabled = true))

        buttons.controller.reports(1)
        assertEquals(emptyList(), buttons.keys.sent, "a report on its own sends nothing: the ring may still be counting")
        assertEquals(
            RING_BUTTON_QUIET_MILLIS,
            buttons.controller.settlesIn(10_000),
            "the wait is the quiet window and nothing else",
        )

        buttons.controller.quiet(lastReportOf(1) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), buttons.keys.sent, "one tap, one key")
        assertEquals(1, buttons.dispatched)
    }

    @Test
    fun aDoubleTap65MsApartSendsNextOnceAndNeverTheClick() {
        // The measurement, as a test: two collections 65 ms apart, counts 1 and 2, both final. Settling on the final
        // one sent PLAY_PAUSE for the first press and NEXT for the second, so pausing a track skipped it.
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, 2)
        assertEquals(emptyList(), buttons.keys.sent, "neither report may fire on arrival")

        buttons.controller.quiet(lastReportOf(2) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(
            listOf(RingButtonAction.NEXT),
            buttons.keys.sent,
            "the second tap's destination, once, and not the first's",
        )
        assertEquals(1, buttons.keys.sent.size)
        assertEquals(1, buttons.dispatched)
    }

    @Test
    fun aTripleTapSendsOnlyPrevious() {
        // The operator's triple tap at 08:47:33: three collections, counts 1/2/3, all final. It sent all three keys
        // and ended on the wrong one.
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, 2, 3)
        buttons.controller.quiet(lastReportOf(3) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(listOf(RingButtonAction.PREVIOUS), buttons.keys.sent, "one gesture, one key: the last of the three")
        assertEquals(1, buttons.dispatched)
    }

    @Test
    fun twoGesturesSeparatedByMoreThanTheWindowSendTwoGesturesInOrder() {
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, at = 10_000)
        buttons.controller.reports(1, 2, at = 10_000 + RING_BUTTON_QUIET_MILLIS + 1)

        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), buttons.keys.sent, "the first gesture closed when the window did")

        buttons.controller.quiet(lastReportOf(2, at = 10_000 + RING_BUTTON_QUIET_MILLIS + 1) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(
            listOf(RingButtonAction.PLAY_PAUSE, RingButtonAction.NEXT),
            buttons.keys.sent,
            "two gestures, each sent once, in the order they were made",
        )
        assertEquals(2, buttons.dispatched)
    }

    @Test
    fun aReportJustInsideTheWindowJoins() {
        val buttons = buttons(allMapped)
        buttons.controller.collection(record(1), startIndex = 5_003, final = true, atMillis = 10_000)
        buttons.controller.collection(
            record(2),
            startIndex = 5_004,
            final = true,
            atMillis = 10_000 + RING_BUTTON_QUIET_MILLIS - 1,
        )
        assertEquals(emptyList(), buttons.keys.sent, "one millisecond inside the window is the same gesture")

        buttons.controller.quiet(10_000 + RING_BUTTON_QUIET_MILLIS - 1 + RING_BUTTON_QUIET_MILLIS)

        assertEquals(listOf(RingButtonAction.NEXT), buttons.keys.sent, "so the two presses are one double click")
        assertEquals(1, buttons.dispatched)
    }

    @Test
    fun aReportJustOutsideTheWindowStartsFreshAndDoesNotResendTheOldGesturesKey() {
        val buttons = buttons(allMapped)
        buttons.controller.collection(record(1), startIndex = 5_003, final = true, atMillis = 10_000)
        buttons.controller.collection(
            record(2),
            startIndex = 5_004,
            final = true,
            atMillis = 10_000 + RING_BUTTON_QUIET_MILLIS,
        )

        assertEquals(
            listOf(RingButtonAction.PLAY_PAUSE),
            buttons.keys.sent,
            "the gesture was sent as it stood when the window closed, and is not sent again",
        )

        buttons.controller.quiet(10_000 + RING_BUTTON_QUIET_MILLIS + RING_BUTTON_QUIET_MILLIS)

        assertEquals(
            listOf(RingButtonAction.PLAY_PAUSE, RingButtonAction.NEXT),
            buttons.keys.sent,
            "the report past the window began a fresh gesture rather than joining the old one",
        )
        assertEquals(2, buttons.dispatched)
    }

    @Test
    fun aHeldReportIsAHoldRecognisedOnceAndSendsNoKey() {
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, held = true)
        assertEquals(emptyList(), buttons.keys.sent)
        assertEquals(0, buttons.dispatched, "a hold waits for the window like everything else")

        buttons.controller.quiet(lastReportOf(1) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(emptyList(), buttons.keys.sent, "a hold is the recording it always was")
        assertEquals(1, buttons.dispatched, "the hold itself was recognised, once")
    }

    @Test
    fun theReportsOfAHoldArrivingWhileItIsDownStillMakeOneHold() {
        // The ring keeps writing collections while the button is down; each one only refreshes the window, so one
        // hold is recognised once rather than five times.
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, held = true, at = 20_000)
        buttons.controller.quiet(20_000 + RING_BUTTON_QUIET_MILLIS - 1)
        buttons.controller.reports(1, held = true, at = 20_000 + RING_BUTTON_QUIET_MILLIS - 1)
        buttons.controller.quiet(20_000 + 2 * RING_BUTTON_QUIET_MILLIS - 1)

        assertEquals(emptyList(), buttons.keys.sent)
        assertEquals(1, buttons.dispatched)
    }

    @Test
    fun aTapThenAHoldIsAClickHoldRecognisedOnceAndSendsNoKey() {
        // The ring counts the held press with the tap that came before it: two presses, the button down. Both reports
        // are inside one window, so the tap is not sent on its own before the hold arrives.
        val buttons = buttons(allMapped)

        buttons.controller.reports(1, 2, held = true)
        buttons.controller.quiet(lastReportOf(2) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(emptyList(), buttons.keys.sent, "a click then a hold is a recording, not a media key")
        assertEquals(1, buttons.dispatched, "and the tap was not sent as a click of its own first")
    }

    @Test
    fun aGestureMappedToNothingSendsNoKey() {
        val buttons = buttons(RingButtonMapping(enabled = true, click = RingButtonAction.NOTHING))

        buttons.controller.reports(1)
        buttons.controller.quiet(lastReportOf(1) + RING_BUTTON_QUIET_MILLIS)

        assertEquals(emptyList(), buttons.keys.sent)
        assertEquals(1, buttons.dispatched, "the gesture still reached the mapping that says it does nothing")
    }

    @Test
    fun aGestureIsSentOnlyOnTheSilenceAndOnlyOnce() {
        val buttons = buttons(allMapped)
        buttons.controller.collection(record(2), startIndex = 5_003, final = false, atMillis = 10_000)

        buttons.controller.quiet(10_000 + RING_BUTTON_QUIET_MILLIS - 1)
        assertEquals(emptyList(), buttons.keys.sent, "still inside the window: the ring may still be writing this gesture")

        buttons.controller.quiet(10_000 + RING_BUTTON_QUIET_MILLIS)
        assertEquals(listOf(RingButtonAction.NEXT), buttons.keys.sent, "the silence settles the two taps it reported")

        buttons.controller.quiet(20_000)
        assertEquals(listOf(RingButtonAction.NEXT), buttons.keys.sent, "a window already settled is not settled again")
        assertEquals(0L, buttons.controller.settlesIn(20_000), "nothing is open any more")
    }

    @Test
    fun aCountThatIsNotAGestureSendsNothing() {
        val buttons = buttons(allMapped)

        buttons.controller.reports(0, start = 1, at = 1_000)
        buttons.controller.quiet(1_000 + RING_BUTTON_QUIET_MILLIS)
        buttons.controller.reports(4, start = 2, at = 2_000)
        buttons.controller.quiet(2_000 + RING_BUTTON_QUIET_MILLIS)

        assertEquals(emptyList(), buttons.keys.sent)
    }

    @Test
    fun forgettingADroppedPairingForgetsAHalfReadGesture() {
        val buttons = buttons(allMapped)
        buttons.controller.collection(record(2), startIndex = 5_003, final = false, atMillis = 10_000)

        buttons.controller.forget()
        buttons.controller.quiet(20_000)

        assertEquals(emptyList(), buttons.keys.sent)
        assertEquals(0L, buttons.controller.settlesIn(20_000))
    }

    @Test
    fun aMediaSessionThatRefusesTheKeyDoesNotEscapeTheController() {
        val refusing = RingButtonController({ RingButtonMapping(enabled = true) }, RingMediaKeys { error("no media session") })

        refusing.reports(1)
        refusing.quiet(10_000 + RING_BUTTON_QUIET_MILLIS)
    }
}
