package com.anopticlabs.gravel.ring.button

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The button's matrix, at the boundaries. Every timing here is an advertisement arrival, which is all the
 * classifier's source ever sees: the ring's `recording` bit, sampled.
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
        // it: a short press that begins [RING_TAP_MILLIS] before it ends began well before its edge.
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
    fun aSyncThatProducedARecordingRetractsTheMediaGestureAndOneThatProducedNoneDoesNot() {
        val silent = RingButtonClassifier()
        silent.onPress(short(1_100))
        silent.onTick(1_800)
        assertFalse(silent.onSyncResult(0))
        assertNull(silent.lastGesture(), "the result consumes the gesture")

        val recorded = RingButtonClassifier()
        recorded.onPress(short(1_100))
        recorded.onTick(1_800)
        assertTrue(recorded.onSyncResult(1))

        val held = RingButtonClassifier()
        held.onPress(long(2_000))
        assertFalse(held.onSyncResult(1), "a hold is a recording, not a media gesture to retract")
        assertFalse(RingButtonClassifier().onSyncResult(1), "nothing to retract")
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

/** The advertisement source: the edges of the `recording` bit, and the measurement they allow. */
class RingAdvertisementPressesTest {

    @Test
    fun aPressBetweenTwoAdvertisementsIsMeasuredAtTheirDistance() {
        val presses = RingAdvertisementPresses()

        assertNull(presses.onAdvertisement(1_000, true), "the press opens")
        val press = presses.onAdvertisement(1_200, false)
        assertNotNull(press)

        assertEquals(RingButtonPress.Kind.SHORT, press.kind)
        assertEquals(1_200, press.atMillis)
        assertEquals(200, press.durationMillis)
    }

    @Test
    fun aPressAtExactlyTheTapWindowIsShortAndOneMillisecondMoreIsLong() {
        val atTheWindow = RingAdvertisementPresses()
        atTheWindow.onAdvertisement(1_000, true)
        val oneMillisecondMore = RingAdvertisementPresses()
        oneMillisecondMore.onAdvertisement(1_000, true)

        assertEquals(
            RingButtonPress.Kind.SHORT,
            atTheWindow.onAdvertisement(1_000 + RING_TAP_MILLIS, false)?.kind,
        )
        assertEquals(
            RingButtonPress.Kind.LONG,
            oneMillisecondMore.onAdvertisement(1_001 + RING_TAP_MILLIS, false)?.kind,
        )
    }

    @Test
    fun anAdvertisementWhoseFlagDidNotChangeIsNoPress() {
        val presses = RingAdvertisementPresses()

        assertNull(presses.onAdvertisement(1_000, false))
        assertNull(presses.onAdvertisement(1_500, false))
        assertNull(presses.onAdvertisement(2_000, true), "the press opens")
        assertNull(presses.onAdvertisement(2_100, true), "still down")
    }

    @Test
    fun aPressIsATapOnlyWhileItIsShort() {
        val presses = RingAdvertisementPresses()
        presses.onAdvertisement(1_000, true)

        assertTrue(presses.tapInProgress(1_000 + RING_TAP_MILLIS), "at the window the press is still a tap")
        assertFalse(presses.tapInProgress(1_001 + RING_TAP_MILLIS), "past it the button is recording")
        presses.onAdvertisement(2_000, false)
        assertFalse(presses.tapInProgress(2_100), "nothing is pressed")
    }

    @Test
    fun aPressTheScanNeverSawCloseIsForgotten() {
        val presses = RingAdvertisementPresses()
        presses.onAdvertisement(1_000, true)
        presses.forget()

        assertFalse(presses.tapInProgress(1_100), "the open press is gone")
        assertNull(presses.onAdvertisement(2_000, false), "so the release closes nothing")
    }

    @Test
    fun theTapWindowAndTheGapAreTheStatedConstants() {
        assertEquals(700L, RING_TAP_MILLIS)
        assertEquals(700L, RING_SEQUENCE_GAP_MILLIS)
        assertTrue(RING_TAP_MILLIS < 1_000, "under the Notebook's 1000 ms tap floor")
    }
}

/** The whole path: advertisements in, media keys out, through the mapping the user set. */
class RingButtonControllerTest {

    private class Keys : RingMediaKeys {
        val sent = mutableListOf<RingButtonAction>()
        override fun send(action: RingButtonAction) {
            sent += action
        }
    }

    private val keys = Keys()

    private fun controller(mapping: RingButtonMapping) = RingButtonController({ mapping }, keys)

    private fun RingButtonController.tap(down: Long, up: Long, at: Long = up + RING_SEQUENCE_GAP_MILLIS) {
        advertisement(down, true)
        advertisement(up, false)
        advertisement(at, false)
    }

    @Test
    fun aTapSendsTheKeyItsGestureIsMappedTo() {
        controller(RingButtonMapping(enabled = true)).tap(1_000, 1_100)

        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), keys.sent)
    }

    @Test
    fun twoTapsAndThreeTapsSendWhatTheirMappingSays() {
        val mapped = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.PLAY_PAUSE,
            doubleClick = RingButtonAction.NEXT,
            tripleClick = RingButtonAction.PREVIOUS,
        )
        val controller = controller(mapped)

        controller.advertisement(1_000, true)
        controller.advertisement(1_050, false)
        controller.advertisement(1_100, true)
        controller.advertisement(1_150, false)
        controller.advertisement(1_850, false)
        controller.advertisement(2_000, true)
        controller.advertisement(2_050, false)
        controller.advertisement(2_100, true)
        controller.advertisement(2_150, false)
        controller.advertisement(2_200, true)
        controller.advertisement(2_250, false)

        assertEquals(listOf(RingButtonAction.NEXT, RingButtonAction.PREVIOUS), keys.sent)
    }

    @Test
    fun aGestureMappedToNothingSendsNoKey() {
        controller(RingButtonMapping(enabled = true, click = RingButtonAction.NOTHING)).tap(1_000, 1_100)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun withTheSwitchOffNothingIsSentAndNoPressIsTakenOver() {
        val controller = controller(RingButtonMapping(enabled = false))
        controller.advertisement(1_000, true)

        assertFalse(controller.holdsCapture(1_100), "the button is left to the ring")
        controller.tap(1_000, 1_100)

        assertEquals(emptyList(), keys.sent)
    }

    @Test
    fun aHoldIsLeftToRecordingAndSendsNoKey() {
        val controller = controller(RingButtonMapping(enabled = true))
        controller.advertisement(1_000, true)
        controller.advertisement(2_000, false)

        assertEquals(emptyList(), keys.sent)
        assertFalse(controller.holdsCapture(1_800), "a press this long is a recording")
    }

    @Test
    fun theSwitchTakesOverATapAndOnlyATap() {
        val controller = controller(RingButtonMapping(enabled = true))
        controller.advertisement(1_000, true)

        assertTrue(controller.holdsCapture(1_000), "the first advertisement of the press")
        assertTrue(controller.holdsCapture(1_000 + RING_TAP_MILLIS), "still short")
        assertFalse(controller.holdsCapture(1_001 + RING_TAP_MILLIS), "now it is a recording")
    }

    @Test
    fun aMediaSessionThatRefusesTheKeyDoesNotEscapeTheController() {
        val refusing = RingButtonController({ RingButtonMapping(enabled = true) }, RingMediaKeys { error("no media session") })

        refusing.tap(1_000, 1_100)
    }
}
