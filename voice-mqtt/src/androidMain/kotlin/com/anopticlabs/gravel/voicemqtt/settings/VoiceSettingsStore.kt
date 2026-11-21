package com.anopticlabs.gravel.voicemqtt.settings

import android.content.Context
import android.content.SharedPreferences
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The loop's settings, as the user edits them.
 *
 * Everything is in a private SharedPreferences file except the password, which is sealed by [KeystoreSecret]
 * and stored as ciphertext. Nothing here is ever logged: [VoiceSettings.toString] redacts the credentials, and
 * this store logs no values at all, so an app log or a crash report cannot carry the password, and neither can
 * the settings file itself.
 *
 * [VoiceSettings.speakReplies], [VoiceSettings.speakUnsolicited] and [VoiceSettings.headsetOnly] are independent
 * switches with an OFF position each, stored like any other value and read back as the user left them: nothing
 * here forces a value.
 * [VoiceSettings.disableAppReminders] is stored the same way and is off until the user turns it on, so a phone
 * that has never touched it keeps the behaviour it had before the switch existed.
 * [VoiceSettings.keepReady] is stored like the other speaking switches and is **on** until the user turns it off:
 * the warm speaking-notice service is what makes a reply's notice dependable on a pocketed phone, so an
 * upgraded install gets the fix, and turning it off is the user's own choice and survives a restart.
 *
 * The connection fields — address, port, TLS, both topics — are read through [VoiceConnectionDefaults], so a
 * value the user saved wins over a value [baked] into the build, and a build that baked in nothing leaves them
 * empty for the user to fill in. A save writes every one of them, which is what makes the user's answer final
 * from the first save onwards.
 */
