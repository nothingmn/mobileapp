package com.anopticlabs.gravel.voicemqtt.settings

import com.anopticlabs.gravel.voicemqtt.protocol.DEFAULT_EGRESS_TOPIC
import com.anopticlabs.gravel.voicemqtt.protocol.DEFAULT_INGRESS_TOPIC
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceTopics

/** The broker defaults from the protocol document. Every one of them is editable in Settings. */
const val DEFAULT_BROKER_HOST = "10.0.0.157"
const val DEFAULT_BROKER_PORT = 1883

/** A field the settings screen can show an inline error against. */
enum class VoiceField { HOST, PORT, INGRESS_TOPIC, EGRESS_TOPIC }

/**
 * Everything the client needs at connect time. Nothing here is compiled in beyond the defaults: the host,
 * port, TLS, credentials and both topics are the user's, and changing any of them reconnects the client.
 *
 * [speakOnlyWithHeadset] defaults to true, and there is deliberately no always-speak-without-a-headset mode:
 * a reply is spoken only when a Bluetooth audio device is connected at the moment of speaking.
 */
data class VoiceSettings(
    val enabled: Boolean = false,
    val host: String = DEFAULT_BROKER_HOST,
    val port: Int = DEFAULT_BROKER_PORT,
    val tls: Boolean = false,
    /** Off by default: a self-signed broker is a deliberate choice, never a silent fallback. */
    val trustSelfSigned: Boolean = false,
    val username: String = "",
    val password: String = "",
    val ingressTopic: String = DEFAULT_INGRESS_TOPIC,
    val egressTopic: String = DEFAULT_EGRESS_TOPIC,
    val speakOnlyWithHeadset: Boolean = true,
    /** BLE/MAC address of the one Bluetooth device replies may be spoken on; null means any of them. */
    val headsetAddress: String? = null,
    /** The chosen device's name, for the picker only; never sent anywhere. */
    val headsetName: String? = null,
) {
    /** Field-keyed inline errors; empty means the settings may be used. */
    fun errors(): Map<VoiceField, String> = buildMap {
        if (host.isBlank()) put(VoiceField.HOST, "Enter the broker's address")
        else if (host.any { it.isWhitespace() }) put(VoiceField.HOST, "An address cannot contain a space")
        if (port !in 1..65535) put(VoiceField.PORT, "A port is between 1 and 65535")
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
            "ingress=$ingressTopic, egress=$egressTopic, headsetOnly=$speakOnlyWithHeadset, headset=${headsetName ?: "any"})"
}
