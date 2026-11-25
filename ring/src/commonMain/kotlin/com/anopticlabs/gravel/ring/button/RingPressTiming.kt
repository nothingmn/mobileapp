package com.anopticlabs.gravel.ring.button

/**
 * The clock of one press: when its advertisement was heard, when the link was up, and — from the caller who read it —
 * when the button record arrived. The three numbers the Ring button page's setting is judged by, and the ones the
 * device supplies: nothing here measures anything, it only carries the marks to the one place that sends the key.
 *
 * Why it is here, beside the controller: the key is [RingButtonController.dispatch]'s, and the delay the user feels
 * is from the tap to that send. The press's own marks are set by the runner and the link keeper (which are in
 * `:ring`'s link package, as is this module's Android side); the record's mark is the `atMillis` the session reads
 * the collection at, so the whole chain shares the one clock the app already uses for the button
 * ([RingButtonController]'s `buttonClock`, `System.currentTimeMillis` in the app).
 *
 * A press is written here by the keeper just before it hands the link to the session, and read on that same
 * coroutine, so the fields need no lock: the runner syncs one press at a time.
 */
class RingPressTiming(private val clock: () -> Long = System::currentTimeMillis) {
    private var heardAtMillis: Long? = null
    private var connectedAtMillis: Long? = null
    private var reused = false

    /** A press was heard at [atMillis] — the runner's own reading of the advertisement. */
    fun pressHeard(atMillis: Long) {
        heardAtMillis = atMillis
        connectedAtMillis = null
        reused = false
    }

    /** A link was built for this press; [connectedAtMillis] is the moment it could carry the first request. */
    fun linkConnected() {
        connectedAtMillis = clock()
        reused = false
    }

    /** This press is being served by a link that was already up, so there is no connect of its own to time. */
    fun linkReused() {
        connectedAtMillis = null
        reused = true
    }

    /** The marks as they stand with the record read at [recordAtMillis]. */
    fun marks(recordAtMillis: Long): RingPressMarks =
        RingPressMarks(heardAtMillis, connectedAtMillis, recordAtMillis, reused)
}

/**
 * One press's marks, taken when its button record was read. [line] is the debug line the controller writes when the
 * gesture settles: the millisecond gaps the operator asked for, and nothing guessed.
 */
data class RingPressMarks(
    val heardAtMillis: Long?,
    val connectedAtMillis: Long?,
    val recordAtMillis: Long,
    val linkReused: Boolean,
) {
    /** [settledAtMillis] is when the gesture was settled (the key sent, for a mapped gesture); [keys] how many keys went. */
    fun line(settledAtMillis: Long, keys: Int): String {
        val parts = mutableListOf<String>()
        val heard = heardAtMillis
        val connected = connectedAtMillis
        when {
            linkReused -> parts += "link reused, no connect for this press"
            heard != null && connected != null -> parts += "advertisement -> connected ${connected - heard} ms"
            connected != null -> parts += "connected, advertisement time unknown"
        }
        if (heard != null) {
            parts += if (connected != null && !linkReused) {
                "connected -> button record ${recordAtMillis - connected} ms"
            } else {
                "advertisement -> button record ${recordAtMillis - heard} ms"
            }
        }
        parts += if (keys > 0) {
            "record -> key ${settledAtMillis - recordAtMillis} ms"
        } else {
            "record -> settle ${settledAtMillis - recordAtMillis} ms, no key"
        }
        return "press timings: " + parts.joinToString(", ")
    }
}
