package com.anopticlabs.gravel.ring.button

/**
 * The clock of one press: when its advertisement was heard, when the link was up, and — from the caller who read it —
 * when the button record arrived. The three numbers the press is judged by, and the ones the device supplies:
 * nothing here measures anything, it only carries the marks to the one place that sends the key.
 *
 * Why it is here, beside the controller: the key is [RingButtonController.dispatch]'s, and the delay the user feels
 * is from the tap to that send. The press's own marks are set by the runner and the link (which are in
 * `:ring`'s link package, as is this module's Android side); the record's mark is the `atMillis` the session reads
 * the collection at, so the whole chain shares the one clock the app already uses for the button
 * ([RingButtonController]'s `buttonClock`, `System.currentTimeMillis` in the app).
 *
 * A press is written here by the link that serves it, before the session reads anything, and read on that same
 * coroutine, so the fields need no lock: the runner syncs one press at a time.
 */
class RingPressTiming(private val clock: () -> Long = System::currentTimeMillis) {
    private var heardAtMillis: Long? = null
    private var connectedAtMillis: Long? = null

    /** A press was heard at [atMillis] — the runner's own reading of the advertisement. */
    fun pressHeard(atMillis: Long) {
        heardAtMillis = atMillis
        connectedAtMillis = null
    }

    /** The link built for this press is up; [connectedAtMillis] is the moment it could carry the first request. */
    fun linkConnected() {
        connectedAtMillis = clock()
    }

    /** The marks as they stand with the record read at [recordAtMillis]. */
    fun marks(recordAtMillis: Long): RingPressMarks =
        RingPressMarks(heardAtMillis, connectedAtMillis, recordAtMillis)
}

/**
 * One press as the link that serves it is asked for: the clock the key's line is drawn from, and the moment the
 * advertisement that started the press was heard. The runner hears that advertisement, the link stamps the rest of
 * the clock as it comes up, and the controller's settle writes the line.
 */
data class RingPressClock(val timing: RingPressTiming, val heardAtMillis: Long)

/**
 * One press's marks, taken when its button record was read. [line] is the debug line the controller writes when the
 * gesture settles: the millisecond gaps the press was measured by, and nothing guessed.
 */
data class RingPressMarks(
    val heardAtMillis: Long?,
    val connectedAtMillis: Long?,
    val recordAtMillis: Long,
) {
    /** [settledAtMillis] is when the gesture was settled (the key sent, for a mapped gesture); [keys] how many keys went. */
    fun line(settledAtMillis: Long, keys: Int): String {
        val parts = mutableListOf<String>()
        val heard = heardAtMillis
        val connected = connectedAtMillis
        when {
            heard != null && connected != null -> parts += "advertisement -> connected ${connected - heard} ms"
            connected != null -> parts += "connected, advertisement time unknown"
        }
        if (heard != null) parts += "advertisement -> button record ${recordAtMillis - heard} ms"
        parts += if (keys > 0) {
            "record -> key ${settledAtMillis - recordAtMillis} ms"
        } else {
            "record -> settle ${settledAtMillis - recordAtMillis} ms, no key"
        }
        return "press timings: " + parts.joinToString(", ")
    }
}
