package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.button.RingButtonController
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.link.RingLinkException
import com.anopticlabs.gravel.ring.protocol.RingAdvertisement
import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import com.anopticlabs.gravel.ring.sync.RingSessionEnd
import com.anopticlabs.gravel.ring.sync.shouldSyncRing
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Where syncing the paired ring stands, for its Devices entry. */
sealed interface RingSyncState {
    /** Running for this ring, with no sync or wait to report yet. */
    data object Listening : RingSyncState
    data object Syncing : RingSyncState
    data class Synced(val atMillis: Long, val added: Int) : RingSyncState
    data class Waiting(val reason: RingWait) : RingSyncState
    data class Failed(val atMillis: Long) : RingSyncState
    data object PairAgain : RingSyncState
    data object PositionLost : RingSyncState

    /** Not running: the service that runs it is not up. */
    data object Stopped : RingSyncState
}

/** Connection attempts in all for one session when Android reports a status worth retrying. */
const val MAX_RING_TRIES = 5

/**
 * Syncs the paired ring while it runs. One coroutine searches for the ring's advertisements, starting again
 * [rescanDelay] after a search that heard the ring and doubling that up to 5 minutes while searches end without
 * hearing it (Android counts scan starts per app, the watch's included: android16-release, packages/modules/Bluetooth
 * `AppScanStats.isScanningTooFrequently`); another syncs, so a failing search does not cut a sync short. It syncs a
 * ring in its normal image when [shouldSyncRing] says so, on first hearing it, and once [safetyInterval] has passed
 * since the last sync, since the advertised count is 8 bits; it asks the sink first and does not connect while the
 * sink would leave recordings on the ring. A status worth retrying is tried up to [MAX_RING_TRIES] times in all; a
 * session that fails or that the sink stops holds syncing off for a while, one that ran out of time after importing
 * something does not, and a damaged saved position stops syncing until [importAgain].
 *
 * [buttons] is the ring's button, kept only to forget a gesture the ring stopped reporting when the pairing
 * changes; the presses themselves are read out of the collections a sync imports (see [RingSession]), so a press
 * starts a sync even inside the hold the last one left, and the button is followed whether or not the Notebook would
 * take a recording.
 *
 * [sync] is given the moment the advertisement that started it was heard, which is what the press timings measure
 * from; the runner does not use it for anything else.
 *
 * A press ([RingAdvertisement.recording]) is never asked about the Notebook: that question exists to keep a
 * *scheduled* sync from connecting while the sink would leave the ring's recordings where they are, and a press is
 * served whatever the answer is (its session imports a one-millisecond recording the sink drops for its length). It
 * is asked within [notebookCheckTimeout] rather than [checkTimeout], because it is a wait for the queue's
 * keystore-backed read, which a press never uses; measured on the operator's phone, 1.4-3.4 s of a press's wait sat
 * between hearing the ring's advertisement and asking for the link, and that question was all of it.
 *
 * A press is served where it is heard (see [servePress]): the search's own coroutine asks for the link, before it
 * suspends again, rather than handing the advertisement to the loop. The loop is one coroutine that also runs a
 * session, closes its link and reads the ring's saved count, and at f85c7e39 a press waited for it to be free and
 * scheduled — measured on the operator's phone at 726 and 1290 ms between the advertisement and the connect being
 * asked for, with nothing in the loop's body that suspends. A scheduled sync is still the loop's, and one link is
 * still open at a time.
 *
 * [mediaRemote] is whether the ring's button currently controls this phone's media (Settings > Ring button's master
 * switch). While it is on the scan never stops listening: no rest follows a sync, and a failed search waits one gap
 * rather than backing off to five minutes. A press is only visible as an advertisement, so a scan with a gap is a
 * press the app cannot hear — measured on the operator's phone at 1.3, 1.3, 3.8 and 5.8 s from the finger leaving the
 * ring, with taps that produced no line at all. With the switch off nothing changes: the rest after a sync and the
 * doubling rescan delay are the schedule this runner has always had, and they are what keeps a ring with nobody
 * using its button from costing the phone's battery.
 */
