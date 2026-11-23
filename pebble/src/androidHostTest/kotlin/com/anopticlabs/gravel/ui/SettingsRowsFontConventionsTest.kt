package com.anopticlabs.gravel.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The enforcement behind the fork's three Settings rows and the slot they are drawn in, against upstream's readable-text
 * rule (`DESIGN_NOTES.md`, Readable text: text fits with the phone's font size at 2.0).
 *
 * Why a source test: the drawn-text tests that measure the real thing (`ScreenFontPiecesDrawnTextTest` and friends) are
 * instrumented, so they run on a phone rather than in the build. What can be held in the build is the property that
 * makes those rows scale — each is drawn by upstream's shared item builder rather than by a `Text` of its own, and that
 * builder clamps neither its title nor its description. A row that grew a `fontSize`, a `maxLines = 1` or a fixed
 * `width(...)` would pass every other test in the suite and be cut off at the largest font size on the phone.
 *
 * The rows are found by the search keywords they carry, which are unique to them and live beside the layout they
 * describe; the block checked is the `basicSettingsActionItem(...)` call that holds one.
 */
class SettingsRowsFontConventionsTest {

    private val moduleDir: File = moduleDir()

    /** Walk up from wherever the test runs until the module's own sources are above us. */
    private fun moduleDir(): File {
        val start = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "src/commonMain/kotlin/coredevices/pebble/ui/WatchSettingsScreen.kt").isFile }
            ?: error("could not find the pebble module from $start")
    }

    private val settingsScreen: String by lazy {
        File(moduleDir, "src/commonMain/kotlin/coredevices/pebble/ui/WatchSettingsScreen.kt").readText()
    }

    private val ringScreen: String by lazy {
        File(moduleDir, "src/commonMain/kotlin/com/anopticlabs/gravel/ui/RingButtonScreen.kt").readText()
    }

    /** The three rows this fork adds to the Settings list, by the keywords that only they carry. */
    private val forkRows = listOf(
        "voice assistant mqtt broker headset speak reply tts",
        "text to speech tts voice engine offline local speaker",
        "ring index button media play pause next previous track",
    )

    /** Spellings that pin text to one size, one line or one width, which upstream's font pass removed. */
    private val clamps = listOf("maxLines", "lineHeight", "fontSize", "Modifier.width(", "Color.")

    /** A font size written in `sp`: `12.sp`, `1.5.sp`. Not `Arrangement.spacedBy`, which reads the same to a search. */
    private val spLiteral = Regex("""\b\d+(?:\.\d+)?\.sp\b""")

    /** [source] with a literal `sp` font size flagged, so the reason names the line rather than the file. */
    private fun spSizes(source: String): List<String> = spLiteral.findAll(source).map { it.value }.toList()

    /** The call that holds [keyword], from its name to its matching close paren. */
    private fun callHolding(source: String, keyword: String): String {
        val at = source.indexOf(keyword)
        assertTrue(at >= 0, "no row carries the keywords \"$keyword\"")
        val open = source.lastIndexOf("basicSettingsActionItem(", at)
        assertTrue(open >= 0, "the row for \"$keyword\" is not a basicSettingsActionItem call")
        return callFrom(source, open)
    }

    /** The call whose name starts at [open], to its matching close paren. */
    private fun callFrom(source: String, open: Int): String {
        val name = "basicSettingsActionItem("
        var depth = 0
        var index = open + name.length - 1
        while (index < source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        error("unbalanced parentheses in the call at $open")
    }

    @Test
    fun everyForkRowIsDrawnByTheSharedItemBuilder() {
        for (keyword in forkRows) {
            val row = callHolding(settingsScreen, keyword)
            // A `button =` argument would draw the row's title in a `Text` of the row's own, outside the builder's
            // wrapping; these three rows are title-and-description rows.
            assertTrue(
                !row.contains("button ="),
                "the row for \"$keyword\" draws its own title instead of the builder's",
            )
        }
    }

    @Test
    fun everyForkRowClampsNeitherItsTitleNorItsDescription() {
        for (keyword in forkRows) {
            val row = callHolding(settingsScreen, keyword)
            for (clamp in clamps) {
                assertTrue(
                    !row.contains(clamp),
                    "the row for \"$keyword\" carries \"$clamp\", which cuts its text at the largest font size",
                )
            }
            assertTrue(
                spSizes(row).isEmpty(),
                "the row for \"$keyword\" pins a font size to ${spSizes(row)}",
            )
        }
    }

    /** What the rows inherit: the builder's own two texts wrap, and neither is pinned to a line or a size. */
    @Test
    fun theSharedItemBuilderWrapsItsText() {
        val at = settingsScreen.indexOf("fun basicSettingsActionItem(")
        assertTrue(at >= 0, "basicSettingsActionItem is gone; the fork's rows no longer share one builder")
        // The declaration and its body, which runs from here to the next top-level declaration.
        val head = settingsScreen.substring(at, settingsScreen.indexOf("\nfun ", at + 1).let { if (it < 0) settingsScreen.length else it })
        for (clamp in listOf("maxLines", "lineHeight")) {
            assertTrue(
                !head.contains(clamp),
                "basicSettingsActionItem carries \"$clamp\": its title and description would not wrap at 2.0",
            )
        }
        // The description's own size is a `sp`, so it grows with the phone's font size; a `dp` there would not.
        assertTrue(
            head.contains("Text(description, fontSize = 12.sp)"),
            "the description is no longer drawn at a font size that follows the phone's",
        )
    }

    /**
     * The value slot is defined once, beside the font-size helper, and the screens that need one take it from there.
     * A second copy is how the two drift: one gets upstream's fix at the next sync and the other does not.
     */
    @Test
    fun theValueSlotIsDefinedOnceAndBothScreensUseIt() {
        val sources = File(moduleDir, "src/commonMain/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .toList()
        val definitions = sources.filter { (_, text) -> text.contains("fun Modifier.widthInOrLongestWord(") }
        assertEquals(
            listOf("FontGrowth.kt"),
            definitions.map { (file, _) -> file.name },
            "widthInOrLongestWord belongs in FontGrowth.kt only",
        )
        assertTrue(
            settingsScreen.contains("Modifier.widthInOrLongestWord(150.dp)"),
            "the settings dropdown's value no longer takes the slot",
        )
        assertTrue(
            ringScreen.contains("Modifier.widthInOrLongestWord(150.dp)"),
            "the ring button's destination picker no longer takes the slot",
        )
    }

    /**
     * The fork's own settings pages wrap their text: no page-level `Text` carries a `maxLines`, a `lineHeight` or a
     * fixed `fontSize`. The ring button's page is drawn with the flag off, so it ships whether or not the voice loop
     * does.
     */
    @Test
    fun theRingButtonPageClampsNoText() {
        for (clamp in listOf("maxLines", "lineHeight", "fontSize")) {
            assertTrue(
                !ringScreen.contains(clamp),
                "RingButtonScreen carries \"$clamp\", which cuts its text at the largest font size",
            )
        }
        assertTrue(
            spSizes(ringScreen).isEmpty(),
            "RingButtonScreen pins a font size to ${spSizes(ringScreen)}",
        )
    }
}
