package com.anopticlabs.gravel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.ui.TopBarParams

/**
 * Settings > General > Ring button: whether the paired ring's button controls this phone's media, and which key each
 * of its three media gestures sends.
 *
 * The page is built from [RingButtonFacts] only — no ring client, no Bluetooth — so the same layout works wherever
 * the settings are drawn, and what it says is testable as plain values. The switch is off by default, the two
 * recording gestures are named but not offered, and the last line states the detection's limit rather than promising
 * a button that always answers: a tap the ring never advertised is a tap nothing saw.
 *
 * Every word comes from this feature's own Compose resources (see [RingButtonKeys]), in the phone's language.
 */
@Composable
fun RingButtonScreen(
    topBarParams: TopBarParams,
    facts: RingButtonFacts,
    onEnabled: (Boolean) -> Unit,
    onAction: (RingButtonTap, RingButtonActionChoice) -> Unit,
) {
    val title = RingButtonCopy(RingButtonKeys.TITLE).text()
    LaunchedEffect(title) {
        topBarParams.searchAvailable(null)
        topBarParams.title(title)
        topBarParams.actions { }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(RingButtonCopy(RingButtonKeys.SECTION_MEDIA).text(), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(RingButtonCopy(RingButtonKeys.ENABLE_TITLE).text())
                Text(
                    RingButtonCopy(RingButtonKeys.ENABLE_SUBTITLE).text(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = facts.enabled, onCheckedChange = onEnabled)
        }
        for (tap in RingButtonTap.entries) {
            TapChoice(tap = tap, facts = facts, onAction = onAction)
        }

        Text(RingButtonCopy(RingButtonKeys.SECTION_RECORDING).text(), style = MaterialTheme.typography.titleMedium)
        Text(RingButtonCopy(RingButtonKeys.RECORDING_NOTE).text(), style = MaterialTheme.typography.bodySmall)

        Text(RingButtonCopy(RingButtonKeys.LOSSY_NOTE).text(), style = MaterialTheme.typography.bodySmall)
    }
}

/** One tap and the destination it sends, as a picker the page offers for each of the three. */
@Composable
private fun TapChoice(
    tap: RingButtonTap,
    facts: RingButtonFacts,
    onAction: (RingButtonTap, RingButtonActionChoice) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val chosen = facts.actionFor(tap)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(ringButtonLabelOf(tap), modifier = Modifier.weight(1f))
        Column {
            TextButton(onClick = { open = true }, enabled = facts.enabled) {
                Text(ringButtonLabelOf(chosen))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (action in RingButtonActionChoice.entries) {
                    DropdownMenuItem(
                        text = { Text(ringButtonLabelOf(action)) },
                        onClick = {
                            open = false
                            onAction(tap, action)
                        },
                    )
                }
            }
        }
    }
}