class RingSyncRunner(
    private val paired: StateFlow<String?>,
    private val hear: (address: String) -> Flow<RingAdvertisement>,
    private val sync: suspend (address: String, advertisement: RingAdvertisement, heardAtMillis: Long) -> RingSessionEnd,
    private val lastCount: suspend (address: String) -> Int?,
    private val positionReadable: suspend (address: String) -> Boolean,
    private val waitReason: suspend () -> RingWait?,
    private val lastWait: () -> RingWait?,
    private val imported: () -> Int,
    private val startOver: suspend (address: String) -> Unit,
    private val buttons: RingButtonController = RingButtonController({ RingButtonMapping() }),
    private val mediaRemote: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val marks: TimeSource = TimeSource.Monotonic,
    private val sessionLimit: Duration = 15.minutes,
    private val safetyInterval: Duration = 6.hours,
    private val rescanDelay: Duration = 3.seconds,
    private val checkTimeout: Duration = 10.seconds,
    private val notebookCheckTimeout: Duration = 1.seconds,
    private val syncHold: Duration = 30.seconds,
) {
    private val _state = MutableStateFlow<RingSyncState>(RingSyncState.Stopped)
    val state: StateFlow<RingSyncState> = _state.asStateFlow()

    private val _searchFailed = MutableStateFlow(false)
    /** The last search ended without hearing the ring. */
    val searchFailed: StateFlow<Boolean> = _searchFailed.asStateFlow()

    private val _lastHeard = MutableStateFlow<Long?>(null)
    /** When the paired ring was last heard, by the wall clock. */
    val lastHeard: StateFlow<Long?> = _lastHeard.asStateFlow()

    private val _positionLost = MutableStateFlow(false)
    /** Syncing is stopped on a damaged saved position, until [importAgain]. */
    val positionLost: StateFlow<Boolean> = _positionLost.asStateFlow()

    private var job: Job? = null
    private val attempts = RingAttempts(marks, safetyInterval, syncHold)

    /** Starts syncing in [scope] until [stop]; a second call while running does nothing. Main thread only. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val previous = job
        val run = scope.launch {
            // A stopped run may still be saving a recording's position; this one reads it afterward.
            previous?.join()
            paired.collectLatest { address ->
                _state.value = RingSyncState.Listening
                _lastHeard.value = null
                _searchFailed.value = false
                _positionLost.value = false
                attempts.reset()
                buttons.forget()
                if (address != null) runFor(address)
            }
        }
        // Written once the run has made its last write, which a stop does not interrupt between suspensions.
        run.invokeOnCompletion { _state.value = RingSyncState.Stopped }
        job = run
    }

    fun stop() {
        job?.cancel()
        _state.value = RingSyncState.Stopped
    }

    /** Forgets the paired ring's damaged position, so the next sync imports everything still on the ring. */
    suspend fun importAgain() {
        val address = paired.value ?: return
        try {
            startOver(address)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "the position of $address could not be cleared" }
            return
        }
        attempts.reset()
        _positionLost.value = false
        if (job?.isActive == true) _state.value = RingSyncState.Listening
        logger.i { "position of $address cleared by the user" }
    }

    private suspend fun runFor(address: String): Unit = coroutineScope {
        if (!checked("reading the position of $address") { positionReadable(address) }.getOrDefault(true)) {
            _positionLost.value = true
            _state.value = RingSyncState.PositionLost
        }
        val ended = EndedCount(checked("reading the last count of $address") { lastCount(address) }.getOrNull())
        val latest = Channel<HeardAd>(Channel.CONFLATED)
        // One link at a time. A press is served on the coroutine that heard it and a scheduled sync on this loop, so
        // this is what says which of them may open the link; a press that arrives while one is open cannot be served
        // without a second one, and is dropped as the loop has always dropped a press heard during a session.
        val link = Mutex()
        // Written when the duty changes rather than on every advertisement: one line per turn of the switch, which is
        // what a field log needs to say whether the app was listening when a tap was made.
        var duty: Boolean? = null
        fun announceDuty() {
            val listening = mediaRemote()
            if (listening == duty) return
            duty = listening
            logger.d {
                if (listening) {
                    "ring scan: continuous, because the ring's media remote is on — no rest follows a sync and a failed " +
                        "search waits $rescanDelay, so a press is heard when it is made"
                } else {
                    "ring scan: scheduled, because the ring's media remote is off — $syncHold follows a sync and failed " +
                        "searches wait up to $MAX_RESCAN_DELAY"
                }
            }
        }
        announceDuty()
        // The search serves a press itself (see servePress) rather than handing it to this loop.
        launch { search(address, latest, link, ended) }
        for (heard in latest) {
            announceDuty()
            val advertisement = heard.advertisement
            // Only a scheduled sync reaches this loop: the search serves a press where it is heard (servePress), so
            // nothing here is on a press's path. What is left is the schedule this loop has always had — the rest
            // after a sync, the hold after a failure, and the Notebook's answer, which keeps a scheduled sync from
            // connecting while the sink would leave the ring's recordings where they are.
            if (_positionLost.value || !(mediaRemote() || attempts.ready()) || !advertisement.pairable) continue
            val wanted = shouldSyncRing(advertisement, ended.value)
            if (!wanted && !attempts.syncDue()) continue
            // The question is asked within [notebookCheckTimeout] rather than [checkTimeout], because it is a wait
            // for the queue's keystore-backed read and a slow answer must not park a scan that hears the next tap.
            val wait = checked("checking the Notebook", notebookCheckTimeout) { waitReason() }
            if (wait.isFailure || wait.getOrNull() != null) {
                wait.getOrNull()?.takeIf { wanted }?.let { _state.value = RingSyncState.Waiting(it) }
                attempts.waited()
                continue
            }
            link.withLock { syncOnce(address, advertisement, heard.atMillis) }
            ended.value = checked("reading the last count of $address") { lastCount(address) }.getOrDefault(ended.value)
            // Heard while the session ran, so it says nothing the session did not read.
            latest.tryReceive()
        }
    }

    /**
     * Serves the press [heard] on the coroutine whose scan heard it, before that coroutine suspends again.
     *
     * This is the press's whole hand-off, and it used to be the wait. The scan marked the advertisement, put it in a
     * conflated channel, and the loop — the one coroutine that also runs sessions, closes the last link and reads the
     * ring's saved count from its file — took it when it was next scheduled. Nothing in the loop's body suspends, so
     * a press's measured wait of 726 and 1290 ms was never a call inside it: it was the loop's coroutine itself, the
     * dispatcher that had to schedule it, and whatever it was finishing first. A press now asks for the connect on
     * the coroutine the advertisement arrived on, which needs no scheduling to get to the point where the link is
     * asked for; only the link ([link]) can still hold it back, and only while a link is open, which the ring does
     * not do while its button is being pressed.
     */
    private suspend fun servePress(address: String, heard: HeardAd, ended: EndedCount) {
        val advertisement = heard.advertisement
        if (_positionLost.value || !advertisement.pairable) return
        syncOnce(address, advertisement, heard.atMillis)
        ended.value = checked("reading the last count of $address") { lastCount(address) }.getOrDefault(ended.value)
    }

    private suspend fun search(
        address: String,
        latest: SendChannel<HeardAd>,
        link: Mutex,
        ended: EndedCount,
    ): Unit = coroutineScope {
        val scan = this
        var failedSearches = 0
        while (true) {
            var heard = false
            // A search that keeps running is working, whether or not the ring is in range.
            val running = launch {
                delay(SEARCH_SETTLED)
                _searchFailed.value = false
            }
            try {
                hear(address).collect {
                    heard = true
                    _searchFailed.value = false
                    val at = clock()
                    _lastHeard.value = at
                    val advertisement = HeardAd(at, it)
                    if (it.recording) {
                        // A press is served here, on the coroutine that has the advertisement in hand, rather than
                        // queued to the loop: that hop is the press's pre-connect wait (see servePress). Started
                        // undispatched, so the connect is asked for before this coroutine yields. A press heard
                        // while a link is open cannot be served without a second one, and is dropped — which is what
                        // the loop's tryReceive has always done with one heard during a session.
                        if (link.tryLock()) {
                            scan.launch(start = CoroutineStart.UNDISPATCHED) {
                                try {
                                    servePress(address, advertisement, ended)
                                } finally {
                                    link.unlock()
                                }
                            }
                        } else {
                            logger.d { "a press was heard while a link was open, so it was dropped: $address" }
                        }
                    } else {
                        latest.send(advertisement)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "searching for $address failed" }
            } finally {
                running.cancel()
            }
            failedSearches = if (heard) 0 else failedSearches + 1
            if (!heard) _searchFailed.value = true
            delay(rescanWait(base = rescanDelay, heard = heard, failedSearches = failedSearches, continuous = mediaRemote()))
        }
    }

    private suspend fun syncOnce(
        address: String,
        advertisement: RingAdvertisement,
        heardAtMillis: Long,
    ) {
        _state.value = RingSyncState.Syncing
        val before = imported()
        var tries = 0
        while (true) {
            tries++
            val outcome = try {
                ended(withTimeout(sessionLimit) { sync(address, advertisement, heardAtMillis) }, before)
            } catch (e: TimeoutCancellationException) {
                logger.w { "sync with $address passed $sessionLimit" }
                if (imported() > before) RingSyncState.Synced(clock(), imported() - before).also { attempts.continued() } else failed()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                logger.w(e) { "sync with $address was canceled from inside" }
                failed()
            } catch (e: RingPositionUnreadableException) {
                logger.e(e) { "sync with $address stopped" }
                _positionLost.value = true
                RingSyncState.PositionLost
            } catch (e: RingLinkException) {
                logger.w(e) { "sync with $address, try $tries" }
                when {
                    e.failure == RingLinkFailure.RETRY && tries < MAX_RING_TRIES -> null
                    e.failure == RingLinkFailure.PAIR_AGAIN -> {
                        attempts.failed()
                        RingSyncState.PairAgain
                    }
                    else -> failed()
                }
            } catch (e: Exception) {
                logger.w(e) { "sync with $address failed" }
                failed()
            }
            if (outcome != null) {
                _state.value = outcome
                return
            }
            delay(RETRY_DELAY)
        }
    }

    private fun ended(end: RingSessionEnd, importedBefore: Int): RingSyncState = when (end) {
        RingSessionEnd.SYNCED -> {
            // With the media remote on, the rest is not taken: the next advertisement may be the user's next tap.
            attempts.synced(rest = !mediaRemote())
            RingSyncState.Synced(clock(), imported() - importedBefore)
        }
        RingSessionEnd.NOT_STORED -> {
            attempts.failed()
            RingSyncState.Waiting(lastWait() ?: RingWait.NOT_KEPT)
        }
        RingSessionEnd.STILL_RECORDING -> {
            logger.w { "the ring was still recording when the session ended" }
            failed()
        }
    }

    private fun failed(): RingSyncState {
        attempts.failed()
        return RingSyncState.Failed(clock())
    }

    /** [check] within [timeout]; a failure or a timeout is logged and returned as a failed result. */
    private suspend fun <T> checked(what: String, timeout: Duration = checkTimeout, check: suspend () -> T): Result<T> = try {
        Result.success(withTimeout(timeout) { check() })
    } catch (e: TimeoutCancellationException) {
        logger.w { "$what took longer than $timeout" }
        Result.failure(e)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "$what failed" }
        Result.failure(e)
    }

    private companion object {
        val logger = Logger.withTag("RingSyncRunner")
        val RETRY_DELAY = 1.seconds
        val SEARCH_SETTLED = 10.seconds
    }
}

