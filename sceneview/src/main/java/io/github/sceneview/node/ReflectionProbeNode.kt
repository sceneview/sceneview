package io.github.sceneview.node

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.google.android.filament.IndirectLight
import com.google.android.filament.Scene
import dev.romainguy.kotlin.math.length
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
 * Multiple `ReflectionProbeNode`s can be declared inside the same `SceneView { }` block. The
 * **last** one whose zone contains the camera wins, following Compose's top-down composition
 * order. Use the [priority] parameter to break ties explicitly — the probe with the highest
 * priority value takes precedence when more than one zone is active simultaneously.
 *
 * ### Usage
 * ```kotlin
 * val environment = rememberEnvironment(environmentLoader) {
 *     environmentLoader.createHDREnvironment("environments/office.hdr") ?: createEnvironment(environmentLoader)
 * }
 *
 * SceneView(
 *     environment = defaultEnvironment,
 *     onFrame = { cameraPosition = cameraNode.worldPosition }
 * ) {
 *     // Global fallback — always active
 *     ReflectionProbeNode(
 *         filamentScene = scene,
 *         environment = environment,
 *         cameraPosition = cameraPosition
 *     )
 *
 *     // Local zone — active only when the camera is within 3 m of the origin
 *     ReflectionProbeNode(
 *         filamentScene = scene,
 *         environment = officeEnvironment,
 *         position = Position(x = 0f, y = 1f, z = 0f),
 *         radius = 3f,
 *         cameraPosition = cameraPosition
 *     )
 * }
 * ```
 *
 * @param filamentScene  The Filament [Scene] whose [Scene.setIndirectLight] is overridden.
 *                       Obtain via [rememberScene] or the `scene` parameter of `SceneView { }`.
 * @param environment    The [Environment] containing the [IndirectLight] to apply.
 * @param position       Centre of the reflection zone in world space. Defaults to the origin.
 * @param radius         Sphere radius (metres) of the zone influence. `0f` or negative means
 *                       the probe is always active (global).
 * @param priority       Higher value wins when multiple probes are simultaneously active.
 *                       Has no effect when only one probe is active.
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
    // The indirect light this probe replaced, restored when the probe is removed. Taken again each
    // time the probe takes over: a dark/light toggle swaps the scene's light while it is inactive.
    val previousIndirectLight = remember(filamentScene) {
        arrayOf<IndirectLight?>(filamentScene.indirectLight)
    }

    // Determine whether this probe's zone contains the camera.
    val isActive = radius <= 0f || length(cameraPosition - position) <= radius

    // Only push to Filament when the active state actually transitions or when the
    // bound environment changes — not on every successful recomposition. The previous
    // SideEffect ran after every recompose (60×/s when caller writes camera position
    // into Compose state), which made ReflectionProbeNode the dominant Compose-driven
    // JNI cost in cinematic demos. Per-frame Filament JNI is fine; 60×/s redundant
    // assignment of the SAME indirectLight pointer is wasteful.
    LaunchedEffect(filamentScene, environment, isActive) {
        if (isActive && filamentScene.indirectLight !== environment.indirectLight) {
            previousIndirectLight[0] = filamentScene.indirectLight
            filamentScene.indirectLight = environment.indirectLight
        }
    }

    DisposableEffect(filamentScene, environment) {
        onDispose {
            // Restore the indirect light that was active before this probe was composed, and only
            // while the scene still holds this probe's: the scene's environment, or another probe,
            // may have replaced it since. The captured light may also be dead by now — a
            // dark/light toggle destroys the environment it belonged to — and Filament throws
            // "Calling method on destroyed IndirectLight" when it is handed back.
            // If a different probe is still active, its own LaunchedEffect will re-apply
            // after this disposal.
            if (filamentScene.indirectLight === environment.indirectLight) {
                filamentScene.indirectLight = previousIndirectLight[0]?.takeIf { it.isAlive }
            }
        }
    }
}

/** `false` once the engine destroyed this light: its handle getter throws from then on. */
private val IndirectLight.isAlive: Boolean
    get() = runCatching { nativeObject }.isSuccess