class VoiceSettingsStore(
    context: Context,
    /** What this build baked in; injected so the rule can be exercised without a phone or a rebuild. */
    private val baked: BakedVoiceDefaults = bakedVoiceDefaults(),
) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secrets = KeystoreSecret()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<VoiceSettings> = _settings.asStateFlow()

    /**
     * The MQTT client id, minted once and kept: the broker's persistent session (cleanSession = false) is
     * keyed on it, which is what keeps the reply subscription alive across a brief reconnect.
     */
    val clientId: String = prefs.getString(KEY_CLIENT_ID, null) ?: ("gravel-voice-" + UUID.randomUUID().toString().take(8)).also {
        prefs.edit().putString(KEY_CLIENT_ID, it).apply()
    }

    /** Applies [transform] and stores the result; returns what was stored. */
    fun update(transform: (VoiceSettings) -> VoiceSettings): VoiceSettings {
        val next = transform(_settings.value)
        store(next)
        _settings.value = next
        return next
    }

    fun set(next: VoiceSettings): VoiceSettings = update { next }

    private fun read(): VoiceSettings {
        val speaking = VoiceSpeakingSettings.read(prefs.all)
        // Fetched with "has the user ever saved this field?", not "is the value non-null?": a field the user
        // emptied stays empty, and only a field the phone has never held is filled from what the build baked in.
        val connection = VoiceConnectionDefaults.resolve(
            stored = StoredConnectionValues(
                host = storedString(KEY_HOST),
                port = if (prefs.contains(KEY_PORT)) prefs.getInt(KEY_PORT, VoiceConnectionDefaults.UNSET_PORT) else null,
                tls = if (prefs.contains(KEY_TLS)) prefs.getBoolean(KEY_TLS, false) else null,
                requestTopic = storedString(KEY_INGRESS),
                responseTopic = storedString(KEY_EGRESS),
            ),
            baked = baked,
        )
        return VoiceSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            host = connection.host,
            port = connection.port,
            tls = connection.tls,
            trustSelfSigned = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = secrets.open(prefs.getString(KEY_PASSWORD, null)).orEmpty(),
            ingressTopic = connection.requestTopic,
            egressTopic = connection.responseTopic,
            speakReplies = speaking.speakReplies,
            speakUnsolicited = speaking.speakUnsolicited,
            headsetOnly = speaking.headsetOnly,
            preferredDevice = speaking.preferredDevice,
            ttsEngine = speaking.ttsEngine,
            preferredVoice = speaking.preferredVoice,
            offlineOnly = speaking.offlineOnly,
            keepReady = speaking.keepReady,
            disableAppReminders = prefs.getBoolean(KEY_DISABLE_APP_REMINDERS, false),
        )
    }

    /** The stored string — blank included, because clearing a field is the user's choice — or null if never saved. */
    private fun storedString(key: String): String? =
        if (prefs.contains(key)) prefs.getString(key, "").orEmpty() else null

    private fun store(next: VoiceSettings) {
        val editor = prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putString(KEY_HOST, next.host)
            .putInt(KEY_PORT, next.port)
            .putBoolean(KEY_TLS, next.tls)
            .putBoolean(KEY_TRUST_SELF_SIGNED, next.trustSelfSigned)
            .putString(KEY_USERNAME, next.username)
            .putString(KEY_INGRESS, next.ingressTopic)
            .putString(KEY_EGRESS, next.egressTopic)
            .putBoolean(VoiceSpeakingSettings.KEY_SPEAK_REPLIES, next.speakReplies)
            .putBoolean(VoiceSpeakingSettings.KEY_SPEAK_UNSOLICITED, next.speakUnsolicited)
            .putBoolean(VoiceSpeakingSettings.KEY_HEADSET_ONLY, next.headsetOnly)
        val preferred = next.preferredDevice
        if (preferred == null) {
            editor.remove(VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS)
                .remove(VoiceSpeakingSettings.KEY_PREFERRED_NAME)
        } else {
            editor.putString(VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS, preferred.address)
                .putString(VoiceSpeakingSettings.KEY_PREFERRED_NAME, preferred.name)
        }
        // The engine and voice choices are stored only when set, so "the phone's default engine" is the absence
        // of a value rather than the name of whichever engine happened to be default when the app was installed.
        editor.putStringOrRemove(VoiceSpeakingSettings.KEY_TTS_ENGINE, next.ttsEngine)
            .putStringOrRemove(VoiceSpeakingSettings.KEY_PREFERRED_VOICE, next.preferredVoice)
            .putBoolean(VoiceSpeakingSettings.KEY_OFFLINE_ONLY, next.offlineOnly)
            .putBoolean(VoiceSpeakingSettings.KEY_KEEP_READY, next.keepReady)
            .putBoolean(KEY_DISABLE_APP_REMINDERS, next.disableAppReminders)
        // The first increment's keys are migrated on read and gone after the first save, so a value it wrote
        // can never come back over a choice the user has since changed.
        editor.remove(VoiceSpeakingSettings.LEGACY_KEY_HEADSET_ADDRESS)
            .remove(VoiceSpeakingSettings.LEGACY_KEY_HEADSET_NAME)
            .remove(VoiceSpeakingSettings.LEGACY_KEY_SPEAK_ONLY_WITH_HEADSET)
        if (next.password.isEmpty()) {
            editor.remove(KEY_PASSWORD)
        } else {
            val sealed = secrets.seal(next.password)
            if (sealed == null) {
                // A password that cannot be sealed is not written at all; plaintext in a prefs file is worse
                // than an emptied field the user has to retype.
                logger.w { "broker password not stored: the keystore refused it" }
                editor.remove(KEY_PASSWORD)
            } else {
                editor.putString(KEY_PASSWORD, sealed)
            }
        }
        editor.apply()
    }

    private companion object {
        val logger = Logger.withTag("VoiceSettingsStore")
        const val PREFS = "gravel_voice_mqtt"
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_ENABLED = "enabled"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TLS = "tls"
        const val KEY_TRUST_SELF_SIGNED = "trustSelfSigned"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_INGRESS = "ingressTopic"
        const val KEY_EGRESS = "egressTopic"
        /** "Disable app reminders": false is the shipped position, so an upgraded phone keeps today's behaviour. */
        const val KEY_DISABLE_APP_REMINDERS = "disableAppReminders"
    }
}

/** Stores [value] under [key], or clears the key when there is no value to store. */
private fun SharedPreferences.Editor.putStringOrRemove(key: String, value: String?): SharedPreferences.Editor =
    if (value.isNullOrBlank()) remove(key) else putString(key, value)
