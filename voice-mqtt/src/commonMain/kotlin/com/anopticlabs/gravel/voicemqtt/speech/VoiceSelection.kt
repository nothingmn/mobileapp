package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys

/**
 * One voice an engine offers, reduced to the four facts the rule needs.
 *
 * [networkRequired] is the single fact that matters for privacy: `Voice.isNetworkConnectionRequired()` on
 * Android is true for a voice whose text is sent to a server to be synthesised, and false for one synthesised
 * on the phone. A voice that needs the network is never chosen, whatever else it offers.
 *
 * [locale] is a language tag (`en-US`, `de-DE`); [quality] is the platform's own scale (see the constants),
 * used only to rank two voices of the same language against each other.
 */
data class OfferedVoice(
    val name: String,
    val locale: String,
    val networkRequired: Boolean,
    val quality: Int = QUALITY_NORMAL,
) {
    /** The language part of the tag: `en-US` reads as `en`, which is how a device locale is matched. */
    val language: String get() = locale.substringBefore('-').substringBefore('_').lowercase()

    /** How the picker shows it: the language tag and the engine's own name for the voice. */
    val label: String get() = "$locale · $name"

    /** Where the words become sound: developer text for the log line, never shown to the user. */
    val spokenWhere: String
        get() = if (networkRequired) "synthesised by the engine's cloud" else "synthesised on this phone"

    companion object {
        const val QUALITY_VERY_LOW = 100
        const val QUALITY_LOW = 200
        const val QUALITY_NORMAL = 300
        const val QUALITY_HIGH = 400
        const val QUALITY_VERY_HIGH = 500
    }
}

/**
 * Why no voice could be used. Every one of them is a fact the settings screen states in plain language, and
 * every one of them means silence rather than a voice that leaves the phone.
 */
enum class VoiceRefusal {
    /** The engine enumerated no voices at all: it cannot speak, and nothing is sent anywhere. */
    NO_VOICES_OFFERED,

    /** Every voice the engine offers needs the internet; there is nothing on-device to speak with. */
    NETWORK_VOICES_ONLY,

    /** The engine has on-device voices, but none for the language the replies should be in. */
    NO_OFFLINE_VOICE_FOR_LANGUAGE,

    /** Offline speech is off, and the engine has no voice at all for the language. */
    NO_VOICE_FOR_LANGUAGE,

    /** The user pinned a voice, and that voice needs the internet while offline speech is on. */
    PINNED_VOICE_IS_NETWORK_ONLY,

    /** The engine accepted the voice list but would not use the voice when asked to. */
    VOICE_NOT_ACCEPTED,
}

/** What the speaker will do with a sentence, decided once per engine start from the engine's own voice list. */
sealed interface VoiceChoice {
    /**
     * An on-device voice. [pinnedHonoured] is false when a pin was set but that voice is no longer offered
     * (uninstalled, or the engine was changed), so the best on-device voice for the language stood in.
     */
    data class Selected(val voice: OfferedVoice, val pinnedHonoured: Boolean = false) : VoiceChoice

    /** Nothing on-device can speak this language: the reply is not spoken at all. */
    data class Refused(val reason: VoiceRefusal) : VoiceChoice
}

/**
 * The one rule about which voice speaks, and the one setting that decides how strict it is.
 *
 * [choose] is pure and in common code on purpose. The rule is the operator's privacy requirement, so it is
 * decided here where a host test can prove every case — a network-only engine, one offline voice among network
 * ones, a language with no offline voice, a pin that needs the internet — without a phone, an engine or a voice
 * file. The Android speaker only maps real `Voice` objects into [OfferedVoice] and applies the answer.
 *
 * With [offlineOnly] on (the default, "Offline speech"):
 * 1. A pinned voice wins when it exists and is on-device. A pin that needs the network is **refused**, not
 *    quietly replaced: the user asked for those words to be spoken that way, and they must not leave the phone.
 * 2. A pin that no longer exists falls through to automatic selection, still on-device only.
 * 3. Automatic: the best-quality on-device voice whose language matches the device's own language, ties broken
 *    by name so the choice is the same on every run. A different region of the same language is acceptable
 *    (`en-GB` phone, `en-US` voice); a different language is not.
 * 4. Nothing on-device: a refusal that names why, never a network voice and never the engine's default voice.
 *
 * With [offlineOnly] off, the same order applies except that a network voice may be chosen: still offline
 * first (a local voice is quicker and works on a plane), then the best network voice of the language. A pin is
 * honoured whatever it needs. Nothing usable still gives a refusal with a reason, never silence without one.
 */
