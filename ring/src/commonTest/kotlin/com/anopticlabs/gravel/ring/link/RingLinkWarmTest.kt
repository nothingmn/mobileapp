package com.anopticlabs.gravel.ring.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The warm-link decision, on a host: the five behaviours Settings > General > Ring button's switch promises, and the
 * arithmetic behind them. None of it needs a ring, which is the point of keeping the decision out of the GATT layer —
 * what is left for a phone is the GATT connection itself (see DESIGN_NOTES, "A warm link between presses").
 *
 * Times are plain milliseconds of one clock. A window of 20 s is [RingWarmSettings]'s default; the keeper starts its
 * quiet clock when a press is served and restarts it when the next press arrives.
 */
class RingLinkWarmTest {

    private val window = RingWarmSettings(enabled = true, windowSeconds = 20)

    /** A link held since [lastUseMillis], with the setting on and the default window. */
    private fun held(lastUseMillis: Long) = RingWarmLink(
        enabled = true,
        windowMillis = window.windowMillis,
        linked = true,
        lastUseMillis = lastUseMillis,
    )

    @Test
    fun aPressInsideTheWindowReusesTheHeldLinkAndRestartsTheClock() {
        val step = held(1_000).press(15_000)

        assertEquals(RingWarmAction.REUSE, step.action)
        assertTrue(step.link.linked)
        assertEquals(15_000L, step.link.lastUseMillis, "the press restarts the window")
        assertEquals(35_000L, step.link.quietEndsAtMillis())
    }

    @Test
    fun aWindowThatRunsOutClosesTheLink() {
        val step = held(1_000).lapsed(21_000)

        assertEquals(RingWarmAction.CLOSE, step.action)
        assertFalse(step.link.linked)
        assertNull(step.link.lastUseMillis)
        assertNull(step.link.quietEndsAtMillis(), "nothing is held, so no timer is set")
    }

    @Test
    fun aWindowThatHasNotRunOutLeavesTheTimerWhereItWas() {
        val step = held(1_000).lapsed(5_000)

        assertEquals(RingWarmAction.HOLD, step.action)
        assertEquals(1_000L, step.link.lastUseMillis)
        assertEquals(21_000L, step.link.quietEndsAtMillis(), "the same end, so the timer is set again for it")
    }

    @Test
    fun aPressAfterTheWindowHasRunOutReconnects() {
        // The link is still listed as held: the timer that would have closed it has not run yet. One link at a time,
        // so the stale one is closed before the press builds its own.
        val step = held(1_000).press(21_001)

        assertEquals(RingWarmAction.CLOSE_THEN_CONNECT, step.action)
        assertEquals(21_001L, step.link.lastUseMillis)

        // And the press it connects for is inside the new window, so the one after that reuses it.
        assertEquals(RingWarmAction.REUSE, step.link.press(21_001 + window.windowMillis - 1).action)
    }

    @Test
    fun aPressWithNothingHeldConnects() {
        val step = RingWarmLink(enabled = true, windowMillis = window.windowMillis).press(4_000)

        assertEquals(RingWarmAction.CONNECT, step.action)
        assertTrue(step.link.linked)
    }

    @Test
    fun withTheSettingOffTheLinkClosesAsTheSessionEndsAndTheNextPressConnectsAgain() {
        val warm = held(1_000).copy(enabled = false)

        val ended = warm.ended(9_000)
        assertEquals(RingWarmAction.CLOSE, ended.action, "off is the behaviour before the setting existed: close now")
        assertFalse(ended.link.linked)

        // A link still held when the switch went off is given up on the next press rather than handed over.
        val press = held(1_000).copy(enabled = false).press(2_000)
        assertEquals(RingWarmAction.CLOSE_THEN_CONNECT, press.action)
    }

    @Test
    fun aDroppedLinkEndsTheWindowWithNothingLeftToClose() {
        val lost = held(1_000).lost()

        assertEquals(RingWarmAction.NOTHING, lost.action)
        assertFalse(lost.link.linked)
        assertNull(lost.link.lastUseMillis)

        // The timer that was running for that window finds nothing to close, and the next press connects.
        assertEquals(RingWarmAction.NOTHING, lost.link.lapsed(30_000).action)
        assertEquals(RingWarmAction.CONNECT, lost.link.press(30_000).action)
    }

    @Test
    fun theSessionEndStartsTheWindowAndAPressInsideItRestartsIt() {
        // A press at 0, its session ending at 8 s: the window is 20 s of quiet from the end, not from the press.
        val pressed = RingWarmLink(enabled = true, windowMillis = window.windowMillis).press(0)
        val ended = pressed.link.ended(8_000)

        assertEquals(RingWarmAction.HOLD, ended.action)
        assertEquals(28_000L, ended.link.quietEndsAtMillis())
        assertEquals(RingWarmAction.REUSE, ended.link.press(25_000).action, "still inside the window")
        assertEquals(RingWarmAction.CLOSE_THEN_CONNECT, ended.link.press(28_001).action, "one millisecond past it")
    }

    @Test
    fun theWindowIsTheOneThePageOffersAndNothingElse() {
        assertEquals(20_000L, RingWarmSettings(enabled = true).windowMillis, "twenty seconds unless asked otherwise")
        assertEquals(30_000L, RingWarmSettings(enabled = true, windowSeconds = 30).windowMillis)
        assertEquals(20_000L, RingWarmSettings(enabled = true, windowSeconds = 45).windowMillis, "a window the page does not offer reads as the default")
        assertEquals(20_000L, RING_WARM_DEFAULT_SECONDS * 1_000L)
        assertEquals(listOf(20, 30), RING_WARM_WINDOWS_SECONDS)
        assertEquals(window.windowMillis, RingWarmLink().withSettings(window).windowMillis, "the settings are read on every event")
    }
}
