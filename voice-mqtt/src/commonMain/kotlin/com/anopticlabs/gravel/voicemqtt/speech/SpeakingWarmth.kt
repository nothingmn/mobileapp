package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.session.RETRY_WINDOW_MILLIS
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings

/**
 * Whether the feature is on in settings in the three senses the notice needs: the loop is switched on, so a reply
 * can arrive at all; replies are spoken, so there is something for the notice to announce; and the user has left
 * "Keep the assistant ready in the background" on, so the warm notification is his choice rather than the app's.
 *
 * The loop switch is only offered once the connection settings are usable, so `enabled` already implies a
 * connected loop; `valid` is deliberately not read again here.
 */
val VoiceSettings.warmsSpeakingNotice: Boolean get() = enabled && speakReplies && keepReady

/** What the warm notice must do next. */
enum class WarmNoticeCommand {
    /** Nothing changes: the service is already in the state this decision asks for. */
    NONE,

    /** The service must enter the foreground showing the ready notification. */
    START,

    /** The service must leave the foreground and take the notification with it (`STOP_FOREGROUND_REMOVE`). */
    STOP,
}

/**
 * Which face of the one notification is showing.
 *
 * One notification, two faces: [READY] while the service is only warm, [SPEAKING] while a reply is being played,
 * and the same notification id all the while — so the shade holds one entry, and it never says "speaking" for a
 * reply that is not playing or "ready" over audio the user asked for.
 */
enum class NoticeFace {
    /** No notification: nothing warm and nothing speaking. */
    NONE,

    /** Warm and idle: the service holds the foreground so a reply's notice never needs a background start. */
    READY,

    /** A reply is being played, with its own text and the Stop action. */
    SPEAKING,
}

/** The face for the two states, in one place, so the Android shell has no rule of its own to get wrong. */
fun noticeFace(warm: Boolean, speaking: Boolean): NoticeFace = when {
    // Speaking wins: the audio is what the user needs to be able to stop.
    speaking -> NoticeFace.SPEAKING
    warm -> NoticeFace.READY
    else -> NoticeFace.NONE
}

/**
 * Whether the speaking notice's foreground service should be held warm, and for how long.
 *
 * The defect this answers is in `VOICE_MQTT.md`: a reply arrives from the broker asynchronously, so the notice's
 * service was being started while no activity was visible, which Android may refuse — leaving a reply that is
 * spoken with no notice and no Stop button, and (on a release that requires a running foreground service for
 * background audio) at risk of not being heard at all. Starting the service while an activity *is* visible and
 * keeping it up is the fix: once this app hosts a foreground service, the process is a foreground-service process,
 * and a later start command to the same service — the reply's — is permitted.
 *
 * The policy, in three clauses and no more:
 *
 * 1. **Warm while an activity is resumed.** The app's own visibility signal ([onVisible]) is the only thing that
 *    may *start* the warmth, because it is the only moment the platform is guaranteed to permit the start.
 * 2. **Warm after the last activity is stopped, for one window measured from the last voice activity** — a
 *    published turn or a received reply ([onVoiceActivity]). The window is [RETRY_WINDOW_MILLIS], ten minutes,
 *    because that is exactly how long this client keeps a request eligible for an answer and retries under the
 *    same `requestId`: past it the turn is dead, no answer to it can still arrive, and there is nothing to
 *    announce. Reusing the protocol's own number rather than inventing one is the point — the window cannot
 *    drift away from the rule it exists to cover.
 * 3. **Never warm from the background.** A reply that arrives with the app cold and pocketed does not extend
 *    anything and does not try a start: a background start is the thing being avoided, and attempting it would
 *    only log a refusal. [onVoiceActivity] therefore refreshes a warmth that already exists and never creates
 *    one.
 *
 * Everything else is an ending: the window lapsing ([onLapse], which re-reads the clock rather than trusting the
 * timer), the loop or the speaking switch being turned off ([onFeatureEnabled], which is asked of the stored
 * settings rather than a snapshot), and the app leaving the screen with no voice activity inside the window.
 *
 * Pure, so every clause above is a host test — the Android class that starts and stops the service is a thin
 * shell around these three calls, and what cannot be proved here is the platform's own behaviour on a device.
 */
class SpeakingWarmTracker(
    private val now: () -> Long,
    /** The window a warmth outlives the screen by; see clause 2 above. */
    val windowMillis: Long = RETRY_WINDOW_MILLIS,
) {

    private var visible = false
    private var enabled = false
    private var lastActivityAtMillis: Long? = null
    private var warm = false

    /** True while the service should hold the foreground for the notice. */
    val isWarm: Boolean get() = warm

    /**
     * When the warmth ends on its own, or null when nothing holds it open (not warm, or warm because a screen is
     * up — that warmth ends when the screen does).
     */
    val lapseAtMillis: Long?
        get() = if (warm && !visible) lastActivityAtMillis?.plus(windowMillis) else null

    /** An activity of the app was resumed or paused. The only signal that may start the warmth. */
    fun onVisible(resumed: Boolean): WarmNoticeCommand {
        visible = resumed
        return evaluate()
    }

    /**
     * A turn was published, or a reply arrived. Refreshes the window of a warmth that is already up; a reply that
     * lands on a cold, pocketed app arms nothing, because a background start is what this exists to avoid.
     */
    fun onVoiceActivity(): WarmNoticeCommand {
        if (enabled) lastActivityAtMillis = now()
        return evaluate()
    }

    /** The stored settings changed: the loop switched off, or spoken replies switched off, ends the warmth. */
    fun onFeatureEnabled(value: Boolean): WarmNoticeCommand {
        enabled = value
        return evaluate()
    }

    /** The timer the last [lapseAtMillis] asked for has fired. Re-decided against the clock, never trusted. */
    fun onLapse(): WarmNoticeCommand = evaluate()

    private fun evaluate(): WarmNoticeCommand {
        val wanted = enabled && (visible || tailOpen())
        return when {
            wanted == warm -> WarmNoticeCommand.NONE
            wanted -> {
                warm = true
                WarmNoticeCommand.START
            }
            else -> {
                warm = false
                WarmNoticeCommand.STOP
            }
        }
    }

    /** Whether a warmth that is already up is still inside its window. Never opens one. */
    private fun tailOpen(): Boolean {
        if (!warm) return false
        val last = lastActivityAtMillis ?: return false
        // Half-open on purpose: at exactly [lapseAtMillis] the window is over, which is what makes the timer's
        // firing and the tracker's own answer agree.
        return now() - last < windowMillis
    }
}
