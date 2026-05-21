package io.github.sceneview.demo.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
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
 * The bar pins itself **bottom-start** of the scene `Box`, deliberately clear of
 * the Settings FAB at bottom-end so the two never overlap. Multiple actions sit
 * in a single [Row]; the first is rendered as a filled [Button] (the dominant
 * action), the rest as [FilledTonalButton]s — both opaque containers so the
 * cluster stays legible over a live AR camera feed or a busy 3D scene (#1476).
 *
 * Place it as the last child of the scene `Box` so it draws above the
 * `SceneView` / `ARSceneView`. Status pills (top-center / bottom-center)
 * continue to live in the demo and never collide with this bottom-start bar.
 *
 * Usage:
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     ARSceneView(...) { ... }
 *     SceneActionBar(
 *         SceneAction("Drop", onClick = { drop() }),
 *         SceneAction("Reset", onClick = { reset() }, enabled = count > 0),
 *     )
 * }
 * ```
 */
@Composable
fun BoxScope.SceneActionBar(
    vararg actions: SceneAction,
    modifier: Modifier = Modifier,
) {
    if (actions.isEmpty()) return
    Row(
        modifier = modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(16.dp),
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
