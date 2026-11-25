package com.anopticlabs.gravel.ring.link

/**
 * The windows Settings > General > Ring button offers, in seconds.
 *
 * Why eight and ten, and why nothing longer. Measured on the operator's phone with the window on: the ring drops a
 * held link by itself about ten seconds after a press ("the ring dropped the held link (ring link failed: OTHER)"),
 * so a window of twenty or thirty seconds was a promise the hardware cancels: the second tap of a slow pair always
 * paid for a fresh connect, and the setting said it would not. Eight and ten seconds are windows the ring actually
 * keeps, and ten is the longest it can be held at all. Why a list of two rather than a slider: the window is paid
 * for by the ring's battery, so a number the user can drag out to a minute is a number that flattens the ring's cell
 * without saying so. Both are short enough that the ring is asleep again by the time the phone is back in a pocket.
 */
val RING_WARM_WINDOWS_SECONDS: List<Int> = listOf(8, 10)

/** What a fresh install reads: enough for a double tap, inside the window the ring itself keeps. */
const val RING_WARM_DEFAULT_SECONDS: Int = 8

/**
 * Whether the ring's link stays connected after a press, and for how long it is kept once nothing else happens.
 *
 * **Off** by default, and read fresh at every press (through `RingButtonMapping`), so the switch in Settings takes
 * effect on the next press rather than at the next restart. The trade is stated on the page and here: a held link
 * is a GATT connection the ring has to keep alive between presses, and the ring's battery pays for it. What it
 * buys is that the next tap does not pay for a fresh connect and service discovery before the button record can
 * be read.
 */
data class RingWarmSettings(
    val enabled: Boolean = false,
    val windowSeconds: Int = RING_WARM_DEFAULT_SECONDS,
) {
    /**
     * The quiet window in milliseconds, at the nearest window the page offers.
     *
     * A stored twenty or thirty — what this setting wrote before the ring was measured dropping the link itself —
     * reads as ten, the longest window the ring honours, rather than as nothing or as a crash: the user asked for
     * the longest hold they were offered, and this is the longest hold there is.
     */
    val windowMillis: Long
        get() {
            val seconds = when {
                windowSeconds in RING_WARM_WINDOWS_SECONDS -> windowSeconds
                windowSeconds > RING_WARM_WINDOWS_SECONDS.last() -> RING_WARM_WINDOWS_SECONDS.last()
                else -> RING_WARM_WINDOWS_SECONDS.first()
            }
            return seconds * 1000L
        }

    companion object {
        /** No link held between presses: what the app did before this setting existed. */
        val Off: RingWarmSettings = RingWarmSettings()
    }
}

/**
 * What whoever holds the link should do next, one step of [RingWarmLink].
 *
 * [REUSE] is the whole point of the setting: a press that arrives inside the window uses the connection that is
 * already up. [CLOSE_THEN_CONNECT] exists because one link is held at a time — a link whose window has run out has
 * been given up on, so it is closed before the press builds its own, rather than handed over stale.
 */
enum class RingWarmAction {
    /** Nothing is held: open a link for this press. */
    CONNECT,

    /** A link is held inside its window: use it, and restart the clock. */
    REUSE,

    /** A link is held but its window is spent (or the setting was turned off): close it, then open one. */
    CLOSE_THEN_CONNECT,

    /** A link is held and the quiet window is over: close it. */
    CLOSE,

    /** A link is held and its window is still running: leave it alone. */
    HOLD,

    /** Nothing is held, and nothing should be. */
    NOTHING,
}

/** The action and the state it leaves behind; the state is what a caller stores in place of the old one. */
data class RingWarmStep(val action: RingWarmAction, val link: RingWarmLink)