object VoiceSelection {

    /**
     * [language] is the language part the replies should be in (`en`, from `Locale.getDefault()`'s language).
     * [pinnedName] is the voice the user chose in the picker, if any. [offlineOnly] is the "Offline speech"
     * switch.
     */
    fun choose(
        voices: List<OfferedVoice>,
        language: String,
        pinnedName: String? = null,
        offlineOnly: Boolean = true,
    ): VoiceChoice {
        if (voices.isEmpty()) return VoiceChoice.Refused(VoiceRefusal.NO_VOICES_OFFERED)
        val allowed = if (offlineOnly) voices.filter { !it.networkRequired } else voices

        val pinned = pinnedName?.takeIf { it.isNotBlank() }?.let { name -> voices.firstOrNull { it.name == name } }
        if (pinned != null && offlineOnly && pinned.networkRequired) {
            return VoiceChoice.Refused(VoiceRefusal.PINNED_VOICE_IS_NETWORK_ONLY)
        }
        if (pinned != null) return VoiceChoice.Selected(pinned, pinnedHonoured = true)

        if (allowed.isEmpty()) return VoiceChoice.Refused(VoiceRefusal.NETWORK_VOICES_ONLY)
        // A full tag is accepted as well as the bare language, so `en-GB` and `en` both mean English.
        val wanted = language.trim().lowercase().substringBefore('-').substringBefore('_')
        val sameLanguage = if (wanted.isEmpty()) allowed else allowed.filter { it.language == wanted }
        if (sameLanguage.isEmpty()) {
            return VoiceChoice.Refused(
                if (offlineOnly) VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE else VoiceRefusal.NO_VOICE_FOR_LANGUAGE,
            )
        }
        val best = sameLanguage
            .sortedWith(
                compareByDescending<OfferedVoice> { !it.networkRequired }
                    .thenByDescending { it.quality }
                    .thenBy { it.name },
            )
            .first()
        return VoiceChoice.Selected(best, pinnedHonoured = false)
    }

    /**
     * The line the settings screen shows, in plain language, naming the problem and the language it is about.
     * [languageLabel] is the language's name as the phone says it (`English`), used only where it helps.
     *
     * Returned as [VoiceCopy] so the rule stays pure and the screen resolves it in the phone's language; the key's
     * one argument is the language label where the sentence names one.
     */
    fun explanation(reason: VoiceRefusal, languageLabel: String): VoiceCopy = when (reason) {
        VoiceRefusal.NO_VOICES_OFFERED -> VoiceCopy(VoiceKeys.REFUSAL_NO_VOICES)
        VoiceRefusal.NETWORK_VOICES_ONLY -> VoiceCopy(VoiceKeys.REFUSAL_NETWORK_VOICES_ONLY)
        VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE ->
            VoiceCopy(VoiceKeys.REFUSAL_NO_OFFLINE_VOICE_FOR_LANGUAGE, languageLabel)
        VoiceRefusal.NO_VOICE_FOR_LANGUAGE ->
            VoiceCopy(VoiceKeys.REFUSAL_NO_VOICE_FOR_LANGUAGE, languageLabel)
        VoiceRefusal.PINNED_VOICE_IS_NETWORK_ONLY -> VoiceCopy(VoiceKeys.REFUSAL_PINNED_VOICE_NETWORK_ONLY)
        VoiceRefusal.VOICE_NOT_ACCEPTED -> VoiceCopy(VoiceKeys.REFUSAL_VOICE_NOT_ACCEPTED)
    }

    /** The line written to the log when a sentence is held back because no usable voice was found. */
    fun logLine(reason: VoiceRefusal, languageLabel: String): String = when (reason) {
        VoiceRefusal.NO_VOICES_OFFERED -> "reply not spoken: the engine reported no voices"
        VoiceRefusal.NETWORK_VOICES_ONLY -> "reply not spoken: the engine offers only network voices, offline speech is on"
        VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE ->
            "reply not spoken: no offline voice is installed for $languageLabel"
        VoiceRefusal.NO_VOICE_FOR_LANGUAGE -> "reply not spoken: the engine has no voice for $languageLabel"
        VoiceRefusal.PINNED_VOICE_IS_NETWORK_ONLY ->
            "reply not spoken: the chosen voice requires a network connection"
        VoiceRefusal.VOICE_NOT_ACCEPTED -> "reply not spoken: the engine would not use its voice"
    }
}
