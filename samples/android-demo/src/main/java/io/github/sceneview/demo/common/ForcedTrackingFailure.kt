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
import androidx.compose.ui.unit.dp
import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.demo.DemoSettings

/**
 * Developer-only debug shim that forces ARCore's [TrackingFailureReason] in demos
 * so QA can validate every [trackingFailureMessage] mapping without having to
 * physically trigger each failure mode (dark room, textureless surface,
 * `EXCESSIVE_MOTION`, etc.).
 *
 * Tracking failures are genuinely hard to QA on modern devices (#1881): a
 * Pixel 9 handles dim lighting too well to trigger `INSUFFICIENT_LIGHT`, modern
 * camera stabilisation absorbs the shaking that would otherwise trigger
 * `EXCESSIVE_MOTION`, and `CAMERA_UNAVAILABLE` requires another camera app to
 * steal focus. This shim lets a developer pick any [TrackingFailureReason] from
 * the demo settings panel and have the tracking-failure observer emit that
 * value instead of the real one.
 *
 * **Visibility:** the [ForceTrackingFailureMenu] composable hides itself unless
 * [DemoSettings.qaMode] is on, so end users never see the menu by accident.
 * Long-press the demo's peek-chip to enable QA mode (see [DemoScaffold]) or
 * launch with `--ez qa_mode true`.
 *
 * **Wiring a demo:** in the demo's tracking-failure observer, prefer the
 * forced value when it is set:
 *
 * ```kotlin
 * onTrackingFailureChanged = { reason ->
 *     trackingFailureReason = ForcedTrackingFailure.override ?: reason
 * }
 * ```
 *
 * Then drop a single [ForceTrackingFailureMenu] call inside the demo's
 * `DemoScaffold` `controls = { ... }` block.
 *
 * Pure demo-side state — never reaches the [io.github.sceneview.ar.ARSceneView]
 * library.
 */
object ForcedTrackingFailure {
    /**
     * When non-null, demos that opt in route the forced reason through their
     * tracking-failure observer instead of the real ARCore-reported reason.
     * `null` (default) restores normal behaviour.
     *
     * Compose-observable so flipping it from [ForceTrackingFailureMenu]
     * immediately re-renders the demo's status banner.
     */
    var override: TrackingFailureReason? by mutableStateOf(null)
}

/**
 * Compose settings-panel section that lets a developer pick any
 * [TrackingFailureReason] to force-emit via [ForcedTrackingFailure.override].
 * Includes a "Clear" button to release the override and go back to the real
 * ARCore-reported reason.
 *
 * Renders nothing unless [DemoSettings.qaMode] is on, so end users never see
 * it. Drop the call site inside a demo's `DemoScaffold` `controls = { ... }`
 * block — the section places its own header.
 *
 * @param modifier Optional [Modifier] applied to the outer [Column].
 */
@Composable
fun ForceTrackingFailureMenu(modifier: Modifier = Modifier) {
    if (!DemoSettings.qaMode) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Debug — force tracking failure",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = "Forces the AR tracking-failure overlay so each " +
                "TrackingFailureReason can be validated without staging a real " +
                "failure (dark room, textureless surface, etc.).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        val current = ForcedTrackingFailure.override
        FORCEABLE_REASONS.forEach { reason ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = current == reason,
                        onClick = { ForcedTrackingFailure.override = reason },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = current == reason,
                    onClick = null, // handled by the Row's `selectable`
                )
                Text(
                    text = reason.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { ForcedTrackingFailure.override = null },
                enabled = current != null,
            ) {
                Text("Clear override")
            }
        }
    }
}

/**
 * Every [TrackingFailureReason] except [TrackingFailureReason.NONE] — that one
 * represents "no failure to surface", which is the default state when no
 * override is set, so exposing it as a radio button would be redundant with
 * "Clear override".
 */
private val FORCEABLE_REASONS: List<TrackingFailureReason> =
    TrackingFailureReason.values().filter { it != TrackingFailureReason.NONE }
