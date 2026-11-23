package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.button.RING_SEQUENCE_GAP_MILLIS
import com.anopticlabs.gravel.ring.button.RING_TAP_MILLIS
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonController
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.button.RingMediaKeys
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Where the button and the sync meet: an advertisement whose only reason to sync is the button being down either
 * starts a session (the button is the ring's, as today) or does not (the switch has taken the tap over).
 */
class RingButtonRunnerTest {
    private val address = "A1:B2:C3:D4:E5:F6"
    private val paired = MutableStateFlow<String?>(address)
    private val heard = Channel<RingAdvertisement>(Channel.UNLIMITED)
    private val synced = mutableListOf<Int>()
    private var imported = 0
    private val sent = mutableListOf<RingButtonAction>()

    private val keys = RingMediaKeys { sent += it }

    private fun TestScope.runner(mapping: RingButtonMapping) = RingSyncRunner(
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
        buttons = RingButtonController({ mapping }, keys),
        clock = { testScheduler.currentTime },
        marks = testScheduler.timeSource,
        // Every advertisement below is meant to be acted on: the hold between sessions is not what is under test.
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

    private fun TestScope.tap() {
        hear(4, recording = true)
        advanceTimeBy(300.milliseconds)
        hear(4)
        advanceTimeBy(RING_SEQUENCE_GAP_MILLIS.milliseconds + 100.milliseconds)
        hear(4)
    }

    @Test
    fun aTapTheSwitchHasTakenOverStartsNoSyncAndSendsPlayPause() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        tap()

        assertEquals(before, synced.size, "the tap started no session, so no empty recording was read")
        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), sent)
    }

    @Test
    fun aTapWithTheSwitchOffSyncsAsTodayAndSendsNoKey() = runTest {
        runner(RingButtonMapping(enabled = false))
        firstHearing()
        val before = synced.size

        tap()

        assertEquals(before + 1, synced.size, "today's empty recording: the recording flag alone starts a sync")
        assertEquals(emptyList(), sent, "and the button stays the ring's")
    }

    @Test
    fun aHoldIsStillSyncedAndSendsNoKey() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        hear(4, recording = true)
        advanceTimeBy(1.seconds)
        hear(4, recording = true)

        assertEquals(before + 1, synced.size, "a press this long is a recording, and is read as it always was")
        assertEquals(emptyList(), sent)
    }

    @Test
    fun aTapWhoseAdvertisementAlsoCarriesANewCountIsStillSynced() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        hear(4, recording = true)
        advanceTimeBy(1.seconds)
        hear(5)

        assertEquals(before + 1, synced.size, "the count changed, so something was recorded and is not stranded")
    }

    @Test
    fun anAdvertisementWhoseFlagDidNotChangeNeitherPressesNorSyncs() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        repeat(3) {
            advanceTimeBy(1.seconds)
            hear(4)
        }

        assertEquals(before, synced.size)
        assertEquals(emptyList(), sent)
    }

    @Test
    fun aPressStillShorterThanTheTapWindowSuppressesTheSync() = runTest {
        runner(RingButtonMapping(enabled = true))
        firstHearing()
        val before = synced.size

        hear(4, recording = true)
        advanceTimeBy(RING_TAP_MILLIS)
        hear(4, recording = true)

        assertEquals(before, synced.size, "still inside the tap window at the second advertisement")
    }
}
