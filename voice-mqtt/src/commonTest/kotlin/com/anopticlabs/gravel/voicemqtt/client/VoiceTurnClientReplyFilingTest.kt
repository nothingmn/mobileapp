package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureStore
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelope
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelopeCodec
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.SpeechOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the reply path hands to the note-filing seam, and what it must never hand over.
 *
 * The sink itself is the notebook's; this proves the call site: an answer is offered to it only when there is
 * an answer to keep — after it has been spoken — and a failure, a busy back-off or a stranger's reply never
 * reaches it at all.
 */
class VoiceTurnClientReplyFilingTest {

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

    /** Speech and filing in one list, so the order between them is observable. */
    private class Recorder : SpeechOutput {
        val events = mutableListOf<String>()
        override suspend fun speak(text: String) {
            events += "spoken: $text"
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
        scope: CoroutineScope,
        transport: FakeTransport,
        speaker: SpeechOutput,
        filed: MutableList<String>,
    ) = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { settings },
        session = VoiceSessionManager(newId = ::newId),
        replies = VoiceReplyHandler(now = { clock }),
        scope = scope,
        now = { clock },
        captures = VoiceCaptureStore(),
        copy = { it.key },
        replyTimeoutMillis = 60_000L,
        onAnswer = { requestId, text -> filed += "$requestId:$text" },
    )

    @Test
    fun aFinalReplyIsSpokenFirstAndHandedToTheNoteOnlyAfterwards() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = ours, text = "The garage lights are on."))

        assertEquals(listOf("spoken: The garage lights are on."), speaker.events, "the answer is spoken, once")
        assertEquals(listOf("$ours:The garage lights are on."), filed, "and offered to the note afterwards")
    }

    @Test
    fun anErrorReplyIsNeverHandedToTheNote() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(
            REPLY_TOPIC,
            """{"version":1,"status":"error","sessionId":"s","requestId":"$ours","correlationId":"c","text":"invalid_request","format":"plain"}"""
                .encodeToByteArray(),
        )

        assertTrue(filed.isEmpty(), "a failure is spoken as a sentence and never filed into a note")
    }

    @Test
    fun aBusyReplyIsNeverHandedToTheNote() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(
            REPLY_TOPIC,
            """{"version":1,"status":"error","sessionId":"s","requestId":"$ours","correlationId":"c","text":"busy","format":"plain"}"""
                .encodeToByteArray(),
        )

        assertTrue(filed.isEmpty(), "a busy answer is a back-off, not an answer to keep")
    }

    /**
     * A message that answers nothing this phone asked is spoken now — that is the opened-up filter — and is
     * still never filed, because there is no recording behind it to file it into.
     */
    @Test
    fun aReplyForAnotherRequestIsSpokenAndNeverHandedToTheNote() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, filed)
        client.publishTranscript("turn on the garage lights")

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = "00000000-0000-4000-8000-00000000dead", text = "not ours"))

        assertEquals(listOf("spoken: not ours"), speaker.events, "it carries words, so it is said")
        assertTrue(filed.isEmpty(), "but it answers nothing of ours, so there is no note to file it into")
    }

    /**
     * The user taps Stop on the speaking notice while the answer is being read. Playback ends early — and the
     * answer still reaches its note, because the note is the only copy that outlives the sentence.
     *
     * A Stop is modelled the way it reaches this code: the speaker's own call ends in a cancellation. Filing
     * sits in a `finally`, so nothing about how the sentence ended can take the answer with it.
     */
    @Test
    fun anAnswerIsStillFiledWhenTheUserStopsItFromBeingSpoken() = runTest {
        val transport = FakeTransport()
        val filed = mutableListOf<String>()
        val stopping = object : SpeechOutput {
            val events = mutableListOf<String>()
            override suspend fun speak(text: String) {
                events += "stopped: $text"
                throw CancellationException("the user stopped this reply")
            }
        }
        val client = client(backgroundScope, transport, stopping, filed)
        client.publishTranscript("turn on the garage lights")
        val ours = transport.published.single().second.requestId

        client.onReplyMessage(REPLY_TOPIC, reply(requestId = ours, text = "The garage lights are on."))

        assertEquals(listOf("stopped: The garage lights are on."), stopping.events, "the reply was cut short")
        assertEquals(
            listOf("$ours:The garage lights are on."),
            filed,
            "a stop ends the speech, never the answer: it is still offered to its note",
        )

        // And the loop is not left broken by it: the next turn goes out and is answered as usual.
        client.publishTranscript("turn on the kitchen lights")
        val next = transport.published.last().second.requestId
        client.onReplyMessage(REPLY_TOPIC, reply(requestId = next, text = "The kitchen lights are on."))
        assertEquals(2, filed.size, "the turn after a stop files normally")
    }

    private companion object {
        /** What a real filter delivers: the server's own last level under the subscribed wildcard. */
        const val REPLY_TOPIC = "example/topic/gravel-voice-0123abcd"
    }

    private fun reply(requestId: String, text: String) =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c","text":"$text","format":"plain"}"""
            .encodeToByteArray()
}
