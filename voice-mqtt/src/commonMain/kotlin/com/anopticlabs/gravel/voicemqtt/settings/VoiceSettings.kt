package com.anopticlabs.gravel.voicemqtt.settings

import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceTopics

/** A field the settings screen can show an inline error against. */
enum class VoiceField { HOST, PORT, INGRESS_TOPIC, EGRESS_TOPIC }

/**
 * A Bluetooth audio device the user pinned replies to. The name is for the picker only; nothing here is ever
 * sent anywhere.
 */
data class PreferredDevice(val address: String, val name: String)

/**
 * Everything the client needs at connect time.
 *
 * Nothing here is compiled in: the host, port, TLS, credentials and both topics are the user's, and changing
 * any of them reconnects the client. A build may *pre-fill* the connection fields by baking values in (see
 * [BakedVoiceDefaults] and [VoiceConnectionDefaults]); a build that bakes nothing — which is what this
 * repository ships — leaves them empty, and [errors] then says so field by field until the user supplies them.
 *
 * The two speaking switches are independent, and neither is forced:
 * - [speakReplies] (default on) is whether replies are spoken at all. Off is the user's own choice, so the
 *   silence that follows is correct: the reply still arrives, and a later increment still writes it to the note.
 * - [headsetOnly] (default on) is whether playback is restricted to Bluetooth. Off means whatever output is
 *   active, the phone's speaker included.
 *
 * [preferredDevice] only means anything while [headsetOnly] is on: it narrows playback to that one device, and
 * a device that is paired but not connected is still not an output.
 */
data class VoiceSettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = VoiceConnectionDefaults.UNSET_PORT,
    val tls: Boolean = false,
    /** Off by default: a self-signed broker is a deliberate choice, never a silent fallback. */
    val trustSelfSigned: Boolean = false,
    val username: String = "",
    val password: String = "",
    val ingressTopic: String = "",
    val egressTopic: String = "",
    val speakReplies: Boolean = true,
    val headsetOnly: Boolean = true,
    /** The one Bluetooth device replies may be spoken on; null means any of them. */
    val preferredDevice: PreferredDevice? = null,
    /**
     * The text-to-speech engine replies are synthesised with, by package-qualified name (`Voice.name` of an
     * engine, as `TextToSpeech.getEngines()` lists it). Null means the phone's own default engine. Whatever the
     * engine, the voice rule stands: only a voice that synthesises on the phone may speak.
     */
    val ttsEngine: String? = null,
    /** The one voice within that engine, by the engine's own name for it; null picks the best offline one. */
    val preferredVoice: String? = null,
    /**
     * "Offline speech", default on: only a voice that synthesises on the phone may speak, and every utterance
     * asks the engine for embedded synthesis. Off permits a voice that sends the text to the engine's cloud,
     * which the settings screen states plainly before it is turned off.
     */
    val offlineOnly: Boolean = true,
) {
    /** Field-keyed inline errors; empty means the settings may be used. Copy, resolved by the screen. */
    fun errors(): Map<VoiceField, VoiceCopy> = buildMap {
        if (host.isBlank()) put(VoiceField.HOST, VoiceCopy(VoiceKeys.ERROR_HOST_BLANK))
        else if (host.any { it.isWhitespace() }) put(VoiceField.HOST, VoiceCopy(VoiceKeys.ERROR_HOST_SPACE))
        // No port at all is a different mistake from a port that is not one, and the screen says which.
        when {
            port == VoiceConnectionDefaults.UNSET_PORT -> put(VoiceField.PORT, VoiceCopy(VoiceKeys.ERROR_PORT_BLANK))
            port !in 1..65535 -> put(VoiceField.PORT, VoiceCopy(VoiceKeys.ERROR_PORT_RANGE))
        }
        VoiceTopics.refusal(ingressTopic, allowWildcard = false)?.let { put(VoiceField.INGRESS_TOPIC, it) }
        VoiceTopics.refusal(egressTopic, allowWildcard = true)?.let { put(VoiceField.EGRESS_TOPIC, it) }
    }

    val valid: Boolean get() = errors().isEmpty()

    /** The URL the transport connects to: `tcp://` or `ssl://`, plainly on the port given. */
    val serverUri: String get() = "${if (tls) "ssl" else "tcp"}://$host:$port"

    /** What the connection shows while it is up; the credentials are never part of it. */
    val serverDescription: String get() = serverUri

    /**
     * Redacted on purpose: this object travels into logs and crash reports, and the password must not. The
     * username is reduced to whether one is set for the same reason.
     */
    override fun toString(): String =
        "VoiceSettings(enabled=$enabled, server=$serverUri, tls=$tls, trustSelfSigned=$trustSelfSigned, " +
            "username=${if (username.isEmpty()) "" else "<set>"}, password=${if (password.isEmpty()) "" else "<set>"}, " +
            "ingress=$ingressTopic, egress=$egressTopic, speakReplies=$speakReplies, headsetOnly=$headsetOnly, " +
            "headset=${preferredDevice?.name ?: "any"}, engine=${ttsEngine ?: "system default"}, " +
            "voice=${preferredVoice ?: "automatic"}, offlineOnly=$offlineOnly)"
}