/** The longest a search waits before starting again while it keeps ending without hearing the ring. */
private val MAX_RESCAN_DELAY = 5.minutes

/** One advertisement the search heard, with the moment it heard it: the press timings measure from [atMillis]. */
private data class HeardAd(val atMillis: Long, val advertisement: RingAdvertisement)

/**
 * The collection count the last session ended at.
 *
 * A holder rather than a local, because two coroutines now end sessions: the loop for a scheduled sync, and the
 * scan's own coroutine for a press ([RingSyncRunner.servePress]). The write is a whole reference to an `Int?`, so the
 * field only has to be visible to the reader, not locked.
 */
private class EndedCount(@Volatile var value: Int?)

/**
 * How long the search waits before starting again: [base] after a search that heard the ring, and doubling up to five
 * minutes while searches end without hearing it — Android counts scan starts per app, the watch's included
 * (android16-release, packages/modules/Bluetooth `AppScanStats.isScanningTooFrequently`).
 *
 * With [continuous] — the ring's media remote switched on — the gap is [base] whatever happened, so the app is
 * listening when the finger lands instead of waiting out a window in which the press arrives and is not heard. A
 * search that ended is still followed by one gap rather than started again at once: a scan that keeps failing must not
 * spin, and Android's own limit on scan starts would refuse it anyway.
 */
