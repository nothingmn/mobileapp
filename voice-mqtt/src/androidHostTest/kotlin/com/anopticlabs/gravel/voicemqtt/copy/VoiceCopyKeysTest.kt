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
    fun bothLocalesHoldExactlyTheSameVoiceKeys() {
        // Scoped to this feature's own keys, and deliberately not full-file parity: a string that is not the voice
        // loop's business (a future upstream one in this module) must not fail the build for having no translation.
        assertEquals(
            voiceKeys(english),
            french.keys,
            "a key missing from values-fr/strings.xml would fall back to English silently",
        )
        assertTrue(french.keys.all { it.startsWith("voice_") }, "values-fr holds this feature's copy only")
    }

    @Test
    fun theCodeAndTheResourcesNameTheSameKeys() {
        assertTrue(VoiceKeys.all.all { it in english.keys }, "every key the code can ask for is in the resources")
        assertEquals(VoiceKeys.all, voiceKeys(english), "no voice_ resource is unreachable from the code")
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
        assertEquals(voiceKeys(english), mapped.values.toSet(), "the table reaches every resource and only those")
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
        // `Port` is the only word on this screen that is written the same way in both languages. A value with no
        // words in it at all — the list's `%1$s · %2$s · …` separator line, whose only letters are the format
        // arguments — has nothing to translate, so it is not an offence.
        val identical = english.keys
            .filter { key -> english.getValue(key).replace(formatArgument, "").any { it.isLetter() } }
            .filter { english.getValue(it) == french.getValue(it) }
            .toSet()
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
        val sources = filesUnder("src/androidMain/kotlin") + filesUnder("src/commonMain/kotlin")
        val offences = scan(sources)
        assertTrue(offences.isEmpty(), "user-facing English left in the source:\n" + offences.joinToString("\n"))
    }

    @Test
    fun theScanFindsASentenceWhenThereIsOne() {
        // The control for the scan above, kept inside the test rather than as a probe file in the source tree: a
        // passing scan must mean "nothing left", not "the scan stopped looking".
        val probe = File(moduleDir, "build/scan-probe/Probe.kt").apply {
            parentFile.mkdirs()
            writeText("internal const val PROBE = \"A user-facing sentence that fails the scan.\"\n")
        }
        try {
            assertEquals(1, scan(listOf(probe)).size, "the scan does find a sentence when one is there")
        } finally {
            probe.parentFile.deleteRecursively()
        }
    }

    /**
     * Every literal in [sources] that reads as copy someone would see and is not developer text.
     *
     * Log tags, log lines, topics and protocol values are developer text and stay as they are, which is what the
     * allowlist is: everything on it is unreachable from the screen and from the speaker.
     */
    private fun scan(sources: List<File>): List<String> {
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
        for (source in sources) {
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
        return offences
    }

    /** This feature's own keys in [values]: the prefix is what keeps the rest of a module's strings out of scope. */
    private fun voiceKeys(values: Map<String, String>): Set<String> =
        values.keys.filter { it.startsWith("voice_") }.toSet()

    private fun strings(path: String): Map<String, String> {
        val text = File(moduleDir, path).readText()
        return Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The format arguments a value takes, so a translation cannot drop or add one. */
    private fun placeholders(value: String): List<String> =
        Regex("""%(\d+)\$[sd]""").findAll(value).map { it.value }.sorted().toList()

    /** One format argument in a resource value, removed when asking whether a value has any words of its own. */
    private val formatArgument = Regex("%(\\d+)\\$[sd]")

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
     * (`AndroidSpeechOutput`), a topic or an address with a path in it (`example/topic`) and a JSON key are not.
     */
    private fun looksLikeUserCopy(literal: String): Boolean {
        if (literal.isBlank()) return false
        val words = literal.count { it == ' ' }
        return words > 0 && literal.any { it.isLetter() } && !literal.contains('/')
    }
}
