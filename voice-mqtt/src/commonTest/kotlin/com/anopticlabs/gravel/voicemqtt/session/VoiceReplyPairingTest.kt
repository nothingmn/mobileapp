package com.anopticlabs.gravel.voicemqtt.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pairing is on `requestId` alone. A correlationId is server-minted, one per session, reused across turns and
 * shared by every client on the broker, so a reply that only matches on it answers nothing.
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
        val outcome = handler.accept(final(ours))
        assertEquals(ReplyOutcome.Final(ours, "It is 4:43pm."), outcome)
        assertFalse(handler.isAwaiting(ours), "an answered request is no longer outstanding")
    }

    @Test
    fun aReplyWithAMismatchedRequestIdIsIgnored() {
        val handler = handler()
        handler.await(ours)
        assertEquals(ReplyOutcome.Ignored, handler.accept(final(other)))
        assertTrue(handler.isAwaiting(ours), "our request is still waiting")
    }

    @Test
    fun aReplyForAnUnknownRequestIsIgnored() {
        val handler = handler()
        assertEquals(ReplyOutcome.Ignored, handler.accept(final(other)))
    }

    @Test
    fun aReplyWithAnEmptyRequestIdIsIgnored() {
        // A malformed payload comes back with empty echoes; it answers nothing.
        val handler = handler()
        handler.await(ours)
        assertEquals(ReplyOutcome.Ignored, handler.accept(error("invalid_request", requestId = "")))
        assertTrue(handler.isAwaiting(ours))
    }

    @Test
    fun aReplyThatIsNotJsonIsIgnored() {
        val handler = handler()
        handler.await(ours)
        assertEquals(ReplyOutcome.Ignored, handler.accept("not json at all"))
    }

    @Test
    fun anErrorReplyFailsWithoutExposingTheOpaqueCode() {
        val handler = handler()
        handler.await(ours)
        val outcome = handler.accept(error("invalid_request"))
        assertTrue(outcome is ReplyOutcome.Failed)
        assertEquals("invalid_request", (outcome as ReplyOutcome.Failed).code)
        assertFalse(handler.isAwaiting(ours))
    }

    @Test
    fun busyKeepsTheRequestOutstandingForASameRequestIdRetry() {
        val handler = handler()
        handler.await(ours)
        assertEquals(ReplyOutcome.Busy(ours), handler.accept(error(VoiceReplyHandler.BUSY_CODE)))
        assertTrue(handler.isAwaiting(ours), "busy is the in-flight cap, so the same requestId may go again")
    }

    @Test
    fun aFinalReplyWithNoTextIsAFailureRatherThanSilence() {
        val handler = handler()
        handler.await(ours)
        val outcome = handler.accept(final(ours, text = ""))
        assertTrue(outcome is ReplyOutcome.Failed)
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

    @Test
    fun aDuplicateOfAnAnsweredRequestIsIgnored() {
        val handler = handler()
        handler.await(ours)
        assertTrue(handler.accept(final(ours)) is ReplyOutcome.Final)
        assertEquals(ReplyOutcome.Ignored, handler.accept(final(ours, text = "a second answer")))
    }
}
