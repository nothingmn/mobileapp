package com.anopticlabs.gravel.voicemqtt.capture

/**
 * Whether the captured-messages list on the settings page is folded away.
 *
 * Folded by default, and deliberately not persisted: the page builds a fresh one every time it is opened, so a
 * list the operator expanded on his last visit is closed again on the next. The header carries the count either
 * way — a folded list still says whether anything arrived — and only the entries themselves wait for a tap.
 *
 * The decisions live here rather than inside the `@Composable` so they can be run on a plain JVM: the layout
 * itself needs an Android runtime, this does not.
 */
data class CaptureListSection(val expanded: Boolean = EXPANDED_BY_DEFAULT) {

    /** The header tapped: folded becomes open, open becomes folded. */
    fun toggled(): CaptureListSection = CaptureListSection(!expanded)

    /**
     * What the header says about the list: how many messages are held, whichever way the section is folded. The
     * count is never something that has to be read by opening the list.
     */
    fun headerCount(captures: List<VoiceCapturedMessage>): Int = captures.size

    /** The entries the section shows. Folded means none of them, however many arrived. */
    fun entries(captures: List<VoiceCapturedMessage>): List<VoiceCapturedMessage> =
        if (expanded) captures else emptyList()

    /**
     * An opened section holding nothing says so in words rather than showing an empty gap. Folded, it says nothing
     * beyond its own count — there is no line to read until the operator asks for the list.
     */
    fun showsEmptyLine(captures: List<VoiceCapturedMessage>): Boolean = expanded && captures.isEmpty()

    companion object {
        /** Closed when the page opens. Not a setting and not remembered: every entry to the page starts folded. */
        const val EXPANDED_BY_DEFAULT = false
    }
}
