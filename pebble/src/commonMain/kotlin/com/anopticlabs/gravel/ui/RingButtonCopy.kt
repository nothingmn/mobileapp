package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import coreapp.pebble.generated.resources.Res
import coreapp.pebble.generated.resources.ring_button_action_next
import coreapp.pebble.generated.resources.ring_button_action_nothing
import coreapp.pebble.generated.resources.ring_button_action_play_pause
import coreapp.pebble.generated.resources.ring_button_action_previous
import coreapp.pebble.generated.resources.ring_button_click
import coreapp.pebble.generated.resources.ring_button_double
import coreapp.pebble.generated.resources.ring_button_enable_subtitle
import coreapp.pebble.generated.resources.ring_button_enable_title
import coreapp.pebble.generated.resources.ring_button_item_description
import coreapp.pebble.generated.resources.ring_button_item_title
import coreapp.pebble.generated.resources.ring_button_recording_note
import coreapp.pebble.generated.resources.ring_button_section_media
import coreapp.pebble.generated.resources.ring_button_section_recording
import coreapp.pebble.generated.resources.ring_button_source_note
import coreapp.pebble.generated.resources.ring_button_title
import coreapp.pebble.generated.resources.ring_button_triple
import coreapp.pebble.generated.resources.ring_button_section_link
import coreapp.pebble.generated.resources.ring_button_warm_subtitle
import coreapp.pebble.generated.resources.ring_button_warm_title
import coreapp.pebble.generated.resources.ring_button_warm_window_10
import coreapp.pebble.generated.resources.ring_button_warm_window_8
import coreapp.pebble.generated.resources.ring_button_warm_window_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every string the ring button's settings can show, by its Compose resource name — the same names as
 * `composeResources/values/strings.xml`. A test compares this set with both locale files and with the lookup table
 * one for one, so a key cannot drift apart from its translation unnoticed.
 */
object RingButtonKeys {
    const val TITLE = "ring_button_title"
    const val ITEM_TITLE = "ring_button_item_title"
    const val ITEM_DESCRIPTION = "ring_button_item_description"
    const val SECTION_MEDIA = "ring_button_section_media"
    const val ENABLE_TITLE = "ring_button_enable_title"
    const val ENABLE_SUBTITLE = "ring_button_enable_subtitle"
    const val CLICK = "ring_button_click"
    const val DOUBLE = "ring_button_double"
    const val TRIPLE = "ring_button_triple"
    const val ACTION_PLAY_PAUSE = "ring_button_action_play_pause"
    const val ACTION_NEXT = "ring_button_action_next"
    const val ACTION_PREVIOUS = "ring_button_action_previous"
    const val ACTION_NOTHING = "ring_button_action_nothing"
    const val SECTION_RECORDING = "ring_button_section_recording"
    const val RECORDING_NOTE = "ring_button_recording_note"
    const val SOURCE_NOTE = "ring_button_source_note"
    const val SECTION_LINK = "ring_button_section_link"
    const val WARM_TITLE = "ring_button_warm_title"
    const val WARM_SUBTITLE = "ring_button_warm_subtitle"
    const val WARM_WINDOW_TITLE = "ring_button_warm_window_title"

    /** Every key this page ships, so the resource files and the lookup table can be held to it. */
    val all: Set<String> = setOf(
        TITLE,
        ITEM_TITLE,
        ITEM_DESCRIPTION,
        SECTION_MEDIA,
        ENABLE_TITLE,
        ENABLE_SUBTITLE,
        CLICK,
        DOUBLE,
        TRIPLE,
        ACTION_PLAY_PAUSE,
        ACTION_NEXT,
        ACTION_PREVIOUS,
        ACTION_NOTHING,
        SECTION_RECORDING,
        RECORDING_NOTE,
        SOURCE_NOTE,
        SECTION_LINK,
        WARM_TITLE,
        WARM_SUBTITLE,
        WARM_WINDOW_TITLE,
        // The two window labels are drawn by the picker through RingButtonWarmWindow.labelKey.
        RingButtonWarmWindow.EIGHT.labelKey,
        RingButtonWarmWindow.TEN.labelKey,
    )
}

/** The Compose resource that holds [key], or null for a key this build does not know. */
fun ringButtonResourceFor(key: String): StringResource? = when (key) {
    RingButtonKeys.TITLE -> Res.string.ring_button_title
    RingButtonKeys.ITEM_TITLE -> Res.string.ring_button_item_title
    RingButtonKeys.ITEM_DESCRIPTION -> Res.string.ring_button_item_description
    RingButtonKeys.SECTION_MEDIA -> Res.string.ring_button_section_media
    RingButtonKeys.ENABLE_TITLE -> Res.string.ring_button_enable_title
    RingButtonKeys.ENABLE_SUBTITLE -> Res.string.ring_button_enable_subtitle
    RingButtonKeys.CLICK -> Res.string.ring_button_click
    RingButtonKeys.DOUBLE -> Res.string.ring_button_double
    RingButtonKeys.TRIPLE -> Res.string.ring_button_triple
    RingButtonKeys.ACTION_PLAY_PAUSE -> Res.string.ring_button_action_play_pause
    RingButtonKeys.ACTION_NEXT -> Res.string.ring_button_action_next
    RingButtonKeys.ACTION_PREVIOUS -> Res.string.ring_button_action_previous
    RingButtonKeys.ACTION_NOTHING -> Res.string.ring_button_action_nothing
    RingButtonKeys.SECTION_RECORDING -> Res.string.ring_button_section_recording
    RingButtonKeys.RECORDING_NOTE -> Res.string.ring_button_recording_note
    RingButtonKeys.SOURCE_NOTE -> Res.string.ring_button_source_note
    RingButtonKeys.SECTION_LINK -> Res.string.ring_button_section_link
    RingButtonKeys.WARM_TITLE -> Res.string.ring_button_warm_title
    RingButtonKeys.WARM_SUBTITLE -> Res.string.ring_button_warm_subtitle
    RingButtonKeys.WARM_WINDOW_TITLE -> Res.string.ring_button_warm_window_title
    RingButtonWarmWindow.EIGHT.labelKey -> Res.string.ring_button_warm_window_8
    RingButtonWarmWindow.TEN.labelKey -> Res.string.ring_button_warm_window_10
    else -> null
}

/** The words of [tap] or of [action], in the phone's language. */
@Composable
fun ringButtonLabelOf(tap: RingButtonTap): String = RingButtonCopy(tap.labelKey).text()

@Composable
fun ringButtonLabelOf(action: RingButtonActionChoice): String = RingButtonCopy(action.labelKey).text()

/** One of this page's sentences: the resource that holds it, resolved in the phone's language when drawn. */
data class RingButtonCopy(val key: String) {
    /** Looked up in this page's own table, so a key with no resource fails loudly instead of drawing a blank. */
    @Composable
    fun text(): String = stringResource(ringButtonResourceFor(key) ?: error("no string resource for $key"))
}
