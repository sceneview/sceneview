package io.github.sceneview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode

/**
 * Creates and remembers a [CameraGestureDetector.CameraManipulator] for orbit/pan/zoom control.
 *
 * The manipulator translates touch gestures into camera transform updates — one-finger drag to
 * orbit, two-finger drag to pan, pinch to zoom. It is updated automatically every frame inside
 * `SceneView`.
 *
 * Pass `null` to `SceneView(cameraManipulator = null)` to disable camera interaction entirely.
 *
 * Two ways to say where the camera starts — pick one:
 *
 * ```kotlin
 * // 1. A distance (recommended): 2.5 m from the subject, along the default 3/4 viewing angle.
 * val cameraManipulator = rememberCameraManipulator(orbitRadius = 2.5f)
 *
 * // 2. An explicit eye position: it is the LENGTH of this vector that frames an auto-centred
 * //    subject (see below), so this is also ≈ 2.5 m.
 * val cameraManipulator = rememberCameraManipulator(
 *     eyePosition    = Position(x = 0f, y = 0.5f, z = 2.45f),
 *     targetPosition = Position(x = 0f, y = 0f, z = 0f)
 * )
 * ```
 *
 * `eyePosition` was called `orbitHomePosition` before 4.32 (Filament's name for it). The old
 * parameter still compiles with a deprecation warning — same behaviour, nothing moved (#2932).
 * The name promised a "home" gesture that does not exist: nothing returns the camera there,
 * `onDoubleTap` is a plain callback forwarded to your code and never wired to the camera.
 *
 * ### How far away the camera actually ends up
 *
 * `eyePosition` is the eye's **absolute world position**: Filament's `OrbitManipulator`
 * assigns it verbatim (`mEye = mProps.orbitHomePosition`, defaulting to `(0, 0, 1)`) and never
 * re-bases it on `targetPosition`. `targetPosition` sets the orbit pivot and the direction the
 * camera initially looks in — it does not set the distance.
 *
 * What makes that easy to get wrong is the interaction with auto-centring. Under `SceneView`'s
 * default `autoCenterContent = true` the DSL content is translated so its bounding-box centre
 * lands on the **world origin**, so the distance your subject is framed from is
 * **`|eyePosition|`** — the coordinates you gave your nodes do not survive, and
 * `targetPosition` does not enter into it:
 *
 * ```kotlin
 * // Nodes authored at z = -1.5 are auto-centred back onto the origin, so this frames the
 * // subject from |(0, 0.2, 1.2)| ≈ 1.22 m — NOT from |(0, 0.2, 1.2) − (0, 0, -1.5)| ≈ 2.7 m.
 * rememberCameraManipulator(
 *     eyePosition    = Position(0f, 0.2f, 1.2f),
 *     targetPosition = Position(0f, 0f, -1.5f)
 * )
 * ```
 *
 * Aiming `targetPosition` at where the content was authored does not push the subject away from
 * the camera; it only tilts the view. Both readings coincide whenever the target is the origin,
 * which is why every doc example looks fine and why this cost issue #2873 its diagnosis (the demo
 * was framed from 1.22 m while its own comment claimed 2.7 m). Pass `autoCenterContent = false`
 * to `SceneView` when authored world positions should survive; the framing distance is then
 * `|eyePosition − contentCentre|`. The `orbitRadius` overload sidesteps all of this for the
 * common case: with the default (origin) target its value *is* the subject distance.
 *
 * @param eyePosition    Camera's initial eye position in **world space** (optional). Its
 *                       *length* is the framing distance under the default
 *                       `autoCenterContent = true` — see above. Omitting it does **not** give
 *                       you Filament's `(0, 0, 1)`: `SceneView`'s own default manipulator
 *                       passes `cameraNode.worldPosition`, i.e. `(0, 0.4, 2.75)` ≈ 2.78 m for
 *                       the default [CameraNode].
 * @param targetPosition Point in world space the camera orbits around and initially looks at
 *                       (optional; defaults to the origin). Does not affect the distance.
 * @param creator        Factory for the manipulator. Override to set a custom orbit speed, etc.
 */
@Composable
fun rememberCameraManipulator(
    eyePosition: Position? = null,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(eyePosition = eyePosition, targetPosition = targetPosition)
    }
) = remember(creator)

/**
 * Creates and remembers a [CameraGestureDetector.CameraManipulator] whose camera starts
 * [orbitRadius] metres from [targetPosition], along
 * [io.github.sceneview.gesture.DEFAULT_ORBIT_DIRECTION] — the same gentle 3/4 angle as the
 * default [CameraNode], so `orbitRadius = 2.78f` reproduces the stock framing.
 *
 * This is the distance-first spelling of [rememberCameraManipulator] (it mirrors the iOS
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

/**
 * Deprecated spelling of [rememberCameraManipulator] — `orbitHomePosition` promised a "home"
 * gesture that does not exist (#2932). Same behaviour, new name: it is the camera's initial
 * eye position, and nothing ever returns the camera to it.
 */
@Deprecated(
    message = "orbitHomePosition is the camera's initial eye position — there is no home " +
        "gesture. Use eyePosition, or rememberCameraManipulator(orbitRadius = …) (#2932).",
    replaceWith = ReplaceWith(
        "rememberCameraManipulator(eyePosition = orbitHomePosition, targetPosition = targetPosition, creator = creator)"
    ),
    level = DeprecationLevel.WARNING
)
@JvmName("rememberCameraManipulatorOrbitHome")
@Composable
fun rememberCameraManipulator(
    orbitHomePosition: Position,
    targetPosition: Position? = null,
    creator: () -> CameraGestureDetector.CameraManipulator = {
        createDefaultCameraManipulator(eyePosition = orbitHomePosition, targetPosition = targetPosition)
    }
) = rememberCameraManipulator(
    eyePosition = orbitHomePosition,
    targetPosition = targetPosition,
    creator = creator
)
