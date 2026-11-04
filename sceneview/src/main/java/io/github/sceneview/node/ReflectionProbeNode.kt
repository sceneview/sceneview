package io.github.sceneview.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import io.github.sceneview.environment.Environment
import io.github.sceneview.math.Position

/**
 * A composable that overrides the scene's indirect light (IBL) with a baked environment
 * for a defined zone around a world-space position.
 *
 * When [radius] is greater than zero, the probe is a local zone: the override only applies
 * while [cameraPosition] is within [radius] metres of [position]. Set [radius] to `0f` (or
 * any non-positive value) for a global probe that always overrides the scene IBL.
 *
 * All the probes of a [Scene] are resolved together, again on every change (a probe added,
 * removed or moved, a zone entered or left, a new environment):
 * - among the probes whose zone contains the camera, the highest [priority] wins; on a tie, the
 *   probe composed **last** wins (Compose's top-down order);
 * - when no probe is active — the camera left every local zone, or the last probe left the
 *   composition — the scene goes back to its own environment's light, the `environment` passed
 *   to `SceneView`, even if that environment changed while a probe was active;
 * - a new `SceneView` environment (a dark/light toggle, `environment.copy(skybox = …)`) never
 *   overrides an active probe: it only replaces the light shown when no probe is active.
 *
 * Outside `SceneView` (a [Scene] you render yourself), the fallback is the light the scene held
 * before a probe took over, or the one you wrote into it since.
 *
 * ### Usage
 * ```kotlin
 * val officeEnvironment = rememberEnvironment(environmentLoader) {
 *     environmentLoader.createHDREnvironment("environments/office.hdr") ?: createEnvironment(environmentLoader)
 * }
 * var cameraPosition by remember { mutableStateOf(Position()) }
 *
 * SceneView(
 *     scene = scene,
 *     environment = defaultEnvironment, // shown wherever no probe is active
 *     onFrame = { cameraPosition = cameraNode.worldPosition }
 * ) {
 *     // Local zone — active only when the camera is within 3 m of (0, 1, 0)
 *     ReflectionProbeNode(
 *         filamentScene = scene,
 *         environment = officeEnvironment,
 *         position = Position(x = 0f, y = 1f, z = 0f),
 *         radius = 3f,
 *         priority = 1, // wins over overlapping zones of lower priority
 *         cameraPosition = cameraPosition
 *     )
 * }
 * ```
 *
 * @param filamentScene  The Filament [Scene] whose indirect light is overridden. Pass the same
 *                       scene as `SceneView(scene = …)`, obtained via `rememberScene`.
 * @param environment    The [Environment] containing the [IndirectLight] to apply. Its light is
 *                       let go of before the environment is destroyed.
 * @param position       Centre of the reflection zone in world space. Defaults to the origin.
 * @param radius         Sphere radius (metres) of the zone influence. `0f` or negative means
 *                       the probe is always active (global).
 * @param priority       Higher value wins when several probes are active at the same time; on a
 *                       tie, the probe composed last wins.
 * @param cameraPosition Current camera world-space position, updated each frame. Drive this
 *                       from `onFrame` via [CameraNode.worldPosition].
 */
@Composable
fun ReflectionProbeNode(
    filamentScene: Scene,
    environment: Environment,
    position: Position = Position(x = 0f, y = 0f, z = 0f),
    radius: Float = 0f,
    priority: Int = 0,
    cameraPosition: Position = Position(x = 0f, y = 0f, z = 0f)
) {
    // One registry per scene decides which light the scene shows (#4928); this probe is one entry,
    // ordered by its first composition so ties keep following Compose's order.
    val registry = remember(filamentScene) { filamentScene.reflectionProbes }
    val probe = remember(registry) { registry.newProbe() }

    // Keyed on the environment: `rememberEnvironment(key = …)` destroys the previous environment in
    // the same pass that hands over the next one, and this effect, remembered after the caller's,
    // lets go of the old light first (#4924). The registry never hands back a dead light either.
    DisposableEffect(registry, probe, environment) {
        registry.update(probe, environment.indirectLight, position, radius, priority, cameraPosition)
        onDispose { registry.remove(probe) }
    }

    // Zone, priority and camera updates. Pure Kotlin: the scene is only written when the winning
    // light changes, not on every recomposition a moving camera causes.
    SideEffect {
        registry.update(probe, environment.indirectLight, position, radius, priority, cameraPosition)
    }
}
