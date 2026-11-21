package com.anopticlabs.gravel.voicemqtt.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The speaking notification's decisions, without a phone.
 *
 * The notification itself is a foreground service posting to the platform's notification manager, which needs a
 * device; what it *does* — appear on the first utterance, never on a suppressed reply, replace itself rather
 * than stack, and disappear on every ending — is decided by [SpeakingNoticeTracker], and that is what is proved
 * here. `AndroidSpeechOutput` collapses the engine's done, stopped and failed callbacks and the user's Stop onto
 * [SpeakingNoticeTracker.end]; that wiring is the part a host test cannot reach, because it needs a live
 * text-to-speech engine.
 */
class SpeakingNoticeTest {

    private val silent = PlaybackDecision.Silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT)

    @Test
    fun theFirstUtteranceAsksForTheNotice() {
        val tracker = SpeakingNoticeTracker()
        assertEquals(SpeakingNoticeCommand.SHOW, tracker.begin(PlaybackDecision.Speak))
        assertTrue(tracker.isSpeaking)
    }

    @Test
    fun aSuppressedReplyNeverAsksForTheNotice() {
        val tracker = SpeakingNoticeTracker()
        assertEquals(SpeakingNoticeCommand.NONE, tracker.begin(silent))
        assertFalse(tracker.isSpeaking, "a held-back reply must not leave the tracker thinking it is speaking")
        // And the silence does not consume the first-utterance slot: the next real reply still starts the service.
        assertEquals(SpeakingNoticeCommand.SHOW, tracker.begin(PlaybackDecision.Speak))
    }

    @Test
    fun everyWayOfBeingSuppressedIsSilent() {
        val tracker = SpeakingNoticeTracker()
        for (reason in SpeechSuppression.entries) {
            assertEquals(
                SpeakingNoticeCommand.NONE,
                tracker.begin(PlaybackDecision.Silent(reason)),
                "$reason must not post a notice",
            )
        }
        assertFalse(tracker.isSpeaking)
    }

    @Test
    fun aSecondUtteranceUpdatesTheSameNoticeRatherThanStackingAnother() {
        val tracker = SpeakingNoticeTracker()
        assertEquals(SpeakingNoticeCommand.SHOW, tracker.begin(PlaybackDecision.Speak))
        assertEquals(SpeakingNoticeCommand.UPDATE, tracker.begin(PlaybackDecision.Speak))
        assertEquals(SpeakingNoticeCommand.UPDATE, tracker.begin(PlaybackDecision.Speak))
        assertTrue(tracker.isSpeaking)
    }

    @Test
    fun finishingSpeechRemovesTheNotice() {
        val tracker = SpeakingNoticeTracker()
        tracker.begin(PlaybackDecision.Speak)
        assertEquals(SpeakingNoticeCommand.HIDE, tracker.end())
        assertFalse(tracker.isSpeaking)
    }

    @Test
    fun theUsersStopRemovesTheNotice() {
        // Reached by SpeakingNoticeService's Stop action, which aborts the utterance and calls the same end().
        val tracker = SpeakingNoticeTracker()
        tracker.begin(PlaybackDecision.Speak)
        assertEquals(SpeakingNoticeCommand.HIDE, tracker.end())
        assertFalse(tracker.isSpeaking)
    }

    @Test
    fun anEngineFailureRemovesTheNotice() {
        // The engine's error callback reports an ending like any other, so nothing is left on screen.
        val tracker = SpeakingNoticeTracker()
        tracker.begin(PlaybackDecision.Speak)
        assertEquals(SpeakingNoticeCommand.HIDE, tracker.end())
        assertFalse(tracker.isSpeaking)
    }

    @Test
    fun stoppingWhenNothingIsSpokenDoesNothing() {
        val tracker = SpeakingNoticeTracker()
        assertEquals(SpeakingNoticeCommand.NONE, tracker.end())
        // A second ending after the first is a no-op too, so a late callback cannot chase a newer notice away.
        tracker.begin(PlaybackDecision.Speak)
        assertEquals(SpeakingNoticeCommand.HIDE, tracker.end())
        assertEquals(SpeakingNoticeCommand.NONE, tracker.end())
    }

    @Test
    fun aShortReplyIsShownAsItIs() {
        assertEquals("The garage lights are on.", SpeakingPreview.of("The garage lights are on."))
    }

    @Test
    fun aReplyIsOneLineInTheNotification() {
        assertEquals("One two three", SpeakingPreview.of("One\n\ntwo   three\n"))
    }

    @Test
    fun aLongReplyIsCutAtAWordAndEllipsised() {
        val reply = "The garage lights are on and the kitchen light is off and the door is locked and the alarm " +
            "is set for the night"
        val preview = SpeakingPreview.of(reply)
        assertTrue(preview.endsWith("…"), "a cut reply is ellipsised: $preview")
        assertTrue(preview.length <= SpeakingPreview.MAX_CHARS + 1, "the preview stays about one line: $preview")
        assertTrue(reply.startsWith(preview.dropLast(1).trimEnd()), "the preview is the reply's own beginning")
        assertFalse(preview.contains("  "), "one line, single spaces: $preview")
        assertFalse(preview.dropLast(1).endsWith(" "), "no space before the ellipsis: $preview")
    }

    @Test
    fun aLongWordWithoutASpaceIsCutAtTheCap() {
        val preview = SpeakingPreview.of("x".repeat(200))
        assertEquals("x".repeat(SpeakingPreview.MAX_CHARS) + "…", preview)
    }

    @Test
    fun punctuationAtTheCutIsNotDoubled() {
        val reply = "word. ".repeat(40).trim()
        val preview = SpeakingPreview.of(reply)
        assertTrue(preview.endsWith("…"), preview)
        assertFalse(preview.endsWith(".…"), "a full stop before the ellipsis reads as two: $preview")
    }
}
