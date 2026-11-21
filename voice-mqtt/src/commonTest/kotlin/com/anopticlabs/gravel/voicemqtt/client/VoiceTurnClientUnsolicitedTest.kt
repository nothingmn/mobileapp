package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureStore
import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureVerdict
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelope
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelopeCodec
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.SpeechOutput
import com.anopticlabs.gravel.voicemqtt.speech.VoiceLoopSentences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The opened-up reply filter, end to end through the client: what is said, what is only recorded, what is filed,
 * and what the switch changes.
 *
 * The rules under test, in one place because they interact:
 * - a matched answer is spoken, filed and recorded exactly as it was before this change;
 * - a message that answers nothing and carries words is spoken and never filed;
 * - an opaque code, an empty payload and a payload with no text are recorded and not spoken;
 * - the same content inside the dedupe window is said once;
 * - with the switch off, the unmatched half is recorded and silent — and the matched half is untouched.
 */
class VoiceTurnClientUnsolicitedTest {

    private class FakeTransport : VoiceTransport {
        val published = mutableListOf<Pair<String, VoiceEnvelope>>()

        override fun setIncoming(listener: (topic: String, payload: ByteArray) -> Unit) = Unit

        override suspend fun connect(settings: VoiceSettings) = ConnectionState(ConnectionPhase.CONNECTED)

        override suspend fun disconnect() = Unit

        override suspend fun publish(topic: String, payload: ByteArray): Boolean {
            published += topic to VoiceEnvelopeCodec.json.decodeFromString<VoiceEnvelope>(payload.decodeToString())
            return true
        }
    }

