package com.anopticlabs.gravel.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The page's plain values: the switch starts off, each tap reaches its own destination, and the Settings entry exists
 * only while a ring is paired.
 */
class RingButtonFactsTest {

    @Test
    fun theDefaultIsOffAndEachTapHasItsOwnDestination() {
        val facts = RingButtonFacts()

        assertFalse(facts.enabled, "the ring's button stays the ring's until the user says otherwise")
        assertEquals(RingButtonActionChoice.PLAY_PAUSE, facts.actionFor(RingButtonTap.CLICK))
        assertEquals(RingButtonActionChoice.NEXT, facts.actionFor(RingButtonTap.DOUBLE_CLICK))
        assertEquals(RingButtonActionChoice.PREVIOUS, facts.actionFor(RingButtonTap.TRIPLE_CLICK))
    }

    @Test
    fun aTapChangesOnlyItsOwnDestination() {
        val changed = RingButtonFacts().withAction(RingButtonTap.DOUBLE_CLICK, RingButtonActionChoice.NOTHING)

        assertEquals(RingButtonActionChoice.NOTHING, changed.actionFor(RingButtonTap.DOUBLE_CLICK))
        assertEquals(RingButtonActionChoice.PLAY_PAUSE, changed.actionFor(RingButtonTap.CLICK))
        assertEquals(RingButtonActionChoice.PREVIOUS, changed.actionFor(RingButtonTap.TRIPLE_CLICK))
        assertFalse(changed.enabled, "choosing a destination does not turn the switch on")
    }

    @Test
    fun everyTapCanReachEveryDestinationIncludingNothing() {
        for (tap in RingButtonTap.entries) {
            for (action in RingButtonActionChoice.entries) {
                assertEquals(action, RingButtonFacts().withAction(tap, action).actionFor(tap), "$tap -> $action")
            }
        }
    }

    @Test
    fun theSettingsItemExistsOnlyWhileARingIsPaired() {
        assertTrue(ringButtonItemShown(paired = true))
        assertFalse(ringButtonItemShown(paired = false), "with no ring there is no button to configure")
    }
}
