package com.anopticlabs.gravel.ring.link

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connect timings line, on a host: the one place a press's five marks become words. The marks themselves are the
 * Android link's (see [RingConnectTiming]); this proves what the line says from whatever marks it is given, including
 * the case where one of them never arrived — a link that failed between two steps still has to say what the earlier
 * steps cost, and must not print a gap it cannot know.
 */
class RingConnectMarksTest {

    private val marks = RingConnectMarks(
        gattConnectedAtMillis = 4_500,
        discoveredAtMillis = 4_700,
        controlSubscribedAtMillis = 4_712,
        dataSubscribedAtMillis = 4_725,
        connectedAtMillis = 4_726,
    )

    @Test
    fun theLineNamesEveryStepOfTheOpenAndWhatItCost() {
        assertEquals(
            "connect timings: advertisement -> gatt connected 1200 ms, gatt connected -> services discovered 200 ms, " +
                "services discovered -> control channel 12 ms, control channel -> data channel 13 ms, " +
                "data channel -> connected 1 ms",
            marks.line(heardAtMillis = 3_300),
        )
    }

    @Test
    fun withoutTheAdvertisementTheLineSaysSoAndStillTimesEveryStepAfterIt() {
        val line = marks.line(heardAtMillis = null)

        assertEquals(
            "connect timings: advertisement time unknown, gatt connected -> services discovered 200 ms, " +
                "services discovered -> control channel 12 ms, control channel -> data channel 13 ms, " +
                "data channel -> connected 1 ms",
            line,
        )
    }

    @Test
    fun aMarkThatNeverArrivedDropsItsTwoStepsAndKeepsTheRest() {
        // Discovery was never marked — the link failed before the services could be read, or the caller's mark was
        // lost — so both steps that end or begin at it are gone, and nothing is invented to replace them.
        val partial = marks.copy(discoveredAtMillis = null)

        assertEquals(
            "connect timings: advertisement -> gatt connected 1200 ms, control channel -> data channel 13 ms, " +
                "data channel -> connected 1 ms",
            partial.line(heardAtMillis = 3_300),
        )

        // A link that died at the second channel: the steps up to the first channel are still there.
        val beforeData = marks.copy(dataSubscribedAtMillis = null, connectedAtMillis = null)

        assertEquals(
            "connect timings: advertisement -> gatt connected 1200 ms, gatt connected -> services discovered 200 ms, " +
                "services discovered -> control channel 12 ms",
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
    fun theTimingStampsEachStepOnceOnItsOwnClockAndTheFirstGattStampWins() {
        var now = 1_000L
        val connects = RingConnectTiming { now }

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
        assertEquals(1_000L, stamped.gattConnectedAtMillis, "the first stamp of a step is the one kept")
        assertEquals(1_600L, stamped.discoveredAtMillis)
        assertEquals(1_610L, stamped.controlSubscribedAtMillis)
        assertEquals(1_620L, stamped.dataSubscribedAtMillis)
        assertEquals(1_621L, stamped.connectedAtMillis)
    }

    @Test
    fun aFreshTimingHasNoMarksAtAll() {
        val fresh = RingConnectTiming().marks()

        assertTrue(fresh.isEmpty)
        assertNull(fresh.gattConnectedAtMillis)
        assertNull(fresh.connectedAtMillis)
    }
}
