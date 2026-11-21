package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.session.RETRY_WINDOW_MILLIS
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The warm speaking notice, without a phone.
 *
 * What the platform does with a foreground service — whether it permits the start, whether the notification is
 * visible, whether a warm service makes a later start permitted — needs a device and is listed as unproven in
 * `VOICE_MQTT.md`. What is decided here is everything this app controls: when the warmth may begin, what
 * refreshes it, when it ends, and that the one notification never shows two things at once.
 */
class SpeakingWarmthTest {

    private var clock = 1_000_000L

    private fun tracker(): SpeakingWarmTracker = SpeakingWarmTracker(now = { clock })

    /** The switch-on path every test starts from: the loop is on, and an activity is on screen. */
    private fun visibleAndEnabled(): SpeakingWarmTracker = tracker().apply {
        assertEquals(WarmNoticeCommand.NONE, onFeatureEnabled(true), "an off-screen app is not warmed by a setting")
        assertEquals(WarmNoticeCommand.START, onVisible(true))
    }

    @Test
    fun aVisibleAppWithTheLoopOnStartsTheWarmNotice() {
        val warm = visibleAndEnabled()
        assertTrue(warm.isWarm, "the service is warm while an activity is resumed")
        // Warm because a screen is up: nothing is on a clock, so there is no timer to arm.
        assertNull(warm.lapseAtMillis)
    }

    @Test
    fun nothingIsWarmWhileTheFeatureIsOff() {
        val warm = tracker()
        // The activity comes first and the setting is off: no start, and no warmth recorded.
        assertEquals(WarmNoticeCommand.NONE, warm.onVisible(true))
        assertFalse(warm.isWarm, "the speaking notice does not exist with the loop off")
        assertEquals(WarmNoticeCommand.START, warm.onFeatureEnabled(true), "turning it on while visible warms it")
    }

    @Test
    fun everySwitchThatGatesTheNoticeIsReadFromTheSettings() {
        // The gate is read off the stored settings, so all three inputs are proved here rather than assumed: a
        // loop that is off, a loop that never speaks, and a user who turned the warm notice off all mean there
        // is nothing to keep ready.
        assertTrue(VoiceSettings(enabled = true, speakReplies = true, keepReady = true).warmsSpeakingNotice)
        assertFalse(
            VoiceSettings(enabled = true, speakReplies = true, keepReady = false).warmsSpeakingNotice,
            "the user's own switch outranks the default",
        )
        assertFalse(VoiceSettings(enabled = true, speakReplies = false).warmsSpeakingNotice)
        assertFalse(VoiceSettings(enabled = false, speakReplies = true).warmsSpeakingNotice)
        assertFalse(VoiceSettings().warmsSpeakingNotice)
    }