internal fun rescanWait(base: Duration, heard: Boolean, failedSearches: Int, continuous: Boolean): Duration = when {
    continuous -> base
    heard -> base
    else -> (base * (1 shl (failedSearches - 1).coerceIn(0, 7))).coerceAtMost(MAX_RESCAN_DELAY)
}

/**
 * When the runner may try again: [syncHold] after a sync; after a failure, a doubling delay from 30 s up to 15 min; after
 * a wait for the Notebook, a minute. A sync is due on first hearing the ring and [safetyInterval] after the last one.
 *
 * [synced] takes the rest as an argument because it is not always wanted: while the ring's media remote is on, the
 * next advertisement has to be acted on at once, and a rest after a sync is 30 s in which the button's own tap would
 * arrive and be ignored. A failure's backoff is not conditional: a ring that will not connect must not be retried in
 * a loop.
 */
internal class RingAttempts(private val marks: TimeSource, private val safetyInterval: Duration, private val syncHold: Duration) {
    private var lastSynced: TimeMark? = null
    private var holdUntil: TimeMark? = null
    private var failures = 0

    @Synchronized
    fun ready(): Boolean = holdUntil?.hasPassedNow() ?: true

    @Synchronized
    fun syncDue(): Boolean = lastSynced?.let { it.elapsedNow() >= safetyInterval } ?: true

    /** A session ended SYNCED; [rest] is false where the schedule would delay the next press. */
    @Synchronized
    fun synced(rest: Boolean = true) {
        val now = marks.markNow()
        lastSynced = now
        holdUntil = if (rest) now + syncHold else null
        failures = 0
    }

    /** A session that ran out of time after importing something: the next one may start at once. */
    @Synchronized
    fun continued() {
        holdUntil = null
    }

    @Synchronized
    fun failed() {
        holdUntil = marks.markNow() + backoff(failures)
        failures++
    }

    @Synchronized
    fun waited() {
        holdUntil = marks.markNow() + 1.minutes
    }

    @Synchronized
    fun reset() {
        lastSynced = null
        holdUntil = null
        failures = 0
    }

    private fun backoff(failures: Int): Duration = (30.seconds * (1 shl failures.coerceAtMost(5))).coerceAtMost(15.minutes)
}
