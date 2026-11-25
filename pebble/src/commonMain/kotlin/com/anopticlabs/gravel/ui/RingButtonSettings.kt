package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coredevices.pebble.ui.TopBarParams
import kotlin.math.abs
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

/**
 * Gravel: the paired ring's button, as common code sees it — Settings > General > Ring button.
 *
 * The button is read and sent on Android (`:ring` and Gradle's `composeApp`), so `:pebble`, which also builds for
 * iOS, cannot depend on the code that does it. This interface therefore carries **plain data only**, and common code
 * looks the implementation up with `getOrNull`. A missing binding means "this build has no ring to configure",
 * which is what an iOS build produces: no Settings item, no page.
 *
 * The switch is **off** by default ([RingButtonFacts.enabled]), and the page exists only while a ring is paired
 * ([paired]): with no ring there is no button to configure, and taking the button over without being asked would
 * change what it does for every owner of the ring.
 */
interface RingButtonSettings {
    /** True while a ring is paired, which is when the Settings item and this page exist. */
    val paired: StateFlow<Boolean>

    /** What the settings are now; read after every change and at construction. */
    val facts: StateFlow<RingButtonFacts>

    fun setEnabled(enabled: Boolean)

    /** Sends [tap] to [action]; the recording gestures are not remappable and have no setter. */
    fun setAction(tap: RingButtonTap, action: RingButtonActionChoice)

    /**
     * Keeps the ring's link open after a press, for [RingButtonFacts.warmWindow] of quiet, so the next tap does not
     * pay for a connect. Off unless the user asks: the ring's battery pays for the held link.
     */
    fun setWarmLink(warm: Boolean)

    /**
     * How long the link is held once nothing else happens: eight seconds or ten, eight by default. Ten is the
     * longest the ring allows — it drops a held link itself after roughly ten seconds.
     */
    fun setWarmWindow(window: RingButtonWarmWindow)

    /** The page itself; the top bar and the layout are the app's own. */
    @Composable
    fun Screen(topBarParams: TopBarParams)
}

/** Null in a build with no ring client bound, which is every non-Android build. */
@Composable
fun rememberRingButtonSettings(): RingButtonSettings? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<RingButtonSettings>() }
}

/** The taps that can be mapped. Hold and click-hold are the ring's own recording gestures and are not here. */
enum class RingButtonTap(val labelKey: String) {
    CLICK("ring_button_click"),
    DOUBLE_CLICK("ring_button_double"),
    TRIPLE_CLICK("ring_button_triple"),
}

/** Where a tap sends its input, in the words the page offers. */
enum class RingButtonActionChoice(val labelKey: String) {
    PLAY_PAUSE("ring_button_action_play_pause"),
    NEXT("ring_button_action_next"),
    PREVIOUS("ring_button_action_previous"),
    NOTHING("ring_button_action_nothing"),
}

/**
 * How long the ring's link is kept open after a press, when the user has asked for that: the two windows the page
 * offers, and nothing else. A window is paid for with the ring's battery, so the page does not offer a longer one —
 * and it could not: the ring drops a held link itself about ten seconds after a press, which is why the longest
 * window offered is ten seconds.
 */
enum class RingButtonWarmWindow(val seconds: Int, val labelKey: String) {
    EIGHT(8, "ring_button_warm_window_8"),
    TEN(10, "ring_button_warm_window_10"),
    ;

    companion object {
        /**
         * The window nearest [seconds]: a value the page no longer offers — the twenty or thirty this setting wrote
         * before the ring's own drop was measured — reads as the closest window it offers now, which for both is ten.
         */
        fun of(seconds: Int): RingButtonWarmWindow = entries.minBy { abs(it.seconds - seconds) }
    }
}

/** Everything the page shows, in plain values: the switch, the three taps' destinations, and the warm link. */
data class RingButtonFacts(
    val enabled: Boolean = false,
    val click: RingButtonActionChoice = RingButtonActionChoice.PLAY_PAUSE,
    val doubleClick: RingButtonActionChoice = RingButtonActionChoice.NEXT,
    val tripleClick: RingButtonActionChoice = RingButtonActionChoice.PREVIOUS,
    val warmLink: Boolean = false,
    val warmWindow: RingButtonWarmWindow = RingButtonWarmWindow.EIGHT,
) {
    fun actionFor(tap: RingButtonTap): RingButtonActionChoice = when (tap) {
        RingButtonTap.CLICK -> click
        RingButtonTap.DOUBLE_CLICK -> doubleClick
        RingButtonTap.TRIPLE_CLICK -> tripleClick
    }

    fun withAction(tap: RingButtonTap, action: RingButtonActionChoice): RingButtonFacts = when (tap) {
        RingButtonTap.CLICK -> copy(click = action)
        RingButtonTap.DOUBLE_CLICK -> copy(doubleClick = action)
        RingButtonTap.TRIPLE_CLICK -> copy(tripleClick = action)
    }
}

/** The Settings item is offered only while a ring is paired; with none paired there is no button to configure. */
fun ringButtonItemShown(paired: Boolean): Boolean = paired
