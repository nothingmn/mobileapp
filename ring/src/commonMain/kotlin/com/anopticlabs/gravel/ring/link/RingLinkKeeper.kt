package com.anopticlabs.gravel.ring.link

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.button.RingPressTiming
import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the ring's GATT link open between presses, for the window Settings > General > Ring button chooses, so the
 * second tap of a pair does not pay for a connect and service discovery again. All of the deciding is
 * [RingWarmLink]'s — this class only carries it out: it opens, hands over, holds and closes one link, and it always
 * closes.
 *
 * The rule it keeps, in the order the events arrive:
 *
 * - **A press.** [withLink] asks [RingWarmLink.press] what to do. Inside the window the held link is handed to
 *   [syncing] as it is, with anything the ring sent while it was idle drained first, and the clock restarts. Outside
 *   it, or with nothing held, a link is opened (bounded by [connectTimeout]) and the session runs on that. A link
 *   this press opened writes one debug line on the `RingButton` tag saying what each step of the open cost
 *   ([RingConnectMarks.line]); a press that reused a link has no open of its own and writes none.
 * - **A session that ends.** [RingWarmLink.ended] says hold or close: with the setting on the link stays and a timer
 *   is set for the end of the quiet window; with it off the link goes at once, exactly as `withRingConnection` did
 *   before this existed.
 * - **The timer.** It fires at the window's end and closes the link, logging why. A press in the meantime has
 *   already restarted the clock, so the timer finds the window still open and sets itself again.
 * - **A link that drops.** The ring went out of range, or hung up. The window ends, the link is released and the
 *   reason is logged; nothing is left holding a socket, and the next press connects.
 * - **A session that throws.** The link is closed straight away — a link that failed a sync is not the link the next
 *   press wants — and the exception goes to the runner unchanged, so its retry and backoff rules still apply.
 * - **The app going away.** [close] is for the background service stopping and for the setting being turned off:
 *   neither leaves a link open that nothing is using.
 *
 * One press at a time is the runner's own guarantee (it syncs one session at a time), so the timing marks this class
 * sets are read on the coroutine the session runs on. The [scope] is only for the timer and the lost-link watch; it
 * must outlive a session, which is why the caller owns it.
 */
