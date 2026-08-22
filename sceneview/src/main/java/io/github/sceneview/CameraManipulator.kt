package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.gesture.CameraGestureDetector
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
 * @param orbitRadius    Camera-to-target distance in metres. Must be `> 0`.
 * @param targetPosition Point in world space the camera orbits around and initially looks at
 *                       (optional; defaults to the origin).
 * @param creator        Factory for the manipulator. Override to set a custom orbit speed, etc.
 */
@Composable
fun rememberCameraManipulator(
    orbitRadius: Float,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(orbitRadius = orbitRadius, targetPosition = targetPosition)
    }
) = remember(creator)
