package com.anopticlabs.gravel.voicemqtt.speech

/**
 * The one line the speaking notification shows under its title: the beginning of the reply, on one line,
 * ellipsised.
 *
 * Pure, so the shape of that line is a host test rather than something only a phone can show. Two rules:
 * a reply is collapsed to a single line (a notification body is one line wide), and a long one is cut near
 * [MAX_CHARS] at a word boundary where there is one, so the preview reads as words rather than a chopped word.
 */
object SpeakingPreview {

    /** How much of the reply the notification shows before the ellipsis. */
    const val MAX_CHARS: Int = 80

    /** The shortest prefix worth cutting back to: below this the word boundary is not worth the lost text. */
    private const val MIN_CUT: Int = MAX_CHARS / 2

    fun of(reply: String): String {
        val oneLine = reply.replace(WHITESPACE, " ").trim()
        if (oneLine.length <= MAX_CHARS) return oneLine
        val head = oneLine.take(MAX_CHARS)
        val lastSpace = head.lastIndexOf(' ')
        val cut = if (lastSpace >= MIN_CUT) head.take(lastSpace) else head
        return cut.trimEnd().trimEnd { it in TRAILING_PUNCTUATION } + ELLIPSIS
    }

    private val WHITESPACE = Regex("\\s+")
    private const val ELLIPSIS = "…"
    private const val TRAILING_PUNCTUATION = ",;:.…"
}

/** What the speaking notification must do next. */
enum class SpeakingNoticeCommand {
    /** Nothing to change: a suppressed reply, or an ending with nothing on screen to end. */
    NONE,

    /** Post the notification and take the foreground: the first utterance of the reply. */
    SHOW,

    /** Re-post the same notification with the new reply's beginning: a second utterance, not a second notice. */
    UPDATE,

    /** Take the notification down and leave the foreground. */
    HIDE,
}

/**
 * Whether the speaking notification should be on screen, and what the next command is.
 *
 * The whole decision is here, in common code, because it is the part that can be proved without a phone: the
 * Android class that posts the notification is a thin shell around [begin] and [end], and the tests drive this
 * object directly.
 *
 * The rule that matters most: **a suppressed reply never shows a notice.** [begin] is given the same
 * [PlaybackDecision] the speaker acted on, so a reply held back for a switched-off switch, a missing headset or
 * a missing offline voice asks for [SpeakingNoticeCommand.NONE] — the notification is not posted and the
 * service is never started for it.
 *
 * Every ending is one removal, by design: the utterance finishing, the user's Stop, and an engine failure all
 * reach [end]. The three are different events in `AndroidSpeechOutput` (the engine's done/stopped/error
 * callbacks and the stop path), and they exist so that no ending can leave a notice behind; what differs
 * between them is not something the notification needs to say.
 */
class SpeakingNoticeTracker {

    private var speaking = false

    /** True between the first utterance of a reply and its ending; what the notice is on screen for. */
    val isSpeaking: Boolean get() = speaking

    /** The command for an utterance that is about to play, given the decision that allowed it. */
    fun begin(decision: PlaybackDecision): SpeakingNoticeCommand = when {
        // Held back before any audio: nothing to announce, and nothing already on screen is touched either.
        decision is PlaybackDecision.Silent -> SpeakingNoticeCommand.NONE
        !speaking -> {
            speaking = true
            SpeakingNoticeCommand.SHOW
        }
        // A newer line replaces the one being spoken (the engine is asked to flush), so the notice is re-posted
        // rather than a second one stacked beside it.
        else -> SpeakingNoticeCommand.UPDATE
    }

    /** The command for speech that has ended. Safe to call when nothing is speaking: that is a no-op. */
    fun end(): SpeakingNoticeCommand {
        if (!speaking) return SpeakingNoticeCommand.NONE
        speaking = false
        return SpeakingNoticeCommand.HIDE
    }
}
