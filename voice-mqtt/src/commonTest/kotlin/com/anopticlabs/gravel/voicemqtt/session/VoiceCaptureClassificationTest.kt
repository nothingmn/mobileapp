package com.anopticlabs.gravel.voicemqtt.session

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How one message on the reply filter is classified, in the order [VoiceReplyHandler] applies it.
 *
 * Every message is recorded; only some are spoken. The cases here are the refusals in that order — an empty
 * payload, a payload with no readable text, an opaque code, a duplicate — and the one new thing the loop says:
 * a message that carries words and answers nothing this phone asked.
 */
class VoiceCaptureClassificationTest {

    private var clock = 1_000_000L
    private val ours = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
    private val topic = "voice/reply/gravel-voice-0123abcd"

    private fun handler(dedupeWindowMillis: Long = 60_000L) =
        VoiceReplyHandler(now = { clock }, replyTimeoutMillis = 60_000L, dedupeWindowMillis = dedupeWindowMillis)

    private fun final(requestId: String, text: String) =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c","text":"$text","format":"plain"}"""

    @Test
    fun anEmptyPayloadIsCapturedAndNeverSpoken() {
        val handler = handler()
        for (raw in listOf("", "   ")) {
            val decision = handler.accept(raw, topic)
            assertEquals(ReplyOutcome.Ignored, decision.outcome)
            assertEquals(VoiceCaptureVerdict.EMPTY, decision.capture.verdict)
            assertFalse(decision.capture.spoken, "an empty payload is nothing to say")
            assertFalse(decision.capture.matched)
        }
    }

    @Test
    fun aPayloadWithNoReadableTextIsCapturedAndNeverSpoken() {
        val handler = handler()
        // An envelope from nobody, carrying no text field at all: nothing in it can be read aloud, but its shape
        // is now visible in the list — which is the point of listing it.
        val decision = handler.accept("""{"version":1,"status":"final","correlationId":"c","format":"plain"}""", topic)
        assertEquals(ReplyOutcome.Ignored, decision.outcome)
        assertEquals(VoiceCaptureVerdict.NO_TEXT, decision.capture.verdict)
        assertFalse(decision.capture.spoken)
        assertTrue(decision.capture.preview.contains("\"status\":\"final\""), "the list shows what actually arrived")
    }

    @Test
    fun anOpaqueErrorCodeFromNobodyIsCapturedAndNeverSpoken() {
        val handler = handler()
        val decision = handler.accept("""{"version":1,"status":"error","text":"invalid_request","format":"plain"}""", topic)
        assertEquals(ReplyOutcome.Ignored, decision.outcome)
        assertEquals(VoiceCaptureVerdict.ERROR_CODE, decision.capture.verdict)
        assertFalse(decision.capture.spoken, "a code nobody asked for is not a sentence to read aloud")
    }

    @Test
    fun plainTextIsSpokenAsItArrived() {
        val handler = handler()
        val decision = handler.accept("Reminder: take the pills at 4.", topic)
        assertEquals(ReplyOutcome.Unsolicited("Reminder: take the pills at 4."), decision.outcome)
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, decision.capture.verdict)
        assertFalse(decision.capture.matched)
        assertTrue(decision.capture.spoken)
    }

    @Test
    fun theEnvelopesTextFieldIsReadForAnUnsolicitedMessageToo() {
        val handler = handler()
        val decision = handler.accept(final(requestId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301", text = "Take the bins out."), topic)
        assertEquals(ReplyOutcome.Unsolicited("Take the bins out."), decision.outcome)
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, decision.capture.verdict)
    }

    @Test
    fun aPayloadThatIsAJsonStringIsPlainText() {
        val handler = handler()
        val decision = handler.accept("\"Take the bins out.\"", topic)
        assertEquals(ReplyOutcome.Unsolicited("Take the bins out."), decision.outcome)
    }

    @Test
    fun theCaptureNamesTheTopicTheArrivalTimeAndWhetherItMatched() {
        val handler = handler()
        handler.await(ours)
        val matched = handler.accept(final(ours, "It is 4:43pm."), topic).capture
        assertEquals(topic, matched.topic)
        assertEquals(clock, matched.atMillis)
        assertTrue(matched.matched)

        clock += 1_500L
        val stranger = handler.accept("Take the bins out.", "voice/reply/someone-else").capture
        assertEquals("voice/reply/someone-else", stranger.topic)
        assertEquals(clock, stranger.atMillis)
        assertFalse(stranger.matched)
    }

    @Test
    fun thePreviewIsTheFirstHundredAndTwentyCharactersOnOneLine() {
        val handler = handler()
        val long = "Take the bins out.\n" + "And the recycling, ".repeat(20)
        val preview = handler.accept(long, topic).capture.preview
        assertTrue(preview.length <= 121, "the preview is one short line: $preview")
        assertTrue(preview.endsWith("…"), "a cut preview says it was cut")
        assertFalse(preview.contains("\n"), "line breaks are collapsed, so the list keeps its shape")
    }

    @Test
    fun aShortMessageIsPreviewedWhole() {
        val handler = handler()
        assertEquals("Take the bins out.", handler.accept("Take the bins out.", topic).capture.preview)
    }

    @Test
    fun theSameContentInsideTheWindowIsSpokenOnce() {
        val handler = handler()
        val first = handler.accept("Reminder: take the pills.", topic)
        assertEquals(ReplyOutcome.Unsolicited("Reminder: take the pills."), first.outcome)

        clock += 59_000L
        val again = handler.accept("Reminder: take the pills.", topic)
        assertEquals(ReplyOutcome.Ignored, again.outcome, "the broker's redelivery is not said twice")
        assertEquals(VoiceCaptureVerdict.DUPLICATE, again.capture.verdict)
        assertFalse(again.capture.spoken)
    }

    @Test
    fun theSameContentIsSpokenAgainOnceTheWindowHasPassed() {
        val handler = handler()
        handler.accept("Reminder: take the pills.", topic)
        clock += 60_001L
        val later = handler.accept("Reminder: take the pills.", topic)
        assertEquals(
            ReplyOutcome.Unsolicited("Reminder: take the pills."),
            later.outcome,
            "a minute later it is a new reminder, not a duplicate",
        )
    }

    @Test
    fun aDifferentMessageInsideTheWindowIsSpoken() {
        val handler = handler()
        handler.accept("Take the bins out.", topic)
        clock += 1_000L
        assertEquals(ReplyOutcome.Unsolicited("Take the water meter reading."), handler.accept("Take the water meter reading.", topic).outcome)
    }

    /**
     * The window exists for the broker's duplicate, never for our own traffic: two turns whose answers happen to
     * read the same are two answers, and both are spoken and filed. That is what keeps a matched reply's
     * behaviour exactly as it was.
     */
    @Test
    fun aMatchedAnswerIsNeverSuppressedByTheWindow() {
        val handler = handler()
        val other = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
        handler.await(ours)
        assertEquals(ReplyOutcome.Final(ours, "Done."), handler.accept(final(ours, "Done."), topic).outcome)
        handler.await(other)
        assertEquals(ReplyOutcome.Final(other, "Done."), handler.accept(final(other, "Done."), topic).outcome)
    }
}
