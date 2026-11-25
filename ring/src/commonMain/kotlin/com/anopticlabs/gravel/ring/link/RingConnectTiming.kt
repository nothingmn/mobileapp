package com.anopticlabs.gravel.ring.link

/**
 * The clock of one link open, marked as each step of it finishes: the GATT link coming up, service discovery
 * finishing, the control channel's subscription being confirmed, the data channel's, and the moment the link could
 * carry a session's first request.
 *
 * Why five marks rather than one. Measured on the operator's phone, the press spent about 3.2 s between hearing the
 * ring's advertisement and having a usable link, against 152 ms for the debounce — so the cost is in the open, and
 * "connected" as one number could not say which part of it. The marks are set by the Android link (see
 * `withRingConnection`); this class only carries them, and [RingConnectMarks.line] is the one place they become
 * words, so the shape of the line is proved on a host rather than on a phone.
 *
 * [clock] is the app's button clock (`System.currentTimeMillis`, the one the advertisement's own time is read on),
 * so the first gap can be taken from the advertisement the runner heard. One open is marked on the coroutine that
 * opened it, so the fields need no lock.
 */
class RingConnectTiming(private val clock: () -> Long = System::currentTimeMillis) {
    private var gattAtMillis: Long? = null
    private var discoveryAtMillis: Long? = null
    private var controlAtMillis: Long? = null
    private var dataAtMillis: Long? = null
    private var upAtMillis: Long? = null

    /**
     * The GATT link came up. The first stamp wins: the link watches the peripheral's state for the moment Android
     * reports the connection, and reads the same mark at the end of `connect()` in case that report was missed, so
     * whichever arrives first is the one kept and the second can only be later than the truth.
     */
    fun gattConnected() {
        if (gattAtMillis == null) gattAtMillis = clock()
    }

    /** The services and their characteristics had been read: service discovery is complete. */
    fun discovered() {
        if (discoveryAtMillis == null) discoveryAtMillis = clock()
    }

    /** The control channel (the ring's answers and control notifications) is subscribed. */
    fun controlSubscribed() {
        if (controlAtMillis == null) controlAtMillis = clock()
    }

    /** The data channel (recordings, audio and the button report) is subscribed. */
    fun dataSubscribed() {
        if (dataAtMillis == null) dataAtMillis = clock()
    }

    /** The link is usable: both channels are subscribed, which is what "connected" means here. */
    fun connected() {
        if (upAtMillis == null) upAtMillis = clock()
    }

    fun marks(): RingConnectMarks =
        RingConnectMarks(gattAtMillis, discoveryAtMillis, controlAtMillis, dataAtMillis, upAtMillis)
}

/**
 * One link open's marks, taken once the link is up. [line] is the debug line a press writes on the `RingButton` tag,
 * beside the press timings: the milliseconds each step of the open cost, and nothing guessed.
 *
 * Every mark but the advertisement's is the link's own (see [RingConnectTiming]); the advertisement's is the runner's,
 * handed in because the link never saw it. The two channels are named as this codebase names them: the **control**
 * channel, which carries the ring's answers and control notifications, and the **data** channel, which carries the
 * recordings, the audio and the button report. A mark that is missing drops only the step it belongs to, so a link
 * that failed before the second channel was subscribed still says what the first steps cost.
 */
data class RingConnectMarks(
    val gattConnectedAtMillis: Long? = null,
    val discoveredAtMillis: Long? = null,
    val controlSubscribedAtMillis: Long? = null,
    val dataSubscribedAtMillis: Long? = null,
    val connectedAtMillis: Long? = null,
) {
    /** Nothing was marked: a link a test's fake opened, or one whose open had not begun. */
    val isEmpty: Boolean
        get() = gattConnectedAtMillis == null &&
            discoveredAtMillis == null &&
            controlSubscribedAtMillis == null &&
            dataSubscribedAtMillis == null &&
            connectedAtMillis == null

    /** The open's steps and what each cost, from the advertisement heard at [heardAtMillis]. */
    fun line(heardAtMillis: Long?): String {
        val marks = listOf(
            "advertisement" to heardAtMillis,
            "gatt connected" to gattConnectedAtMillis,
            "services discovered" to discoveredAtMillis,
            "control channel" to controlSubscribedAtMillis,
            "data channel" to dataSubscribedAtMillis,
            "connected" to connectedAtMillis,
        )
        val parts = mutableListOf<String>()
        if (heardAtMillis == null) parts += "advertisement time unknown"
        for (index in 1 until marks.size) {
            val (fromName, from) = marks[index - 1]
            val (toName, to) = marks[index]
            if (from != null && to != null) parts += "$fromName -> $toName ${to - from} ms"
        }
        return if (parts.isEmpty()) "connect timings: nothing marked" else "connect timings: " + parts.joinToString(", ")
    }
}