/**
 * The warm-link decision, as a value: given the clock, the window, the last use and whether a link is held, what to
 * do next. Pure — no coroutines, no Bluetooth, no wall clock of its own — so the five behaviours that matter can be
 * proved on a host rather than on a phone:
 *
 * - a press inside the window reuses the held link and restarts the clock ([press] answers [RingWarmAction.REUSE]);
 * - a window that runs out closes the link ([lapsed] answers [RingWarmAction.CLOSE]);
 * - a press after the window has run out reconnects ([press] answers [RingWarmAction.CLOSE_THEN_CONNECT], and the
 *   next press inside the new window reuses again);
 * - with the setting off the link closes as the session ends ([ended] answers [RingWarmAction.CLOSE]);
 * - a link the ring dropped itself ends the window with nothing left to close ([lost] answers
 *   [RingWarmAction.NOTHING], and [lapsed] afterwards answers [RingWarmAction.NOTHING] too).
 *
 * The caller is the only thing that touches a socket: this type only says which socket to close and which to use.
 * [lastUseMillis] is the last press or the end of the last session, whichever is later, because the window is a
 * window of *quiet*: a press restarts it, and so does the session that press started.
 */
data class RingWarmLink(
    val enabled: Boolean = false,
    val windowMillis: Long = RING_WARM_DEFAULT_SECONDS * 1000L,
    val linked: Boolean = false,
    val lastUseMillis: Long? = null,
) {
    /** This link, with the settings the user has now; the timer and each press re-read them. */
    fun withSettings(settings: RingWarmSettings): RingWarmLink =
        copy(enabled = settings.enabled, windowMillis = settings.windowMillis)

    /** When the quiet window ends, by the caller's clock; null when no link is held. */
    fun quietEndsAtMillis(): Long? = if (linked) lastUseMillis?.plus(windowMillis) else null

    /** Whether the quiet window has run out at [nowMillis]; a held link with no use recorded counts as spent. */
    fun spent(nowMillis: Long): Boolean = lastUseMillis?.let { nowMillis - it >= windowMillis } ?: true

    /**
     * A press at [nowMillis]: reuse a held link inside its window, or say that a link has to be closed and one
     * opened. Either way the clock restarts here, which is how one press after another keeps the link up.
     */
    fun press(nowMillis: Long): RingWarmStep {
        val action = when {
            linked && (!enabled || spent(nowMillis)) -> RingWarmAction.CLOSE_THEN_CONNECT
            linked -> RingWarmAction.REUSE
            else -> RingWarmAction.CONNECT
        }
        return RingWarmStep(action, copy(linked = true, lastUseMillis = nowMillis))
    }

    /**
     * The session started by a press has ended at [nowMillis], and the link is still up. With the setting on, the
     * quiet window starts here and the link is held; with it off, the link goes at once, as it always did.
     */
    fun ended(nowMillis: Long): RingWarmStep = when {
        !linked -> RingWarmStep(RingWarmAction.NOTHING, copy(lastUseMillis = null))
        !enabled -> RingWarmStep(RingWarmAction.CLOSE, copy(linked = false, lastUseMillis = null))
        else -> RingWarmStep(RingWarmAction.HOLD, copy(lastUseMillis = nowMillis))
    }

    /**
     * The quiet timer fired at [nowMillis]. A press may have restarted the clock in the meantime, in which case the
     * link stays and the timer is set again for the new end.
     */
    fun lapsed(nowMillis: Long): RingWarmStep = when {
        !linked -> RingWarmStep(RingWarmAction.NOTHING, copy(lastUseMillis = null))
        !enabled || spent(nowMillis) -> RingWarmStep(RingWarmAction.CLOSE, copy(linked = false, lastUseMillis = null))
        else -> RingWarmStep(RingWarmAction.HOLD, this)
    }

    /**
     * The link went down by itself — the ring went out of range, or hung up. There is nothing left to close, so the
     * window ends here: [RingWarmAction.NOTHING], no held link, no clock running, and the next press builds a link
     * of its own.
     */
    fun lost(): RingWarmStep = RingWarmStep(RingWarmAction.NOTHING, copy(linked = false, lastUseMillis = null))
}
