package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * How much wider [text] is drawn in [style] at the phone's font size than at font size 1.0, and never less than zero:
 * what a width set for font 1.0 has to gain for the text to keep fitting.
 */
@Composable
fun fontGrowth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, text, style) {
        fun widthAt(scale: Density) = measurer.measure(text, style, density = scale).size.width
        with(density) { (widthAt(density) - widthAt(Density(density.density))).coerceAtLeast(0).toDp() }
    }
}

/**
 * At most [max] wide or, when wider, the text's min intrinsic width (its widest stretch without a line-break
 * opportunity: ui-text-android 1.11.2, LayoutIntrinsics.getMinIntrinsicWidth) up to half the width offered, which
 * leaves the row's title the other half.
 *
 * The slot a row offers a value: [max] is the width the value gets at font size 1.0, the intrinsic width is what it
 * needs at the largest, so the row keeps its title beside it. Moved here from `WatchSettingsScreen.kt` by the fork's
 * upstream sync, so that a screen outside that file (the ring button's pickers) takes the same slot rather than
 * setting a width of its own.
 */
fun Modifier.widthInOrLongestWord(max: Dp) = layout { measurable, constraints ->
    val longestWord = minOf(measurable.minIntrinsicWidth(constraints.maxHeight), constraints.maxWidth / 2)
    val width = maxOf(max.roundToPx(), longestWord).coerceIn(constraints.minWidth, constraints.maxWidth)
    val placeable = measurable.measure(constraints.copy(maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