    private class Recorder : SpeechOutput {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) {
            spoken += text
        }
    }

    private var clock = 1_000_000L
    private var minted = 0
    private var speakUnsolicited = true

    private val settings: VoiceSettings
        get() = VoiceSettings(
            enabled = true,
            host = "broker.example",
            port = 1883,
            ingressTopic = "example/topic",
            egressTopic = "example/topic/#",
            speakUnsolicited = speakUnsolicited,
        )

    private fun newId(): String {
        minted += 1
        return "00000000-0000-4000-8000-${minted.toString().padStart(12, '0')}"
    }

    private fun client(
        scope: CoroutineScope,
        transport: FakeTransport,
        speaker: SpeechOutput,
        captures: VoiceCaptureStore,
        filed: MutableList<String>,
    ) = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { settings },
        session = VoiceSessionManager(newId = ::newId),
        replies = VoiceReplyHandler(now = { clock }),
        scope = scope,
        now = { clock },
        captures = captures,
        copy = { it.key },
        replyTimeoutMillis = 60_000L,
        onAnswer = { requestId, text -> filed += "$requestId:$text" },
    )

    @Test
    fun theSwitchDefaultsToOn() {
        assertTrue(VoiceSettings().speakUnsolicited, "the operator asked for the filter open by default")
    }

    @Test
    fun aMatchedReplyIsSpokenFiledAndCapturedAsMatched() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(TOPIC, reply(requestId = ours, text = "The garage lights are on."))

        assertEquals(listOf("The garage lights are on."), speaker.spoken, "spoken, once — as before")
        assertEquals(listOf("$ours:The garage lights are on."), filed, "and filed into its note — as before")
        val captured = captures.messages.value.single()
        assertEquals(VoiceCaptureVerdict.ANSWER, captured.verdict)
        assertTrue(captured.matched)
        assertTrue(captured.spoken)
        assertEquals(TOPIC, captured.topic)
        assertEquals(clock, captured.atMillis)
    }

    @Test
    fun anUnmatchedMessageWithTextIsSpokenAndNotFiled() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        client.onReplyMessage(TOPIC, "Reminder: take the pills at 4.".encodeToByteArray())

        assertEquals(listOf("Reminder: take the pills at 4."), speaker.spoken)
        assertTrue(filed.isEmpty(), "there is no recording behind it, so there is no note to file it into")
        val captured = captures.messages.value.single()
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, captured.verdict)
        assertFalse(captured.matched)
        assertTrue(captured.spoken)
    }

    @Test
    fun anUnmatchedErrorMessageIsCapturedAndNotSpoken() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        client.onReplyMessage(
            TOPIC,
            """{"version":1,"status":"error","text":"invalid_request","format":"plain"}""".encodeToByteArray(),
        )

        assertTrue(speaker.spoken.isEmpty(), "an opaque code is not a sentence")
        assertTrue(filed.isEmpty())
        val captured = captures.messages.value.single()
        assertEquals(VoiceCaptureVerdict.ERROR_CODE, captured.verdict)
        assertFalse(captured.spoken)
    }

    @Test
    fun aBlankPayloadIsCapturedAndNotSpoken() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        client.onReplyMessage(TOPIC, ByteArray(0))
        client.onReplyMessage(TOPIC, "   ".encodeToByteArray())

        assertTrue(speaker.spoken.isEmpty())
        assertEquals(2, captures.messages.value.size, "both arrivals are listed")
        assertTrue(captures.messages.value.all { it.verdict == VoiceCaptureVerdict.EMPTY })
        assertTrue(captures.messages.value.none { it.spoken })
    }

    @Test
    fun aPlainTextPayloadIsSpoken() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        client.onReplyMessage(TOPIC, "The water meter reading is due today.".encodeToByteArray())

        assertEquals(listOf("The water meter reading is due today."), speaker.spoken)
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, captures.messages.value.single().verdict)
    }

    @Test
    fun aDuplicateInsideTheWindowIsSpokenOnce() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        val delivery = "Reminder: take the pills at 4.".encodeToByteArray()
        client.onReplyMessage(TOPIC, delivery)
        clock += 5_000L
        client.onReplyMessage(TOPIC, delivery)

        assertEquals(listOf("Reminder: take the pills at 4."), speaker.spoken, "the broker's redelivery is silent")
        assertEquals(
            listOf(VoiceCaptureVerdict.DUPLICATE, VoiceCaptureVerdict.UNSOLICITED),
            captures.messages.value.map { it.verdict },
            "both deliveries are listed, newest first; only the first was said",
        )
    }

    @Test
    fun withTheSwitchOffNothingUnmatchedIsSpokenButItIsStillCaptured() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)
        speakUnsolicited = false

        client.onReplyMessage(TOPIC, "Reminder: take the pills at 4.".encodeToByteArray())

        assertTrue(speaker.spoken.isEmpty(), "the switch is off, so nothing unmatched is said")
        val captured = captures.messages.value.single()
        assertEquals(VoiceCaptureVerdict.UNSOLICITED, captured.verdict)
        assertFalse(captured.spoken)
    }

    @Test
    fun withTheSwitchOffAMatchedReplyIsStillSpokenAndFiled() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)
        speakUnsolicited = false
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(TOPIC, reply(requestId = ours, text = "The garage lights are on."))

        assertEquals(listOf("The garage lights are on."), speaker.spoken, "the switch is about strangers, not answers")
        assertEquals(listOf("$ours:The garage lights are on."), filed)
    }

    @Test
    fun aMatchedFailureIsStillSpokenAsASentenceAndNotFiled() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(
            TOPIC,
            """{"version":1,"status":"error","sessionId":"s","requestId":"$ours","correlationId":"c","text":"invalid_request","format":"plain"}"""
                .encodeToByteArray(),
        )

        assertEquals(listOf(VoiceLoopSentences.FAILURE.key), speaker.spoken)
        assertTrue(filed.isEmpty())
        val captured = captures.messages.value.single()
        assertEquals(VoiceCaptureVerdict.FAILED, captured.verdict)
        assertTrue(captured.matched)
    }

    /** The list is bounded through the client's own path, not only in the store: 55 arrivals leave the newest 50. */
    @Test
    fun theCaptureListKeepsTheNewestFiftyThroughTheClientsOwnPath() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val captures = VoiceCaptureStore()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, captures, filed)

        (1..55).forEach { index ->
            clock += 1_000L
            client.onReplyMessage(TOPIC, "message $index".encodeToByteArray())
        }

        val kept = captures.messages.value
        assertEquals(50, kept.size)
        assertEquals("message 55", kept.first().preview)
        assertEquals("message 6", kept.last().preview)
    }

    private fun reply(requestId: String, text: String) =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c","text":"$text","format":"plain"}"""
            .encodeToByteArray()

    private companion object {
        /** What a wildcard filter delivers: the broker's topic for this phone. */
        const val TOPIC = "example/topic/gravel-voice-0123abcd"
    }
}
