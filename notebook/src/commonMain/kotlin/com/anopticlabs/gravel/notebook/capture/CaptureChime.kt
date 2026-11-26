package com.anopticlabs.gravel.notebook.capture

import co.touchlab.kermit.Logger

/** Plays the app's one capture chime. A platform player does its own work off the caller's thread. */
fun interface ChimePlayer {
    fun play()
}

/**
 * Why the chime was not played. Each is a fact to record, never an error to show: the capture itself went through.
 * [logLine] is the debug line written at that moment, in the same shape the reply path writes.
 */
enum class ChimeHeldReason(val logLine: String) {
    /** Spoken replies are switched off, and the acknowledgement obeys the same switch. */
    REPLIES_OFF("chime not played: spoken replies are switched off"),
    SILENCED("chime not played: the phone is in silent or vibrate mode"),
    DO_NOT_DISTURB("chime not played: Do Not Disturb is holding interruptions back"),
    NO_BLUETOOTH_OUTPUT("chime not played: no Bluetooth audio output is connected"),
    PINNED_DEVICE_NOT_CONNECTED("chime not played: the chosen Bluetooth device is not connected"),
}

/**
 * Where the app allows the chime's sound to go, asked at the moment of every chime: null when it may be heard, the
 * reason when it may not.
 *
 * The rule behind it is the voice assistant's own output policy — the replies switch, headset only, and the
 * pinned device — so the acknowledgement is heard in exactly the places a spoken reply would be. It is
 * implemented where that policy and the connected audio outputs are both visible, in the app; :notebook knows
 * only how to ask, and a build with the voice loop compiled out answers with the conservative half of the rule.
 */
fun interface ChimeRouting {
    fun held(): ChimeHeldReason?
}

/**
 * Announces a ring capture the moment it has been received and taken for transcription, and nothing else: the
 * Notebook's own dictation, a typed note and a recording from the watch stay silent.
 *
 * Two questions are asked at each capture, never once at startup, so a phone that went into silent mode or Do Not
 * Disturb, or a headset switched off, after the app started is still obeyed: the phone's own state ([silence]),
 * then the app's audio routing ([routing]). Both must permit the chime, and the state is asked first so a phone
 * that is merely silenced is logged as that rather than as a routing problem.
 *
 * The caller never waits: [captureTaken] hands the player a request and returns, so no capture, sync or button
 * path is held up by the chime.
 */
class CaptureChime(
    private val player: ChimePlayer,
    private val routing: ChimeRouting = ANY_OUTPUT,
    private val silence: () -> ChimeHeldReason? = { null },
    private val log: (String) -> Unit = { logger.d { it } },
) {
    /** Announce the capture that has just been accepted for transcription. Call once per capture. */
    fun captureTaken() {
        val held = silence() ?: routing.held()
        if (held == null) player.play() else log(held.logLine)
    }
}

/** A chime with no routing rule to consult: for a caller that has no audio policy of its own. */
private val ANY_OUTPUT = ChimeRouting { null }

private val logger = Logger.withTag("CaptureChime")

/**
 * The phone's own state as a reason a chime is held back, or null when that state allows it: silent or vibrate
 * mode ([silenced]) and Do Not Disturb ([doNotDisturb]) are both obeyed as they are now, and neither is overridden.
 */
fun chimeSilence(silenced: Boolean, doNotDisturb: Boolean): ChimeHeldReason? = when {
    silenced -> ChimeHeldReason.SILENCED
    doNotDisturb -> ChimeHeldReason.DO_NOT_DISTURB
    else -> null
}
