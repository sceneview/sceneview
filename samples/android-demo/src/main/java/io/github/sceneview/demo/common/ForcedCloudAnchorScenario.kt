package io.github.sceneview.demo.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.demos.internal.CloudAnchorScenario
import io.github.sceneview.demo.theme.SceneViewTokens

/**
 * QA-only override that pins the Cloud Anchor demo to one named visual state (#3421).
 *
 * Same shim as [ForcedTrackingFailure], for the same reason and with the same rules:
 * a `mutableStateOf`-backed singleton whose `null` is the normal case, read by the demo
 * with `?:`, and a menu that returns immediately unless [DemoSettings.qaMode] is on so no
 * end user ever meets it.
 *
 * What it buys that a forced *tracking failure* does not: Cloud Anchor states cannot be
 * staged. "Hosting…", "Hosted", "No anchor for that code" each need a live Google Cloud
 * project, a mapped room and — for the interesting half — a second phone. On
 * `emulator-5554`, which cannot run ARCore at all (#2754), none of them are reachable, so
 * before this the demo's screenshot coverage was one empty frame.
 *
 * Two ingresses, both QA-gated:
 *  - this menu, from the settings sheet, for a human looking at a device;
 *  - `--es qa_state <name>` ([DemoSettings.qaDemoState]), for the emulator smoke suite,
 *    which needs light and dark captures of a dozen states without tapping through a sheet.
 */
object ForcedCloudAnchorScenario {
    /** The pinned scenario, or `null` to let the demo run for real. */
    var override: CloudAnchorScenario? by mutableStateOf(null)
}

/**
 * `true` when a QA state override may be honoured at all.
 *
 * Both ingresses go through this. A forced state makes the screen *lie* — it shows a code
 * that was never hosted — so it is gated on QA mode exactly like the rest of the debug
 * surface, and an intent extra alone is never enough.
 */
fun qaStateOverridesAllowed(): Boolean = DemoSettings.qaMode

/**
 * The QA radio list that pins [ForcedCloudAnchorScenario.override].
 *
 * Rendered from the demo's `controls` block, next to [ForceTrackingFailureMenu]. Hidden
 * entirely outside QA mode.
 */
@Composable
fun ForceCloudAnchorScenarioMenu(modifier: Modifier = Modifier) {
    if (!DemoSettings.qaMode) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Debug — force cloud anchor state",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(
                top = SceneViewTokens.Space.sm,
                bottom = SceneViewTokens.Space.xs,
            ),
        )
        Text(
            text = "Pins the screen to one state so hosting, sharing and every failure " +
                "can be reviewed without a Cloud project, a mapped room or a second device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = SceneViewTokens.Space.sm),
        )

        val current = ForcedCloudAnchorScenario.override
        CloudAnchorScenario.entries.forEach { scenario ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == scenario,
                        onClick = { ForcedCloudAnchorScenario.override = scenario },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = SceneViewTokens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Selection handled by the Row's `selectable`, so the button itself is inert.
                RadioButton(selected = current == scenario, onClick = null)
                Text(
                    text = scenario.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = SceneViewTokens.Space.sm),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = SceneViewTokens.Space.xs),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { ForcedCloudAnchorScenario.override = null },
                enabled = current != null,
            ) { Text("Clear override") }
        }
    }
}
