package com.anopticlabs.gravel.voicemqtt.session

import com.anopticlabs.gravel.voicemqtt.capture.VoiceCaptureVerdict
import com.anopticlabs.gravel.voicemqtt.capture.VoiceCapturedMessage
import com.anopticlabs.gravel.voicemqtt.capture.capturePreview
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A reply on the reply topic. Every field is defaulted because a malformed payload comes back with empty
 * `sessionId`/`requestId` echoes and an opaque code, and the client must still parse it far enough to see
 * that it answers nothing it asked.
 */
@Serializable
data class VoiceReply(
    val version: Int = 1,
    val status: String = "",
    val sessionId: String = "",
    val requestId: String = "",
    val correlationId: String = "",
    val text: String = "",
    val format: String = "plain",
)

/** What one message on the reply topic means for this phone. */
sealed interface ReplyOutcome {
    /**
     * Nothing to say and nothing to keep: an empty payload, a payload with no text in it, an opaque error code,
     * a duplicate inside the dedupe window, or another client's traffic that carries no words.
     *
     * It is still recorded in the capture list — [ReplyDecision.capture] says why it was not spoken.
     */
    data object Ignored : ReplyOutcome

    /** The answer to speak, and to file into the note its recording produced. */
    data class Final(val requestId: String, val text: String) : ReplyOutcome

    /**
     * A failure. [code] is for the log only and is never spoken: it is the server's opaque word, not a
     * sentence anyone understands.
     */
    data class Failed(val requestId: String, val code: String) : ReplyOutcome

    /** Too many turns in flight; the same requestId may be published again after a back-off. */
    data class Busy(val requestId: String) : ReplyOutcome

    /**
     * Carries text and answers nothing this phone asked: a reminder or a push from the broker, or another
     * client's answer on the same filter. It is spoken and **never filed** — there is no recording behind it.
     */
    data class Unsolicited(val text: String) : ReplyOutcome
}

/**
 * One message, decided: what the loop should do ([outcome]) and what the capture list should say about it.
 *
 * Both halves come out of the same read of the same payload, so the list can never disagree with what was done
 * with the message.
 */
data class ReplyDecision(val outcome: ReplyOutcome, val capture: VoiceCapturedMessage)

/**
 * Reads every message on the reply topic and says what this phone should do with it.
 *
 * Pairing is on `requestId` alone. A correlationId is server-minted, one per session, and reused for every turn
 * in it; it is diagnostic and must never be treated as the pairing key.
 *
 * The decision, in this order — the first line that holds decides, and every message ends up in the capture list
 * whichever line it stops at:
 *
 * 1. an empty or blank payload is [VoiceCaptureVerdict.EMPTY] — recorded, not spoken;
 * 2. the payload is read for text: a reply envelope's `text` field, a JSON string, or the payload itself as plain
 *    text — an unsolicited message need not use the envelope's shape;
 * 3. a non-empty `requestId` this phone is waiting on **matches**, and then behaves exactly as it did before this
 *    change: `final` + text is the answer (spoken, filed), `final` + no text and any error other than `busy` is a
 *    failure (the loop's own sentence spoken, never the code), `busy` keeps the request outstanding and says
 *    nothing;
 * 4. a matching-but-not-our message is read, not dropped: an `error` status is an opaque code
 *    ([VoiceCaptureVerdict.ERROR_CODE], never spoken as text), nothing extractable is
 *    [VoiceCaptureVerdict.NO_TEXT], the same content already spoken inside [dedupeWindowMillis] is
 *    [VoiceCaptureVerdict.DUPLICATE], and anything else is [VoiceCaptureVerdict.UNSOLICITED]: spoken, never filed.
 *
 * The dedupe window exists because QoS 1 can deliver the same message twice — after a reconnect, the broker
 * replays what it could not confirm — and a reply spoken twice is worse than one spoken late.
 */
