package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

/**
 * Gravel: the phone's text-to-speech settings, as common code sees them — Settings > AI > Text to Speech.
 *
 * The speech engine is Android-only (the voice loop's module is a JVM library), so :pebble, which has iOS
 * targets, cannot depend on it. This interface therefore carries **plain data only** — engine names, voice
 * names with the one flag that matters, the current choice — and common code looks the implementation up with
 * `getOrNull`. A missing binding means "this build has no speech engine to configure", which is what a build
 * with VOICE_MQTT_ENABLED off produces: no binding, no Settings item, no route to the screen.
 *
 * The rule the screen exists for: with [TextToSpeechFacts.offlineOnly] **on** ("Offline speech", the default)
 * the engine may only use a voice that synthesises on the phone, so a reply is never sent to a cloud service to
 * be spoken. Turning it off is allowed, and the screen says in one sentence what that means.
 */
interface TextToSpeechSettings {
    /** False leaves the Settings item out entirely; true only where the Android side bound an engine. */
    val supported: Boolean

    /** What the phone has now; read again by [refresh] and after every change. */
    val facts: StateFlow<TextToSpeechFacts>

    fun refresh()

    /** The engine to speak with, by the name [SpeechEngine.name]; null is the phone's default engine. */
    fun chooseEngine(name: String?)

    /** The one voice within that engine, by [SpeechVoice.name]; null is the best voice for the language. */
    fun chooseVoice(name: String?)

    /** "Offline speech": on means only a voice that synthesises on this phone may speak. */
    fun setOfflineOnly(enabled: Boolean)

    /** The screen itself; the top bar and the layout are the app's own. */
    @Composable
    fun Screen(topBarParams: TopBarParams)
}

/** Null in a build without the speech engine, because nothing binds [TextToSpeechSettings] then. */
@Composable
fun rememberTextToSpeechSettings(): TextToSpeechSettings? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<TextToSpeechSettings>() }
}

/** One installed text-to-speech engine, as the picker shows it. */
data class SpeechEngine(
    val name: String,
    val label: String,
    val isSystemDefault: Boolean = false,
)

/**
 * One voice an engine offers. [offline] is the fact the whole screen is about: true when the voice synthesises
 * on this phone, false when the engine sends the text to its own cloud service to be spoken.
 */
data class SpeechVoice(
    val name: String,
    val locale: String,
    val offline: Boolean,
    val quality: Int = 0,
) {
    /** How the picker shows it: the language tag and the engine's own name for the voice. */
    val label: String get() = "$locale · $name"
}

/**
 * Everything Settings > AI > Text to Speech shows, in plain values: what engines exist, which is chosen, which
 * voices it offers, which voice speaks the next reply, whether offline speech is on, and — where something is
 * wrong — the one sentence that says so. Never a network voice silently standing in for an offline one.
 */
data class TextToSpeechFacts(
    /** False until the phone was first read; nothing below is known before. */
    val read: Boolean = false,
    val engines: List<SpeechEngine> = emptyList(),
    /** The chosen engine's name; null is the phone's default engine. */
    val engine: String? = null,
    /** Blank until an engine has been read; the screen then says "the phone's default engine" itself. */
    val engineLabel: String = "",
    /** The pinned voice's name; null is the best voice for the phone's language. */
    val voice: String? = null,
    /** "Offline speech": on means only a voice that synthesises on this phone may speak. */
    val offlineOnly: Boolean = true,
    /** The voices the chosen engine offers — offline ones first, cloud ones last. */
    val voices: List<SpeechVoice> = emptyList(),
    /** The voice that will speak the next reply, or null when nothing can. */
    val inUse: SpeechVoice? = null,
    /** Why nothing can speak, in plain words; null when a voice will speak. */
    val problem: String? = null,
    /** The language the replies should be in, as the phone names it (`English`). */
    val languageLabel: String = "",
) {
    /** The voices the picker may offer: only on-phone ones while offline speech is on. */
    val selectableVoices: List<SpeechVoice>
        get() = if (offlineOnly) voices.filter { it.offline } else voices
}

/** The switch's own words, used by the screen and by the test that proves they are plain. */
val OFFLINE_SPEECH_TITLE = SpeechCopy(TextToSpeechKeys.OFFLINE_SPEECH_TITLE)
val OFFLINE_SPEECH_SUBTITLE = SpeechCopy(TextToSpeechKeys.OFFLINE_SPEECH_SUBTITLE)

/**
 * The sentence under the switch while offline speech is **off**, so nobody turns the text-leaves-the-phone
 * mode on without reading it. Null while it is on: there is nothing to warn about.
 */
fun cloudSpeechNote(offlineOnly: Boolean): SpeechCopy? = if (offlineOnly) {
    null
} else {
    SpeechCopy(TextToSpeechKeys.CLOUD_SPEECH_NOTE)
}

/** The Settings item is shown only where an engine is bound; with VOICE_MQTT_ENABLED off nothing is. */
fun textToSpeechItemShown(supported: Boolean): Boolean = supported

/**
 * What the screen says the current voice is, in words that say where the words become sound. Null before the
 * phone has been read.
 */
fun speechInUseLine(facts: TextToSpeechFacts): SpeechCopy? {
    if (!facts.read) return null
    val voice = facts.inUse ?: return facts.problem?.let { SpeechCopy(TextToSpeechKeys.NOTHING_SPOKEN, it) }
    return if (voice.offline) {
        SpeechCopy(TextToSpeechKeys.SPEAKING_WITH_OFFLINE, voice.label)
    } else {
        SpeechCopy(TextToSpeechKeys.SPEAKING_WITH_CLOUD, voice.label)
    }
}

/**
 * The note when a pinned voice is no longer offered by the engine — it was removed, or the engine was changed.
 * The best voice for the language stands in, and the screen says so rather than showing a choice that is gone.
 */
fun pinnedVoiceNote(facts: TextToSpeechFacts): SpeechCopy? {
    val pinned = facts.voice ?: return null
    if (facts.inUse?.name == pinned) return null
    return if (facts.languageLabel.isBlank()) {
        // The sentence needs a noun phrase for the language; with no language known, it says "this language".
        SpeechCopy(TextToSpeechKeys.PINNED_VOICE_GONE_THIS_LANGUAGE)
    } else {
        SpeechCopy(TextToSpeechKeys.PINNED_VOICE_GONE, facts.languageLabel)
    }
}
