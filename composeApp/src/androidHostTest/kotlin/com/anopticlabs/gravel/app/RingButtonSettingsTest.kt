package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ui.RingButtonActionChoice
import com.anopticlabs.gravel.ui.RingButtonFacts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The saved settings and the translation between the ring's vocabulary and the page's: what the file holds, what
 * reads back, and the one place the two enums meet.
 */
class RingButtonSettingsTest {

    private fun tempDir(): File = File.createTempFile("ring-button", "").let {
        it.delete()
        it.mkdirs()
        it
    }

    @Test
    fun theSavedFileHoldsTheSwitchAndTheThreeDestinationsAndReadsBack() {
        val dir = tempDir()
        try {
            val file = RingButtonFile(dir)
            assertEquals(RingButtonMapping(), file.read(), "nothing saved yet is the switch off")

            val mapping = RingButtonMapping(
                enabled = true,
                click = RingButtonAction.NEXT,
                doubleClick = RingButtonAction.NOTHING,
                tripleClick = RingButtonAction.PREVIOUS,
            )
            assertTrue(file.save(mapping))
            assertEquals(mapping, RingButtonFile(dir).read(), "a fresh instance reads what was written")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aDamagedFileReadsAsTheSwitchOff() {
        val dir = tempDir()
        try {
            File(dir, "ring_button").writeText("not a mapping at all, honestly")
            assertEquals(RingButtonMapping(), RingButtonFile(dir).read())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun everyActionAndChoiceCrossesBetweenTheTwoVocabularies() {
        for (action in RingButtonAction.entries) assertEquals(action, action.toChoice().toAction())
        for (choice in RingButtonActionChoice.entries) assertEquals(choice, choice.toAction().toChoice())
    }

    @Test
    fun aMappingAndItsFactsAreTheSameSettings() {
        val mapping = RingButtonMapping(
            enabled = true,
            click = RingButtonAction.PREVIOUS,
            doubleClick = RingButtonAction.PLAY_PAUSE,
            tripleClick = RingButtonAction.NOTHING,
        )

        assertEquals(mapping, mapping.toFacts().toMapping())
        assertEquals(RingButtonFacts(), RingButtonMapping().toFacts())
        assertFalse(RingButtonMapping().toFacts().enabled, "the page shows the switch off by default")
    }
}
