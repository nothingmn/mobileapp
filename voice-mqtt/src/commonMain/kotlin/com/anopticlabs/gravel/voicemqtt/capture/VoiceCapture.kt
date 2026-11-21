package com.anopticlabs.gravel.voicemqtt.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Why one message that arrived on the reply filter was spoken, filed, or neither.
 *
 * Every message on the filter gets one of these, and the settings screen shows them in arrival order, so a
 * message the loop could not use is a visible fact rather than a silence. The order the classifier applies them
 * in is the order they are declared here, and it is what [VoiceCapturedMessage.matched] alone cannot say.
 */
enum class VoiceCaptureVerdict {
    /** Answered a request this phone had outstanding and carried text: spoken, and filed into its note. */
    ANSWER,

    /** Answered an outstanding request and was a failure: the loop's own sentence is spoken, nothing is filed. */
    FAILED,

    /** Answered an outstanding request and hit the in-flight cap: nothing is spoken, the same turn goes again. */
    BUSY,

    /** Carries text and answers nothing outstanding: spoken, and never filed — there is no recording behind it. */
    UNSOLICITED,

    /** The same content was already spoken inside the dedupe window: not spoken a second time. */
    DUPLICATE,

    /** Nothing in the payload can be said: it is not text, and it has no text field to read. */
    NO_TEXT,

    /** An empty or blank payload: nothing arrived to read. */
    EMPTY,

    /** An opaque code with an `error` status: captured and listed, never spoken as text. */
    ERROR_CODE,
}

/**
 * One message the loop saw on the reply filter, as the capture list shows it.
 *
 * [preview] is the first [PREVIEW_CHARS] characters of the message's content on one line, so a payload that was
 * neither spoken nor filed can still be recognised by the operator. [spoken] means the loop handed a sentence
 * to the speaker for this message — its own text, or one of its failure sentences; it is not a promise that
 * sound came out, since the speaking switches and the headset rule are applied inside the speaker.
 */
data class VoiceCapturedMessage(
    val atMillis: Long,
    val topic: String,
    val matched: Boolean,
    val verdict: VoiceCaptureVerdict,
    val preview: String,
    val spoken: Boolean = false,
)

/**
 * The bounded inventory of what has arrived on the reply filter, newest first.
 *
 * In memory and deliberately not persisted: it exists to answer "what is actually reaching this phone", which is
 * a question about this session, and a file of other people's messages is not something to leave on disk. The
 * bound is [LIMIT] entries; the oldest falls off the end as a new one arrives.
 */
class VoiceCaptureStore(private val limit: Int = LIMIT) {

    private val _messages = MutableStateFlow<List<VoiceCapturedMessage>>(emptyList())

    /** Newest first, at most [limit] entries. */
    val messages: StateFlow<List<VoiceCapturedMessage>> = _messages.asStateFlow()

    fun record(message: VoiceCapturedMessage) {
        _messages.value = (listOf(message) + _messages.value).take(limit)
    }

    fun clear() {
        _messages.value = emptyList()
    }

    companion object {
        /** How many messages the list keeps. */
        const val LIMIT = 50

        /** How much of a message's content the list shows: enough to recognise it, short enough for one line. */
        const val PREVIEW_CHARS = 120
    }
}

/**
 * The one-line preview of [content]: line breaks and runs of space collapsed, cut at [VoiceCaptureStore.PREVIEW_CHARS]
 * with an ellipsis, so a pasted payload cannot wreck the list's layout.
 */
fun capturePreview(content: String): String {
    val flattened = content.replace(Regex("\\s+"), " ").trim()
    if (flattened.length <= VoiceCaptureStore.PREVIEW_CHARS) return flattened
    return flattened.take(VoiceCaptureStore.PREVIEW_CHARS).trimEnd() + "…"
}
