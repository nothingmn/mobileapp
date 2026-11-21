package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureStore
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelope
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelopeCodec
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.SpeechOutput
import com.anopticlabs.gravel.voicemqtt.speech.VoiceLoopSentences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The turn as a whole: what leaves the phone, what is retried, and what the user hears when nothing comes back.
 * A fake transport stands in for the broker, so the retry identity and the timeout are observable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTurnClientTest {

    private class FakeTransport(private var failuresLeft: Int = 0) : VoiceTransport {
        /** Every publish the client asked for, refused ones included, so a retry's identity is visible. */
        val attempts = mutableListOf<Pair<String, VoiceEnvelope>>()
        val published = mutableListOf<Pair<String, VoiceEnvelope>>()
        var listener: ((String, ByteArray) -> Unit)? = null

        override fun setIncoming(listener: (topic: String, payload: ByteArray) -> Unit) {
            this.listener = listener
        }

        override suspend fun connect(settings: VoiceSettings) = ConnectionState(ConnectionPhase.CONNECTED)

        override suspend fun disconnect() = Unit

        override suspend fun publish(topic: String, payload: ByteArray): Boolean {
            val envelope = VoiceEnvelopeCodec.json.decodeFromString<VoiceEnvelope>(payload.decodeToString())
            attempts += topic to envelope
            if (failuresLeft > 0) {
                failuresLeft -= 1
                return false
            }
            published += topic to envelope
            return true
        }
    }

    private class RecordingSpeaker : SpeechOutput {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) {
            spoken += text
        }
    }

    private val settings = VoiceSettings(
        enabled = true,
        host = "broker.example",
        port = 1883,
        ingressTopic = "example/topic",
        egressTopic = "example/topic/#",
    )

    private var clock = 1_000_000L
    private var minted = 0

    private fun newId(): String {
        minted += 1
        return "00000000-0000-4000-8000-${minted.toString().padStart(12, '0')}"
    }

    private fun client(
        scope: kotlinx.coroutines.CoroutineScope,
        transport: FakeTransport,
        speaker: RecordingSpeaker,
    ) = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { settings },
        session = VoiceSessionManager(newId = ::newId),
        replies = VoiceReplyHandler(now = { clock }),
        scope = scope,
        now = { clock },
        captures = VoiceCaptureStore(),
        // The resolver the phone has is its resources; here the key stands for the sentence, so an assertion
        // names the copy that was said instead of an English literal.
        copy = { it.key },
        replyTimeoutMillis = 60_000L,
    )

    @Test
    fun aTranscriptIsPublishedOnTheIngressTopic() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        client(backgroundScope, transport, speaker).publishTranscript("TURN ON THE GARAGE LIGHTS")

        assertEquals(1, transport.published.size)
        assertEquals(settings.ingressTopic, transport.published.single().first)
        assertEquals("TURN ON THE GARAGE LIGHTS", transport.published.single().second.text)
        assertTrue(speaker.spoken.isEmpty(), "a successfully published turn says nothing yet")
    }

    @Test
    fun aTransportFailureRetriesWithTheSameRequestId() = runTest {
        val transport = FakeTransport(failuresLeft = 1)
        val speaker = RecordingSpeaker()
        client(backgroundScope, transport, speaker).publishTranscript("TURN ON THE GARAGE LIGHTS")

        assertEquals(2, transport.attempts.size, "the refused publish and the retry")
        assertEquals(1, transport.published.size, "the retry is what made it onto the wire")
        val firstAttemptId = transport.attempts[0].second.requestId
        val retryId = transport.attempts[1].second.requestId
        assertEquals(firstAttemptId, retryId, "a retry must reuse the requestId; a new one would run the turn twice")
        assertTrue(VoiceEnvelopeCodec.isUuidV4(firstAttemptId))
        assertTrue(speaker.spoken.isEmpty(), "a turn that got through says nothing yet")
    }

    @Test
    fun aTurnThatCannotBePublishedIsSpokenAsUnreachable() = runTest {
        val transport = FakeTransport(failuresLeft = 99)
        val speaker = RecordingSpeaker()
        client(backgroundScope, transport, speaker).publishTranscript("anyone there")

        assertTrue(transport.published.isEmpty())
        assertEquals(listOf(VoiceLoopSentences.NOT_CONNECTED.key), speaker.spoken)
    }

    @Test
    fun aReplyThatNeverArrivesTimesOutAudibly() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        val client = client(backgroundScope, transport, speaker)
        client.publishTranscript("what time is it")

        advanceTimeBy(59_000L)
        assertTrue(speaker.spoken.isEmpty())
        advanceTimeBy(2_000L)
        assertEquals(listOf(VoiceLoopSentences.TIMEOUT.key), speaker.spoken, "silence is the failure mode this prevents")
    }

    @Test
    fun aMatchingReplyIsSpoken() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        val client = client(backgroundScope, transport, speaker)
        client.publishTranscript("what time is it")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = ours, text = "It is 4:43pm."))
        assertEquals(listOf("It is 4:43pm."), speaker.spoken)
    }

    /**
     * A message that answers nothing this phone asked used to be dropped unread. It is now read and said: that
     * is the whole point of opening the filter up, and our own request stays outstanding while it happens.
     */
    @Test
    fun aMismatchedReplyCarryingTextIsSpokenAndLeavesOurTurnWaiting() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        val client = client(backgroundScope, transport, speaker)
        client.publishTranscript("what time is it")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = "00000000-0000-4000-8000-00000000dead", text = "not ours"))
        assertEquals(listOf("not ours"), speaker.spoken)

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = ours, text = "It is 4:43pm."))
        assertEquals(listOf("not ours", "It is 4:43pm."), speaker.spoken, "our own turn is still answered")
    }

    @Test
    fun aBlankTranscriptNeverLeavesThePhoneAndIsSpoken() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        client(backgroundScope, transport, speaker).publishTranscript("   ")

        assertTrue(transport.published.isEmpty())
        assertEquals(listOf(VoiceLoopSentences.EMPTY_TRANSCRIPT.key), speaker.spoken)
    }

    @Test
    fun anOverlongTranscriptIsRefusedWithASpokenReason() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        client(backgroundScope, transport, speaker).publishTranscript("a".repeat(2000))

        assertTrue(transport.published.isEmpty())
        assertEquals(listOf(VoiceLoopSentences.TOO_LONG.key), speaker.spoken)
    }

    @Test
    fun anErrorReplyIsSpokenAsASentenceAndNotAsTheCode() = runTest {
        val transport = FakeTransport()
        val speaker = RecordingSpeaker()
        val client = client(backgroundScope, transport, speaker)
        client.publishTranscript("what time is it")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(
            REPLY_TOPIC,
            """{"version":1,"status":"error","sessionId":"s","requestId":"$ours","correlationId":"c","text":"invalid_request","format":"plain"}"""
                .encodeToByteArray(),
        )
        assertEquals(listOf(VoiceLoopSentences.FAILURE.key), speaker.spoken)
        assertFalse(speaker.spoken.any { it.contains("invalid_request") }, "the opaque code is never spoken")
    }

    private fun reply(requestId: String, text: String) =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c","text":"$text","format":"plain"}"""
            .encodeToByteArray()

    private companion object {
        /** The filter a real phone subscribes to: a wildcard, so the arriving topic is the server's own last level. */
        const val REPLY_TOPIC = "example/topic/gravel-voice-0123abcd"
    }
}
