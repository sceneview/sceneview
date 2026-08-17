package io.github.sceneview.demo.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shared on-screen action cluster for demo screens (issue
 * [#1964](https://github.com/sceneview/sceneview/issues/1964)).
 *
 * Enforces the one action-placement rule across the sample app: **a demo's
 * primary action — the thing its on-screen banner tells the user to do (place,
 * host, drop, record, clear, reset) — must be an on-screen button**, never
 * buried in the Settings bottom sheet. Only secondary / configuration controls
 * (toggles, sliders, pickers) belong in `DemoScaffold(controls = …)`.
 *
 * Multiple actions sit in a single [Row]; the first is rendered as a filled
 * [Button] (the dominant action), the rest as [FilledTonalButton]s — both opaque
 * containers so the cluster stays legible over a live AR camera feed or a busy
 * 3D scene (#1476).
 *
 * ## Put it in the `bottomOverlay` slot
 *
 * ```
 * DemoScaffold(
 *     bottomOverlay = {
 *         DemoStatusBanner(statusText, tone = DemoStatusTone.Error)
 *         SceneActionBar(
 *             SceneAction("Drop", onClick = { drop() }),
 *             SceneAction("Reset", onClick = { reset() }, enabled = count > 0),
 *         )
 *     },
 * ) { ARSceneView(...) { ... } }
 * ```
 *
 * The slot is a bottom-aligned Column, so the banner and the bar **stack**.
 *
 * This KDoc used to say the opposite — that status pills "continue to live in the
 * demo and never collide with this bottom-start bar" — and 23 demo files followed
 * it, hand-placing a banner in the scene lambda. The claim was false. Both this
 * bar and a hand-placed `align(BottomCenter).padding(bottom = 24.dp)` banner
 * resolve into the same ~40…96 dp band above the system bars, so on
 * `ARTerrainAnchorDemo` the first-launch banner ran under the "Drop here" button
 * *and* under the Settings FAB — visible to anyone who cloned the repo without an
 * ARCore Cloud key. Nothing about the old shape made that avoidable: a banner's
 * height follows its string, its wrap and the font scale, so no clearance
 * constant survives contact with a longer sentence.
 *
 * The [BoxScope] overload below is the pre-existing shape, kept so the scene
 * lambda still compiles. It is what `check-demo-bottom-overlay.py` refuses.
 */
@Composable
fun ColumnScope.SceneActionBar(
    vararg actions: SceneAction,
    modifier: Modifier = Modifier,
) {
    // The Column is already inset for the system bars by the slot itself, and it
    // already spaces its children — so this overload adds only its own gutter, and
    // aligns start to keep the bar where users learned to find it.
    SceneActionBarRow(
        actions = actions,
        modifier = modifier
            .align(Alignment.Start)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Scene-lambda placement — pins itself bottom-start of the scene `Box`.
 *
 * Prefer the [ColumnScope] overload in `DemoScaffold(bottomOverlay = …)`: this one
 * shares the bottom band with anything else anchored there, and cannot be told
 * about it. See that overload's KDoc for what that cost.
 */
@Composable
fun BoxScope.SceneActionBar(
    vararg actions: SceneAction,
    modifier: Modifier = Modifier,
) {
    SceneActionBarRow(
        actions = actions,
        modifier = modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
    )
}

@Composable
private fun SceneActionBarRow(
    actions: Array<out SceneAction>,
    modifier: Modifier,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { index, action ->
            if (index == 0) {
                Button(onClick = action.onClick, enabled = action.enabled) {
                    Text(action.label)
                }
            } else {
                FilledTonalButton(onClick = action.onClick, enabled = action.enabled) {
                    Text(action.label)
                }
            }
        }
    }
}

/**
 * One primary action shown in a [SceneActionBar]. [enabled] gates the button the
 * same way an in-sheet button would — keeping the disabled-state semantics
 * identical when an action moves out of the Settings sheet onto the scene.
 */
data class SceneAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)
