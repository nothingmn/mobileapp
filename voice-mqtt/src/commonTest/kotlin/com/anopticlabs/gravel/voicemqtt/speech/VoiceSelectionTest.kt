package com.anopticlabs.gravel.voicemqtt.speech

import com.anopticlabs.gravel.voicemqtt.copy.VoiceCopy
import com.anopticlabs.gravel.voicemqtt.copy.VoiceKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one rule about which voice speaks, cell by cell, with no phone, no engine and no voice file.
 *
 * The requirement it protects: with "Offline speech" on, a reply is never sent to a third party to be spoken.
 * So every case here is either an on-device voice or a named refusal — never a network voice standing in.
 */
class VoiceSelectionTest {

    private val embedded = OfferedVoice("en-us-x-sfg#female_1-local", "en-US", networkRequired = false, quality = 300)
    private val embeddedBetter = OfferedVoice("en-us-x-sfg#male_1-local", "en-US", networkRequired = false, quality = 400)
    private val cloud = OfferedVoice("en-us-x-sfg#female_2-network", "en-US", networkRequired = true, quality = 500)
    private val german = OfferedVoice("de-de-x-dea#female_1-local", "de-DE", networkRequired = false, quality = 300)

    private fun refuse(reason: VoiceRefusal) = VoiceChoice.Refused(reason)

    private fun selected(choice: VoiceChoice): OfferedVoice {
        assertTrue(choice is VoiceChoice.Selected, "expected a voice, got $choice")
        return (choice as VoiceChoice.Selected).voice
    }

    @Test
    fun aNetworkOnlyEngineIsRefusedRatherThanSendingTheReplyToTheCloud() {
        val choice = VoiceSelection.choose(listOf(cloud), language = "en")
        assertEquals(refuse(VoiceRefusal.NETWORK_VOICES_ONLY), choice)
        assertEquals(
            VoiceCopy(VoiceKeys.REFUSAL_NETWORK_VOICES_ONLY),
            VoiceSelection.explanation(VoiceRefusal.NETWORK_VOICES_ONLY, "English"),
            "the screen says why nothing is spoken, by name of the copy it resolves",
        )
    }

    @Test
    fun anEngineWithOneOfflineAndOneNetworkVoicePicksTheOfflineOne() {
        // The cloud voice is the higher quality one on the platform's scale; it is still not allowed to speak.
        val voice = selected(VoiceSelection.choose(listOf(cloud, embedded), language = "en"))
        assertEquals(embedded, voice)
        assertFalse(voice.networkRequired)
    }

    @Test
    fun aLanguageWithNoOfflineVoiceGivesTheNamedReason() {
        val choice = VoiceSelection.choose(listOf(german), language = "en")
        assertEquals(refuse(VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE), choice)
        val explanation = VoiceSelection.explanation(VoiceRefusal.NO_OFFLINE_VOICE_FOR_LANGUAGE, "English")
        assertEquals(VoiceKeys.REFUSAL_NO_OFFLINE_VOICE_FOR_LANGUAGE, explanation.key)
        assertEquals(listOf("English"), explanation.args, "the sentence names the language it is about")
    }

    @Test
    fun aPinnedVoiceThatNeedsTheNetworkIsRefusedWhileOfflineSpeechIsOn() {
        val choice = VoiceSelection.choose(listOf(cloud, embedded), language = "en", pinnedName = cloud.name)
        assertEquals(refuse(VoiceRefusal.PINNED_VOICE_IS_NETWORK_ONLY), choice)
    }

    @Test
    fun aPinnedOfflineVoiceIsHonoured() {
        val choice = VoiceSelection.choose(listOf(cloud, embedded, embeddedBetter), language = "en", pinnedName = embedded.name)
        assertEquals(embedded, selected(choice), "the pin wins over the higher-quality automatic choice")
        assertTrue((choice as VoiceChoice.Selected).pinnedHonoured)
    }

    @Test
    fun aPinnedVoiceTheEngineNoLongerOffersFallsBackToAnOfflineOneAndSaysSo() {
        val choice = VoiceSelection.choose(listOf(embedded), language = "en", pinnedName = "voice-that-was-uninstalled")
        assertEquals(embedded, selected(choice))
        assertFalse((choice as VoiceChoice.Selected).pinnedHonoured, "the pin is gone, and the choice says so")
    }

    @Test
    fun theBestQualityOfflineVoiceForTheLanguageWinsAndTiesBreakOnName() {
        assertEquals(
            embeddedBetter,
            selected(VoiceSelection.choose(listOf(embedded, embeddedBetter), language = "en")),
        )
        val a = OfferedVoice("a-voice", "en-GB", networkRequired = false, quality = 300)
        val b = OfferedVoice("b-voice", "en-GB", networkRequired = false, quality = 300)
        assertEquals(a, selected(VoiceSelection.choose(listOf(b, a), language = "en")))
    }

    @Test
    fun anotherRegionOfTheSameLanguageIsAcceptableButAnotherLanguageIsNot() {
        assertEquals(embedded, selected(VoiceSelection.choose(listOf(embedded, german), language = "en-GB")))
    }

    @Test
    fun anEngineWithNoVoicesAtAllIsRefused() {
        assertEquals(refuse(VoiceRefusal.NO_VOICES_OFFERED), VoiceSelection.choose(emptyList(), language = "en"))
    }

    @Test
    fun withOfflineSpeechOffALocalVoiceIsStillPreferredAndACloudOneIsAllowed() {
        assertEquals(
            embedded,
            selected(VoiceSelection.choose(listOf(cloud, embedded), language = "en", offlineOnly = false)),
            "a local voice is quicker and works on a plane, so it still wins",
        )
        assertEquals(
            cloud,
            selected(VoiceSelection.choose(listOf(cloud), language = "en", offlineOnly = false)),
            "with offline speech off a cloud voice is allowed, which is what the switch says",
        )
        // The pin is honoured too: the switch is the user's decision, not the picker's.
        val pinned = VoiceSelection.choose(listOf(cloud, embedded), "en", pinnedName = cloud.name, offlineOnly = false)
        assertEquals(cloud, selected(pinned))
    }

    @Test
    fun withOfflineSpeechOffAnEngineWithNoVoiceForTheLanguageStillGivesAReason() {
        assertEquals(
            refuse(VoiceRefusal.NO_VOICE_FOR_LANGUAGE),
            VoiceSelection.choose(listOf(german), language = "en", offlineOnly = false),
        )
    }

    @Test
    fun everyRefusalHasALogLineAndASentenceForTheScreen() {
        for (reason in VoiceRefusal.entries) {
            assertTrue(VoiceSelection.logLine(reason, "English").isNotBlank(), "log line for $reason")
            val explanation = VoiceSelection.explanation(reason, "English")
            assertTrue(explanation.key in VoiceKeys.all, "explanation for $reason names shipped copy: $explanation")
            // The words themselves, and the punctuation and plain-language rules they kept, are asserted against
            // the resource files in VoiceCopyKeysTest — that is where the sentences actually live now.
            assertTrue(explanation.args.size <= 1, "one value at most, so a translation may reorder it: $reason")
        }
    }
}
