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
 * Times are plain milliseconds of one clock. The longest window the page offers is ten seconds, which is the longest
 * the ring itself keeps a link (it drops one about ten seconds after a press), so the tests that matter here are the
 * ones at its edge: a press just inside it reuses the link, and a press one millisecond past it does not.
 */
class RingLinkWarmTest {

    private val window = RingWarmSettings(enabled = true, windowSeconds = 10)

    /** A link held since [lastUseMillis], with the setting on and the longest window the page offers. */
    private fun held(lastUseMillis: Long) = RingWarmLink(
        enabled = true,
        windowMillis = window.windowMillis,
        linked = true,
        lastUseMillis = lastUseMillis,
    )

    @Test
    fun aPressInsideTheWindowReusesTheHeldLinkAndRestartsTheClock() {
        val step = held(1_000).press(5_000)

        assertEquals(RingWarmAction.REUSE, step.action)
        assertTrue(step.link.linked)
        assertEquals(5_000L, step.link.lastUseMillis, "the press restarts the window")
        assertEquals(15_000L, step.link.quietEndsAtMillis())
    }

    @Test
    fun aWindowThatRunsOutClosesTheLink() {
        val step = held(1_000).lapsed(11_000)

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
        assertEquals(11_000L, step.link.quietEndsAtMillis(), "the same end, so the timer is set again for it")
    }

    @Test
    fun aPressAfterTheWindowHasRunOutReconnects() {
        // The link is still listed as held: the timer that would have closed it has not run yet. One link at a time,
        // so the stale one is closed before the press builds its own.
        val step = held(1_000).press(11_001)

        assertEquals(RingWarmAction.CLOSE_THEN_CONNECT, step.action)
        assertEquals(11_001L, step.link.lastUseMillis)

        // And the press it connects for is inside the new window, so the one after that reuses it.
        assertEquals(RingWarmAction.REUSE, step.link.press(11_001 + window.windowMillis - 1).action)
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
        // A press at 0, its session ending at 2 s: the window is 10 s of quiet from the end, not from the press.
        val pressed = RingWarmLink(enabled = true, windowMillis = window.windowMillis).press(0)
        val ended = pressed.link.ended(2_000)

        assertEquals(RingWarmAction.HOLD, ended.action)
        assertEquals(12_000L, ended.link.quietEndsAtMillis())
        assertEquals(RingWarmAction.REUSE, ended.link.press(11_999).action, "still inside the window")
        assertEquals(RingWarmAction.CLOSE_THEN_CONNECT, ended.link.press(12_000).action, "the moment it is spent")
    }

    @Test
    fun theWindowIsTheOneThePageOffersAndNothingElse() {
        assertEquals(8_000L, RingWarmSettings(enabled = true).windowMillis, "eight seconds unless asked otherwise")
        assertEquals(8_000L, RingWarmSettings(enabled = true, windowSeconds = 8).windowMillis)
        assertEquals(10_000L, RingWarmSettings(enabled = true, windowSeconds = 10).windowMillis)
        assertEquals(8_000L, RING_WARM_DEFAULT_SECONDS * 1_000L)
        assertEquals(listOf(8, 10), RING_WARM_WINDOWS_SECONDS)
        assertTrue(RING_WARM_WINDOWS_SECONDS.all { it * 1_000L <= 10_000L }, "no window outlasts the ring's own ten seconds")
        assertEquals(window.windowMillis, RingWarmLink().withSettings(window).windowMillis, "the settings are read on every event")
    }

    @Test
    fun aStoredTwentyOrThirtyReadsAsTheLongestWindowTheRingKeeps() {
        // What this setting wrote before the ring was measured dropping the link itself. The user asked for the
        // longest hold they were offered; ten seconds is the longest hold there is, so that is what they get.
        assertEquals(10_000L, RingWarmSettings(enabled = true, windowSeconds = 20).windowMillis)
        assertEquals(10_000L, RingWarmSettings(enabled = true, windowSeconds = 30).windowMillis)
        assertEquals(10_000L, RingWarmSettings(enabled = true, windowSeconds = 3_600).windowMillis)

        // Below the shortest window the page offers: eight, the nearest one above it.
        assertEquals(8_000L, RingWarmSettings(enabled = true, windowSeconds = 0).windowMillis)
        assertEquals(8_000L, RingWarmSettings(enabled = true, windowSeconds = 5).windowMillis)
    }
}
