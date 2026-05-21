package io.github.sceneview.ar.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import io.github.sceneview.ar.ARSceneScope
import io.github.sceneview.ar.camera.ARCameraStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Environment-aware AR fog configuration (issue #1717).
 *
 * Applied to the AR camera passthrough via the depth-aware
 * [ARCameraStream] material, fading distant real-world surfaces into a
 * coloured haze using the ARCore depth image. Parameter names and ranges
 * mirror the virtual [io.github.sceneview.node.FogNode] so the same numbers
 * fog both real and virtual content consistently.
 *
 * Inspired by ARCore Depth Lab's *AR Fog* sample.
 *
 * @param density Fog density coefficient in 1/m, range `[0, 1]`. Higher
 *                values build up fog faster with distance. Default `0.05`
 *                matches [io.github.sceneview.node.FogNode]'s default.
 * @param start   Distance in metres at which fog begins to accumulate. Real
 *                surfaces closer than this stay crisp. Default `0`.
 * @param end     Distance in metres at which fog is fully opaque. Default
 *                `30`.
 * @param color   Fog colour. Default light grey-blue (0xFFCCDDFF) — matches
 *                [io.github.sceneview.node.FogNode]'s default.
 * @param enabled Whether fog is active. Default `true`.
 */
data class ARFogConfig(
    val density: Float = 0.05f,
    val start: Float = 0f,
    val end: Float = 30f,
    val color: Color = Color(0xFFCCDDFF),
    val enabled: Boolean = true,
)

/**
 * Composable that applies environment-aware AR fog to the camera passthrough.
 *
 * [ARFogNode] is not a scene-graph node — fog is a per-material effect
 * applied through [ARCameraStream]'s depth-aware material. The composable
 * wraps that API reactively: every recomposition syncs the fog parameters
 * onto the [cameraStream] passed in.
 *
 * To match a virtual [io.github.sceneview.node.FogNode] visually, pass the
 * same `density`, `start`, `end`, and `color`. The shader fog factor is
 * `1 - exp(-density * max(depth - start, 0))`, clamped to `[0, 1]`, and
 * collapses to a no-op when `enabled` is false.
 *
 * Requires the host [io.github.sceneview.ar.ARSceneView] to have depth
 * occlusion enabled (`ARCameraStream.isDepthOcclusionEnabled = true`). Without
 * depth pixels there is nothing to drive the per-pixel fog factor.
 *
 * ### Usage
 * ```kotlin
 * ARSceneView(
 *     cameraStream = rememberARCameraStream(materialLoader, creator = {
 *         createARCameraStream(materialLoader).apply { isDepthOcclusionEnabled = true }
 *     }),
 *     sessionConfiguration = { _, config -> config.depthMode = Config.DepthMode.AUTOMATIC },
 * ) {
 *     ARFogNode(
 *         cameraStream = cameraStream,
 *         density = 0.08f,
 *         color = Color(0xFFCCDDFF),
 *     )
 * }
 * ```
 *
 * @param cameraStream Camera stream that owns the depth-aware material instance.
 * @param density      Fog density. See [ARFogConfig.density].
 * @param start        Distance at which fog begins. See [ARFogConfig.start].
 * @param end          Distance at which fog is fully opaque. See [ARFogConfig.end].
 * @param color        Fog colour. See [ARFogConfig.color].
 * @param enabled      Whether fog is active. See [ARFogConfig.enabled].
 */
@Composable
fun ARSceneScope.ARFogNode(
    cameraStream: ARCameraStream,
    density: Float = 0.05f,
    start: Float = 0f,
    end: Float = 30f,
    color: Color = Color(0xFFCCDDFF),
    enabled: Boolean = true,
) {
    SideEffect {
        cameraStream.arFog = ARFogConfig(
            density = density,
            start = start,
            end = end,
            color = color,
            enabled = enabled,
        )
    }
}

/**
 * Pure-Kotlin reference implementation of the shader-side fog factor. Kept
 * separate from the GLSL so the math is exercised by JVM unit tests without
 * needing a Filament engine. Mirrors the formula in
 * `arsceneview/src/main/materials/camera_stream_depth.mat`.
 *
 * @param depthMeters   Per-pixel depth from ARCore, in metres. Zero or
 *                      negative values are treated as "no depth available"
 *                      and return `0` (camera pixel stays crisp).
 * @param density       Fog density (1/m).
 * @param start         Distance at which fog begins.
 * @param end           Distance at which fog reaches full opacity.
 * @param enabled       When `false`, returns `0` regardless of other inputs.
 *
 * @return Fog factor in `[0, 1]`. `0` = no fog, `1` = fully fogged.
 */
fun computeARFogFactor(
    depthMeters: Float,
    density: Float,
    start: Float,
    end: Float,
    enabled: Boolean = true,
): Float {
    if (!enabled) return 0f
    if (depthMeters <= 0.001f) return 0f
    val safeEnd = max(end, start + 0.001f)
    val adjusted = min(max(depthMeters - start, 0f), safeEnd - start)
    val factor = 1f - exp(-density.coerceIn(0f, 1f) * adjusted)
    return factor.coerceIn(0f, 1f)
}
