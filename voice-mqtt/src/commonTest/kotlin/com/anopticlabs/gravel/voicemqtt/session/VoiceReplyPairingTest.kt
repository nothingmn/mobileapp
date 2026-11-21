package com.anopticlabs.gravel.voicemqtt.session

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pairing is on `requestId` alone. A correlationId is server-minted, one per session, reused across turns and
 * shared by every client on the broker, so a reply that only matches on it answers nothing.
 *
 * Answering nothing is no longer the same as being dropped: since the reply filter was opened up, a message that
 * matches no outstanding request is still read, still recorded, and spoken when it carries words. What pairing
 * decides is what happens to it — a matched reply is spoken *and* filed, an unmatched one is only spoken.
 */
class VoiceReplyPairingTest {

    private val ours = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val other = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"

    private var clock = 1_000_000L
    private fun handler() = VoiceReplyHandler(now = { clock }, replyTimeoutMillis = 60_000L)

    private fun final(requestId: String, text: String = "It is 4:43pm.") =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c1","text":"$text","format":"plain"}"""

    private fun error(code: String, requestId: String = ours) =
        """{"version":1,"status":"error","sessionId":"s","requestId":"$requestId","correlationId":"c1","text":"$code","format":"plain"}"""

    @Test
    fun aFinalReplyWithTheMatchingRequestIdIsSpoken() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(final(ours))
        assertEquals(ReplyOutcome.Final(ours, "It is 4:43pm."), decision.outcome)
        assertEquals(VoiceCaptureVerdict.ANSWER, decision.capture.verdict)
        assertTrue(decision.capture.matched, "the list has to be able to say this answered our request")
        assertTrue(decision.capture.spoken)
        assertFalse(handler.isAwaiting(ours), "an answered request is no longer outstanding")
    }

    @Test
    fun aReplyWithAMismatchedRequestIdIsReadAndSpokenButAnswersNothing() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(final(other, text = "This is someone else's answer."))
        assertEquals(ReplyOutcome.Unsolicited("This is someone else's answer."), decision.outcome)
        assertFalse(decision.capture.matched, "it answered no request of ours")
        assertTrue(handler.isAwaiting(ours), "our request is still waiting")
    }

    @Test
    fun aReplyForAnUnknownRequestIsStillRead() {
        val handler = handler()
        val decision = handler.accept(final(other, text = "Reminder: take the pills."))
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, decision.capture.verdict)
        assertEquals(ReplyOutcome.Unsolicited("Reminder: take the pills."), decision.outcome)
    }

    @Test
    fun aReplyWithAnEmptyRequestIdAndAnErrorStatusIsIgnored() {
        // A malformed payload comes back with empty echoes and an opaque code; it answers nothing and says
        // nothing a person could act on, so it is listed and never spoken.
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(error("invalid_request", requestId = ""))
        assertEquals(ReplyOutcome.Ignored, decision.outcome)
        assertEquals(VoiceCaptureVerdict.ERROR_CODE, decision.capture.verdict)
        assertTrue(handler.isAwaiting(ours))
    }

    @Test
    fun aReplyThatIsNotJsonIsSpokenAsTheTextItIs() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept("not json at all")
        assertEquals(ReplyOutcome.Unsolicited("not json at all"), decision.outcome)
    }

    @Test
    fun anErrorReplyFailsWithoutExposingTheOpaqueCode() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(error("invalid_request"))
        val outcome = decision.outcome
        assertTrue(outcome is ReplyOutcome.Failed)
        assertEquals("invalid_request", (outcome as ReplyOutcome.Failed).code)
        assertEquals(VoiceCaptureVerdict.FAILED, decision.capture.verdict)
        assertFalse(handler.isAwaiting(ours))
    }

    @Test
    fun busyKeepsTheRequestOutstandingForASameRequestIdRetry() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(error(VoiceReplyHandler.BUSY_CODE))
        assertEquals(ReplyOutcome.Busy(ours), decision.outcome)
        assertEquals(VoiceCaptureVerdict.BUSY, decision.capture.verdict)
        assertFalse(decision.capture.spoken, "a back-off is not something to say aloud")
        assertTrue(handler.isAwaiting(ours), "busy is the in-flight cap, so the same requestId may go again")
    }

    @Test
    fun aFinalReplyWithNoTextIsAFailureRatherThanSilence() {
        val handler = handler()
        handler.await(ours)
        val decision = handler.accept(final(ours, text = ""))
        assertTrue(decision.outcome is ReplyOutcome.Failed)
        assertEquals(VoiceCaptureVerdict.NO_TEXT, decision.capture.verdict)
        assertTrue(decision.capture.spoken, "the loop still says its own failure sentence for it")
    }

    @Test
    fun aReplyThatNeverArrivesTimesOutAndIsDropped() {
        val handler = handler()
        handler.await(ours)
        assertEquals(emptyList(), handler.timingOut(clock + 59_000L))
        assertEquals(listOf(ours), handler.timingOut(clock + 61_000L))
        assertFalse(handler.isAwaiting(ours))
        assertEquals(emptyList(), handler.timingOut(clock + 61_000L), "a timeout is spoken once")
    }

    /**
     * The broker re-delivers an answer that has already been answered — QoS 1 does that after a reconnect. It
     * matches nothing outstanding any more, so without the dedupe window the opened-up filter would read the
     * same answer aloud a second time. It is the dedupe that keeps that from happening.
     */
    @Test
    fun aDuplicateOfAnAnsweredRequestIsCapturedAndNotSpokenAgain() {
        val handler = handler()
        handler.await(ours)
        assertEquals(ReplyOutcome.Final(ours, "It is 4:43pm."), handler.accept(final(ours)).outcome)
        val again = handler.accept(final(ours, text = "It is 4:43pm."))
        assertEquals(ReplyOutcome.Ignored, again.outcome, "the same answer is not read aloud twice")
        assertEquals(VoiceCaptureVerdict.DUPLICATE, again.capture.verdict)
        assertFalse(again.capture.spoken)
    }
}
