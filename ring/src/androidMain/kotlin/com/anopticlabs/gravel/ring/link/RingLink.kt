package com.anopticlabs.gravel.ring.link

import android.bluetooth.BluetoothDevice
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ring.button.RingPressClock
import com.anopticlabs.gravel.ring.protocol.RingProtocolException
import com.anopticlabs.gravel.ring.sync.RingConnection
import com.anopticlabs.gravel.ring.sync.RingLinkFailure
import com.anopticlabs.gravel.ring.sync.RingNotification
import com.anopticlabs.gravel.ring.sync.ringLinkFailure
import com.juul.kable.Characteristic
import com.juul.kable.GattStatusException
import com.juul.kable.Peripheral
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.logs.Logging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The service UUID the ring advertises, for scan filters. */
val RING_ADVERTISED_SERVICE: Uuid = Uuid.parse("607b5c9b-3700-4e94-f44a-2df900bcb0c3")

private val CONTROL = Uuid.parse("c0ef558a-2058-fabf-a140-8d5acde50b39")
private val DATA = Uuid.parse("daad3d52-237c-90a7-b54b-8854a134d801")
private val SYSTEM_INPUT = Uuid.parse("1d1f4039-23f5-33b2-c24e-704351f20585")

/** Notifications a session may leave unread before the link counts as flooded. */
private const val NOTIFICATION_BUFFER = 256

private val log = Logger.withTag("RingLink")

/** The tag a press's own lines are written on: the button's, beside the press timings and the settled gesture. */
private val presses = Logger.withTag("RingButton")

/**
 * Connects to the bonded ring [device], runs [block] over the connection (see [runRingLink]), then disconnects and
 * closes the link. A device that is not bonded is refused before connecting, as needing pairing again.
 *
 * The link lives exactly as long as the press that opened it: a press is heard only as an advertisement, a ring with
 * a link open does not advertise, and so a link held between presses makes the next tap invisible (DESIGN_NOTES,
 * "Why the ring's link is not held between presses"). [press] is that press's clock, marked as the link comes up,
 * and the line saying what each step of the open cost is written on the button's own tag.
 */
suspend fun <T> withRingConnection(
    device: BluetoothDevice,
    press: RingPressClock? = null,
    block: suspend (RingConnection) -> T,
): T = coroutineScope {
    // Before the open, so the first gap is measured from the advertisement rather than from connecting.
    press?.timing?.pressHeard(press.heardAtMillis)
    val connects = RingConnectTiming()
    val link = openRingLink(device, this, connects = connects)
    if (press != null) {
        press.timing.linkConnected()
        if (!link.connect.isEmpty) presses.d { link.connect.line(press.heardAtMillis) }
    }
    overLink(link) { block(it) }
}

/**
 * Connects to the bonded ring [device] and leaves the link up until [close]. [scope] owns the subscriptions and the
 * watch on the link, so it must outlive the link; [withRingConnection] gives it the press's own coroutine.
 *
 * The returned link is usable when this returns: connected, service discovery done, both channels subscribed. That
 * is also what the press timings call "connected" — the moment a session could send its first request.
 *
 * [connects] is marked as each of those steps finishes, so a press can say which of them the wait was spent in
 * ([RingConnectMarks.line]).
 */
