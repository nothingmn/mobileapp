package com.anopticlabs.gravel.voicemqtt.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the folded header reads, in both languages, and the one property that keeps it folded.
 *
 * The layout cannot run here: it is Compose on an Android runtime, and this module has no instrumented test source
 * set, so a host test cannot draw the row or tap it. What is checked instead is the copy the header renders —
 * read from the same resource files the build packages, applied the way `stringResource` applies them — and the
 * page's use of `remember` rather than `rememberSaveable`, which is what makes every entry to the page start
 * folded.
 */
class CaptureSectionHeaderTest {

    private val moduleDir: File = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .firstOrNull { File(it, "src/androidMain/res/values/strings.xml").isFile }
        ?: error("could not find the voice-mqtt module from ${System.getProperty("user.dir")}")

    private val english = strings("src/androidMain/res/values/strings.xml")
    private val french = strings("src/androidMain/res/values-fr/strings.xml")

    /** The header row's text exactly as it is drawn: the section's own label, then the count in brackets. */
    private fun header(values: Map<String, String>, count: Int): String = String.format(
        values.getValue("voice_capture_count"),
        values.getValue("voice_section_captured"),
        count,
    )

    @Test
    fun theHeaderReadsAsTheLabelAndItsCountInBothLanguages() {
        assertEquals("Messages received (3)", header(english, 3))
        assertEquals("Messages reçus (3)", header(french, 3))
    }

    @Test
    fun aFoldedHeaderWithNothingInItStillReadsSensibly() {
        // Nothing captured is the ordinary state of a fresh phone: the header has to say zero, not go blank.
        assertEquals("Messages received (0)", header(english, 0))
        assertEquals("Messages reçus (0)", header(french, 0))
    }

    @Test
    fun theCountIsAFormatSoTheLabelItselfIsWrittenOnce() {
        assertEquals("%1\$s (%2\$d)", english.getValue("voice_capture_count"))
        assertEquals(english.getValue("voice_capture_count"), french.getValue("voice_capture_count"))
        // And no second label was invented beside it: one place holds these words, so the two cannot drift.
        val label = english.getValue("voice_section_captured")
        assertEquals(setOf("voice_section_captured"), english.filterValues { it == label }.keys)
    }

    @Test
    fun theFoldedStateIsRememberedRatherThanSaved() {
        val source = File(
            moduleDir,
            "src/androidMain/kotlin/com/anopticlabs/gravel/voicemqtt/ui/VoiceMqttSettingsContent.kt",
        ).readText()
        val built = source.lines().single { it.contains("mutableStateOf(CaptureListSection(") }
        assertTrue(
            built.contains("remember {"),
            "the fold must be plain remember: a saved one would reopen the page as it was left. Built by: $built",
        )
        assertFalse(
            source.contains("rememberSaveable"),
            "a saveable fold survives navigating away and back, which is the one thing this must not do",
        )
    }

    private fun strings(path: String): Map<String, String> = Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        .findAll(File(moduleDir, path).readText())
        .associate { it.groupValues[1] to it.groupValues[2] }
}
