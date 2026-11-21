package com.anopticlabs.gravel.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The enforcement behind Settings > AI > Text to Speech's localisation: the two locales hold the same keys, the code
 * agrees with them, and no English sentence is left in the Kotlin that draws one.
 *
 * Why this reads the XML files rather than a `Resources` object: the host test runs on a plain JVM, and a missing
 * translation has to fail the build rather than quietly fall back to English. The files are the artefact the build
 * packages, so checking them directly is checking the shipped copy.
 *
 * The boundary this test also states: only the `voice_*` keys are translated. The rest of the app's UI is
 * English-only and out of scope, so `values-fr` deliberately does not carry those strings.
 */
class TextToSpeechCopyKeysTest {

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

    @Test
    fun bothLocalesHoldTheSameVoiceKeys() {
        assertEquals(
            english.keys.filter { it.startsWith("voice_") }.toSet(),
            french.keys,
            "a key missing from values-fr/strings.xml would fall back to English silently",
        )
    }

    @Test
    fun onlyTheFeaturesOwnKeysAreTranslated() {
        // The rest of the app stays English on purpose; a translated key outside voice_* would be scope creep that
        // silently half-translates the UI.
        assertTrue(french.keys.all { it.startsWith("voice_") }, "values-fr holds this feature's copy only")
        assertTrue(english.keys.any { !it.startsWith("voice_") }, "the app's own English strings are still in values")
    }

    @Test
    fun theCodeAndTheResourcesNameTheSameKeys() {
        assertEquals(
            TextToSpeechKeys.all,
            french.keys,
            "every key the screen can ask for is in both files, and no resource is unreachable",
        )
    }

    @Test
    fun everyKeyHasItsComposeResource() {
        for (key in TextToSpeechKeys.all) {
            assertNotNull(resourceFor(key), "$key has no entry in the screen's lookup table")
        }
    }

    @Test
    fun aTranslationKeepsTheSameArgumentsInTheSameNumber() {
        for ((key, englishValue) in english.filterKeys { it.startsWith("voice_") }) {
            val frenchValue = french.getValue(key)
            assertEquals(
                placeholders(englishValue),
                placeholders(frenchValue),
                "$key: the French copy must take the same arguments as the English one",
            )
        }
    }

    @Test
    fun everyValueIsTranslatedExceptTheWordsThatAreTheSameInFrench() {
        for ((key, value) in french) {
            assertTrue(value.isNotBlank(), "$key is blank in French")
        }
        // A copy-paste that skipped the translation shows up as a French value identical to the English one.
        val identical = french.keys.filter { french.getValue(it) == english.getValue(it) }.toSet()
        assertEquals(emptySet(), identical, "a French value identical to the English one was not translated")
    }

    @Test
    fun noUserFacingEnglishSentenceIsLeftInTheKotlinThatDrawsOne() {
        // Log tags, log lines, resource keys and protocol values are developer text and stay as they are.
        val developerText = setOf("no string resource for ")
        val offences = mutableListOf<String>()
        for (source in filesOf("TextToSpeechScreen.kt", "TextToSpeechSettings.kt", "TextToSpeechCopy.kt")) {
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
        assertTrue(offences.isEmpty(), "user-facing English left in the source:\n" + offences.joinToString("\n"))
    }

    @Test
    fun theSettingsListItemDrawsItsWordsFromTheResourcesToo() {
        // The AI section's item lives in the big upstream settings screen, which stays English; only this item's two
        // strings were taken from the resources, so the check is that neither English sentence is in that file.
        val settings = File(moduleDir, "src/commonMain/kotlin/coredevices/pebble/ui/WatchSettingsScreen.kt").readText()
        assertTrue(
            !settings.contains("\"Text to Speech\""),
            "the AI item's title is drawn from the resources, not from a literal",
        )
        assertTrue(
            !settings.contains("The engine and voice that speak the assistant"),
            "the AI item's description is drawn from the resources, not from a literal",
        )
        assertTrue(settings.contains("TextToSpeechKeys.TTS_ITEM_TITLE"), "and it names the key it uses")
    }

    private fun strings(path: String): Map<String, String> {
        val text = File(moduleDir, path).readText()
        return Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The format arguments a value takes, so a translation cannot drop or add one. */
    private fun placeholders(value: String): List<String> =
        Regex("""%(\d+)\$[sd]""").findAll(value).map { it.value }.sorted().toList()

    private fun filesOf(vararg names: String): List<File> =
        names.map { File(moduleDir, "src/commonMain/kotlin/com/anopticlabs/gravel/ui/$it") }

    private val literalPattern = Regex("(?<![\\w\"])\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun literalsIn(line: String): List<String> =
        literalPattern.findAll(line).map { it.groupValues[1] }.toList()

    /** Whether a literal reads as copy someone would see: a space between words, or sentence punctuation. */
    private fun looksLikeUserCopy(literal: String): Boolean {
        if (literal.isBlank()) return false
        return literal.count { it == ' ' } > 0 && literal.any { it.isLetter() }
    }
}
