package com.anopticlabs.gravel.voicemqtt.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The three literals the interaction block carries. They are frozen: the server validates all three and
 * carries none of them forward, so nothing about the answer's length, tone or format depends on them.
 */
object VoiceInteractionLiterals {
    const val INPUT = "speech_to_text"
    const val OUTPUT = "text_to_speech"
    const val RESPONSE_STYLE = "voice_concise"
}

/** The wire's own limits; both are client-side refusals before anything is published. */
const val MAX_TEXT_CHARS = 1024
const val MAX_PAYLOAD_BYTES = 8192
const val ENVELOPE_VERSION = 1

@Serializable
data class VoiceInteraction(
    val input: String = VoiceInteractionLiterals.INPUT,
    val output: String = VoiceInteractionLiterals.OUTPUT,
    val responseStyle: String = VoiceInteractionLiterals.RESPONSE_STYLE,
)

/**
 * The publish envelope, and nothing else: exactly five root keys. The server refuses identity in the
 * payload (no clientId, no platformId, no user id, no correlationId), so this type cannot grow one.
 */
@Serializable
data class VoiceEnvelope(
    val version: Int = ENVELOPE_VERSION,
    val sessionId: String,
    val requestId: String,
    val text: String,
    val interaction: VoiceInteraction = VoiceInteraction(),
)

/** Why a turn never leaves the phone. Each one is heard by the user; none is a silent drop. */
enum class EnvelopeRefusal { BLANK_TEXT, TOO_LARGE, TOO_LONG, BAD_SESSION_ID, BAD_REQUEST_ID }

object VoiceEnvelopeCodec {

    /**
     * encodeDefaults, because the wire must carry all five root keys and all three interaction literals;
     * kotlinx.serialization omits defaulted fields unless told otherwise, which would send an envelope the
     * server rejects for a missing key.
     */
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** Canonical lowercase UUID v4: version nibble 4, variant 8/9/a/b. Anything else is rejected server-side. */
    private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun isUuidV4(value: String): Boolean = UUID_V4.matches(value)

    fun encode(envelope: VoiceEnvelope): String = json.encodeToString(envelope)

    fun payload(envelope: VoiceEnvelope): ByteArray = encode(envelope).encodeToByteArray()

    /** The exact byte count the broker would carry, so the 8192-byte cap is checked on what is sent. */
    fun payloadBytes(envelope: VoiceEnvelope): Int = payload(envelope).size

    /**
     * Null when [envelope] may be published. The byte cap is checked before the character cap: with the
     * 1024-character cap in place a 8192-byte payload is only reachable through multi-byte text, and the
     * byte cap is the wire's own limit, so it is the one that must hold if the character cap ever moves.
     */
    fun refusal(envelope: VoiceEnvelope): EnvelopeRefusal? = when {
        envelope.text.isBlank() -> EnvelopeRefusal.BLANK_TEXT
        !isUuidV4(envelope.sessionId) -> EnvelopeRefusal.BAD_SESSION_ID
        !isUuidV4(envelope.requestId) -> EnvelopeRefusal.BAD_REQUEST_ID
        payloadBytes(envelope) > MAX_PAYLOAD_BYTES -> EnvelopeRefusal.TOO_LARGE
        envelope.text.length > MAX_TEXT_CHARS -> EnvelopeRefusal.TOO_LONG
        else -> null
    }
}
