package com.anopticlabs.gravel.voicemqtt.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the speaking section reads back from a preferences file, including the one file shape that predates the
 * two switches: the first increment wrote a headset choice and no switch at all.
 */
class VoiceSpeakingSettingsTest {

    @Test
    fun aFreshInstallGetsTheDocumentedDefaults() {
        val speaking = VoiceSpeakingSettings.read(emptyMap())
        assertTrue(speaking.speakReplies, "replies are spoken by default")
        assertTrue(speaking.headsetOnly, "playback is restricted to Bluetooth by default")
        assertNull(speaking.preferredDevice, "any Bluetooth device by default")
    }

    @Test
    fun aFileFromTheFirstIncrementKeepsItsBehaviourAndItsDevice() {
        // Exactly what 91bbf175 could leave behind: a chosen headset, and no switch key at all (it forced the
        // value in code and never wrote it).
        val speaking = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.LEGACY_KEY_HEADSET_ADDRESS to "AA:BB:CC:DD:EE:01",
                VoiceSpeakingSettings.LEGACY_KEY_HEADSET_NAME to "Jabra Elite",
            ),
        )
        assertTrue(speaking.speakReplies, "the old build always spoke with a headset, so replies stay on")
        assertTrue(speaking.headsetOnly, "and it only ever spoke through Bluetooth")
        assertEquals(PreferredDevice("AA:BB:CC:DD:EE:01", "Jabra Elite"), speaking.preferredDevice)
    }

    @Test
    fun aStoredSwitchIsReadBackAsTheUserLeftIt() {
        val off = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.KEY_SPEAK_REPLIES to false,
                VoiceSpeakingSettings.KEY_HEADSET_ONLY to false,
            ),
        )
        assertFalse(off.speakReplies)
        assertFalse(off.headsetOnly)
    }

    @Test
    fun theNewKeysWinOverTheLegacyOnes() {
        val speaking = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.KEY_SPEAK_REPLIES to false,
                VoiceSpeakingSettings.KEY_HEADSET_ONLY to false,
                VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS to "11:22:33:44:55:66",
                VoiceSpeakingSettings.KEY_PREFERRED_NAME to "New headset",
                VoiceSpeakingSettings.LEGACY_KEY_SPEAK_ONLY_WITH_HEADSET to true,
                VoiceSpeakingSettings.LEGACY_KEY_HEADSET_ADDRESS to "AA:BB:CC:DD:EE:01",
                VoiceSpeakingSettings.LEGACY_KEY_HEADSET_NAME to "Old headset",
            ),
        )
        assertFalse(speaking.speakReplies)
        assertFalse(speaking.headsetOnly)
        assertEquals(PreferredDevice("11:22:33:44:55:66", "New headset"), speaking.preferredDevice)
    }

    @Test
    fun aClearedChoiceOfAnyDeviceReadsBackAsAny() {
        assertNull(
            VoiceSpeakingSettings.read(mapOf(VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS to "")).preferredDevice,
        )
        assertNull(VoiceSpeakingSettings.read(mapOf(VoiceSpeakingSettings.LEGACY_KEY_HEADSET_ADDRESS to " ")).preferredDevice)
    }

    @Test
    fun anAddressWithNoNameFallsBackToTheAddress() {
        val speaking = VoiceSpeakingSettings.read(
            mapOf(VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS to "AA:BB:CC:DD:EE:01"),
        )
        assertEquals(PreferredDevice("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:01"), speaking.preferredDevice)
    }

    @Test
    fun theSettingsObjectNeverPrintsTheCredentials() {
        val printed = VoiceSettings(username = "someone", password = "hunter2").toString()
        assertTrue(printed.contains("speakReplies=true"))
        assertTrue(printed.contains("headsetOnly=true"))
        assertFalse(printed.contains("hunter2"))
        assertFalse(printed.contains("someone"))
    }

    @Test
    fun aFileWrittenBeforeTheEngineChoicesKeepsBothSwitchesTheDeviceAndGoesOfflineInSpeech() {
        // Exactly what 711716a0 wrote on this phone: the two switches and the pinned headset, and no engine,
        // voice or offline-speech key at all.
        val speaking = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.KEY_SPEAK_REPLIES to true,
                VoiceSpeakingSettings.KEY_HEADSET_ONLY to false,
                VoiceSpeakingSettings.KEY_PREFERRED_ADDRESS to "AA:BB:CC:DD:EE:01",
                VoiceSpeakingSettings.KEY_PREFERRED_NAME to "Jabra Elite",
            ),
        )
        assertTrue(speaking.speakReplies, "the switch the user left on stays on")
        assertFalse(speaking.headsetOnly, "the switch the user turned off stays off")
        assertEquals(PreferredDevice("AA:BB:CC:DD:EE:01", "Jabra Elite"), speaking.preferredDevice, "the pin survives")
        assertEquals(null, speaking.ttsEngine, "no engine was ever chosen: the phone's default is used")
        assertEquals(null, speaking.preferredVoice, "and the best offline voice for the language")
        assertTrue(speaking.offlineOnly, "a file written before the switch existed reads back as offline only")
    }

    @Test
    fun aChosenEngineAndVoiceAreReadBack() {
        val speaking = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.KEY_TTS_ENGINE to "com.samsung.android.ptts.enus",
                VoiceSpeakingSettings.KEY_PREFERRED_VOICE to "en-us-x-sfg#female_1-local",
                VoiceSpeakingSettings.KEY_OFFLINE_ONLY to false,
            ),
        )
        assertEquals("com.samsung.android.ptts.enus", speaking.ttsEngine)
        assertEquals("en-us-x-sfg#female_1-local", speaking.preferredVoice)
        assertFalse(speaking.offlineOnly, "the user's own choice of the looser mode is kept")
    }

    @Test
    fun aBlankEngineChoiceReadsBackAsThePhonesDefault() {
        val speaking = VoiceSpeakingSettings.read(
            mapOf(
                VoiceSpeakingSettings.KEY_TTS_ENGINE to "",
                VoiceSpeakingSettings.KEY_PREFERRED_VOICE to "  ",
            ),
        )
        assertEquals(null, speaking.ttsEngine)
        assertEquals(null, speaking.preferredVoice)
    }
}
