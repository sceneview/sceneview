package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.gesture.Inertia
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode

/**
 * Creates and remembers a [CameraGestureDetector.CameraManipulator] whose camera starts
 * [orbitRadius] metres from [targetPosition], along
 * [io.github.sceneview.gesture.DEFAULT_ORBIT_DIRECTION] — the same gentle 3/4 angle as the
 * default [CameraNode], so `orbitRadius = 2.78f` reproduces the stock framing.
 *
 * This is the distance-first spelling of `rememberCameraManipulator` (it mirrors the iOS
 * `CameraControls.orbitRadius`). Under the default `autoCenterContent = true` the subject sits on
 * the origin, so with the default target [orbitRadius] is exactly the camera-to-subject distance
 * — no vector length to compute. See [io.github.sceneview.gesture.orbitEyePosition] for the
 * derivation.
 *
 * ```kotlin
 * SceneView(
 *     cameraManipulator = rememberCameraManipulator(orbitRadius = 3f),
 * ) { ModelNode(modelInstance) }
 * ```
 *
 * ### Changing the radius or the target
 *
 * The manipulator is keyed on [orbitRadius] and [targetPosition]: pass a new value (a radius
 * derived from a model's measured size, a target that moves once the model has loaded) and a
 * fresh manipulator frames it. `SceneView` glides the camera from the pose on screen to the new
 * framing over ~0.6 s instead of cutting. A rebuild starts a new orbit, so the user's current
 * orbit angle is replaced by the default 3/4 view: derive the radius once from what it depends
 * on, don't animate it frame by frame.
 *
 * ### Inertia
 *
 * A flick keeps the camera turning (or panning, or zooming) after the finger lifts, then slows
 * down to a stop — [Inertia.Default]. Pass `inertia = Inertia.None` for a camera that stops
 * dead on release. Changing it does not rebuild the manipulator.
 *
 * @param orbitRadius    Camera-to-target distance in metres. Must be `> 0`.
 * @param targetPosition Point in world space the camera orbits around and initially looks at
 *                       (optional; defaults to the origin).
 * @param inertia        How orbit, pan and pinch coast after release. Applied to the manipulator
 *                       [creator] returns when it is a
 *                       [CameraGestureDetector.DefaultCameraManipulator].
 * @param creator        Factory for the manipulator. Override to set a custom orbit speed, etc.
 *                       Called again whenever [orbitRadius] or [targetPosition] changes.
 */
@Composable
fun rememberCameraManipulator(
    orbitRadius: Float,
    targetPosition: Position? = null,
    inertia: Inertia = Inertia.Default,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(orbitRadius = orbitRadius, targetPosition = targetPosition)
    }
): CameraGestureDetector.CameraManipulator =
    // Keyed on the framing inputs. The keyless `remember(creator)` this replaces built the
    // manipulator once and silently ignored every later radius, which pushed callers into
    // rebuilding it themselves — and every such rebuild used to be a visible camera cut.
    remember(orbitRadius, targetPosition) { creator() }.also { it.applyInertia(inertia) }

/**
 * Binary-compatibility shim for the pre-`inertia` descriptor of the `orbitRadius`
 * [rememberCameraManipulator]: keeps a library compiled against the old signature linking, with
 * the default [Inertia].
 */
@Deprecated(
    "Binary-compatibility overload. Use the rememberCameraManipulator overload that takes `inertia`.",
    level = DeprecationLevel.HIDDEN,
)
@Composable
fun rememberCameraManipulator(
    orbitRadius: Float,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(orbitRadius = orbitRadius, targetPosition = targetPosition)
    }
): CameraGestureDetector.CameraManipulator = rememberCameraManipulator(
    orbitRadius = orbitRadius,
    targetPosition = targetPosition,
    inertia = Inertia.Default,
    creator = creator,
)

/** Sets [inertia] on the manipulator when it is the stock one (a plain property write). */
internal fun CameraGestureDetector.CameraManipulator.applyInertia(inertia: Inertia) {
    val stock = this as? CameraGestureDetector.DefaultCameraManipulator ?: return
    if (stock.inertia != inertia) stock.inertia = inertia
}
