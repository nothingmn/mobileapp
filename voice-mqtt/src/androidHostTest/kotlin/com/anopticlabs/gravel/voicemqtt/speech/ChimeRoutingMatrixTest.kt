package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.notebook.capture.ChimeHeldReason
import com.anopticlabs.gravel.voicemqtt.settings.PreferredDevice
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The capture chime against the voice output policy, cell by cell: it follows the same restrictions as a spoken
 * reply, so the phone's speaker never announces a capture while headset only is on, and switching replies off
 * silences it too. Nothing here needs a phone — [PlaybackPolicy] is the decision and [chimeHeld] only restates it
 * in the chime's terms.
 */
class ChimeRoutingMatrixTest {

    private val headset = ConnectedOutput(bluetooth = true, address = "AA:BB:CC:DD:EE:01")
    private val otherHeadset = ConnectedOutput(bluetooth = true, address = "AA:BB:CC:DD:EE:02")

    /** The phone's own speaker: an output that is not Bluetooth. */
    private val phoneSpeaker = ConnectedOutput(bluetooth = false)

    private fun settings(
        speakReplies: Boolean = true,
        headsetOnly: Boolean,
        preferred: PreferredDevice? = null,
    ) = VoiceSettings(speakReplies = speakReplies, headsetOnly = headsetOnly, preferredDevice = preferred)

    private fun held(settings: VoiceSettings, outputs: List<ConnectedOutput>) = chimeHeld(settings, outputs)

    @Test
    fun repliesOffSilencesTheChime() {
        val off = settings(speakReplies = false, headsetOnly = false)

        assertEquals(ChimeHeldReason.REPLIES_OFF, held(off, listOf(phoneSpeaker)), "the speaker is not its own answer")
        assertEquals(ChimeHeldReason.REPLIES_OFF, held(off, listOf(headset)), "not even through a connected headset")
        assertEquals(
            ChimeHeldReason.REPLIES_OFF,
            held(off.copy(headsetOnly = true), listOf(headset)),
        )
    }

    @Test
    fun headsetOnlyWithNoBluetoothOutputSilencesTheChime() {
        val on = settings(headsetOnly = true)

        assertEquals(ChimeHeldReason.NO_BLUETOOTH_OUTPUT, held(on, emptyList()))
        assertEquals(
            ChimeHeldReason.NO_BLUETOOTH_OUTPUT,
            held(on, listOf(phoneSpeaker)),
            "the phone's speaker never takes a headset's place",
        )
    }

    @Test
    fun aConnectedBluetoothOutputWithNoPinnedDeviceLetsTheChimeThrough() {
        assertNull(held(settings(headsetOnly = true), listOf(phoneSpeaker, headset)))
    }

    @Test
    fun aPinnedDeviceThatIsNotConnectedSilencesTheChime() {
        val pinned = settings(headsetOnly = true, preferred = PreferredDevice(otherHeadset.address!!, "Something else"))

        assertEquals(
            ChimeHeldReason.PINNED_DEVICE_NOT_CONNECTED,
            held(pinned, listOf(phoneSpeaker, headset)),
            "another headset is connected, but not the chosen one",
        )
    }

    @Test
    fun aConnectedPinnedDeviceLetsTheChimeThrough() {
        val pinned = settings(headsetOnly = true, preferred = PreferredDevice(headset.address!!, "Jabra"))

        assertNull(held(pinned, listOf(phoneSpeaker, headset, otherHeadset)))
    }

    @Test
    fun headsetOnlyOffLetsTheChimeThroughThePhoneSpeaker() {
        val off = settings(headsetOnly = false)

        assertNull(held(off, listOf(phoneSpeaker)))
        assertNull(held(off, emptyList()), "whatever output is active, including none reported")
        assertNull(held(off, listOf(phoneSpeaker, headset)))
    }

    @Test
    fun aPlatformThatCannotNameAddressesDoesNotRefuseAPinnedDevice() {
        val pinned = settings(headsetOnly = true, preferred = PreferredDevice(headset.address!!, "Jabra"))

        assertNull(
            held(pinned, listOf(ConnectedOutput(bluetooth = true, address = null))),
            "before Android P an output has no address to compare, and the connected output stands",
        )
    }
}
