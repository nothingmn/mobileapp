package com.anopticlabs.gravel.voicemqtt.copy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The enforcement behind the localisation: the two locales hold the same keys, the code agrees with them, and no
 * English sentence is left in the Kotlin that shows or says one.
 *
 * Why this reads the XML files rather than a `Resources` object: the host test runs on a plain JVM with no
 * Android runtime, and a missing translation has to fail the build rather than quietly fall back to English. The
 * files are the artefact the build packages, so checking them directly is checking the shipped copy.
 */
class VoiceCopyKeysTest {

    private val moduleDir: File = moduleDir()

    /** Walk up from wherever the test runs until the module's own sources are above us. */
    private fun moduleDir(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "src/androidMain/res/values/strings.xml").isFile }
            ?: error("could not find the voice-mqtt module from $start")
    }

    private val english = strings("src/androidMain/res/values/strings.xml")
    private val french = strings("src/androidMain/res/values-fr/strings.xml")

    @Test
    fun bothLocalesHoldExactlyTheSameKeys() {
        assertEquals(
            english.keys,
            french.keys,
            "a key missing from values-fr/strings.xml would fall back to English silently",
        )
    }

    @Test
    fun theCodeAndTheResourcesNameTheSameKeys() {
        assertEquals(
            VoiceKeys.all,
            english.keys,
            "every key the code can ask for is in the resources, and no resource is unreachable",
        )
    }

    @Test
    fun everyKeyHasItsResourceInTheAndroidLookupTable() {
        // The lookup table itself is checked rather than called: a host test has no Android runtime, so
        // `R.string` cannot be loaded here, but a key the table forgets is a sentence that would throw on the
        // phone — and that is exactly what this catches.
        val source = File(moduleDir, "src/androidMain/kotlin/com/anopticlabs/gravel/voicemqtt/copy/AndroidVoiceText.kt")
            .readText()
        val mapped = Regex("""VoiceKeys\.([A-Z_0-9]+) -> R\.string\.(voice_[a-z_]+)""")
            .findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(VoiceKeys.all.size, mapped.size, "one branch per key, no more and no fewer")
        for ((constant, resource) in mapped) {
            assertEquals("voice_" + constant.lowercase(), resource, "$constant maps to the resource of its own name")
        }
        assertEquals(english.keys, mapped.values.toSet(), "the table reaches every resource and only those")
    }

    @Test
    fun aTranslationKeepsTheSameArgumentsInTheSameNumber() {
        for ((key, englishValue) in english) {
            assertEquals(
                placeholders(englishValue),
                placeholders(french.getValue(key)),
                "$key: the French copy must take the same arguments as the English one",
            )
        }
    }

    @Test
    fun everyValueIsTranslatedExceptTheOneWordThatIsTheSameInFrench() {
        for ((key, value) in french) {
            assertTrue(value.isNotBlank(), "$key is blank in French")
        }
        // A copy-paste that skipped the translation shows up as a French value identical to the English one.
        // `Port` is the only word on this screen that is written the same way in both languages.
        val identical = english.keys.filter { english.getValue(it) == french.getValue(it) }.toSet()
        assertEquals(setOf("voice_field_port"), identical, "a French value identical to the English one was not translated")
    }

    @Test
    fun everySentenceMeantForTheScreenIsASentence() {
        val sentences = VoiceKeys.all.filter {
            it.startsWith("voice_not_played_") || it.startsWith("voice_refusal_") || it.startsWith("voice_spoken_") ||
                it.startsWith("voice_filing_") || it == VoiceKeys.ENGINE_START_FAILED ||
                it == VoiceKeys.ENGINE_INIT_FAILED
        }
        for (key in sentences) {
            for ((locale, values) in listOf("en" to english, "fr" to french)) {
                val value = values.getValue(key)
                assertTrue(
                    value.trimEnd().endsWith(".") || value.trimEnd().endsWith("!") || value.trimEnd().endsWith("?"),
                    "$key ($locale) is a spoken or shown sentence and ends its sentence: $value",
                )
                assertTrue(!value.contains('_'), "$key ($locale) is plain language, not an identifier: $value")
            }
        }
    }

    @Test
    fun noUserFacingEnglishSentenceIsLeftInTheKotlinThatShowsOrSaysOne() {
        // Log tags, log lines, topics and protocol values are developer text and stay as they are, which is what
        // this allowlist is: everything on it is unreachable from the screen and from the speaker.
        val developerText = setOf(
            "reply not spoken: spoken replies are switched off",
            "reply not spoken: no Bluetooth audio output is connected",
            "reply not spoken: the chosen Bluetooth device is not connected",
            "reply not spoken: no voice that works offline is installed for this language",
            "reply not spoken: the engine has no voice it can use for this language",
            "reply not spoken: the engine reported no voices",
            "reply not spoken: the engine offers only network voices, offline speech is on",
            "reply not spoken: no offline voice is installed for ",
            "reply not spoken: the engine has no voice for ",
            "reply not spoken: the chosen voice requires a network connection",
            "reply not spoken: the engine would not use its voice",
            "synthesised by the engine's cloud",
            "synthesised on this phone",
            "no string resource for ",
            "The platform's abstract signature",
            "<set>",
            "system default",
            "any",
            "true",
            "false",
        )
        val offences = mutableListOf<String>()
        for (source in filesUnder("src/androidMain/kotlin") + filesUnder("src/commonMain/kotlin")) {
            source.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return@forEachIndexed
                if (trimmed.startsWith("@")) return@forEachIndexed
                // The redacted settings toString is a debug dump, never shown; the log lines are developer text.
                if (!line.contains("logger") && !line.contains("password=")) {
                    for (literal in literalsIn(line)) {
                        // Copy is never assembled by interpolation any more: a sentence is a resource with
                        // positional arguments, which the placeholder test holds to the translation.
                        if (literal.contains('$')) continue
                        if (!looksLikeUserCopy(literal)) continue
                        if (developerText.any { literal.startsWith(it) || literal == it }) continue
                        offences += "${source.name}:${index + 1}: $literal"
                    }
                }
            }
        }
        assertTrue(offences.isEmpty(), "user-facing English left in the source:\n" + offences.joinToString("\n"))
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

    private fun filesUnder(relative: String): List<File> =
        File(moduleDir, relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * The double-quoted literals on one line, unescaped enough to compare.
     *
     * The lookbehind skips a quote that closes the previous literal, so a line like
     * `"${if (tls) "ssl" else "tcp"}` yields `ssl` and `tcp` rather than a bogus `" else "` between them.
     */
    private val literalPattern = Regex("(?<![\\w\"])\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun literalsIn(line: String): List<String> =
        literalPattern.findAll(line).map { it.groupValues[1] }.toList()
    /**
     * Whether a literal reads as copy someone would see: a space between words, or sentence punctuation. A tag
     * (`AndroidSpeechOutput`), a topic (`agentry/v1/...`) and a JSON key are not.
     */
    private fun looksLikeUserCopy(literal: String): Boolean {
        if (literal.isBlank()) return false
        val words = literal.count { it == ' ' }
        return words > 0 && literal.any { it.isLetter() } && !literal.startsWith("agentry/")
    }
}
