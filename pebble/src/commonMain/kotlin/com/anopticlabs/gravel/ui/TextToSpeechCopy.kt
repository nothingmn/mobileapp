package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.voice_best_voice
import coreapp.pebble.generated.resources.voice_cloud_speech_note
import coreapp.pebble.generated.resources.voice_engine_default
import coreapp.pebble.generated.resources.voice_engine_default_marked
import coreapp.pebble.generated.resources.voice_engine_intro
import coreapp.pebble.generated.resources.voice_no_engine_installed
import coreapp.pebble.generated.resources.voice_nothing_spoken
import coreapp.pebble.generated.resources.voice_offline_speech_subtitle
import coreapp.pebble.generated.resources.voice_offline_speech_title
import coreapp.pebble.generated.resources.voice_pinned_voice_gone
import coreapp.pebble.generated.resources.voice_pinned_voice_gone_this_language
import coreapp.pebble.generated.resources.voice_section_engine
import coreapp.pebble.generated.resources.voice_section_speaking
import coreapp.pebble.generated.resources.voice_section_voice
import coreapp.pebble.generated.resources.voice_speaking_with_cloud
import coreapp.pebble.generated.resources.voice_speaking_with_offline
import coreapp.pebble.generated.resources.voice_tts_item_description
import coreapp.pebble.generated.resources.voice_tts_item_title
import coreapp.pebble.generated.resources.voice_tts_title
import coreapp.pebble.generated.resources.voice_voice_cloud
import coreapp.pebble.generated.resources.voice_voice_on_phone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One sentence Settings > AI > Text to Speech says: the string resource that holds it and the values it needs.
 *
 * The screen's own words are looked up where they are drawn, but three sentences are assembled from a rule — what
 * the switch is warning about, what the current voice is, and whether a pinned voice is gone. Those are decided by
 * plain functions proved by the host tests, so they hand back a [SpeechCopy] and never an English sentence, and the
 * screen resolves it through [text].
 */
data class SpeechCopy(
    val key: String,
    val args: List<String> = emptyList(),
) {
    constructor(key: String, arg: String) : this(key, listOf(arg))
}

/**
 * Every string this screen can show, by its Compose resource name — the same names as
 * `composeResources/values/strings.xml`. A key with no resource cannot drift apart from its XML unnoticed: a test
 * compares this set with both locale files and with the lookup table one for one.
 */
object TextToSpeechKeys {
    const val TTS_TITLE = "voice_tts_title"
    const val SECTION_SPEAKING = "voice_section_speaking"
    const val OFFLINE_SPEECH_TITLE = "voice_offline_speech_title"
    const val OFFLINE_SPEECH_SUBTITLE = "voice_offline_speech_subtitle"
    const val SECTION_ENGINE = "voice_section_engine"
    const val ENGINE_INTRO = "voice_engine_intro"
    const val ENGINE_DEFAULT = "voice_engine_default"
    const val ENGINE_DEFAULT_MARKED = "voice_engine_default_marked"
    const val NO_ENGINE_INSTALLED = "voice_no_engine_installed"
    const val SECTION_VOICE = "voice_section_voice"
    const val BEST_VOICE = "voice_best_voice"
    const val VOICE_ON_PHONE = "voice_voice_on_phone"
    const val VOICE_CLOUD = "voice_voice_cloud"
    const val CLOUD_SPEECH_NOTE = "voice_cloud_speech_note"
    const val NOTHING_SPOKEN = "voice_nothing_spoken"
    const val SPEAKING_WITH_OFFLINE = "voice_speaking_with_offline"
    const val SPEAKING_WITH_CLOUD = "voice_speaking_with_cloud"
    const val PINNED_VOICE_GONE = "voice_pinned_voice_gone"
    const val PINNED_VOICE_GONE_THIS_LANGUAGE = "voice_pinned_voice_gone_this_language"
    const val TTS_ITEM_TITLE = "voice_tts_item_title"
    const val TTS_ITEM_DESCRIPTION = "voice_tts_item_description"