class VoiceReplyHandler(
    private val now: () -> Long,
    private val replyTimeoutMillis: Long = 60_000L,
    /** How long a piece of content stays spoken-once. Short enough that a repeat a minute later is still said. */
    private val dedupeWindowMillis: Long = 60_000L,
    /** A cap on the dedupe note itself, so a busy broker cannot grow it without limit. */
    private val dedupeLimit: Int = 64,
) {
    private val awaiting = LinkedHashMap<String, Long>()

    /** The content this loop has already spoken, and when; insertion-ordered, so the oldest is first. */
    private val spokenContent = LinkedHashMap<String, Long>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = false
    }

    /** Starts waiting for [requestId]; the timeout clock runs from now. */
    fun await(requestId: String) {
        awaiting[requestId] = now()
    }

    fun isAwaiting(requestId: String): Boolean = awaiting.containsKey(requestId)

    fun awaitingRequestIds(): Set<String> = awaiting.keys.toSet()

    /** Stops waiting; true when it was outstanding, which is what makes a timeout speak exactly once. */
    fun forget(requestId: String): Boolean = awaiting.remove(requestId) != null

    /** The requestIds whose reply did not arrive inside [replyTimeoutMillis], removed as they are returned. */
    fun timingOut(atMillis: Long = now()): List<String> {
        val due = awaiting.filterValues { atMillis - it > replyTimeoutMillis }.keys.toList()
        due.forEach { awaiting.remove(it) }
        return due
    }

    /**
     * Reads one message from the reply topic. [topic] is the topic it actually arrived on, which the capture list
     * names; it plays no part in pairing.
     */
    fun accept(raw: String, topic: String = ""): ReplyDecision {
        val at = now()
        val preview = capturePreview(raw)
        if (raw.isBlank()) {
            return ReplyDecision(ReplyOutcome.Ignored, capture(at, topic, matched = false, VoiceCaptureVerdict.EMPTY, preview, spoken = false))
        }
        val body = bodyOf(raw)
        val requestId = body.requestId
        if (requestId.isNotEmpty() && awaiting.containsKey(requestId)) {
            return matched(body, requestId, at, topic, preview)
        }
        return unsolicited(body, at, topic, preview)
    }

    /** The message answers a turn this phone published: today's path, unchanged. */
    private fun matched(body: IncomingBody, requestId: String, at: Long, topic: String, preview: String): ReplyDecision =
        when (body.status) {
            "final" -> {
                awaiting.remove(requestId)
                if (body.text.isBlank()) {
                    // Nothing to speak and nothing to file, but the turn must not be left silent: the client
                    // says its own failure sentence for this code.
                    ReplyDecision(
                        ReplyOutcome.Failed(requestId, BLANK_ANSWER_CODE),
                        capture(at, topic, matched = true, VoiceCaptureVerdict.NO_TEXT, preview, spoken = true),
                    )
                } else {
                    rememberSpoken(body.text, at)
                    ReplyDecision(
                        ReplyOutcome.Final(requestId, body.text),
                        capture(at, topic, matched = true, VoiceCaptureVerdict.ANSWER, preview, spoken = true),
                    )
                }
            }
            "error" -> {
                if (body.text == BUSY_CODE) {
                    // The in-flight cap: the turn may be retried with the same requestId, so it stays outstanding.
                    ReplyDecision(
                        ReplyOutcome.Busy(requestId),
                        capture(at, topic, matched = true, VoiceCaptureVerdict.BUSY, preview, spoken = false),
                    )
                } else {
                    awaiting.remove(requestId)
                    ReplyDecision(
                        ReplyOutcome.Failed(requestId, body.text.ifBlank { "unspecified" }),
                        capture(at, topic, matched = true, VoiceCaptureVerdict.FAILED, preview, spoken = true),
                    )
                }
            }
            else -> {
                awaiting.remove(requestId)
                ReplyDecision(
                    ReplyOutcome.Failed(requestId, "unknown_status"),
                    capture(at, topic, matched = true, VoiceCaptureVerdict.FAILED, preview, spoken = true),
                )
            }
        }

    /**
     * Nothing this phone asked for. It is read rather than dropped: a reminder pushed from the broker is exactly
     * the message this path exists for, and it is spoken without being filed.
     */
    private fun unsolicited(body: IncomingBody, at: Long, topic: String, preview: String): ReplyDecision {
        // An error status carries an opaque code in `text`; it is listed and never read aloud.
        if (body.envelope && body.status == "error") {
            return ReplyDecision(ReplyOutcome.Ignored, capture(at, topic, matched = false, VoiceCaptureVerdict.ERROR_CODE, preview, spoken = false))
        }
        if (body.text.isBlank()) {
            return ReplyDecision(ReplyOutcome.Ignored, capture(at, topic, matched = false, VoiceCaptureVerdict.NO_TEXT, preview, spoken = false))
        }
        if (alreadySpoken(body.text, at)) {
            return ReplyDecision(ReplyOutcome.Ignored, capture(at, topic, matched = false, VoiceCaptureVerdict.DUPLICATE, preview, spoken = false))
        }
        rememberSpoken(body.text, at)
        return ReplyDecision(
            ReplyOutcome.Unsolicited(body.text),
            capture(at, topic, matched = false, VoiceCaptureVerdict.UNSOLICITED, preview, spoken = true),
        )
    }

    /**
     * The payload as it can be read: the reply envelope if it is one, a JSON string, or the raw payload.
     *
     * `envelope` is what separates a reply from something that merely looks like JSON: only an envelope's
     * `status` carries the server's vocabulary, so only an envelope's `error` is a code rather than words.
     */
    private fun bodyOf(raw: String): IncomingBody {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            null
        }
        if (element is JsonObject) {
            return IncomingBody(
                envelope = true,
                status = element.stringOrEmpty("status"),
                requestId = element.stringOrEmpty("requestId"),
                text = element.stringOrEmpty("text"),
            )
        }
        // A JSON string is a plain-text payload that happens to be quoted.
        if (element is JsonPrimitive && element.isString) {
            return IncomingBody(envelope = false, text = element.content)
        }
        return IncomingBody(envelope = false, text = raw.trim())
    }

    private fun JsonObject.stringOrEmpty(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    /** True when this content was already spoken inside the window; the note is pruned as it is read. */
    private fun alreadySpoken(content: String, atMillis: Long): Boolean {
        pruneSpoken(atMillis)
        return spokenContent.containsKey(content.trim())
    }

    private fun rememberSpoken(content: String, atMillis: Long) {
        pruneSpoken(atMillis)
        spokenContent[content.trim()] = atMillis
        while (spokenContent.size > dedupeLimit) {
            val oldest = spokenContent.keys.firstOrNull() ?: break
            spokenContent.remove(oldest)
        }
    }

    private fun pruneSpoken(atMillis: Long) {
        val stale = spokenContent.filterValues { atMillis - it > dedupeWindowMillis }.keys.toList()
        stale.forEach { spokenContent.remove(it) }
    }

    private fun capture(
        atMillis: Long,
        topic: String,
        matched: Boolean,
        verdict: VoiceCaptureVerdict,
        preview: String,
        spoken: Boolean,
    ) = VoiceCapturedMessage(
        atMillis = atMillis,
        topic = topic,
        matched = matched,
        verdict = verdict,
        preview = preview,
        spoken = spoken,
    )

    /** One payload, read as far as it can be. */
    private class IncomingBody(
        val envelope: Boolean,
        val status: String = "",
        val requestId: String = "",
        val text: String = "",
    )

    companion object {
        /** The only error code the client acts on without speaking. */
        const val BUSY_CODE = "busy"

        /** The code a `final` reply with no words in it is failed under: nothing to say, nothing to keep. */
        const val BLANK_ANSWER_CODE = "blank_answer"
    }
}
