@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.button.RING_BURST_QUIET_MILLIS
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonController
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.button.RingMediaKeys
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.RingButtonRecord
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Where the button and the sync meet, now that the advertisement carries no gesture.
 *
 * A press is only visible once connected — it is a record inside a collection — so the runner's job is to let a press
 * start a session even inside the hold the previous one left, and the session reads the gesture. The runner itself
 * sends no key: what it keeps of the button is the pairing change's forget.
 */
class RingButtonRunnerTest {
    private val address = "A1:B2:C3:D4:E5:F6"
    private val paired = MutableStateFlow<String?>(address)
    private val heard = Channel<RingAdvertisement>(Channel.UNLIMITED)
    private val synced = mutableListOf<Int>()
    private var imported = 0
    private val sent = mutableListOf<RingButtonAction>()

    private val keys = RingMediaKeys { sent += it }

    private fun TestScope.runner(
        mapping: RingButtonMapping,
        buttons: RingButtonController = RingButtonController({ mapping }, keys),
    ) = RingSyncRunner(
        paired = paired,
        hear = { heard.receiveAsFlow() },
        sync = { _, advertisement ->
            synced += advertisement.collectionCount
            RingSessionEnd.SYNCED
        },
        lastCount = { synced.lastOrNull() },
        positionReadable = { true },
        waitReason = { null },
        lastWait = { null },
        imported = { imported },
        startOver = {},
        buttons = buttons,
        clock = { testScheduler.currentTime },
        marks = testScheduler.timeSource,
        // The hold is put back by every sync; the press tests below are about the press crossing it.
        syncHold = Duration.ZERO,
    ).also { it.start(backgroundScope) }

    private fun ad(count: Int, recording: Boolean = false) =
        RingAdvertisement(0x1234_FFFF, count, needsServicing = false, recording = recording)

    /** One advertisement at the current virtual time, and everything it set off. */
    private fun TestScope.hear(count: Int, recording: Boolean = false) {
        heard.trySend(ad(count, recording))
        runCurrent()
    }

    /** The ring is heard once with nothing new, which is the runner's first-hearing sync, and then settles. */
    private fun TestScope.firstHearing() {
        hear(4)
        advanceTimeBy(30.seconds)
        runCurrent()
    }

    @Test
    fun aPressStartsASyncAndTheRunnerSendsNothingItself() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        hear(4, recording = true)

        assertEquals(before + 1, synced.size, "the press is read by connecting, so it starts a session")
        assertEquals(emptyList(), sent, "the key comes from the session's collections, not from the advertisement")
    }

    @Test
    fun aPressStartsASyncInsideTheHoldTheLastOneLeft() = runTest {
        // A press is not advertisement news that repeats: it is the one chance to read the button, so the 30 s that
        // keep the app from reconnecting after a sync do not apply to it. The session a sync starts puts the hold
        // back, so a burst of taps cannot open a session each.
        val held = RingSyncRunner(
            paired = paired,
            hear = { heard.receiveAsFlow() },
            sync = { _, advertisement ->
                synced += advertisement.collectionCount
                RingSessionEnd.SYNCED
            },
            lastCount = { synced.lastOrNull() },
            positionReadable = { true },
            waitReason = { null },
            lastWait = { null },
            imported = { imported },
            startOver = {},
            buttons = RingButtonController({ RingButtonMapping(enabled = true) }, keys),
            clock = { testScheduler.currentTime },
            marks = testScheduler.timeSource,
            syncHold = 30.seconds,
        ).also { it.start(backgroundScope) }

        hear(4)
        runCurrent()
        val before = synced.size

        hear(4, recording = true)

        assertEquals(before + 1, synced.size, "inside the hold, but a press is not held back")
        held.stop()
    }

    @Test
    fun withTheSwitchOffAPressIsStillReadAndSendsNoKey() = runTest {
        runner(RingButtonMapping(enabled = false))
        firstHearing()
        val before = synced.size

        hear(4, recording = true)

        assertEquals(before + 1, synced.size, "the ring's button is read the same way with the switch off")
        assertEquals(emptyList(), sent, "and the button stays the ring's")
    }

    @Test
    fun aPairingChangeForgetsAHalfReadGesture() = runTest {
        val controller = RingButtonController({ RingButtonMapping(enabled = true) }, keys)
        runner(RingButtonMapping(enabled = true), controller)
        controller.collection(RingButtonRecord(held = false, count = 2), 5_003, final = false, atMillis = 1_000)

        paired.value = null
        runCurrent()
        paired.value = address
        runCurrent()
        controller.quiet(1_000 + RING_BURST_QUIET_MILLIS)

        assertEquals(emptyList(), sent, "the gesture the old pairing reported is gone")
    }
}
