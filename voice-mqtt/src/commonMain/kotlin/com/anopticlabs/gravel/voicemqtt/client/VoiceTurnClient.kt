package com.anopticlabs.gravel.voicemqtt.client

import com.anopticlabs.gravel.voicemqtt.protocol.EnvelopeRefusal
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelope
import com.anopticlabs.gravel.voicemqtt.protocol.VoiceEnvelopeCodec
import com.anopticlabs.gravel.voicemqtt.session.ReplyOutcome
import com.anopticlabs.gravel.voicemqtt.session.VoiceReplyHandler
import com.anopticlabs.gravel.voicemqtt.session.VoiceRequest
import com.anopticlabs.gravel.voicemqtt.session.VoiceSessionManager
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.speech.SpeechOutput
import com.anopticlabs.gravel.voicemqtt.speech.VoiceLoopSentences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One turn: validate, publish, wait for the answer that carries the same requestId, speak it.
 *
 * The rules this enforces, all of them the protocol's:
 * - a blank or oversized transcript never leaves the phone, and the user hears why instead of nothing;
 * - a transport retry reuses the requestId, because the server replays the cached answer for ten minutes
 *   while a fresh requestId would run the turn twice;
 * - a reply is paired on `requestId`; one that matches nothing outstanding is dropped unread;
 * - a failure, a timeout or a `busy` back-off ends in a spoken sentence, never in silence, and the server's
 *   opaque error code is never spoken;
 * - the session is reused until five idle minutes pass, then renewed.
 */
class VoiceTurnClient(
    private val transport: VoiceTransport,
    private val speaker: SpeechOutput,
    private val settings: () -> VoiceSettings,
    private val session: VoiceSessionManager,
    private val replies: VoiceReplyHandler,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val replyTimeoutMillis: Long = 60_000L,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 2_000L, 4_000L),
    private val busyBackoffMillis: Long = 3_000L,
    private val maxBusyRetries: Int = 2,
) {
    /** Requests whose answer is still owed, so a `busy` back-off can republish the very same request. */
    private val pending = mutableMapOf<String, VoiceRequest>()
    private val busyRetries = mutableMapOf<String, Int>()
    private val lock = Mutex()

    /**
     * A transcribed ring turn. The text is what the Notebook's own pipeline produced, unchanged; the
     * requestId it went out under comes back so the caller can hold on to it (a later increment appends the
     * reply into the note that turn settles as). Null when nothing was sent.
     */
    suspend fun publishTranscript(text: String): String? = send(text)

    /** The settings screen's test: a fixed line out, whatever comes back spoken. */
    suspend fun sendTestMessage(): String? = send(VoiceLoopSentences.TEST_MESSAGE)

    suspend fun onReplyMessage(payload: ByteArray) {
        when (val outcome = replies.accept(payload.decodeToString())) {
            ReplyOutcome.Ignored -> Unit
            is ReplyOutcome.Final -> {
                forget(outcome.requestId)
                speaker.speak(outcome.text)
            }
            is ReplyOutcome.Failed -> {
                forget(outcome.requestId)
                speaker.speak(failureFor(outcome.code))
            }
            is ReplyOutcome.Busy -> retryBusy(outcome.requestId)
        }
    }

    private suspend fun send(text: String): String? {
        val current = settings()
        val request = session.beginTurn(text, now())
        val envelope = VoiceEnvelope(sessionId = request.sessionId, requestId = request.requestId, text = text)
        VoiceEnvelopeCodec.refusal(envelope)?.let { refusal ->
            speaker.speak(sentenceFor(refusal))
            return null
        }
        return if (deliver(request, current, attempt = 0)) request.requestId else null
    }

    /**
     * Publishes [request], retrying the same requestId while the server's ten-minute window is open. A turn
     * that cannot be published inside it is spoken as unreachable rather than left silent.
     */
    private suspend fun deliver(request: VoiceRequest, current: VoiceSettings, attempt: Int): Boolean {
        val envelope = VoiceEnvelope(sessionId = request.sessionId, requestId = request.requestId, text = request.text)
        val payload = VoiceEnvelopeCodec.payload(envelope)
        if (transport.publish(current.ingressTopic, payload)) {
            remember(request)
            return true
        }
        val backoff = retryDelaysMillis.getOrNull(attempt)
        val retry = backoff?.let { session.retryFor(request, now()) }
        if (backoff == null || retry == null) {
            speaker.speak(VoiceLoopSentences.NOT_CONNECTED)
            return false
        }
        delay(backoff)
        return deliver(request, current, attempt + 1)
    }

    private suspend fun remember(request: VoiceRequest) {
        lock.withLock {
            pending[request.requestId] = request
            replies.await(request.requestId)
        }
        scope.launch {
            delay(replyTimeoutMillis)
            val expired = lock.withLock {
                if (replies.forget(request.requestId)) {
                    pending.remove(request.requestId)
                    true
                } else {
                    false
                }
            }
            if (expired) speaker.speak(VoiceLoopSentences.TIMEOUT)
        }
    }

    private suspend fun forget(requestId: String) {
        lock.withLock {
            pending.remove(requestId)
            busyRetries.remove(requestId)
        }
    }

    /**
     * The in-flight cap answered: back off and publish the same requestId again, which the server treats as
     * the same turn. Past the window or past [maxBusyRetries] the user is told, so it is never silence.
     */
    private suspend fun retryBusy(requestId: String) {
        val request = lock.withLock { pending[requestId] } ?: return
        val attempts = busyRetries[requestId] ?: 0
        val retry = session.retryFor(request, now())
        if (attempts >= maxBusyRetries || retry == null) {
            forget(requestId)
            lock.withLock { replies.forget(requestId) }
            speaker.speak(VoiceLoopSentences.BUSY)
            return
        }
        busyRetries[requestId] = attempts + 1
        delay(busyBackoffMillis)
        val envelope = VoiceEnvelope(sessionId = retry.sessionId, requestId = retry.requestId, text = retry.text)
        if (!transport.publish(settings().ingressTopic, VoiceEnvelopeCodec.payload(envelope))) {
            forget(requestId)
            lock.withLock { replies.forget(requestId) }
            speaker.speak(VoiceLoopSentences.NOT_CONNECTED)
        }
    }

    private fun sentenceFor(refusal: EnvelopeRefusal): String = when (refusal) {
        EnvelopeRefusal.BLANK_TEXT -> VoiceLoopSentences.EMPTY_TRANSCRIPT
        EnvelopeRefusal.TOO_LONG -> VoiceLoopSentences.TOO_LONG
        EnvelopeRefusal.TOO_LARGE -> VoiceLoopSentences.TOO_LARGE
        EnvelopeRefusal.BAD_SESSION_ID, EnvelopeRefusal.BAD_REQUEST_ID -> VoiceLoopSentences.BROKEN_IDS
    }

    /** The opaque code is never spoken; only the sentence that goes with it. */
    private fun failureFor(code: String): String = when (code) {
        "payload_size" -> VoiceLoopSentences.TOO_LARGE
        else -> VoiceLoopSentences.FAILURE
    }
}
