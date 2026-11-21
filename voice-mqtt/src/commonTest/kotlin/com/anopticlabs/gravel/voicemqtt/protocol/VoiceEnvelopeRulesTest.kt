package com.anopticlabs.gravel.voicemqtt.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The publish contract, as the server validates it: exactly five root keys, three frozen interaction
 * literals, canonical UUID v4 identifiers, a 1024-character text and an 8192-byte payload.
 */
class VoiceEnvelopeRulesTest {

    private val sessionId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301"
    private val requestId = "7c9e6679-7425-40de-944b-e07fc1f90ae7"

    private fun envelope(text: String) = VoiceEnvelope(sessionId = sessionId, requestId = requestId, text = text)

    @Test
    fun theWireCarriesExactlyTheFiveRootKeys() {
        val root = Json.parseToJsonElement(VoiceEnvelopeCodec.encode(envelope("TURN ON THE GARAGE LIGHTS"))).jsonObject
        assertEquals(
            setOf("version", "sessionId", "requestId", "text", "interaction"),
            root.keys,
        )
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        val interaction = root.getValue("interaction").jsonObject
        assertEquals(setOf("input", "output", "responseStyle"), interaction.keys)
        assertEquals("speech_to_text", interaction.getValue("input").jsonPrimitive.content)
        assertEquals("text_to_speech", interaction.getValue("output").jsonPrimitive.content)
        assertEquals("voice_concise", interaction.getValue("responseStyle").jsonPrimitive.content)
    }

    @Test
    fun theWireNeverCarriesACorrelationClientOrPlatformId() {
        val wire = VoiceEnvelopeCodec.encode(envelope("what time is it"))
        assertFalse(wire.contains("correlationId"))
        assertFalse(wire.contains("clientId"))
        assertFalse(wire.contains("platformId"))
    }

    @Test
    fun aWellFormedTurnIsAccepted() {
        assertNull(VoiceEnvelopeCodec.refusal(envelope("TURN ON THE GARAGE LIGHTS")))
    }

    @Test
    fun aBlankTranscriptIsRefused() {
        assertEquals(EnvelopeRefusal.BLANK_TEXT, VoiceEnvelopeCodec.refusal(envelope("   ")))
    }

    @Test
    fun aTwoThousandCharacterTextIsRefused() {
        val text = "a".repeat(2000)
        assertTrue(text.length > MAX_TEXT_CHARS)
        assertTrue(VoiceEnvelopeCodec.payloadBytes(envelope(text)) < MAX_PAYLOAD_BYTES)
        assertEquals(EnvelopeRefusal.TOO_LONG, VoiceEnvelopeCodec.refusal(envelope(text)))
    }

    @Test
    fun aPayloadOverEightKilobytesIsRefused() {
        // Four-byte UTF-8 characters: the only way a transcript reaches the wire's byte cap before its
        // character cap, which is why the byte cap is checked first.
        val text = "\uD83D\uDE00".repeat(2100)
        assertTrue(VoiceEnvelopeCodec.payloadBytes(envelope(text)) > MAX_PAYLOAD_BYTES)
        assertEquals(EnvelopeRefusal.TOO_LARGE, VoiceEnvelopeCodec.refusal(envelope(text)))
    }

    @Test
    fun bothIdentifiersMustBeCanonicalLowercaseUuidV4() {
        assertTrue(VoiceEnvelopeCodec.isUuidV4(sessionId))
        assertFalse(VoiceEnvelopeCodec.isUuidV4(sessionId.uppercase()), "uppercase is not the wire's format")
        assertFalse(VoiceEnvelopeCodec.isUuidV4("3f2504e0-4f89-11d3-9a0c-0305e82c3301"), "version nibble must be 4")
        assertFalse(VoiceEnvelopeCodec.isUuidV4("3f2504e0-4f89-41d3-ca0c-0305e82c3301"), "variant must be 8, 9, a or b")
        assertFalse(VoiceEnvelopeCodec.isUuidV4("not-a-uuid"))
        assertEquals(
            EnvelopeRefusal.BAD_REQUEST_ID,
            VoiceEnvelopeCodec.refusal(envelope("hello").copy(requestId = "not-a-uuid")),
        )
    }
}
