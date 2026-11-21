package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings

enum class ConnectionPhase { DISCONNECTED, CONNECTING, CONNECTED }

/** What the settings screen shows, so a wrong host is diagnosable from the phone. */
data class ConnectionState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    /** The last failure in the user's words; null while connected. Never contains a credential. */
    val lastError: String? = null,
)

/**
 * The transport seam, so the turn logic is testable without a broker. [connect] resolves once the connection
 * is up or has failed; [publish] returns false rather than throwing when the connection is not usable, which
 * is what makes a retry with the same requestId the client's decision.
 */
interface VoiceTransport {
    /**
     * Called for every message on the subscribed reply filter, with the topic it arrived on; the listener decides
     * whether it is ours. The topic matters because the filter can be a wildcard and a message that answers
     * nothing this phone asked is still recorded — and, if it carries words, spoken — so the record has to be
     * able to say where it came from.
     */
    fun setIncoming(listener: (topic: String, payload: ByteArray) -> Unit)

    suspend fun connect(settings: VoiceSettings): ConnectionState

    suspend fun disconnect()

    suspend fun publish(topic: String, payload: ByteArray): Boolean
}
