package com.anopticlabs.gravel.notebook.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The chime's decision: one capture taken for transcription, one sound, and nothing when the phone's own state or
 * the app's routing rule holds it back. Both must permit it, and a silence always says which one it was.
 */
class CaptureChimeTest {
    private var plays = 0
    private val lines = mutableListOf<String>()
    private var route: ChimeHeldReason? = null
    private var state: ChimeHeldReason? = null

    private val chime = CaptureChime(
        player = ChimePlayer { plays++ },
        routing = { route },
        silence = { state },
        log = { lines += it },
    )

    @Test
    fun aCaptureTakenForTranscriptionChimesOnce() {
        chime.captureTaken()

        assertEquals(1, plays)
        assertEquals(emptyList(), lines, "an audible chime logs nothing")
    }

    @Test
    fun aPhoneInSilentModeIsNotChimedAndSaysSo() {
        state = chimeSilence(silenced = true, doNotDisturb = false)

        chime.captureTaken()

        assertEquals(0, plays, "silent mode is not overridden")
        assertEquals(listOf("chime not played: the phone is in silent or vibrate mode"), lines)
    }

    @Test
    fun aPhoneUnderDoNotDisturbIsNotChimedAndSaysSo() {
        state = chimeSilence(silenced = false, doNotDisturb = true)

        chime.captureTaken()

        assertEquals(0, plays, "Do Not Disturb is not overridden")
        assertEquals(listOf("chime not played: Do Not Disturb is holding interruptions back"), lines)
    }

    @Test
    fun aRouteThatDoesNotPermitIsNotChimedAndNamesItsReason() {
        for (held in listOf(
            ChimeHeldReason.REPLIES_OFF,
            ChimeHeldReason.NO_BLUETOOTH_OUTPUT,
            ChimeHeldReason.PINNED_DEVICE_NOT_CONNECTED,
        )) {
            route = held

            chime.captureTaken()
        }

        assertEquals(0, plays)
        assertEquals(
            listOf(
                "chime not played: spoken replies are switched off",
                "chime not played: no Bluetooth audio output is connected",
                "chime not played: the chosen Bluetooth device is not connected",
            ),
            lines,
        )
    }

    @Test
    fun bothThePhonesStateAndTheRoutingMustPermitTheChime() {
        state = null
        route = ChimeHeldReason.NO_BLUETOOTH_OUTPUT
        chime.captureTaken()
        assertEquals(0, plays, "routing alone holds it back")

        state = chimeSilence(silenced = false, doNotDisturb = true)
        route = null
        chime.captureTaken()
        assertEquals(0, plays, "the phone's state alone holds it back")

        state = null
        route = null
        chime.captureTaken()
        assertEquals(1, plays, "and with both permitting it is heard")
    }

    @Test
    fun theStateIsNamedFirstWhenBothWouldHoldItBack() {
        state = chimeSilence(silenced = true, doNotDisturb = false)
        route = ChimeHeldReason.NO_BLUETOOTH_OUTPUT

        chime.captureTaken()

        assertEquals(listOf("chime not played: the phone is in silent or vibrate mode"), lines)
    }

    @Test
    fun theRouteIsAskedAtEachCaptureRatherThanOnceAtStartup() {
        route = ChimeHeldReason.NO_BLUETOOTH_OUTPUT
        chime.captureTaken()
        route = null
        chime.captureTaken()
        route = ChimeHeldReason.NO_BLUETOOTH_OUTPUT
        chime.captureTaken()

        assertEquals(1, plays, "only the capture made while a headset was connected is announced")
    }

    @Test
    fun aChimeIsSilencedOnlyWhenThePhoneIsNeitherSilentNorUnderDoNotDisturb() {
        assertNull(chimeSilence(silenced = false, doNotDisturb = false))
        assertEquals(ChimeHeldReason.SILENCED, chimeSilence(silenced = true, doNotDisturb = false))
        assertEquals(ChimeHeldReason.DO_NOT_DISTURB, chimeSilence(silenced = false, doNotDisturb = true))
        assertEquals(ChimeHeldReason.SILENCED, chimeSilence(silenced = true, doNotDisturb = true))
    }
}