    @Test
    fun withTheSwitchOffNothingIsEverWarmed() {
        val warm = tracker()
        // The setting arrives before the screen does, which is the order the app produces at startup.
        assertEquals(WarmNoticeCommand.NONE, warm.onFeatureEnabled(VoiceSettings(enabled = true, keepReady = false).warmsSpeakingNotice))
        assertEquals(WarmNoticeCommand.NONE, warm.onVisible(true), "a visible screen does not override the switch")
        assertFalse(warm.isWarm, "off means the notification does not exist at all")
        // And nothing a turn or a reply does warms it either.
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())
        assertFalse(warm.isWarm)
    }

    @Test
    fun theSwitchOffWhileWarmEndsItAtOnce() {
        val warm = visibleAndEnabled()
        warm.onVoiceActivity()
        // Flipped off with the window still wide open: the setting outranks the window.
        assertEquals(WarmNoticeCommand.STOP, warm.onFeatureEnabled(warmGate(keepReady = false)), "off is the ending")
        assertFalse(warm.isWarm)
    }

    @Test
    fun theSwitchOnWhileVisibleStartsItAtOnce() {
        val warm = tracker()
        warm.onFeatureEnabled(warmGate(keepReady = false))
        warm.onVisible(true)
        assertFalse(warm.isWarm, "off, nothing is warm even on screen")
        // Flipped on from the settings page: it starts now, without a restart and without leaving the screen.
        assertEquals(WarmNoticeCommand.START, warm.onFeatureEnabled(warmGate(keepReady = true)))
        assertTrue(warm.isWarm)
    }

    /** The settings as the store hands them over: the loop on, spoken replies on, and the warm switch as given. */
    private fun warmGate(keepReady: Boolean): Boolean =
        VoiceSettings(enabled = true, speakReplies = true, keepReady = keepReady).warmsSpeakingNotice

    @Test
    fun theBackgroundNeverStartsIt() {
        val warm = tracker()
        warm.onFeatureEnabled(true)
        // A reply arrives on a cold, pocketed app: it must not try a start that the platform would refuse.
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())
        assertFalse(warm.isWarm)
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())
        assertFalse(warm.isWarm, "activity refreshes a warmth, it never creates one")
    }

    @Test
    fun everyTurnAndEveryReplyRefreshTheWindow() {
        val warm = visibleAndEnabled()
        // A turn goes out, then he pockets the phone.
        warm.onVoiceActivity()
        assertEquals(WarmNoticeCommand.NONE, warm.onVisible(false), "one activity keeps it warm after the screen")
        assertTrue(warm.isWarm)
        assertEquals(clock + RETRY_WINDOW_MILLIS, warm.lapseAtMillis)

        // A reply arrives a minute later: the window is measured again from that reply.
        clock += 60_000L
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())
        assertEquals(clock + RETRY_WINDOW_MILLIS, warm.lapseAtMillis, "a reply pushes the lapse a full window out")

        // So does a second turn, published from wherever he is.
        clock += 60_000L
        warm.onVoiceActivity()
        assertEquals(clock + RETRY_WINDOW_MILLIS, warm.lapseAtMillis)
    }

    @Test
    fun theWindowLapsesAndTheNoticeEnds() {
        val warm = visibleAndEnabled()
        warm.onVoiceActivity()
        warm.onVisible(false)
        val deadline = checkNotNull(warm.lapseAtMillis)

        // A timer that fires early changes nothing: the ending is re-decided against the clock.
        clock = deadline - 1
        assertEquals(WarmNoticeCommand.NONE, warm.onLapse())
        assertTrue(warm.isWarm)

        clock = deadline
        assertEquals(WarmNoticeCommand.STOP, warm.onLapse(), "the window closing is the ending")
        assertFalse(warm.isWarm)
        assertNull(warm.lapseAtMillis)
        // And a second firing of the same timer is a no-op rather than a second stop.
        assertEquals(WarmNoticeCommand.NONE, warm.onLapse())
    }

    @Test
    fun theWindowIsTheReplyWindow() {
        // The number is not chosen for taste: it is how long this client keeps a request eligible for an answer,
        // so any reply that can still arrive finds the service warm.
        assertEquals(RETRY_WINDOW_MILLIS, tracker().windowMillis)
        assertEquals(10 * 60 * 1000L, RETRY_WINDOW_MILLIS)
    }

    @Test
    fun leavingTheScreenWithNoVoiceActivityEndsItAtOnce() {
        // Opening the app and walking away publishes nothing, so no reply is coming and nothing is worth warming.
        val warm = visibleAndEnabled()
        assertEquals(WarmNoticeCommand.STOP, warm.onVisible(false))
        assertFalse(warm.isWarm)
    }

    @Test
    fun theFeatureOffEndsItAtOnce() {
        val warm = visibleAndEnabled()
        warm.onVoiceActivity()
        assertEquals(WarmNoticeCommand.STOP, warm.onFeatureEnabled(false), "switched off, the service goes")
        assertFalse(warm.isWarm, "and it does not come back on the strength of an old turn")
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())
        assertFalse(warm.isWarm)
    }

    @Test
    fun theFeatureOffEndsItEvenWithAWindowStillOpen() {
        val warm = visibleAndEnabled()
        warm.onVoiceActivity()
        warm.onVisible(false)
        // The window is wide open, and the setting outranks it.
        assertEquals(WarmNoticeCommand.STOP, warm.onFeatureEnabled(false))
        assertFalse(warm.isWarm)
    }

    @Test
    fun turningItBackOnWhileVisibleWarmsItAgain() {
        val warm = visibleAndEnabled()
        warm.onFeatureEnabled(false)
        assertEquals(WarmNoticeCommand.START, warm.onFeatureEnabled(true))
        assertTrue(warm.isWarm)
        // Off the screen and with no turn behind it, the new warmth has nothing holding it open.
        assertEquals(WarmNoticeCommand.STOP, warm.onVisible(false))
    }

    @Test
    fun theOneNotificationShowsTheSpeakingContentOverTheReadyContent() {
        val warm = visibleAndEnabled()
        val speaking = SpeakingNoticeTracker()
        // Warm and idle: the ready face, and nothing else.
        assertEquals(NoticeFace.READY, noticeFace(warm.isWarm, speaking.isSpeaking))

        // A reply begins: the same notification takes the speaking content.
        assertEquals(SpeakingNoticeCommand.SHOW, speaking.begin(PlaybackDecision.Speak))
        assertEquals(NoticeFace.SPEAKING, noticeFace(warm.isWarm, speaking.isSpeaking))

        // It ends: the ready face comes back, because the service is still warm.
        assertEquals(SpeakingNoticeCommand.HIDE, speaking.end())
        assertEquals(NoticeFace.READY, noticeFace(warm.isWarm, speaking.isSpeaking))

        // And when the warmth has gone, the ending leaves nothing at all.
        warm.onVisible(false)
        warm.onFeatureEnabled(false)
        assertEquals(NoticeFace.NONE, noticeFace(warm.isWarm, speaking.isSpeaking))
    }

    @Test
    fun aSecondReplyReplacesTheTextRatherThanAddingASecondNotice() {
        val warm = visibleAndEnabled()
        val speaking = SpeakingNoticeTracker()
        assertEquals(SpeakingNoticeCommand.SHOW, speaking.begin(PlaybackDecision.Speak))
        assertEquals(SpeakingNoticeCommand.UPDATE, speaking.begin(PlaybackDecision.Speak))
        assertEquals(NoticeFace.SPEAKING, noticeFace(warm.isWarm, speaking.isSpeaking))
        assertEquals(NoticeFace.SPEAKING, noticeFace(warm.isWarm, speaking.isSpeaking))
    }

    @Test
    fun aHeldBackReplyLeavesTheReadyNoticeAlone() {
        val warm = visibleAndEnabled()
        val speaking = SpeakingNoticeTracker()
        // No headset, spoken replies off, no offline voice: nothing is spoken, so nothing is announced, and the
        // ready notice is neither replaced nor removed by it.
        assertEquals(
            SpeakingNoticeCommand.NONE,
            speaking.begin(PlaybackDecision.Silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT)),
        )
        assertEquals(NoticeFace.READY, noticeFace(warm.isWarm, speaking.isSpeaking))
    }

    @Test
    fun theFaceIsOneThingAtATimeInEveryCombination() {
        // The shell posts exactly what this returns at one notification id, so "one notification, not two" is
        // this function having one value per pair of states — and speaking never sharing the shade with ready.
        assertEquals(NoticeFace.NONE, noticeFace(warm = false, speaking = false))
        assertEquals(NoticeFace.READY, noticeFace(warm = true, speaking = false))
        assertEquals(NoticeFace.SPEAKING, noticeFace(warm = false, speaking = true))
        assertEquals(NoticeFace.SPEAKING, noticeFace(warm = true, speaking = true))
        assertEquals(
            3,
            NoticeFace.entries.size,
            "three faces and no fourth: there is no state in which two notifications are the answer",
        )
    }

    @Test
    fun aReplyAfterTheScreenIsLeftStillFindsTheServiceWarm() {
        // The defect this whole change answers, walked through once end to end in time.
        val warm = visibleAndEnabled()
        warm.onVoiceActivity() // he spoke, and put the phone in his pocket
        warm.onVisible(false)
        clock += 30_000L // the answer comes back half a minute later

        assertTrue(warm.isWarm, "the service is already up, so the notice needs no background start")
        assertEquals(WarmNoticeCommand.NONE, warm.onVoiceActivity())

        // It is spoken, and the notice goes back to ready — still with no start of any kind.
        val speaking = SpeakingNoticeTracker()
        speaking.begin(PlaybackDecision.Speak)
        speaking.end()
        assertEquals(NoticeFace.READY, noticeFace(warm.isWarm, speaking.isSpeaking))
    }
}
