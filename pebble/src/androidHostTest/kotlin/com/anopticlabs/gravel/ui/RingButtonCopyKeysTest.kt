package com.anopticlabs.gravel.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The enforcement behind Settings > General > Ring button's localisation: the two locales hold the same keys, the code
 * agrees with them, and no English sentence is left in the Kotlin that draws one.
 *
 * The files themselves are the artefact the build packages, so they are read directly; the host test runs on a plain
 * JVM and a missing translation has to fail the build rather than fall back to English silently.
 *
 * The boundary: only this feature's `ring_button_*` keys are translated here. The rest of the app's UI is
 * English-only and out of scope, which is why `values-fr` carries one block per translated feature rather than
 * everything.
 */
class RingButtonCopyKeysTest {

    private val moduleDir: File = moduleDir()

    /** Walk up from wherever the test runs until the module's own sources are above us. */
    private fun moduleDir(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "src/commonMain/composeResources/values/strings.xml").isFile }
            ?: error("could not find the pebble module from $start")
    }

    private val english = strings("src/commonMain/composeResources/values/strings.xml")
    private val french = strings("src/commonMain/composeResources/values-fr/strings.xml")

    private val ours = { keys: Map<String, String> -> keys.filterKeys { it.startsWith("ring_button_") } }

    @Test
    fun bothLocalesHoldTheSameRingButtonKeys() {
        assertEquals(
            ours(english).keys,
            ours(french).keys,
            "a key missing from values-fr/strings.xml would fall back to English silently",
        )
    }

    @Test
    fun theCodeAndTheResourcesNameTheSameKeys() {
        assertEquals(RingButtonKeys.all, ours(french).keys, "every key the page can ask for is in both files")
        assertEquals(RingButtonKeys.all, ours(english).keys, "and no resource is unreachable")
    }

    @Test
    fun everyKeyHasItsComposeResource() {
        for (key in RingButtonKeys.all) {
            assertNotNull(ringButtonResourceFor(key), "$key has no entry in the page's lookup table")
        }
        assertEquals(null, ringButtonResourceFor("voice_tts_title"), "the page's table holds its own keys only")
    }

    @Test
    fun everyLabelThePageOffersHasAKey() {
        val offered = RingButtonTap.entries.map { it.labelKey } + RingButtonActionChoice.entries.map { it.labelKey }

        assertTrue(offered.all { it in RingButtonKeys.all }, "a label with no key would draw nothing: $offered")
    }

    @Test
    fun everyValueIsTranslated() {
        for ((key, value) in ours(french)) assertTrue(value.isNotBlank(), "$key is blank in French")

        // A copy-paste that skipped the translation shows up as a French value identical to the English one.
        val identical = ours(french).keys.filter { french.getValue(it) == english.getValue(it) }
        assertEquals(emptyList(), identical, "a French value identical to the English one was not translated")
    }

    @Test
    fun noUserFacingEnglishSentenceIsLeftInTheKotlinThatDrawsOne() {
        val offences = scan(filesOf("RingButtonScreen.kt", "RingButtonSettings.kt", "RingButtonCopy.kt"))

        assertTrue(offences.isEmpty(), "user-facing English left in the source:\n" + offences.joinToString("\n"))
    }

    @Test
    fun theScanFindsASentenceWhenThereIsOne() {
        // The control for the scan above: a passing scan must mean "nothing left", not "the scan stopped looking".
        val probe = File(moduleDir, "build/ring-button-scan-probe/Probe.kt").apply {
            parentFile.mkdirs()
            writeText("internal const val PROBE = \"A user-facing sentence that fails the scan.\"\n")
        }
        try {
            assertEquals(1, scan(listOf(probe)).size, "the scan does find a sentence when one is there")
        } finally {
            probe.parentFile.deleteRecursively()
        }
    }

    @Test
    fun theSettingsItemDrawsItsWordsFromTheResourcesToo() {
        val settings = File(moduleDir, "src/commonMain/kotlin/coredevices/pebble/ui/WatchSettingsScreen.kt").readText()

        assertTrue(settings.contains("RingButtonKeys.ITEM_TITLE"), "the item's title is drawn from the resources")
        assertTrue(settings.contains("RingButtonKeys.ITEM_DESCRIPTION"), "and so is its description")
        assertTrue(settings.contains("ringButtonItemShown("), "and it is offered only where the rule allows it")
        assertFalse(settings.contains("\"Ring button\""), "no English copy for this item in the settings screen")
    }

    /** Every literal in [sources] that reads as copy someone would see and is not developer text. */
    private fun scan(sources: List<File>): List<String> {
        val developerText = setOf("no string resource for ")
        val offences = mutableListOf<String>()
        for (source in sources) {
            source.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
                if (trimmed.startsWith("@")) return@forEachIndexed
                for (literal in literalsIn(line)) {
                    if (literal.contains('$')) continue
                    if (!looksLikeUserCopy(literal)) continue
                    if (developerText.any { literal.startsWith(it) }) continue
                    offences += "${source.name}:${index + 1}: $literal"
                }
            }
        }
        return offences
    }

    private fun strings(path: String): Map<String, String> {
        val text = File(moduleDir, path).readText()
        return Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun filesOf(vararg names: String): List<File> =
        names.map { File(moduleDir, "src/commonMain/kotlin/com/anopticlabs/gravel/ui/$it") }

    private val literalPattern = Regex("(?<![\\w\"])'?\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun literalsIn(line: String): List<String> =
        literalPattern.findAll(line).map { it.groupValues[1] }.toList()

    /** Whether a literal reads as copy someone would see: a space between words, or sentence punctuation. */
    private fun looksLikeUserCopy(literal: String): Boolean {
        if (literal.isBlank()) return false
        return literal.count { it == ' ' } > 0 && literal.any { it.isLetter() }
    }
}
