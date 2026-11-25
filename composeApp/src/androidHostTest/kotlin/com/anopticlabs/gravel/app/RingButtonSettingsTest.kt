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
    fun theSavedFileHoldsTheWarmLinkAndItsWindowToo() {
        val dir = tempDir()
        try {
            val file = RingButtonFile(dir)
            val mapping = RingButtonMapping(enabled = true, warmLink = true, warmWindowSeconds = 30)
            assertTrue(file.save(mapping))

            assertEquals(mapping, RingButtonFile(dir).read())
            assertEquals(30_000L, RingButtonFile(dir).read().warm.windowMillis)
            assertTrue(RingButtonFile(dir).read().warm.enabled)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aFileSavedBeforeTheWarmLinkExistedStillReadsAsTheLinkNotHeld() {
        val dir = tempDir()
        try {
            // What this feature wrote before the setting existed: the switch and the three destinations.
            File(dir, "ring_button").writeText("1,1,2,3")
            val mapping = RingButtonFile(dir).read()

            assertTrue(mapping.enabled)
            assertEquals(RingButtonAction.NEXT, mapping.click)
            assertFalse(mapping.warmLink, "a link was never held for a file that did not know the setting")
            assertEquals(20, mapping.warmWindowSeconds)
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
