package io.github.sceneview.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.filament.View
import io.github.sceneview.SceneRenderInvalidators
import io.github.sceneview.SceneScope
import io.github.sceneview.rememberView

/**
 * A composable that applies atmospheric fog to the scene.
 *
 * [FogNode] is not a scene-graph node in the traditional sense — fog is a per-[View] effect in
 * Filament, applied through [View.fogOptions]. This composable wraps that API reactively: every
 * recomposition syncs the fog parameters to the [View] passed from [SceneScope].
 *
 * Because [SceneScope] does not expose the [View] directly, callers must pass in the same [view]
 * instance that was provided to [io.github.sceneview.SceneView]. Obtain it with
 * `rememberView(engine)` and pass it both to [io.github.sceneview.SceneView] and here.
 *
 * ### Usage
 * ```kotlin
 * val view = rememberView(engine)
 * var fogEnabled by remember { mutableStateOf(true) }
 * var fogDensity by remember { mutableFloatStateOf(0.05f) }
 *
 * SceneView(view = view, ...) {
 *     FogNode(
 *         view = view,
 *         enabled = fogEnabled,
 *         density = fogDensity,
 *         color = Color(0xFFCCDDFF)
 *     )
 * }
 * ```
 *
 * @param view       The Filament [View] to apply fog options to. Must be the same instance
 *                   passed to [io.github.sceneview.SceneView].
 * @param density    Volumetric fog density [0.0, 1.0]. Higher values produce thicker fog.
 *                   Default 0.05.
 * @param height     Height falloff in world-space meters. Fog is denser below this height.
 *                   Default 1.0.
 * @param color      Fog colour. Default light grey-blue (0xFFCCDDFF).
 * @param enabled    Whether fog is active. Default `true`.
 */
@Composable
fun SceneScope.FogNode(
    view: View,
    density: Float = 0.05f,
    height: Float = 1.0f,
    color: Color = Color(0xFFCCDDFF),
    enabled: Boolean = true
) {
    val prevFog = remember { mutableStateOf<List<Any?>?>(null) }
    SideEffect {
        // Only on a real change, and never on the mere recomposition: `View.fogOptions` is a
        // Filament write that reports nothing, so a parked scene keeps the old fog, while asking
        // for a frame every recomposition would be the blanket invalidation this release removed.
        val current = listOf<Any?>(enabled, density, height, color)
        if (current != prevFog.value) {
            prevFog.value = current
            view.scene?.let { SceneRenderInvalidators.of(it) }?.requestRender()
        }
        view.fogOptions = view.fogOptions.also { opts ->
            opts.enabled = enabled
            opts.density = density.coerceIn(0f, 1f)
            opts.heightFalloff = height
            opts.distance = 0.5f
            opts.cutOffDistance = 40f
            opts.fogColorFromIbl = false
            opts.color[0] = color.red
            opts.color[1] = color.green
            opts.color[2] = color.blue
        }
    }
}
