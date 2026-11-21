package com.anopticlabs.gravel.voicemqtt.session

import kotlin.uuid.Uuid

/** After this long with no turn, the conversation is over and the next turn starts a new one. */
const val SESSION_IDLE_MILLIS = 5 * 60 * 1000L

/** The server replays a cached answer for an exact retry inside this window; outside it a retry is a new turn. */
const val RETRY_WINDOW_MILLIS = 10 * 60 * 1000L

/** One spoken turn, as it goes on the wire. [firstAttemptAtMillis] is when its requestId was minted. */
data class VoiceRequest(
    val sessionId: String,
    val requestId: String,
    val text: String,
    val firstAttemptAtMillis: Long,
)

/**
 * Owns the two identifiers the protocol constrains.
 *
 * A `sessionId` is reused for a continuous conversation and replaced after [sessionIdleMillis] of silence,
 * because the server's thread for a session is reset under the client after five idle minutes. A
 * `requestId` is minted once per turn and never again for that turn: a transport retry reuses it, since the
 * server replays the cached answer, while a fresh requestId for the same words is a second turn and the
 * agent acts twice.
 */
class VoiceSessionManager(
    private val newId: () -> String = { Uuid.random().toString() },
    private val sessionIdleMillis: Long = SESSION_IDLE_MILLIS,
    private val retryWindowMillis: Long = RETRY_WINDOW_MILLIS,
) {
    private var sessionId: String? = null
    private var lastTurnAtMillis: Long? = null
    private var turnsInSession = 0

    /** The conversation in progress, or null before the first turn. */
    val currentSessionId: String? get() = sessionId

    /** How many turns the current session has carried; reset with the session. */
    val turns: Int get() = turnsInSession

    /**
     * The session a turn starting at [atMillis] belongs to: the one in progress, or a new one when the last
     * turn is older than the idle window. Never returns an id it did not mint itself.
     */
    fun sessionFor(atMillis: Long): String {
        val current = sessionId
        val last = lastTurnAtMillis
        val id = if (current == null || last == null || atMillis - last > sessionIdleMillis) {
            newId().also {
                sessionId = it
                turnsInSession = 0
            }
        } else {
            current
        }
        lastTurnAtMillis = atMillis
        turnsInSession += 1
        return id
    }

    /** A new turn: a session for [atMillis] and a requestId of its own. */
    fun beginTurn(text: String, atMillis: Long): VoiceRequest =
        VoiceRequest(sessionFor(atMillis), newId(), text, atMillis)

    /**
     * The same turn again after a transport failure, or null once the server's cached-answer window has
     * closed. The returned request carries the original sessionId and requestId unchanged, which is the
     * whole point: an exact retry replays the answer, a new requestId runs the turn twice.
     */
    fun retryFor(request: VoiceRequest, atMillis: Long): VoiceRequest? =
        if (atMillis - request.firstAttemptAtMillis <= retryWindowMillis) request else null

    /** How long this turn may still be retried; zero or less once the window has closed. */
    fun retryBudgetMillis(request: VoiceRequest, atMillis: Long): Long =
        retryWindowMillis - (atMillis - request.firstAttemptAtMillis)
}
