@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.button.RING_BUTTON_QUIET_MILLIS
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonController
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.button.RingMediaKeys
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.RingButtonRecord
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * No press is lost because a link is being held.
 *
 * Measured on the operator's phone with the keep-the-ring-connected window at 8 s: a tap was served 1159 ms after its
 * advertisement, 243 ms to the button record and 151 ms to the key, and the session's end left the link up. A tap
 * made right after it produced **no log line at all**; the ring was heard again 34 s later. The reason is structural
 * and lives in two places this test puts together: an advertisement is the only thing that starts a session — the
 * runner reads `advertisement.recording` to know a press happened (`RingSyncRunner.runFor`) — and a ring with a link
 * open does not advertise. So a link held past its press makes the next tap invisible, however long the window is.
 *
 * The ring below is that measured ring: it announces a tap by advertising with the recording bit set, says nothing
 * while a link is open, and keeps a press it could not announce until the link closes. Two taps in a row must both
 * reach the media keys, and the link must be gone between them — a press path that holds it fails here.
 */
class RingPressNotLostTest {
    private val address = "A1:B2:C3:D4:E5:F6"
    private val fingerprint = 0x1234_FFFFL

    /**
     * The paired ring as the measurements found it. [tap] is the operator's finger; [open] and [close] are the app's
     * link. A press arriving while the link is up is announced only once that link is gone — the ring cannot
     * advertise and hold a connection at the same time.
     */
    private class MeasuredRing(private val fingerprint: Long) {
        private val heard = Channel<RingAdvertisement>(Channel.UNLIMITED)
        val advertisements: Flow<RingAdvertisement> = heard.receiveAsFlow()

        var linked = false
            private set
        private var unannounced = false

        fun tap() {
            if (linked) unannounced = true else heard.trySend(advertisement())
        }

        fun open() {
            linked = true
        }

        fun close() {
            linked = false
            if (unannounced) {
                unannounced = false
                heard.trySend(advertisement())
            }
        }

        /** A tap starts a one-collection recording, so the recording bit is what says a press happened. */
        private fun advertisement() = RingAdvertisement(fingerprint, collectionCount = 1, needsServicing = false, recording = true)
    }

    @Test
    fun aTapRightAfterAnotherIsServedBecauseNoLinkOutlivesItsPress() = runTest {
        val ring = MeasuredRing(fingerprint)
        val sent = mutableListOf<RingButtonAction>()
        val served = mutableListOf<Int>()
        val controller = RingButtonController(
            mapping = { RingButtonMapping(enabled = true) },
            keys = RingMediaKeys { sent += it },
            clock = { testScheduler.currentTime },
        )

        val runner = RingSyncRunner(
            paired = MutableStateFlow<String?>(address),
            hear = { ring.advertisements },
            sync = { _, advertisement, _ ->
                // A press's link, as the app opens it: connected for this press, the session reads the report, and
                // the link is closed before the press returns. The session's own read is stood in for by the
                // controller, which is the code that turns a report into a key.
                ring.open()
                try {
                    controller.collection(RingButtonRecord(held = false, count = 1), 0, final = true, atMillis = testScheduler.currentTime)
                    controller.quiet(testScheduler.currentTime + RING_BUTTON_QUIET_MILLIS)
                    served += advertisement.collectionCount
                } finally {
                    ring.close()
                }
                RingSessionEnd.SYNCED
            },
            lastCount = { null },
            positionReadable = { true },
            waitReason = { null },
            lastWait = { null },
            imported = { 0 },
            startOver = {},
            buttons = controller,
            clock = { testScheduler.currentTime },
            marks = testScheduler.timeSource,
            // The 30 s that follow a sync are for advertisements, not for presses; leaving them in would hide the
            // press behind a different rule than the one under test.
            syncHold = Duration.ZERO,
        )
        runner.start(backgroundScope)

        ring.tap()
        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(1, served.size, "the first tap starts a session and its key follows")
        assertFalse(ring.linked, "the link is closed with the press that opened it")

        // The second tap, right after the first: inside the window the removed setting offered to hold the link for.
        ring.tap()
        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(listOf(1, 1), served, "the second tap is read too, and within three seconds")
        assertEquals(2, sent.size, "two taps, two keys")
        assertEquals(listOf(RingButtonAction.PLAY_PAUSE, RingButtonAction.PLAY_PAUSE), sent)
    }
}