class RingLinkKeeper(
    private val scope: CoroutineScope,
    private val settings: () -> RingWarmSettings,
    private val open: suspend (address: String) -> RingHeldConnection,
    private val timing: RingPressTiming = RingPressTiming(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectTimeout: Duration = 30.seconds,
) {
    private val lock = Mutex()
    private var link: RingHeldConnection? = null
    private var heldAddress: String? = null
    private var warm = RingWarmLink()
    private var deadline: Job? = null
    private var watching: Job? = null

    /** True while a link is held between presses; for tests and for anything that needs to say so. */
    val holding: Boolean get() = link != null

    /**
     * Runs [syncing] over a link to [address], reusing the held link when the press at [heardAtMillis] arrived
     * inside its window. The returned value and any exception are the session's own.
     */
    suspend fun <T> withLink(
        address: String,
        heardAtMillis: Long,
        syncing: suspend (RingHeldConnection) -> T,
    ): T = lock.withLock {
        timing.pressHeard(heardAtMillis)
        deadline?.cancel()
        deadline = null
        warm = warm.withSettings(settings())
        if (heldAddress != null && heldAddress != address) release("the paired ring is another one")
        val step = warm.press(clock())
        val held = when (step.action) {
            RingWarmAction.CLOSE_THEN_CONNECT -> {
                // One link at a time: the window that has run out is given up before this press builds its own.
                release("the warm window had passed before this press")
                warm = step.link
                connect(address, heardAtMillis)
            }
            RingWarmAction.CONNECT -> {
                warm = step.link
                connect(address, heardAtMillis)
            }
            RingWarmAction.REUSE -> {
                warm = step.link
                reuse(address, heardAtMillis)
            }
            else -> error("a press is never ${step.action}")
        }
        try {
            syncing(held).also { afterUse() }
        } catch (e: Throwable) {
            release("the sync it was holding failed: ${e.message ?: e::class.simpleName}")
            throw e
        }
    }

    /** Closes a held link now, in [scope], and logs [reason]. For the service stopping and for the setting going off. */
    fun close(reason: String) {
        scope.launch { closeNow(reason) }
    }

    /** [close], awaited — the same thing where the caller can wait. */
    suspend fun closeNow(reason: String) {
        lock.withLock { release(reason) }
    }

    private suspend fun reuse(address: String, heardAtMillis: Long): RingHeldConnection {
        val held = link ?: return connect(address, heardAtMillis)
        heldAddress = address
        val dropped = held.drain()
        if (dropped > 0) logger.d { "dropped $dropped notification(s) the ring sent while the link was idle" }
        timing.linkReused()
        logger.d { "using the link held from the last press instead of connecting again" }
        return held
    }

    private suspend fun connect(address: String, heardAtMillis: Long): RingHeldConnection {
        val started = clock()
        val opened = try {
            withTimeoutOrNull(connectTimeout) { open(address) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.w(e) { "connecting to $address for a press failed" }
            throw e
        }
        if (opened == null) {
            logger.w { "connecting to $address for a press took longer than $connectTimeout" }
            throw RingLinkException(null, null, RingLinkFailure.OTHER)
        }
        link = opened
        heldAddress = address
        timing.linkConnected()
        logger.d { "the link to $address was up ${clock() - started} ms after connecting was asked for" }
        // One line per press on the button's own tag, beside the press timings: which part of the open the wait went
        // into. 3.2 s between the advertisement and a usable link is either the link itself or what follows it, and
        // only the marks can say which.
        val connects = opened.connect
        if (!connects.isEmpty) presses.d { connects.line(heardAtMillis) }
        watch(opened)
        return opened
    }

    /** The session is over; hold the link for the window, or close it as the setting asks. */
    private suspend fun afterUse() {
        val step = warm.ended(clock())
        warm = step.link
        when (step.action) {
            RingWarmAction.CLOSE -> release("keep-the-ring-connected is off")
            RingWarmAction.HOLD -> {
                schedule()
                logger.d { "holding the link for ${warm.windowMillis} ms of quiet" }
            }
            else -> Unit
        }
    }

    private fun schedule() {
        val endsAt = warm.quietEndsAtMillis() ?: return
        deadline?.cancel()
        deadline = scope.launch {
            val wait = endsAt - clock()
            if (wait > 0) delay(wait)
            expire()
        }
    }

    private suspend fun expire() {
        lock.withLock {
            // This coroutine is the deadline, and it is finishing: nothing to cancel, nothing that could cancel it.
            deadline = null
            warm = warm.withSettings(settings())
            val step = warm.lapsed(clock())
            warm = step.link
            when (step.action) {
                RingWarmAction.CLOSE -> release("the ring was quiet for the whole ${warm.windowMillis} ms window")
                RingWarmAction.HOLD -> schedule()
                else -> Unit
            }
        }
    }

    private fun watch(held: RingHeldConnection) {
        watching?.cancel()
        watching = scope.launch {
            val why = held.ended.first { it != null }
            lock.withLock {
                if (link !== held) return@withLock
                link = null
                heldAddress = null
                watching = null
                deadline?.cancel()
                deadline = null
                warm = warm.lost().link
                logger.i { "the ring dropped the held link (${why?.message}); the warm window ends here" }
            }
            withContext(NonCancellable) { quietly { held.close() } }
        }
    }

    /** Closes and forgets whatever is held. Under [lock]; the caller has already decided. */
    private suspend fun release(reason: String) {
        val gone = link
        link = null
        heldAddress = null
        deadline?.cancel()
        deadline = null
        val watcher = watching
        watching = null
        if (watcher != null && watcher != currentCoroutineContext()[Job]) watcher.cancel()
        warm = warm.lost().link
        if (gone == null) return
        logger.i { "closing the held ring link: $reason" }
        withContext(NonCancellable) { quietly { gone.close() } }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "closing a ring link failed" }
        }
    }

    private companion object {
        val logger = Logger.withTag("RingLinkKeeper")

        /** The tag the press's own line is written on — the button's, beside the press timings. */
        val presses = Logger.withTag("RingButton")
    }
}
