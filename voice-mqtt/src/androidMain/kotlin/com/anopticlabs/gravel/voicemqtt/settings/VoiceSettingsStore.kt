package com.anopticlabs.gravel.voicemqtt.settings

import android.content.Context
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.voicemqtt.protocol.DEFAULT_EGRESS_TOPIC
import com.anopticlabs.gravel.voicemqtt.protocol.DEFAULT_INGRESS_TOPIC
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
 * [VoiceSettings.speakOnlyWithHeadset] is stored but has no OFF position — the operator's rule is that there
 * is no always-speak-without-a-headset mode — so it is read back as true whatever the file says.
 */
class VoiceSettingsStore(context: Context) {

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
        val next = transform(_settings.value).copy(speakOnlyWithHeadset = true)
        store(next)
        _settings.value = next
        return next
    }

    fun set(next: VoiceSettings): VoiceSettings = update { next }

    private fun read(): VoiceSettings = VoiceSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        host = prefs.getString(KEY_HOST, null) ?: DEFAULT_BROKER_HOST,
        port = prefs.getInt(KEY_PORT, DEFAULT_BROKER_PORT),
        tls = prefs.getBoolean(KEY_TLS, false),
        trustSelfSigned = prefs.getBoolean(KEY_TRUST_SELF_SIGNED, false),
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = secrets.open(prefs.getString(KEY_PASSWORD, null)).orEmpty(),
        ingressTopic = prefs.getString(KEY_INGRESS, null) ?: DEFAULT_INGRESS_TOPIC,
        egressTopic = prefs.getString(KEY_EGRESS, null) ?: DEFAULT_EGRESS_TOPIC,
        speakOnlyWithHeadset = true,
        headsetAddress = prefs.getString(KEY_HEADSET_ADDRESS, null),
        headsetName = prefs.getString(KEY_HEADSET_NAME, null),
    )

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
            .putString(KEY_HEADSET_ADDRESS, next.headsetAddress)
            .putString(KEY_HEADSET_NAME, next.headsetName)
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
        const val KEY_HEADSET_ADDRESS = "headsetAddress"
        const val KEY_HEADSET_NAME = "headsetName"
    }
}