private suspend fun openRingLink(
    device: BluetoothDevice,
    scope: CoroutineScope,
    subscribeTimeout: Duration = 10.seconds,
    connects: RingConnectTiming = RingConnectTiming(),
): RingHeldConnection {
    val peripheral = Peripheral(device) { logging { level = Logging.Level.Warnings } }
    try {
        val (control, data, system) = linkStep(peripheral) {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw RingLinkException(null, null, RingLinkFailure.PAIR_AGAIN)
            }
            // The state is watched rather than read after connect() returns, because connect() does not come back
            // until the services are there: a mark taken after it would fold discovery into the wait for the link
            // and the two steps — the one this measurement is for — could not be told apart.
            coroutineScope {
                val up = launch(start = CoroutineStart.UNDISPATCHED) {
                    peripheral.state.first { it is State.Connected }
                    connects.gattConnected()
                }
                try {
                    connects.requested()
                    peripheral.connect()
                } finally {
                    up.cancel()
                }
                // A report that lost the race with connect()'s own resumption: the link is certainly up by now.
                connects.gattConnected()
            }
            val found = peripheral.services.value.orEmpty().flatMap { it.characteristics }
            connects.discovered()
            if (found.isEmpty()) throw RingLinkException(null, null)
            listOf(CONTROL, DATA, SYSTEM_INPUT).map { uuid ->
                found.firstOrNull { it.characteristicUuid == uuid } ?: throw RingProtocolException("the ring has no characteristic $uuid")
            }
        }
        return attach(peripheral, control, data, system, scope, subscribeTimeout, connects)
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            quietly { peripheral.disconnect() }
            peripheral.close()
        }
        throw e
    }
}

/**
 * Over a connected [peripheral]: subscribes [control] and then [data], runs [block], and hangs up by
 * subscribing to [system], the ring's disconnect signal, before the other subscriptions end. The link going
 * down at any point, or a subscription not confirmed within [subscribeTimeout], throws [RingLinkException];
 * [block]'s own exceptions pass through.
 */
internal suspend fun <T> runRingLink(
    peripheral: Peripheral,
    control: Characteristic,
    data: Characteristic,
    system: Characteristic,
    block: suspend (RingConnection) -> T,
    subscribeTimeout: Duration = 10.seconds,
    connects: RingConnectTiming = RingConnectTiming(),
): T = coroutineScope {
    val link = attach(peripheral, control, data, system, this, subscribeTimeout, connects)
    overLink(link) { block(it) }
}

/** [block] over [link], with the link's loss ending the block at once, and the link closed either way. */
private suspend fun <T> overLink(link: RingHeldConnection, block: suspend (RingConnection) -> T): T = try {
    coroutineScope {
        val lost = launch { throw checkNotNull(link.ended.first { it != null }) }
        try {
            block(link)
        } finally {
            lost.cancel()
        }
    }
} finally {
    withContext(NonCancellable) { quietly { link.close() } }
}

/**
 * Watches, subscribes and returns [peripheral]'s link on [scope]; a link lost while subscribing throws here.
 *
 * Internal rather than private so a host test can open a link over a fake peripheral and prove the marks
 * [RingConnectTiming] takes as each channel is subscribed ([RingConnectMarks]).
 */
internal suspend fun attach(
    peripheral: Peripheral,
    control: Characteristic,
    data: Characteristic,
    system: Characteristic,
    scope: CoroutineScope,
    subscribeTimeout: Duration,
    connects: RingConnectTiming,
): RingHeldConnection = coroutineScope {
    val link = KableHeldLink(peripheral, control, data, system, scope, subscribeTimeout, connects)
    link.startWatching()
    try {
        coroutineScope {
            val lost = launch { throw link.lossWhenEnded() }
            try {
                link.subscribe(control, RingNotification::Control)
                connects.controlSubscribed()
                link.subscribe(data, RingNotification::Data)
                connects.dataSubscribed()
            } finally {
                lost.cancel()
            }
        }
        connects.connected()
        link
    } catch (e: Throwable) {
        withContext(NonCancellable) { quietly { link.close() } }
        throw e
    }
}

/**
 * One connection to the ring, kept open until [close]. Its subscriptions and its watch live on [scope]; a link that
 * goes down without being asked reports itself through [ended], which is what ends a session at once instead of at
 * the next request's timeout.
 */
