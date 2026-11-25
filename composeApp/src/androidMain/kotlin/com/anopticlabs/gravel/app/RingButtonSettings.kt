package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.storage.DeviceStateFile
import com.anopticlabs.gravel.ring.button.RingButtonAction
import com.anopticlabs.gravel.ring.button.RingButtonMapping
import com.anopticlabs.gravel.ring.link.RingWarmSettings
import com.anopticlabs.gravel.ui.RingButtonActionChoice
import com.anopticlabs.gravel.ui.RingButtonFacts
import com.anopticlabs.gravel.ui.RingButtonScreen
import com.anopticlabs.gravel.ui.RingButtonSettings
import com.anopticlabs.gravel.ui.RingButtonTap
import com.anopticlabs.gravel.ui.RingButtonWarmWindow
import coredevices.pebble.ui.TopBarParams
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The ring button's settings in the no-backup directory, one line of six small numbers, so they stay with this phone
 * the way the paired ring and its sync position do. A value that does not read back is the default — the switch off,
 * and the link not held — which is the only safe reading of a damaged file here.
 */
class RingButtonFile(dir: File) {
    private val file = DeviceStateFile(dir, "ring_button", maxChars = 48)

    fun read(): RingButtonMapping = RingButtonMapping.decode(file.read())

    /** False when the value could not be saved; what was saved before stays, and this change is not kept. */
    fun save(mapping: RingButtonMapping): Boolean = file.write(mapping.encode())
}

/**
 * The ring button on this phone: the saved mapping, and the page that changes it. The runner reads [mapping] on
 * every advertisement, so a change here takes effect on the next press rather than at the next restart.
 *
 * Turning the warm-link switch off also closes a link that is being held, through [closeHeldLink]: the setting is
 * the only reason a link outlives a press, so the moment it is off there is nothing left using it.
 */
class AndroidRingButtonSettings(
    private val file: RingButtonFile,
    pairedRing: StateFlow<String?>,
    scope: CoroutineScope,
    private val closeHeldLink: () -> Unit = {},
) : RingButtonSettings {
    private val _facts = MutableStateFlow(file.read().toFacts())
    override val facts: StateFlow<RingButtonFacts> = _facts.asStateFlow()

    override val paired: StateFlow<Boolean> =
        pairedRing.map { it != null }.stateIn(scope, SharingStarted.Eagerly, pairedRing.value != null)

    /** The mapping as the runner reads it. */
    val mapping: RingButtonMapping get() = _facts.value.toMapping()

    /** The keep-the-ring-connected setting, as the link keeper reads it on every press. */
    val warm: RingWarmSettings get() = mapping.warm

    override fun setEnabled(enabled: Boolean) = save(_facts.value.toMapping().copy(enabled = enabled))

    override fun setAction(tap: RingButtonTap, action: RingButtonActionChoice) =
        save(_facts.value.withAction(tap, action).toMapping())

    override fun setWarmLink(warm: Boolean) = save(_facts.value.toMapping().copy(warmLink = warm))

    override fun setWarmWindow(window: RingButtonWarmWindow) =
        save(_facts.value.toMapping().copy(warmWindowSeconds = window.seconds))

    @Composable
    override fun Screen(topBarParams: TopBarParams) {
        val facts by facts.collectAsState()
        RingButtonScreen(
            topBarParams,
            facts,
            onEnabled = ::setEnabled,
            onAction = ::setAction,
            onWarmLink = ::setWarmLink,
            onWindow = ::setWarmWindow,
        )
    }

    private fun save(mapping: RingButtonMapping) {
        val wasWarm = _facts.value.warmLink
        if (!file.save(mapping)) logger.w { "the ring button setting was not saved: $mapping" }
        _facts.value = mapping.toFacts()
        if (wasWarm && !mapping.warmLink) closeHeldLink()
    }

    private companion object {
        val logger = Logger.withTag("AndroidRingButtonSettings")
    }
}

/** The two vocabularies meet here: the ring's own, and the plain one this module's common code can carry. */
internal fun RingButtonMapping.toFacts(): RingButtonFacts = RingButtonFacts(
    enabled = enabled,
    click = click.toChoice(),
    doubleClick = doubleClick.toChoice(),
    tripleClick = tripleClick.toChoice(),
    warmLink = warmLink,
    warmWindow = RingButtonWarmWindow.of(warmWindowSeconds),
)

internal fun RingButtonFacts.toMapping(): RingButtonMapping = RingButtonMapping(
    enabled = enabled,
    click = click.toAction(),
    doubleClick = doubleClick.toAction(),
    tripleClick = tripleClick.toAction(),
    warmLink = warmLink,
    warmWindowSeconds = warmWindow.seconds,
)

internal fun RingButtonAction.toChoice(): RingButtonActionChoice = when (this) {
    RingButtonAction.PLAY_PAUSE -> RingButtonActionChoice.PLAY_PAUSE
    RingButtonAction.NEXT -> RingButtonActionChoice.NEXT
    RingButtonAction.PREVIOUS -> RingButtonActionChoice.PREVIOUS
    RingButtonAction.NOTHING -> RingButtonActionChoice.NOTHING
}

internal fun RingButtonActionChoice.toAction(): RingButtonAction = when (this) {
    RingButtonActionChoice.PLAY_PAUSE -> RingButtonAction.PLAY_PAUSE
    RingButtonActionChoice.NEXT -> RingButtonAction.NEXT
    RingButtonActionChoice.PREVIOUS -> RingButtonAction.PREVIOUS
    RingButtonActionChoice.NOTHING -> RingButtonAction.NOTHING
}
