package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.settings.PreferredDevice
import com.anopticlabs.gravel.voicemqtt.settings.VoiceSettings
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two switches, cell by cell, against the audio outputs that are connected at the moment of speaking.
 *
 * Nothing here needs a phone: [PlaybackPolicy] is the decision itself, and the Android gate only tells it what
 * `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` reported. So each cell of the matrix is provable on the host.
 */
class PlaybackMatrixTest {

    private val headset = ConnectedOutput(bluetooth = true, address = "AA:BB:CC:DD:EE:01")
    private val otherHeadset = ConnectedOutput(bluetooth = true, address = "AA:BB:CC:DD:EE:02")

    /** The phone's own speaker: an output that is not Bluetooth. */
    private val phoneSpeaker = ConnectedOutput(bluetooth = false)

    private fun settings(
        speakReplies: Boolean,
        headsetOnly: Boolean,
        preferred: PreferredDevice? = null,
    ) = VoiceSettings(speakReplies = speakReplies, headsetOnly = headsetOnly, preferredDevice = preferred)

    private fun silent(reason: SpeechSuppression) = PlaybackDecision.Silent(reason)

    private fun decide(settings: VoiceSettings, outputs: List<ConnectedOutput>) =
        PlaybackPolicy.decide(settings, outputs)

    @Test
    fun repliesOnAndHeadsetOnlyOnNeedsALiveBluetoothOutput() {
        val on = settings(speakReplies = true, headsetOnly = true)
        assertEquals(PlaybackDecision.Speak, decide(on, listOf(headset)), "a connected headset")
        assertEquals(
            silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT),
            decide(on, emptyList()),
            "nothing connected",
        )
        assertEquals(
            silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT),
            decide(on, listOf(phoneSpeaker)),
            "the phone's speaker is not an acceptable output while headset only is on",
        )
    }

    @Test
    fun aPairedButDisconnectedHeadsetSuppressesRatherThanFallingThroughToTheSpeaker() {
        // The gate reports outputs connected right now, so a headset that is paired but switched off is not in
        // the list at all — only the phone's speaker is. That must silence the reply, not redirect it.
        val on = settings(speakReplies = true, headsetOnly = true, preferred = PreferredDevice(headset.address!!, "Jabra"))
        assertEquals(silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT), decide(on, listOf(phoneSpeaker)))
        assertEquals(silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT), decide(on, emptyList()))
    }

    @Test
    fun repliesOnAndHeadsetOnlyOffPlaysThroughWhateverIsActive() {
        val off = settings(speakReplies = true, headsetOnly = false)
        assertEquals(PlaybackDecision.Speak, decide(off, listOf(headset)), "a connected headset")
        assertEquals(PlaybackDecision.Speak, decide(off, listOf(phoneSpeaker)), "the phone's speaker")
        assertEquals(PlaybackDecision.Speak, decide(off, emptyList()), "no output the platform names")
        // A pinned device is not consulted at all while headset only is off.
        val pinned = off.copy(preferredDevice = PreferredDevice(otherHeadset.address!!, "Something else"))
        assertEquals(PlaybackDecision.Speak, decide(pinned, listOf(phoneSpeaker)))
    }

    @Test
    fun repliesOffNeverSpeaksWhateverTheOutputsAre() {
        for (headsetOnly in listOf(true, false)) {
            for (outputs in listOf(listOf(headset), listOf(phoneSpeaker), emptyList<ConnectedOutput>())) {
                val decision = decide(settings(speakReplies = false, headsetOnly = headsetOnly), outputs)
                assertEquals(
                    silent(SpeechSuppression.REPLIES_OFF),
                    decision,
                    "replies off, headsetOnly=$headsetOnly, outputs=$outputs",
                )
            }
        }
    }

    @Test
    fun theDefaultsSpeakWithAConnectedHeadsetAndHoldBackWithoutOne() {
        val defaults = VoiceSettings()
        assertTrue(defaults.speakReplies, "replies are spoken by default")
        assertTrue(defaults.headsetOnly, "playback is restricted to Bluetooth by default")
        assertEquals(null, defaults.preferredDevice, "any Bluetooth device by default")
        assertEquals(PlaybackDecision.Speak, decide(defaults, listOf(headset)))
        assertEquals(silent(SpeechSuppression.NO_BLUETOOTH_OUTPUT), decide(defaults, listOf(phoneSpeaker)))
    }

    @Test
    fun aPinnedDeviceIsSpokenOnAndAnyOtherConnectedOneIsNot() {
        val pinned = settings(
            speakReplies = true,
            headsetOnly = true,
            preferred = PreferredDevice("AA:BB:CC:DD:EE:01", "Jabra"),
        )
        assertEquals(PlaybackDecision.Speak, decide(pinned, listOf(headset)))
        assertEquals(
            silent(SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED),
            decide(pinned, listOf(otherHeadset)),
            "another headset is connected, but not the one chosen",
        )
        assertEquals(
            PlaybackDecision.Speak,
            decide(pinned.copy(preferredDevice = PreferredDevice("aa:bb:cc:dd:ee:01", "Jabra")), listOf(headset)),
            "the address match ignores case",
        )
    }

    @Test
    fun aPinnedDeviceThatCannotBeNamedStillSpeaksOnTheConnectedBluetoothOutput() {
        // Before Android P an output reports no address; failing closed there would silence every reply on such
        // a phone, so the connected Bluetooth output stands.
        val pinned = settings(true, true, PreferredDevice("AA:BB:CC:DD:EE:01", "Jabra"))
        assertEquals(PlaybackDecision.Speak, decide(pinned, listOf(ConnectedOutput(bluetooth = true, address = null))))
    }

    @Test
    fun aSuppressedSentenceIsRecordedInTheRunHistory() {
        var clock = 1_700_000_000_000L
        val history = VoiceSpeechHistory(now = { clock }, limit = 3)

        history.suppressed(SpeechSuppression.NO_BLUETOOTH_OUTPUT)
        clock += 1
        history.spoken()
        clock += 1
        history.suppressed(SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED)

        assertEquals(
            VoiceSpeechRun(clock, spoken = false, suppression = SpeechSuppression.PREFERRED_DEVICE_NOT_CONNECTED),
            history.runs.value.first(),
            "newest first",
        )
        assertEquals(3, history.runs.value.size)

        clock += 1
        history.spoken()
        assertEquals(3, history.runs.value.size, "the history is bounded")
        assertEquals(true, history.runs.value.first().spoken)
    }

    @Test
    fun everySuppressionHasALogLineAndASentenceForTheScreen() {
        for (reason in SpeechSuppression.entries) {
            assertTrue(PlaybackPolicy.logLine(reason).isNotBlank(), "log line for $reason")
            // The sentence's own words live in the resources; the rule only names the copy it means.
            assertTrue(
                PlaybackPolicy.explanation(reason).key in VoiceKeys.all,
                "explanation for $reason names shipped copy",
            )
            assertTrue(PlaybackPolicy.explanation(reason).args.isEmpty(), "no value to format: $reason")
        }
    }
}
