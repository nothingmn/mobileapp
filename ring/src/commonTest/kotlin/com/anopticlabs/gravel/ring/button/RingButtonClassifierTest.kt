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
 * the user mapped. Each burst below is what a real gesture looked like on the operator's phone: the parts of one
 * collection group, its count climbing as the taps land, the last part marked final.
 */
class RingButtonControllerTest {

    private class Keys : RingMediaKeys {
        val sent = mutableListOf<RingButtonAction>()
        override fun send(action: RingButtonAction) {
            sent += action
        }
    }

    private val keys = Keys()

    private fun controller(mapping: RingButtonMapping) = RingButtonController({ mapping }, keys)

    private val allMapped = RingButtonMapping(
        enabled = true,
        click = RingButtonAction.PLAY_PAUSE,
        doubleClick = RingButtonAction.NEXT,
        tripleClick = RingButtonAction.PREVIOUS,
    )

    /** One gesture's collections: the counts as the ring reported them, the last one closing the group. */
    private fun RingButtonController.burst(vararg counts: Int, held: Boolean = false, start: Long = 5_003, at: Long = 10_000) {
        counts.forEachIndexed { i, count ->
            collection(
                RingButtonRecord(held = held && i == counts.lastIndex, count = count),
                startIndex = start,
                final = i == counts.lastIndex,
                atMillis = at + i * RING_BURST_STEP_MILLIS,
            )
        }
    }

    @Test
    fun aSingleTapSendsTheKeyItIsMappedTo() {
        controller(RingButtonMapping(enabled = true)).burst(1)

        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), keys.sent)
    }

    @Test
    fun aDoubleTapSendsNextAndATripleSendsPrevious() {
        val controller = controller(allMapped)

        controller.burst(1, 2, start = 5_003, at = 10_000)
        controller.burst(1, 2, 3, start = 5_008, at = 11_000)

        assertEquals(listOf(RingButtonAction.NEXT, RingButtonAction.PREVIOUS), keys.sent)
    }

    @Test
    fun eachGestureReachesItsOwnKeyAndTheSwitchGatesAllOfThem() {
        val on = controller(allMapped)
        on.burst(1, start = 1, at = 1_000)
        on.burst(1, 2, start = 2, at = 2_000)
        on.burst(1, 2, 3, start = 3, at = 3_000)
        assertEquals(listOf(RingButtonAction.PLAY_PAUSE, RingButtonAction.NEXT, RingButtonAction.PREVIOUS), keys.sent)

        keys.sent.clear()
        val off = controller(allMapped.copy(enabled = false))
        off.burst(1, start = 4, at = 4_000)
        off.burst(1, 2, start = 5, at = 5_000)
        off.burst(1, 2, 3, start = 6, at = 6_000)
        assertEquals(emptyList(), keys.sent, "with the switch off the button stays the ring's")
    }

    @Test
    fun aReportThatTheButtonIsHeldIsAHoldAndSendsNoKey() {
        controller(allMapped).burst(1, held = true, at = 10_000)

        assertEquals(emptyList(), keys.sent, "a hold is the recording it always was")
    }

    @Test
    fun aTapThenAHoldIsAClickHoldAndSendsNoKey() {
        // The ring counts the held press with the tap that came before it: two presses, the button down.
        controller(allMapped).burst(1, 2, held = true, at = 10_000)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun aGestureMappedToNothingSendsNoKey() {
        controller(RingButtonMapping(enabled = true, click = RingButtonAction.NOTHING)).burst(1)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun aGroupThatNeverClosedIsSettledOnTheSilenceOnlyOnceItHasPassed() {
        val controller = controller(allMapped)
        controller.collection(RingButtonRecord(held = false, count = 2), 5_003, final = false, atMillis = 10_000)

        controller.quiet(10_000 + RING_BURST_QUIET_MILLIS - 1)
        assertEquals(emptyList(), keys.sent, "still inside the quiet gap: the ring may still be writing")

        controller.quiet(10_000 + RING_BURST_QUIET_MILLIS)
        assertEquals(listOf(RingButtonAction.NEXT), keys.sent, "the silence settles the two taps it reported")
    }

    @Test
    fun aGroupThatNeverClosedIsDroppedRatherThanSettledWhenTheNextOneArrives() {
        val controller = controller(allMapped)
        controller.collection(RingButtonRecord(held = false, count = 2), 5_003, final = false, atMillis = 10_000)

        controller.burst(1, start = 5_010, at = 10_100)

        assertEquals(
            listOf(RingButtonAction.PLAY_PAUSE),
            keys.sent,
            "only the group the ring closed sent a key; the open one's count was never settled",
        )
    }

    @Test
    fun aCountThatIsNotAGestureSendsNothing() {
        val controller = controller(allMapped)

        controller.burst(0, start = 1, at = 1_000)
        controller.burst(4, start = 2, at = 2_000)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun forgettingADroppedPairingForgetsAHalfReadGesture() {
        val controller = controller(allMapped)
        controller.collection(RingButtonRecord(held = false, count = 2), 5_003, final = false, atMillis = 10_000)

        controller.forget()
        controller.quiet(20_000)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun aMediaSessionThatRefusesTheKeyDoesNotEscapeTheController() {
        val refusing = RingButtonController({ RingButtonMapping(enabled = true) }, RingMediaKeys { error("no media session") })

        refusing.burst(1)
    }
}
