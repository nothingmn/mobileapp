package com.anopticlabs.gravel.voicemqtt.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
    /** Not an answer to a request this phone is waiting on: another session's traffic, a duplicate, or noise. */
    data object Ignored : ReplyOutcome

    /** The answer to speak. */
    data class Final(val requestId: String, val text: String) : ReplyOutcome

    /**
     * A failure. [code] is for the log only and is never spoken: it is the server's opaque word, not a
     * sentence anyone understands.
     */
    data class Failed(val requestId: String, val code: String) : ReplyOutcome

    /** Too many turns in flight; the same requestId may be published again after a back-off. */
    data class Busy(val requestId: String) : ReplyOutcome
}

/**
 * Pairs replies with the requests this phone has outstanding, on `requestId` alone.
 *
 * A correlationId is server-minted, one per session, and reused for every turn in it; it is diagnostic and
 * must never be treated as the pairing key. A reply whose requestId matches nothing is dropped without
 * being read for text, which is also what protects one phone from another phone's traffic on the same
 * broker.
 */
class VoiceReplyHandler(
    private val now: () -> Long,
    private val replyTimeoutMillis: Long = 60_000L,
) {
    private val awaiting = LinkedHashMap<String, Long>()

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

    /** Reads one message from the reply topic; anything that is not one of our answers is [ReplyOutcome.Ignored]. */
    fun accept(raw: String): ReplyOutcome {
        val reply = try {
            json.decodeFromString<VoiceReply>(raw)
        } catch (e: Exception) {
            return ReplyOutcome.Ignored
        }
        val requestId = reply.requestId
        if (requestId.isEmpty() || !awaiting.containsKey(requestId)) return ReplyOutcome.Ignored
        return when (reply.status) {
            "final" -> {
                awaiting.remove(requestId)
                if (reply.text.isBlank()) ReplyOutcome.Failed(requestId, "blank_answer") else ReplyOutcome.Final(requestId, reply.text)
            }
            "error" -> {
                // busy is the in-flight cap: the turn may be retried with the same requestId, so it stays outstanding.
                if (reply.text == BUSY_CODE) ReplyOutcome.Busy(requestId) else {
                    awaiting.remove(requestId)
                    ReplyOutcome.Failed(requestId, reply.text.ifBlank { "unspecified" })
                }
            }
            else -> {
                awaiting.remove(requestId)
                ReplyOutcome.Failed(requestId, "unknown_status")
            }
        }
    }

    companion object {
        /** The only error code the client acts on without speaking. */
        const val BUSY_CODE = "busy"
    }
}
