package com.anopticlabs.gravel.voicemqtt.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bound on the capture list: it is the whole reason a list of other people's messages is safe to keep in
 * memory, so the newest-fifty rule is proved rather than described.
 */
class VoiceCaptureStoreTest {

    private fun message(index: Int) = VoiceCapturedMessage(
        atMillis = 1_000L + index,
        topic = "voice/reply/phone",
        matched = false,
        verdict = VoiceCaptureVerdict.UNSOLICITED,
        preview = "message $index",
        spoken = true,
    )

    @Test
    fun theStoreIsNewestFirst() {
        val store = VoiceCaptureStore()
        store.record(message(1))
        store.record(message(2))
        assertEquals(listOf("message 2", "message 1"), store.messages.value.map { it.preview })
    }

    @Test
    fun theStoreKeepsTheNewestFiftyAndDropsTheOldest() {
        val store = VoiceCaptureStore()
        (1..51).forEach { store.record(message(it)) }

        val kept = store.messages.value
        assertEquals(50, kept.size, "the list is bounded at fifty")
        assertEquals("message 51", kept.first().preview, "the newest is at the top")
        assertEquals("message 2", kept.last().preview, "the oldest fell off the end")
        assertTrue(kept.none { it.preview == "message 1" })
    }

    @Test
    fun theBoundIsTheDocumentedOne() {
        assertEquals(50, VoiceCaptureStore.LIMIT)
        assertEquals(120, VoiceCaptureStore.PREVIEW_CHARS)
    }

    @Test
    fun clearingEmptiesTheList() {
        val store = VoiceCaptureStore()
        store.record(message(1))
        store.clear()
        assertEquals(emptyList(), store.messages.value)
    }
}