    /** Every key this screen ships, so the resource files and the lookup table can be held to it. */
    val all: Set<String> = setOf(
        TTS_TITLE,
        SECTION_SPEAKING,
        OFFLINE_SPEECH_TITLE,
        OFFLINE_SPEECH_SUBTITLE,
        SECTION_ENGINE,
        ENGINE_INTRO,
        ENGINE_DEFAULT,
        ENGINE_DEFAULT_MARKED,
        NO_ENGINE_INSTALLED,
        SECTION_VOICE,
        BEST_VOICE,
        VOICE_ON_PHONE,
        VOICE_CLOUD,
        CLOUD_SPEECH_NOTE,
        NOTHING_SPOKEN,
        SPEAKING_WITH_OFFLINE,
        SPEAKING_WITH_CLOUD,
        PINNED_VOICE_GONE,
        PINNED_VOICE_GONE_THIS_LANGUAGE,
        TTS_ITEM_TITLE,
        TTS_ITEM_DESCRIPTION,
    )
}

/** The sentence [this] names, in the phone's language. */
@Composable
fun SpeechCopy.text(): String = stringResourceFor(key, *args.toTypedArray())

/**
 * The text of [key], in the phone's language, for the screen's own labels.
 *
 * One lookup for the whole screen: a label and a sentence assembled by a rule resolve through the same table, so a
 * key with no resource fails loudly here instead of showing an empty line.
 */
@Composable
fun stringResourceFor(key: String, vararg args: String): String =
    stringResource(resourceFor(key) ?: error("no string resource for $key"), *args)

/**
 * The Compose resource that holds [key], or null for a key this build does not know.
 *
 * Not `@Composable`: the same table serves the screen and any caller that has to resolve a sentence outside
 * composition, and the test that proves every key has a resource only needs the table.
 */
fun resourceFor(key: String): StringResource? = when (key) {
    TextToSpeechKeys.TTS_TITLE -> Res.string.voice_tts_title
    TextToSpeechKeys.SECTION_SPEAKING -> Res.string.voice_section_speaking
    TextToSpeechKeys.OFFLINE_SPEECH_TITLE -> Res.string.voice_offline_speech_title
    TextToSpeechKeys.OFFLINE_SPEECH_SUBTITLE -> Res.string.voice_offline_speech_subtitle
    TextToSpeechKeys.SECTION_ENGINE -> Res.string.voice_section_engine
    TextToSpeechKeys.ENGINE_INTRO -> Res.string.voice_engine_intro
    TextToSpeechKeys.ENGINE_DEFAULT -> Res.string.voice_engine_default
    TextToSpeechKeys.ENGINE_DEFAULT_MARKED -> Res.string.voice_engine_default_marked
    TextToSpeechKeys.NO_ENGINE_INSTALLED -> Res.string.voice_no_engine_installed
    TextToSpeechKeys.SECTION_VOICE -> Res.string.voice_section_voice
    TextToSpeechKeys.BEST_VOICE -> Res.string.voice_best_voice
    TextToSpeechKeys.VOICE_ON_PHONE -> Res.string.voice_voice_on_phone
    TextToSpeechKeys.VOICE_CLOUD -> Res.string.voice_voice_cloud
    TextToSpeechKeys.CLOUD_SPEECH_NOTE -> Res.string.voice_cloud_speech_note
    TextToSpeechKeys.NOTHING_SPOKEN -> Res.string.voice_nothing_spoken
    TextToSpeechKeys.SPEAKING_WITH_OFFLINE -> Res.string.voice_speaking_with_offline
    TextToSpeechKeys.SPEAKING_WITH_CLOUD -> Res.string.voice_speaking_with_cloud
    TextToSpeechKeys.PINNED_VOICE_GONE -> Res.string.voice_pinned_voice_gone
    TextToSpeechKeys.PINNED_VOICE_GONE_THIS_LANGUAGE -> Res.string.voice_pinned_voice_gone_this_language
    TextToSpeechKeys.TTS_ITEM_TITLE -> Res.string.voice_tts_item_title
    TextToSpeechKeys.TTS_ITEM_DESCRIPTION -> Res.string.voice_tts_item_description
    else -> null
}
