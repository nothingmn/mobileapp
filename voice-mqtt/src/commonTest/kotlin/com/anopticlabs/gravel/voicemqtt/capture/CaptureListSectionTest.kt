package com.anopticlabs.gravel.voicemqtt.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The captured-messages section folded away: it starts closed, a tap opens it, the count stays on the header
 * either way, and an opened list holding nothing still has something to say.
 *
 * These are the decisions the settings page renders, kept out of the `@Composable` so they run on a plain JVM.
 * The layout itself is Compose on an Android runtime and is not exercised here.
 */
class CaptureListSectionTest {

    private fun message(at: Long, matched: Boolean = true) = VoiceCapturedMessage(
        atMillis = at,
        topic = "voice/reply/1",
        matched = matched,
        verdict = VoiceCaptureVerdict.ANSWER,
        preview = "the answer",
    )

    private val three = listOf(message(3), message(2), message(1))

    @Test
    fun aSectionIsFoldedWhenThePageBuildsIt() {
        assertFalse(CaptureListSection.EXPANDED_BY_DEFAULT, "the list is folded until the operator asks for it")
        assertFalse(CaptureListSection().expanded)
    }

    @Test
    fun aTapOpensItAndTheNextTapFoldsItAgain() {
        val opened = CaptureListSection().toggled()
        assertTrue(opened.expanded)
        assertFalse(opened.toggled().expanded)
    }

    @Test
    fun aFoldedHeaderStillCarriesTheCount() {
        val folded = CaptureListSection()
        assertEquals(3, folded.headerCount(three), "a folded list still says how much arrived")
        assertEquals(0, folded.headerCount(emptyList()), "and says zero rather than nothing at all")
    }

    @Test
    fun theEntriesWaitForTheTap() {
        assertTrue(CaptureListSection().entries(three).isEmpty(), "folded shows none of them, however many arrived")
        assertEquals(three, CaptureListSection(expanded = true).entries(three))
    }

    @Test
    fun anOpenedSectionHoldingNothingSaysSoInWords() {
        assertTrue(
            CaptureListSection(expanded = true).showsEmptyLine(emptyList()),
            "an opened empty list needs a line saying so, not a gap",
        )
        assertFalse(
            CaptureListSection(expanded = true).showsEmptyLine(three),
            "the entries are their own statement; no empty line beside them",
        )
        assertFalse(
            CaptureListSection().showsEmptyLine(emptyList()),
            "folded, there is no line to read at all — the header's count is the whole statement",
        )
    }
}
