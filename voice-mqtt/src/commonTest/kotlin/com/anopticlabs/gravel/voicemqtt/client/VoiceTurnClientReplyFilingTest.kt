package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelope
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelopeCodec
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.SpeechOutput
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

        override fun setIncoming(listener: (payload: ByteArray) -> Unit) = Unit

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
        host = "10.0.0.157",
        port = 1883,
        ingressTopic = "agentry/v1/gateway/req/voicessistant",
        egressTopic = "agentry/v1/gateway/resp/voicessistant/#",
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
        speaker: Recorder,
        filed: MutableList<String>,
    ) = VoiceTurnClient(
        transport = transport,
        speaker = speaker,
        settings = { settings },
        session = VoiceSessionManager(newId = ::newId),
        replies = VoiceReplyHandler(now = { clock }),
        scope = scope,
        now = { clock },
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

        client.onReplyMessage(reply(requestId = ours, text = "The garage lights are on."))

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
            """{"version":1,"status":"error","sessionId":"s","requestId":"$ours","correlationId":"c","text":"busy","format":"plain"}"""
                .encodeToByteArray(),
        )

        assertTrue(filed.isEmpty(), "a busy answer is a back-off, not an answer to keep")
    }

    @Test
    fun aReplyForAnotherRequestIsNeverHandedToTheNote() = runTest {
        val transport = FakeTransport()
        val speaker = Recorder()
        val filed = mutableListOf<String>()
        val client = client(backgroundScope, transport, speaker, filed)
        client.publishTranscript("turn on the garage lights")

        client.onReplyMessage(reply(requestId = "00000000-0000-4000-8000-00000000dead", text = "not ours"))

        assertTrue(filed.isEmpty(), "another turn's answer is dropped unread, so it is never filed here")
        assertTrue(speaker.events.isEmpty())
    }

    private fun reply(requestId: String, text: String) =
        """{"version":1,"status":"final","sessionId":"s","requestId":"$requestId","correlationId":"c","text":"$text","format":"plain"}"""
            .encodeToByteArray()
}