private class KableHeldLink(
    private val peripheral: Peripheral,
    private val control: Characteristic,
    private val data: Characteristic,
    private val system: Characteristic,
    private val scope: CoroutineScope,
    private val subscribeTimeout: Duration,
    private val connects: RingConnectTiming,
) : RingHeldConnection {
    override val notifications = Channel<RingNotification>(NOTIFICATION_BUFFER)

    override val connect: RingConnectMarks get() = connects.marks()

    private val _ended = MutableStateFlow<RingLinkException?>(null)
    override val ended: StateFlow<RingLinkException?> = _ended.asStateFlow()

    private val _subscriptions = mutableListOf<Job>()
    private val closing = Mutex()

    @Volatile
    private var closed = false

    fun startWatching() {
        scope.launch {
            // An observation stays open across a lost link (Kable 0.43.1, Peripheral.observe), so the state is watched.
            val gone = peripheral.state.first { it is State.Disconnected } as State.Disconnected
            if (closed) return@launch
            val failure = RingLinkException((gone.status as? State.Disconnected.Status.Unknown)?.status, null)
            _ended.value = failure
            notifications.close(failure)
        }
    }

    /** Subscribes [characteristic] onto [notifications], and throws when the ring has not confirmed in time. */
    suspend fun subscribe(characteristic: Characteristic, wrap: (ByteArray) -> RingNotification) {
        val subscribed = CompletableDeferred<Unit>()
        val job = scope.launch {
            linkStep(peripheral) {
                peripheral.observe(characteristic) { subscribed.complete(Unit) }.collect { bytes ->
                    if (notifications.trySend(wrap(bytes)).isFailure) {
                        notifications.close(RingProtocolException("more notifications than the session read"))
                    }
                }
            }
        }
        if (withTimeoutOrNull(subscribeTimeout) { subscribed.await() } == null) {
            job.cancel()
            throw RingLinkException(null, null)
        }
        _subscriptions += job
    }

    override suspend fun writeControl(bytes: ByteArray) =
        linkStep(peripheral) { peripheral.write(control, bytes, WriteType.WithoutResponse) }

    override suspend fun writeData(bytes: ByteArray) =
        linkStep(peripheral) { peripheral.write(data, bytes, WriteType.WithoutResponse) }

    /** The exception the link went down with, once it has; for the watch that ends a session or a subscription. */
    suspend fun lossWhenEnded(): RingLinkException = checkNotNull(ended.first { it != null })

    override suspend fun close() {
        withContext(NonCancellable) {
            closing.withLock {
                if (closed) return@withLock
                closed = true
                quietly { hangUp(peripheral, system) }
                _subscriptions.forEach { it.cancel() }
                _subscriptions.clear()
                try {
                    peripheral.disconnect()
                } catch (e: Exception) {
                    log.w(e) { "disconnect failed" }
                }
                peripheral.close()
            }
        }
    }
}

/** When the hang-up signal cannot be sent, the caller's disconnect follows a short wait. */
private suspend fun hangUp(peripheral: Peripheral, system: Characteristic) {
    try {
        withTimeoutOrNull(1.seconds) {
            coroutineScope {
                val subscribing = launch { peripheral.observe(system).collect {} }
                peripheral.state.first { it is State.Disconnected }
                subscribing.cancel()
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        log.w(e) { "hang-up signal failed" }
        delay(200.milliseconds)
    }
}

private suspend fun quietly(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "a link step failed while closing" }
    }
}

/** Runs [step], turning a failure of the link into [RingLinkException] with the status Android reported. */
private suspend fun <T> linkStep(peripheral: Peripheral, step: suspend () -> T): T = try {
    step()
} catch (e: CancellationException) {
    throw e
} catch (e: RingProtocolException) {
    throw e
} catch (e: RingLinkException) {
    throw e
} catch (e: Exception) {
    val status = (e as? GattStatusException)?.status
        ?: ((peripheral.state.value as? State.Disconnected)?.status as? State.Disconnected.Status.Unknown)?.status
    throw RingLinkException(status, e)
}
