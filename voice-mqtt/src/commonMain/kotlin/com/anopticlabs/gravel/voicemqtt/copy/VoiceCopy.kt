package com.anopticlabs.gravel.voicemqtt.copy

/**
 * One piece of the feature's own copy: the string resource that holds it and the values the sentence needs.
 *
 * The split this type exists for: the rules that decide *what* to say (which refusal, which suppression, which
 * failure sentence) live in common code and are proved by the host tests, while *how it reads* belongs to the
 * platform and to the phone's language. So a rule hands back a [VoiceCopy] and never an English sentence, and
 * the edges that can see the platform — the Compose screen and the Android speaker — resolve it through
 * [VoiceText]. Nothing here needs a `Composable`, which is what lets the spoken sentences be localised too.
 *
 * [key] is the resource name itself (`voice_refusal_no_voices`), so the copy is greppable in the resource files
 * and one test can prove that both locales hold exactly the keys [VoiceKeys] names.
 */
data class VoiceCopy(
    val key: String,
    val args: List<String> = emptyList(),
) {
    constructor(key: String, arg: String) : this(key, listOf(arg))
}

/**
 * Resolves a [VoiceCopy] into text in the user's language.
 *
 * One implementation per platform. In this module that is the Android one, which reads
 * `res/values/strings.xml` (and every `values-*` translation beside it) through a `Context`, so it works from
 * a coroutine speaking a reply just as well as from composition.
 */
fun interface VoiceText {
    fun of(copy: VoiceCopy): String
}
