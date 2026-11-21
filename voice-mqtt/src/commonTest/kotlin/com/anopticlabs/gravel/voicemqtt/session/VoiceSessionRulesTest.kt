package com.anopticlabs.gravel.voicemqtt.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two timers the server imposes: a conversation is one session until five idle minutes pass, and an exact
 * retry is only a retry inside the ten minutes the cached answer lives for.
 */
class VoiceSessionRulesTest {

    private var minted = 0
    private fun newId(): String {
        minted += 1
        // A deterministic canonical v4 shape, so the ids are inspectable in a failure message.
        return "00000000-0000-4000-8000-${minted.toString().padStart(12, '0')}"
    }

    private val session = VoiceSessionManager(newId = ::newId)

    private fun minutes(n: Long) = n * 60 * 1000L

    @Test
    fun turnsInsideFiveMinutesShareOneSession() {
        val first = session.beginTurn("first", 1_000L)
        val second = session.beginTurn("second", 1_000L + minutes(4))
        assertEquals(first.sessionId, second.sessionId, "a continuous conversation keeps its session")
        assertNotEquals(first.requestId, second.requestId, "each turn has its own requestId")
        assertEquals(2, session.turns)
    }

    @Test
    fun aTurnAfterFiveIdleMinutesStartsANewSession() {
        val first = session.beginTurn("first", 1_000L)
        val second = session.beginTurn("second", 1_000L + minutes(5) + 1)
        assertNotEquals(first.sessionId, second.sessionId, "the server's thread resets after five idle minutes")
        assertEquals(1, session.turns, "the new session starts its own turn count")
    }

    @Test
    fun exactlyFiveMinutesStillBelongsToTheSameSession() {
        val first = session.beginTurn("first", 1_000L)
        val second = session.beginTurn("second", 1_000L + minutes(5))
        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun aRetryInsideTenMinutesReusesTheSameRequestId() {
        val request = session.beginTurn("turn on the lights", 1_000L)
        val retry = session.retryFor(request, 1_000L + minutes(9))
        assertNotNull(retry)
        assertEquals(request.requestId, retry.requestId, "a retry must never mint a new requestId")
        assertEquals(request.sessionId, retry.sessionId)
        assertEquals(request.text, retry.text)
    }

    @Test
    fun aRetryAfterTenMinutesIsRefusedRatherThanMinted() {
        val request = session.beginTurn("turn on the lights", 1_000L)
        assertNull(session.retryFor(request, 1_000L + minutes(10) + 1))
        assertTrue(session.retryBudgetMillis(request, 1_000L + minutes(10) + 1) < 0)
    }

    @Test
    fun aRetryDoesNotConsumeASessionOrAddATurn() {
        val request = session.beginTurn("first", 1_000L)
        val before = session.currentSessionId
        session.retryFor(request, 1_000L + 30_000L)
        assertEquals(before, session.currentSessionId)
        assertEquals(1, session.turns)
    }
}
