package com.anopticlabs.gravel.voicemqtt.settings

/**
 * The speaking settings as they sit in the preferences file, plus the migrations an older install needs.
 *
 * That first build stored a headset choice but no switch at all: replies were spoken only with a Bluetooth
 * output connected and the settings screen showed the switch permanently on. So a file carrying only the old
 * keys reads back as *speak replies, headset only, that device* — exactly the behaviour it had — rather than as
 * the new defaults with the user's chosen device thrown away. The legacy keys are removed by the next save.
 *
 * The engine and voice keys arrived with the on-device privacy rule. A file written before them has neither, so
 * it reads back as *the phone's default engine, the best offline voice* — the safer of the two, because a
 * default engine's network voices are still refused by [com.anopticlabs.gravel.voicemqtt.speech.VoiceSelection].
 *
 * Kept in common code, with the values handed in as a plain map, so a host test can prove what a legacy file
 * reads back as without an Android preferences file.
 */
object VoiceSpeakingSettings {

    const val KEY_SPEAK_REPLIES = "speakReplies"
    const val KEY_HEADSET_ONLY = "headsetOnly"
    const val KEY_PREFERRED_ADDRESS = "preferredAddress"
    const val KEY_PREFERRED_NAME = "preferredName"
    const val KEY_TTS_ENGINE = "ttsEngine"
    const val KEY_PREFERRED_VOICE = "preferredVoice"
    const val KEY_OFFLINE_ONLY = "offlineOnly"

    /** The first increment's keys: its headset choice and the switch it never wrote. */
    const val LEGACY_KEY_SPEAK_ONLY_WITH_HEADSET = "speakOnlyWithHeadset"
    const val LEGACY_KEY_HEADSET_ADDRESS = "headsetAddress"
    const val LEGACY_KEY_HEADSET_NAME = "headsetName"

    /** What the speaking section holds, read back from [stored]. */
    data class Speaking(
        val speakReplies: Boolean,
        val headsetOnly: Boolean,
        val preferredDevice: PreferredDevice?,
        val ttsEngine: String? = null,
        val preferredVoice: String? = null,
        val offlineOnly: Boolean = true,
    )

    fun read(stored: Map<String, Any?>): Speaking = Speaking(
        speakReplies = stored[KEY_SPEAK_REPLIES] as? Boolean ?: true,
        headsetOnly = (stored[KEY_HEADSET_ONLY] as? Boolean)
            ?: (stored[LEGACY_KEY_SPEAK_ONLY_WITH_HEADSET] as? Boolean)
            ?: true,
        preferredDevice = preferred(stored),
        ttsEngine = (stored[KEY_TTS_ENGINE] as? String)?.takeIf { it.isNotBlank() },
        preferredVoice = (stored[KEY_PREFERRED_VOICE] as? String)?.takeIf { it.isNotBlank() },
        // A file written before the switch existed reads back as ON: the stricter choice, not a looser default.
        offlineOnly = stored[KEY_OFFLINE_ONLY] as? Boolean ?: true,
    )

    private fun preferred(stored: Map<String, Any?>): PreferredDevice? {
        val address = ((stored[KEY_PREFERRED_ADDRESS] as? String) ?: (stored[LEGACY_KEY_HEADSET_ADDRESS] as? String))
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val name = ((stored[KEY_PREFERRED_NAME] as? String) ?: (stored[LEGACY_KEY_HEADSET_NAME] as? String))
            ?.takeIf { it.isNotBlank() }
        return PreferredDevice(address, name ?: address)
    }
}
