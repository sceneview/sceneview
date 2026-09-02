package io.github.sceneview.gesture

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign


/**
 * Maps a two-finger pinch gesture's pointer-separation delta into a
 * non-linear "zoom delta" suitable for translating the camera along its
 * forward axis or shrinking a perspective FOV.
 *
 * Pinch-out (fingers spreading) returns a negative number and pinch-in a
 * positive one — the convention the orbit / dolly camera manipulators
 * consume in [CameraGestureDetector]. A power curve flattens the response
 * once `|delta| > 1 px` so a fast pinch doesn't teleport across the scene,
 * while small movements stay linear (1:1 px-to-zoom mapping under 1 px).
 *
 * @param prevSeparation pointer separation, in pixels, on the previous frame.
 * @param currSeparation pointer separation, in pixels, on the current frame.
 * @param speed multiplicative gain applied at the end. Higher = faster zoom.
 * @param damping exponent (typically `0.6f .. 0.9f`) applied to large
 *   separation deltas — `1.0` is linear, `<1.0` compresses fast pinches.
 */
internal fun pinchZoomDelta(
    prevSeparation: Float,
    currSeparation: Float,
    speed: Float,
    damping: Float,
): Float {
    val delta = prevSeparation - currSeparation
    val absDelta = abs(delta)
    val damped = if (absDelta > 1f) {
        sign(delta) * exp(ln(absDelta) * damping)
    } else {
        delta
    }
    return damped * speed
}

/**
 * The camera-to-target distance a pinch of [zoomDelta] should land on, starting from [distance].
 *
 * ### Why this exists (#3403 / #3426)
 *
 * Filament's `OrbitManipulator::scroll` is an **absolute** translation:
 * `eye += gaze · zoomSpeed · (−scrolldelta)`. The step is a fixed number of world units no matter
 * how far the camera is, which breaks at both ends of the scale the SDK has to cover:
 *
 * - **Far scenes crawl.** With the shipped `zoomSpeed = 0.05`, a full-screen 200 px pinch moved the
 *   camera ~11 cm. On a scene framed at 5 m that is 40+ pinches to halve the distance — the
 *   "beaucoup de gestes pour peu de zoom" of #3426.
 * - **Near scenes teleport, then invert.** On a 5 cm model the same pinch punches the eye straight
 *   through the orbit pivot. Filament notices (`dot(v0, v1) < 0` ⇒ `mFlipped = true`), and the next
 *   orbit drag rebuilds the view from a **negative** bookmark distance, aiming the camera *away*
 *   from the subject. That is #3403's "the zoom completely breaks the camera".
 *
 * The fix is to make a pinch a **ratio**, not a length: the same gesture covers the same fraction
 * of the distance whether the subject is a 5 cm bee or a 155 m landscape. Zooming is therefore
 * exponential in the gesture, `newDistance = distance · exp(zoomDelta)`, and the result is clamped
 * into `[minDistance, maxDistance]` so the eye can never reach — let alone cross — the pivot.
 *
 * @param distance    Current camera-to-target distance. Non-finite / non-positive returns
 *                    [minDistance].
 * @param zoomDelta   Output of [pinchZoomDelta]: positive for a pinch-in (zoom **out**, distance
 *                    grows), negative for a pinch-out.
 * @param minDistance Closest the camera may get. Must be `> 0`.
 * @param maxDistance Furthest the camera may get.
 * @return The clamped new distance.
 */
internal fun zoomedDistance(
    distance: Float,
    zoomDelta: Float,
    minDistance: Float,
    maxDistance: Float,
): Float {
    val low = if (minDistance.isFinite() && minDistance > 0f) minDistance else MIN_ORBIT_DISTANCE
    val high = if (maxDistance.isFinite() && maxDistance > low) maxDistance else low
    if (!distance.isFinite() || distance <= 0f) return low
    if (!zoomDelta.isFinite()) return distance.coerceIn(low, high)
    val scaled = distance * exp(zoomDelta)
    if (!scaled.isFinite()) return distance.coerceIn(low, high)
    return scaled.coerceIn(low, high)
}

/**
 * Converts a target camera-to-target [targetDistance] into the `scrolldelta` Filament's
 * `OrbitManipulator::scroll` needs to get there from [distance].
 *
 * `scroll` moves the eye by `gaze · zoomSpeed · (−scrolldelta)` — along the gaze for a negative
 * delta — so the distance changes by exactly `zoomSpeed · scrolldelta`. Inverting that gives the
 * delta below. [zoomSpeed] is the manipulator's configured `Manipulator.Builder.zoomSpeed`.
 */
internal fun dollyScrollDelta(
    distance: Float,
    targetDistance: Float,
    zoomSpeed: Float,
): Float {
    if (!zoomSpeed.isFinite() || zoomSpeed <= 0f) return 0f
    val delta = (targetDistance - distance) / zoomSpeed
    return if (delta.isFinite()) delta else 0f
}

/** Absolute floor for an orbit distance — the camera may never sit on its own pivot. */
internal const val MIN_ORBIT_DISTANCE: Float = 1e-3f

/**
 * The camera-to-target distance a pinch should land on — the public, one-call form of
 * [pinchZoomDelta] + [zoomedDistance], for camera manipulators that own their own orbit distance
 * instead of delegating the dolly to a Filament `Manipulator`.
 *
 * Use it when a pinch has to *publish* a distance (a slider, a saved state) rather than translate
 * a camera: the step is a fraction of [distance], so the gesture feels the same at every scale.
 *
 * ```kotlin
 * override fun scrollUpdate(x: Int, y: Int, prev: Float, curr: Float) {
 *     zoom = zoomedDistanceForPinch(zoom, prev, curr, minDistance = fit * 0.25f, fit * 4f)
 * }
 * ```
 *
 * @param distance       Current camera-to-target distance.
 * @param prevSeparation Pointer separation, in pixels, on the previous frame.
 * @param currSeparation Pointer separation, in pixels, on the current frame.
 * @param minDistance    Closest the camera may get. Must be `> 0`.
 * @param maxDistance    Furthest the camera may get.
 * @param speed          Pinch gain — see
 *                       [CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_SPEED].
 * @param damping        Damping exponent — see
 *                       [CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_DAMPING].
 */
@JvmOverloads
fun zoomedDistanceForPinch(
    distance: Float,
    prevSeparation: Float,
    currSeparation: Float,
    minDistance: Float,
    maxDistance: Float,
    speed: Float = CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_SPEED,
    damping: Float = CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_DAMPING,
): Float = zoomedDistance(
    distance = distance,
    zoomDelta = pinchZoomDelta(prevSeparation, currSeparation, speed, damping),
    minDistance = minDistance,
    maxDistance = maxDistance,
)

/**
 * Same pinch-delta math as [pinchZoomDelta] but interpreted as a
 * field-of-view step instead of a translation, and bounded to a legal FOV
 * range so the camera can never invert or flip out of [-180°, 180°].
 *
 * Used by perspective-FOV cameras (where "pinch to zoom" semantically means
 * "narrow the FOV") rather than dolly cameras.
 *
 * @param currentFov current FOV in degrees.
 * @param range allowed FOV interval (e.g. `30f..120f`).
 */
internal fun nextFov(
    currentFov: Double,
    prevSeparation: Float,
    currSeparation: Float,
    range: ClosedFloatingPointRange<Float>,
    speed: Float,
): Double {
    val delta = (prevSeparation - currSeparation) * speed
    return (currentFov + delta).coerceIn(
        range.start.toDouble(),
        range.endInclusive.toDouble(),
    )
}
