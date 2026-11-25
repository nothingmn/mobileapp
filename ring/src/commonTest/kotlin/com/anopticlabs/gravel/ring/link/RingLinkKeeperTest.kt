@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.anopticlabs.gravel.ring.link

import com.anopticlabs.gravel.ring.button.RingPressTiming
import com.anopticlabs.gravel.ring.sync.RingNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The keeper around the pure decision: which link a press is given, when the link is closed, and that nothing is left
 * open or waiting when a press ends badly or the ring vanishes.
 *
 * What a host can prove is everything except the GATT connection itself: [FakeHeld] stands in for a connected ring,
 * the virtual clock stands in for the quiet window, and [opened] counts the connects. What is left for the phone is
 * that Kable's connect and the ring's own disconnect behave as the fake does — a warm window that only pays off on a
 * real Index 01 (DESIGN_NOTES, "A warm link between presses").
 */
class RingLinkKeeperTest {

    private val address = "A1:B2:C3:D4:E5:F6"

    /** The links the keeper opened, in order; each close counted, so a link is proved to be closed exactly once. */
    private val opened = mutableListOf<FakeHeld>()
    private var settings = RingWarmSettings(enabled = true, windowSeconds = 20)
    private var refusal: Exception? = null

    private class FakeHeld : RingHeldConnection {
        override val notifications = Channel<RingNotification>(Channel.UNLIMITED)
        private val _ended = MutableStateFlow<RingLinkException?>(null)
        override val ended: StateFlow<RingLinkException?> = _ended
        var closes = 0
        var pending = 0

        override fun drain(): Int = pending.also { pending = 0 }

        override suspend fun close() {
            closes++
        }

        override suspend fun writeControl(bytes: ByteArray) = Unit

        override suspend fun writeData(bytes: ByteArray) = Unit

        /** The ring goes out of range, or hangs up: what a real link reports through [ended]. */
        fun drop() {
            _ended.value = RingLinkException(133, null)
        }
    }

    private fun TestScope.keeper(timing: RingPressTiming = RingPressTiming { testScheduler.currentTime }) = RingLinkKeeper(
        scope = backgroundScope,
        settings = { settings },
        open = { _ ->
            refusal?.let { throw it }
            FakeHeld().also { opened += it }
        },
        timing = timing,
        clock = { testScheduler.currentTime },
    )

    private val TestScope.now: Long get() = testScheduler.currentTime

    @Test
    fun aPressInsideTheWindowReusesTheHeldLink() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        advanceTimeBy(5.seconds)
        runCurrent()

        var served: RingHeldConnection? = null
        keeper.withLink(address, now) { served = it }

        assertEquals(1, opened.size, "one connect for two presses")
        assertSame(opened.single(), served)
        assertEquals(0, opened.single().closes)
        assertTrue(keeper.holding)
    }

    @Test
    fun theWindowLapsesAndTheLinkIsClosedThen() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        advanceTimeBy(19.seconds)
        runCurrent()
        assertEquals(0, opened.single().closes, "still inside the 20 s window")
        assertTrue(keeper.holding)

        advanceTimeBy(1.seconds)
        runCurrent()

        assertEquals(1, opened.single().closes, "the quiet window is over")
        assertFalse(keeper.holding)
    }

    @Test
    fun aPressAfterTheWindowReconnects() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        advanceTimeBy(20.seconds)
        runCurrent()

        keeper.withLink(address, now) { }

        assertEquals(2, opened.size, "the lapsed link is not handed to a press")
        assertEquals(1, opened.first().closes)
        assertEquals(0, opened.last().closes)
    }

    @Test
    fun eachPressRestartsTheClock() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        advanceTimeBy(15.seconds)
        runCurrent()
        keeper.withLink(address, now) { }
        advanceTimeBy(15.seconds)
        runCurrent()

        assertEquals(1, opened.size, "the second press moved the window's end")
        assertEquals(0, opened.single().closes)

        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, opened.single().closes, "20 s after the last press")
    }

    @Test
    fun withTheSettingOffTheLinkIsClosedAsTheSessionEnds() = runTest {
        settings = RingWarmSettings.Off
        val keeper = keeper()
        keeper.withLink(address, now) { }

        assertEquals(1, opened.single().closes, "closed before the press returned, as before the setting existed")
        assertFalse(keeper.holding)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, opened.single().closes, "and no timer was left behind")
    }

    @Test
    fun aPressWithNoLinkIsGivenAFreshOneEachTime() = runTest {
        settings = RingWarmSettings.Off
        val keeper = keeper()
        keeper.withLink(address, now) { }
        advanceTimeBy(1.seconds)
        keeper.withLink(address, now) { }

        assertEquals(2, opened.size)
        assertEquals(listOf(1, 1), opened.map { it.closes })
    }

    @Test
    fun aLinkTheRingDroppedEndsTheWindowAndIsReleased() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        val held = opened.single()
        held.drop()
        runCurrent()

        assertEquals(1, held.closes, "released once, without waiting for the window")
        assertFalse(keeper.holding)

        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(1, held.closes, "the timer that was running did not close it a second time")

        keeper.withLink(address, now) { }
        assertEquals(2, opened.size, "and the next press connects")
    }

    @Test
    fun aSessionThatFailsDoesNotLeaveTheLinkHeld() = runTest {
        val keeper = keeper()
        val failed = assertFailsWith<IllegalStateException> {
            keeper.withLink(address, now) { error("the session broke") }
        }

        assertEquals("the session broke", failed.message)
        assertEquals(1, opened.single().closes, "a link that failed a sync is not the link the next press wants")
        assertFalse(keeper.holding)
    }

    @Test
    fun aConnectThatFailsLeavesNothingHeld() = runTest {
        refusal = IllegalStateException("no Bluetooth")
        val keeper = keeper()

        assertFailsWith<IllegalStateException> { keeper.withLink(address, now) { } }
        assertFalse(keeper.holding)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun theSettingTurningOffClosesAHeldLinkAndSaysWhy() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        settings = RingWarmSettings.Off

        keeper.closeNow("the setting was turned off")

        assertEquals(1, opened.single().closes)
        assertFalse(keeper.holding)
    }

    @Test
    fun aPressOnAnotherRingIsNotServedByTheLinkHeldForThisOne() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }

        keeper.withLink("11:22:33:44:55:66", now) { }

        assertEquals(2, opened.size)
        assertEquals(1, opened.first().closes, "the link to the old ring is closed before another is opened")
    }

    @Test
    fun notificationsLeftWhileTheLinkWasIdleAreDrainedBeforeThePress() = runTest {
        val keeper = keeper()
        keeper.withLink(address, now) { }
        opened.single().pending = 3

        keeper.withLink(address, now) { }

        assertEquals(0, opened.single().pending, "the session would refuse them as bytes between requests")
    }

    @Test
    fun aFreshPressHasAConnectedMarkAndAReusedOneDoesNot() = runTest {
        val timing = RingPressTiming { testScheduler.currentTime }
        val keeper = keeper(timing)
        keeper.withLink(address, now) { }
        val fresh = timing.marks(now)
        assertEquals(testScheduler.currentTime, fresh.connectedAtMillis, "the link was up at this instant")
        assertFalse(fresh.linkReused)

        advanceTimeBy(5.seconds)
        runCurrent()
        keeper.withLink(address, now) { }
        val reused = timing.marks(now)
        assertNull(reused.connectedAtMillis, "there was no connect of this press to time")
        assertTrue(reused.linkReused)
    }
}
