package com.anopticlabs.gravel.ring.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connect timings line, on a host: the one place a press's marks become words. The marks themselves are the
 * Android link's (see [RingConnectTiming]); this proves what the line says from whatever marks it is given, including
 * the case where one of them never arrived — a link that failed between two steps still has to say what the earlier
 * steps cost, and must not print a gap it cannot know.
 */
class RingConnectMarksTest {

    private val marks = RingConnectMarks(
        connectRequestedAtMillis = 4_490,
        gattConnectedAtMillis = 4_500,
        discoveredAtMillis = 4_700,
        controlSubscribedAtMillis = 4_712,
        dataSubscribedAtMillis = 4_725,
        connectedAtMillis = 4_726,
    )

    @Test
    fun theLineNamesEveryStepOfTheOpenAndWhatItCost() {
        assertEquals(
            "connect timings: advertisement -> connect requested 1190 ms, connect requested -> gatt connected 10 ms, " +
                "gatt connected -> services discovered 200 ms, services discovered -> control channel 12 ms, " +
                "control channel -> data channel 13 ms, data channel -> connected 1 ms",
            marks.line(heardAtMillis = 3_300),
        )
    }

    @Test
    fun theGapFromTheAdvertisementSplitsIntoTheAppsHandOffAndTheLinksOwnOpen() {
        // What the split is for: the first segment is the runner hearing the advertisement and asking for the link
        // (our queueing); everything after the request is Android's open. A press used to fold the two together, so
        // neither could be ruled in or out.
        val line = marks.line(heardAtMillis = 3_300)

        assertTrue(line.contains("advertisement -> connect requested 1190 ms"), line)
        assertEquals(
            236L,
            marks.connectedAtMillis!! - marks.connectRequestedAtMillis!!,
            "request -> connected is the link's own open: 10 + 200 + 12 + 13 + 1",
        )
    }

    @Test
    fun withoutTheConnectRequestTheLineStartsAtTheLinkAndInventsNoBridge() {
        // The mark is diagnostics: a link whose request was not stamped drops the two steps it would have divided,
        // as any missing mark does, rather than bridging the advertisement to the link and claiming a gap it did
        // not divide. A stamped open always has it, so this is the shape a broken stamp would take, not a press'.
        val unstamped = marks.copy(connectRequestedAtMillis = null)

        assertEquals(
            "connect timings: gatt connected -> services discovered 200 ms, services discovered -> control channel 12 ms, " +
                "control channel -> data channel 13 ms, data channel -> connected 1 ms",
            unstamped.line(heardAtMillis = 3_300),
        )
    }

    @Test
    fun withoutTheAdvertisementTheLineSaysSoAndStillTimesEveryStepAfterIt() {
        val line = marks.line(heardAtMillis = null)

        assertEquals(
            "connect timings: advertisement time unknown, connect requested -> gatt connected 10 ms, " +
                "gatt connected -> services discovered 200 ms, services discovered -> control channel 12 ms, " +
                "control channel -> data channel 13 ms, data channel -> connected 1 ms",
            line,
        )
    }

    @Test
    fun aMarkThatNeverArrivedDropsItsTwoStepsAndKeepsTheRest() {
        // Discovery was never marked — the link failed before the services could be read, or the caller's mark was
        // lost — so both steps that end or begin at it are gone, and nothing is invented to replace them.
        val partial = marks.copy(discoveredAtMillis = null)

        assertEquals(
            "connect timings: advertisement -> connect requested 1190 ms, connect requested -> gatt connected 10 ms, " +
                "control channel -> data channel 13 ms, data channel -> connected 1 ms",
            partial.line(heardAtMillis = 3_300),
        )

        // A link that died at the second channel: the steps up to the first channel are still there.
        val beforeData = marks.copy(dataSubscribedAtMillis = null, connectedAtMillis = null)

        assertEquals(
            "connect timings: advertisement -> connect requested 1190 ms, connect requested -> gatt connected 10 ms, " +
                "gatt connected -> services discovered 200 ms, services discovered -> control channel 12 ms",
            beforeData.line(heardAtMillis = 3_300),
        )
    }

    @Test
    fun nothingMarkedIsAnEmptyLineRatherThanAGapOfZeroMilliseconds() {
        val empty = RingConnectMarks()

        assertTrue(empty.isEmpty)
        assertFalse(marks.isEmpty)
        assertEquals("connect timings: advertisement time unknown", empty.line(heardAtMillis = null))
        assertEquals("connect timings: nothing marked", empty.line(heardAtMillis = 3_300), "no step is known, so none is claimed")
    }

    @Test
    fun aRequestAloneIsNotAnEmptyRecord() {
        // The connect was asked for and nothing after it was marked: a link that never came up. That is a fact the
        // line has to be able to carry, and it must not read as "nothing marked".
        val requestedOnly = RingConnectMarks(connectRequestedAtMillis = 3_310)

        assertFalse(requestedOnly.isEmpty)
        assertEquals("connect timings: advertisement -> connect requested 10 ms", requestedOnly.line(heardAtMillis = 3_300))
    }

    @Test
    fun theTimingStampsEachStepOnceOnItsOwnClockAndTheFirstGattStampWins() {
        var now = 1_000L
        val connects = RingConnectTiming { now }

        connects.requested()
        now = 1_010L
        connects.requested() // a second request must not move the mark that measures the hand-off
        now = 1_100L
        connects.gattConnected()
        now = 1_500L
        connects.gattConnected() // the watcher's report arriving after connect() returned: the earlier mark is the true one
        now = 1_600L
        connects.discovered()
        now = 1_610L
        connects.controlSubscribed()
        now = 1_620L
        connects.dataSubscribed()
        now = 1_621L
        connects.connected()

        val stamped = connects.marks()
        assertEquals(1_000L, stamped.connectRequestedAtMillis, "the request is stamped when it is made, once")
        assertEquals(1_100L, stamped.gattConnectedAtMillis, "the first stamp of a step is the one kept")
        assertEquals(1_600L, stamped.discoveredAtMillis)
        assertEquals(1_610L, stamped.controlSubscribedAtMillis)
        assertEquals(1_620L, stamped.dataSubscribedAtMillis)
        assertEquals(1_621L, stamped.connectedAtMillis)
    }

    @Test
    fun aFreshTimingHasNoMarksAtAll() {
        val fresh = RingConnectTiming().marks()

        assertTrue(fresh.isEmpty)
        assertNull(fresh.connectRequestedAtMillis)
        assertNull(fresh.gattConnectedAtMillis)
        assertNull(fresh.connectedAtMillis)
    }
}
