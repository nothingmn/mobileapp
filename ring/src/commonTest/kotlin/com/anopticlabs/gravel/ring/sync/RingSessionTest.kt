@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.anopticlabs.gravel.ring.sync

import com.anopticlabs.gravel.ring.button.RING_BUTTON_QUIET_MILLIS
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonController
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.button.RingMediaKeys
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.protocol.bytes
import com.anopticlabs.gravel.ring.protocol.collection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class RingSessionTest {

    private val ring = FakeRing()
    private val store = MemoryStore()
    private val sink = RecordingSink()
    private val idle = RingAdvertisement(0x1234_FFFF, 1, needsServicing = false, recording = false)

    private fun collectionReads() = ring.reads.filter { it and 0xFFFF_0000L == 0x4002_0000L }.map { (it and 0xFFFF).toInt() }

    private val keys = mutableListOf<RingButtonAction>()

    /** The session, with the button's keys collected in [keys]; the mapping defaults to the switch on. */
    private fun TestScope.session(
        maxRecordingMillis: Long = 5 * 60 * 1000L,
        mapping: RingButtonMapping = RingButtonMapping(enabled = true),
        sink: RingRecordingSink = this@RingSessionTest.sink,
    ) = RingSession(
        ring,
        store,
        sink,
        { 1_900_000_000 },
        maxRecordingMillis,
        testScheduler.timeSource,
        buttons = RingButtonController({ mapping }, RingMediaKeys { keys += it }),
        buttonClock = { testScheduler.currentTime },
    )

    private fun ringHolds(vararg parts: Pair<Int, ByteArray>) {
        parts.forEach { (index, bytes) -> ring.collections[index] = bytes }
        ring.start = parts.minOf { it.first }
        ring.end = parts.maxOf { it.first } + 1
    }

    @Test
    fun aFirstSyncReadsEveryStoredCollectionAndStoresTheRecordings() = runTest {
        ringHolds(5 to ringPart(5, true), 6 to ringPart(6, false), 7 to ringPart(6, true))
        ring.count = 8

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(5, 6, 7), collectionReads())
        assertEquals(listOf(10_000, 20_000), sink.stored.map { it.samples.size })
        assertEquals(listOf(5, 6), sink.firstIndexes)
        assertNull(ring.clockSet)
        assertEquals(8, store.next)
        assertEquals(8, store.count)
    }

    @Test
    fun aSyncResumesAtTheSavedPositionAndReadsNothingWhenItIsAtTheEnd() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true), 2 to ringPart(2, true))
        store.next = 2

        session().run(idle)
        assertEquals(listOf(2), collectionReads())

        ring.reads.clear()
        session().run(idle)
        assertEquals(emptyList(), collectionReads())
        assertEquals(1, sink.stored.size)
    }

    @Test
    fun aSavedPositionTheRingNoLongerHoldsRestartsAtTheRangesStart() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true))
        store.next = 40

        session().run(idle)

        assertEquals(listOf(0, 1), collectionReads())
        assertEquals(2, store.next)
    }

    @Test
    fun anUnfinishedRecordingKeepsThePositionAtItsFirstPartAndIsReadAgainNextTime() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, false))

        session().run(idle)
        assertEquals(1, store.next)
        assertEquals(1, sink.stored.size)

        ring.collections[3] = ringPart(1, true)
        ring.end = 4
        ring.reads.clear()
        session().run(idle)

        assertEquals(listOf(1, 2, 3), collectionReads())
        assertEquals(30_000, sink.stored.last().samples.size)
        assertEquals(4, store.next)
    }

    @Test
    fun aCollectionTheParserRefusesIsSkipped() = runTest {
        ringHolds(0 to collection(bytes(0x99, 0, 0)), 1 to ringPart(1, true))

        session().run(idle)

        assertEquals(1, sink.stored.size)
        assertEquals(2, store.next)
    }

    @Test
    fun aSyncCanceledWhileARecordingIsStoredStillSavesThePositionPastIt() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, true))
        lateinit var sync: Job
        // Finishes after the cancel on another dispatcher, as the Notebook's ingest does.
        sink.afterStoring = { withContext(NonCancellable + StandardTestDispatcher(testScheduler)) { sync.cancel() } }

        sync = launch { session().run(idle) }
        sync.join()

        assertTrue(sync.isCancelled)
        assertEquals(1, sink.stored.size)
        assertEquals(1, store.next)
    }

    @Test
    fun aRecordingTheSinkCannotStoreStopsTheSyncBeforeIt() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, true), 3 to ringPart(3, true))
        sink.accept = false
        store.next = 1

        assertEquals(RingSessionEnd.NOT_STORED, session().run(idle))

        assertEquals(1, store.next)
        assertEquals(listOf(1, 2), collectionReads())
        assertNull(store.count)
    }

    @Test
    fun anUnfinishedRecordingPastTheLimitIsDroppedWithItsLaterParts() = runTest {
        ringHolds(0 to ringPart(0, false), 1 to ringPart(0, false))

        session(maxRecordingMillis = 1_500).run(idle)
        assertEquals(2, store.next)

        ringHolds(0 to ringPart(0, false), 1 to ringPart(0, false), 2 to ringPart(0, true), 3 to ringPart(3, true))
        store.next = null
        session(maxRecordingMillis = 1_500).run(idle)

        assertEquals(listOf(3), sink.firstIndexes)
        assertEquals(4, store.next)
    }

    @Test
    fun anUnfinishedGroupIsBoundedInSamplesWhateverRateTheRingClaims() = runTest {
        ringHolds(0 to ringPart(0, false, rate = 100_000), 1 to ringPart(0, false, rate = 100_000), 2 to ringPart(0, false, rate = 100_000))

        session(maxRecordingMillis = 1_500).run(idle)

        assertEquals(3, store.next)
    }

    @Test
    fun aCollectionTheRingRefusesIsPassedWithTheRecordingItBelongedTo() = runTest {
        ringHolds(0 to ringPart(0, true), 1 to ringPart(1, false), 2 to ringPart(1, true), 3 to ringPart(3, true))
        ring.refuseCollections[2] = 0x05

        assertFailsWith<RingRefusedException> { session().run(idle) }
        assertEquals(3, store.next)

        ring.refuseCollections.clear()
        session().run(idle)
        assertEquals(listOf(0, 3), sink.firstIndexes)
    }

    @Test
    fun aRefusedLastCollectionLeavesTheOpenRecordingToBeReadAgain() = runTest {
        ringHolds(0 to ringPart(1, false), 1 to collection(bytes(0x99, 0, 0)))

        session().run(idle)

        assertEquals(0, store.next)
    }

    @Test
    fun aSyncReadsAcrossTheIndexWrap() = runTest {
        ring.collections[65534] = ringPart(65534, false)
        ring.collections[65535] = ringPart(65534, false)
        ring.collections[0] = ringPart(65534, true)
        ring.start = 65534
        ring.end = 1

        session().run(idle)

        assertEquals(listOf(65534, 65535, 0), collectionReads())
        assertEquals(listOf(65534), sink.firstIndexes)
        assertEquals(1, store.next)
    }

    @Test
    fun aRingAskingForServiceGetsItsClockSetFirst() = runTest {
        ringHolds(0 to ringPart(0, true))

        session().run(idle.copy(needsServicing = true))

        assertEquals(1_900_000_000, ring.clockSet)
        assertEquals(0x4003_0001L, ring.reads.first())
    }

    @Test
    fun whileTheRingIsRecordingTheSessionPollsAndReadsWhatArrives() = runTest {
        ringHolds(0 to ringPart(0, false))
        ring.recording = true
        var polls = 0
        ring.beforeAdvertisementRead = {
            polls++
            if (polls == 2) {
                ring.collections[1] = ringPart(0, true)
                ring.end = 2
                ring.recording = false
            }
        }

        assertEquals(RingSessionEnd.SYNCED, session().run(idle.copy(recording = true)))

        assertEquals(20_000, sink.stored.single().samples.size)
        assertEquals(2, store.next)
    }

    @Test
    fun aRingThatNeverStopsRecordingEndsTheSessionAtItsLimit() = runTest {
        ringHolds(0 to ringPart(0, false))
        ring.recording = true

        assertEquals(RingSessionEnd.STILL_RECORDING, session().run(idle))

        assertTrue(testScheduler.currentTime >= 10.minutes.inWholeMilliseconds)
        assertNull(store.count)
    }

    @Test
    fun aReplyBeforeItsDataIsReadTheSame() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.replyFirst = true

        session().run(idle)

        assertEquals(1, sink.stored.size)
    }

    @Test
    fun aRefusedRequestEndsTheSessionWithThePositionUnchanged() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.refuseStatus = 0x43
        store.next = 0

        val refused = assertFailsWith<RingRefusedException> { session().run(idle) }

        assertEquals(0x43, refused.status)
        assertEquals(0, store.next)
    }

    @Test
    fun aRingThatDoesNotAnswerTimesOut() = runTest {
        ring.silent = true

        assertFailsWith<RingTimeoutException> { session().run(idle) }
        assertEquals(10.seconds.inWholeMilliseconds, testScheduler.currentTime)
    }

    @Test
    fun bytesArrivingBetweenRequestsEndTheSession() = runTest {
        ringHolds(0 to ringPart(0, true))
        ring.stray()

        assertFailsWith<RingProtocolException> { session().run(idle) }
        assertEquals(emptyList(), ring.reads)
    }

    @Test
    fun aTapSendsItsKeyAndEveryCollectionItWasWrittenOnGoesToTheSink() = runTest {
        // The ring attaches its button report to collections that carry audio, so the report cannot be a reason to
        // withhold one. Both collections here reach the sink; the Notebook drops the one-millisecond tap for its
        // length, which is the drop `anEmptyTapIsDroppedByTheSinkForItsLength` proves happens there.
        ringHolds(0 to ringButton(0, final = true, count = 1), 1 to ringPart(1, true))
        ring.count = 2

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(0, 1), sink.firstIndexes, "the tap's collection is read and handed over like any other")
        assertEquals(listOf(10, 10_000), sink.stored.map { it.samples.size }, "the tap's ten samples and the note after it")
        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), keys, "the tap reaches the media key it is mapped to")
        assertEquals(2, store.next, "the tap's collection is passed, so it is not read again")
        assertEquals(
            RING_BUTTON_QUIET_MILLIS,
            testScheduler.currentTime,
            "the key comes one quiet window after the ring's last report of the tap, which is the whole latency",
        )
    }

    @Test
    fun anEmptyTapIsDroppedByTheSinkForItsLengthNotByTheSession() = runTest {
        // What the tap costs now: one collection read, one finished recording handed over, and the Notebook's own
        // floor dropping it as it always did. If the session withheld the collection instead, `handed` would be
        // missing the tap and a hold's closing part would go missing with it.
        val floor = FloorSink(floorMillis = 1_000)
        ringHolds(0 to ringButton(0, final = true, count = 1), 1 to ringPart(1, true))

        assertEquals(RingSessionEnd.SYNCED, session(sink = floor).run(idle))

        assertEquals(listOf(10_000), floor.stored.map { it.samples.size }, "only the real note is kept")
        assertTrue(0 in floor.handed, "but the tap's collection was handed to the sink, which is where it is dropped")
        assertEquals(2, store.next, "and passed, so the next sync does not read it again")
        assertEquals(listOf(RingButtonAction.PLAY_PAUSE), keys)
    }

    @Test
    fun aHoldWhosePartsCarryButtonReportsIsOneRecordingHandedOver() = runTest {
        // The shape the regressed code lost: the ring marks every collection of a hold with its button report, and
        // the report says `held` is false on the first part (before the button went down) and on the last one (where
        // it came up). The last part is the one that closes the note, so discarding either loses the whole
        // recording — the operator's report, exactly.
        ringHolds(
            0 to ringButton(0, final = false, count = 1, held = false, millis = 1_000),
            1 to ringButton(0, final = false, count = 1, held = true, millis = 1_000),
            2 to ringButton(0, final = true, count = 1, held = false, millis = 1_000),
        )
        ring.count = 2

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(0), sink.firstIndexes, "one recording, starting at its first part")
        assertEquals(1, sink.stored.size)
        assertEquals(30_000, sink.stored.single().samples.size, "all three seconds of the hold are in it")
        assertEquals(3, store.next, "and the sync has moved past all three parts")
        assertEquals(emptyList(), keys, "a hold is the ring's own note and is left to it")
    }

    @Test
    fun aButtonReportInTheMiddleOfAGroupDoesNotLoseTheRecording() = runTest {
        // A report arriving between two parts of one group is the button's business only: the group keeps being
        // assembled, and the final part closes it. This one says the button is not held, the report the old code
        // treated as a reason to throw the group away.
        ringHolds(
            0 to ringPart(0, final = false),
            1 to ringButton(0, final = false, count = 1, held = false, millis = 1_000),
            2 to ringPart(0, final = true),
        )

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(1, sink.stored.size, "the report did not break the group")
        assertEquals(30_000, sink.stored.single().samples.size)
        assertEquals(listOf(0), sink.firstIndexes)
        assertEquals(3, store.next)
    }

    @Test
    fun aTripleTapMarkedFinalOnEveryCollectionIsOneGestureAndOneKey() = runTest {
        // The shape measured on the phone at 08:47:33: one collection per press, the ring's count climbing 1, 2, 3,
        // and every one of them final. Settling on final sent play/pause, then next, then previous for one gesture.
        ringHolds(
            0 to ringButton(0, final = true, count = 1),
            1 to ringButton(1, final = true, count = 2),
            2 to ringButton(2, final = true, count = 3),
        )
        ring.count = 3

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(RingButtonAction.PREVIOUS), keys, "one gesture, one key, and it is the triple's")
        assertEquals(
            listOf(0, 1, 2),
            sink.firstIndexes,
            "each press's collection is a recording the Notebook drops for its length; the session withholds none",
        )
        assertEquals(3, store.next)
    }

    @Test
    fun aHoldsCollectionKeepsItsAudioBecauseTheHoldIsTheNote() = runTest {
        ringHolds(0 to ringButton(0, final = true, count = 1, held = true, millis = 2_000))
        ring.count = 1

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(1, sink.stored.size, "a hold is still the note it always was")
        assertEquals(emptyList(), keys, "and a hold is never remapped to a media key")
    }

    @Test
    fun aGestureTheRingNeverClosedIsStillSentOnceTheSessionHasWaitedOutItsWindow() = runTest {
        // Nothing closes this report — it is not final and the ring is not recording — so the only thing that can
        // send it is the session's own wait on the way out.
        ringHolds(0 to ringButton(0, final = false, count = 2))
        ring.count = 1

        assertEquals(RingSessionEnd.SYNCED, session().run(idle))

        assertEquals(listOf(RingButtonAction.NEXT), keys, "the two taps the ring reported are sent, once")
        assertEquals(emptyList(), sink.firstIndexes)
        assertEquals(RING_BUTTON_QUIET_MILLIS, testScheduler.currentTime, "after one quiet window and no longer")
    }

    @Test
    fun aGestureTheRingNeverClosedIsSettledWhileTheSyncWaitsForIt() = runTest {
        ringHolds(0 to ringButton(0, final = false, count = 2))
        ring.recording = true
        var polls = 0
        ring.beforeAdvertisementRead = {
            polls++
            if (polls >= 12) ring.recording = false
        }

        assertEquals(RingSessionEnd.SYNCED, session().run(idle.copy(recording = true)))

        assertEquals(listOf(RingButtonAction.NEXT), keys, "the silence past the quiet gap settled the two taps")
    }

    @Test
    fun anOpenGroupTheRingNeverContinuesIsRejectedAndTheRecordingsAfterItAreKept() = runTest {
        // The collection at index 1 belongs to a different recording (its own start index), so the group left open at
        // index 0 is incomplete — rejected by the assembly on that evidence, not because a button report arrived.
        // What follows is kept, including the recording whose collection carries the report.
        ringHolds(0 to ringPart(0, final = false), 1 to ringButton(1, final = true, count = 1), 2 to ringPart(3, true))

        session().run(idle)

        assertEquals(listOf(1, 2), sink.firstIndexes, "the button's own collection and the note after it are both kept")
        assertEquals(3, store.next)
    }

    @Test
    fun aRingIsSyncedWhenItsCountChangedOrItAsksForServiceOrIsRecording() {
        assertTrue(shouldSyncRing(idle, lastCount = null))
        assertTrue(shouldSyncRing(idle, lastCount = 0))
        assertEquals(false, shouldSyncRing(idle, lastCount = 1))
        assertTrue(shouldSyncRing(idle.copy(needsServicing = true), lastCount = 1))
        assertTrue(shouldSyncRing(idle.copy(recording = true), lastCount = 1))
        assertEquals(false, shouldSyncRing(idle.copy(fingerprint = 0xDEADDEAD), lastCount = 0))
    }

    @Test
    fun aGattErrorIsRetriedAndALostBondOrFailedAuthenticationNeedsPairingAgain() {
        assertEquals(RingLinkFailure.RETRY, ringLinkFailure(133))
        for (status in listOf(137, 5, 15)) assertEquals(RingLinkFailure.PAIR_AGAIN, ringLinkFailure(status))
        for (status in listOf(null, 8, 19, 62)) assertEquals(RingLinkFailure.OTHER, ringLinkFailure(status))
    }
}
