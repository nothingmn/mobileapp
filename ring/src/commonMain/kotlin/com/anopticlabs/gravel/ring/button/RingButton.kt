package com.anopticlabs.gravel.ring.button

/**
 * What the ring's button produced, in the vocabulary upstream's `:experimental` gesture routing uses
 * (`RingGesture`): a single tap, two or three taps, a press held down, or a tap followed by a hold.
 *
 * [NONE] is the classifier's answer for an advertisement that closes no sequence — a press still open, or a
 * sequence still waiting for its gap. It is never emitted as an event and never reaches a media key; it exists so
 * that "the button did nothing" is a value a mapping can be asked about, rather than an absence the caller has to
 * remember.
 */
enum class RingButtonGesture {
    NONE,
    CLICK,
    DOUBLE_CLICK,
    TRIPLE_CLICK,
    HOLD,
    CLICK_HOLD,
    ;

    /**
     * Whether this gesture records a note on the ring. The recording gestures are the ring's own, not something
     * this feature invented, so they are never remapped to a media key: [HOLD] records a note, and [CLICK_HOLD]
     * records one after a tap on the button.
     */
    val records: Boolean get() = this == HOLD || this == CLICK_HOLD
}

/** Where a media gesture sends its input, or [NOTHING] for a gesture the user left unassigned. */
enum class RingButtonAction { PLAY_PAUSE, NEXT, PREVIOUS, NOTHING }

/** What sends a [RingButtonAction] to the phone's media session; the Android one is an AudioManager in the app. */
fun interface RingMediaKeys {
    /** Sends [action]. Never called with [RingButtonAction.NOTHING]. */
    fun send(action: RingButtonAction)

    companion object {
        /** For a runner with no media control behind it: every gesture is swallowed. */
        val None: RingMediaKeys = RingMediaKeys { }
    }
}

/**
 * How the user mapped the ring's button: the master switch, and the destination of each media gesture. The
 * recording gestures are not here because they cannot be remapped.
 *
 * The switch is **off** by default: a ring's button already records notes, and taking that button over without
 * being asked would change what it does for every owner of the ring who never opens Settings.
 */
data class RingButtonMapping(
    val enabled: Boolean = false,
    val click: RingButtonAction = RingButtonAction.PLAY_PAUSE,
    val doubleClick: RingButtonAction = RingButtonAction.NEXT,
    val tripleClick: RingButtonAction = RingButtonAction.PREVIOUS,
) {
    /** Where [gesture] goes: a media key for a media gesture with the switch on, nothing otherwise. */
    fun actionFor(gesture: RingButtonGesture): RingButtonAction {
        if (!enabled) return RingButtonAction.NOTHING
        return when (gesture) {
            RingButtonGesture.CLICK -> click
            RingButtonGesture.DOUBLE_CLICK -> doubleClick
            RingButtonGesture.TRIPLE_CLICK -> tripleClick
            RingButtonGesture.NONE, RingButtonGesture.HOLD, RingButtonGesture.CLICK_HOLD -> RingButtonAction.NOTHING
        }
    }

    /**
     * Whether [gesture] is the button's media gesture, so the press that produced it was not asking for a
     * recording. A media gesture mapped to [RingButtonAction.NOTHING] still counts: the user said this tap does
     * nothing on the phone, not that it should record a note.
     */
    fun consumes(gesture: RingButtonGesture): Boolean = enabled && !gesture.records && gesture != RingButtonGesture.NONE

    /** The saved form: the switch and the three ordinals, which [decode] reads back; 48 characters at most. */
    fun encode(): String = listOf(
        if (enabled) "1" else "0",
        click.ordinal.toString(),
        doubleClick.ordinal.toString(),
        tripleClick.ordinal.toString(),
    ).joinToString(",")

    companion object {
        /** The mapping [text] was saved as, or the default one when it is missing or does not read back. */
        fun decode(text: String?): RingButtonMapping {
            val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return RingButtonMapping()
            if (parts.size != 4 || parts.any { it == null }) return RingButtonMapping()
            val actions = RingButtonAction.entries
            if (parts.drop(1).any { it !in actions.indices }) return RingButtonMapping()
            return RingButtonMapping(
                enabled = parts[0] == 1,
                click = actions[parts[1]!!],
                doubleClick = actions[parts[2]!!],
                tripleClick = actions[parts[3]!!],
            )
        }
    }
}
