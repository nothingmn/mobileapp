package com.anopticlabs.gravel.ring.button

import co.touchlab.kermit.Logger

/**
 * What the paired ring's button does between the advertisements and the phone: feeds every advertisement to the
 * [RingAdvertisementPresses] source and the [RingButtonClassifier], sends the media key a gesture was mapped to, and
 * answers whether a press the switch has taken over is open right now.
 *
 * The runner asks [holdsCapture] for an advertisement whose only reason to sync is that the button is down. While
 * the answer is true that advertisement starts no sync, so a tap the classifier is about to consume as a media
 * gesture leaves no empty recording behind and no connection is opened for it. Once the press outlives
 * [RING_TAP_MILLIS] the answer is false and the advertisement is handled exactly as before, so a note being recorded
 * is read as it always was. A sync caused by anything else — a changed collection count, a clock request, the
 * six-hourly one — is never held, whatever the button is doing, so nothing the ring actually recorded is stranded.
 *
 * [mapping] is read on every advertisement, so switching the setting on in Settings takes effect on the next press
 * rather than at the next restart.
 */
class RingButtonController(
    private val mapping: () -> RingButtonMapping,
    private val keys: RingMediaKeys = RingMediaKeys.None,
    private val presses: RingAdvertisementPresses = RingAdvertisementPresses(),
    private val classifier: RingButtonClassifier = RingButtonClassifier(),
) {
    /** One advertisement at [atMillis], as the scan heard it. */
    fun advertisement(atMillis: Long, recording: Boolean) {
        presses.onAdvertisement(atMillis, recording)?.let { press ->
            classifier.onPress(press).forEach { dispatch(it, mapping()) }
        }
        classifier.onTick(atMillis).forEach { dispatch(it, mapping()) }
    }

    /** Whether a press the switch has taken over is open at [atMillis]; see the class comment. */
    fun holdsCapture(atMillis: Long): Boolean = mapping().enabled && presses.tapInProgress(atMillis)

    /** Called with the recordings a sync produced; logged when a media gesture's press left one on the ring. */
    fun syncFinished(collections: Int) {
        classifier.onSyncResult(collections)
    }

    /** Forgets a half-read press, for a ring that was unpaired or a search that restarted it. */
    fun forget() {
        presses.forget()
        classifier.forget()
    }

    private fun dispatch(event: RingButtonEvent, mapping: RingButtonMapping) {
        if (!mapping.consumes(event.gesture)) {
            logger.i { "ring button: ${event.gesture} left to the ring (recording)" }
            return
        }
        val action = mapping.actionFor(event.gesture)
        if (action == RingButtonAction.NOTHING) {
            logger.i { "ring button: ${event.gesture} does nothing (not mapped)" }
            return
        }
        // A phone whose media session refuses the key must not end the scan, so the send is contained here.
        try {
            keys.send(action)
            logger.i { "ring button: ${event.gesture} -> $action" }
        } catch (e: Exception) {
            logger.e(e) { "ring button: ${event.gesture} -> $action was not sent" }
        }
    }

    private companion object {
        val logger = Logger.withTag("RingButton")
    }
}
