package io.github.sceneview.gesture

import io.github.sceneview.math.Scale

/**
 * Pure gesture transform utilities — no platform dependencies.
 *
 * These functions implement the math behind gesture-driven transform editing
 * (scale damping, range clamping) in a platform-independent way.
 */

/**
 * Apply a pinch scale gesture with sensitivity damping and range clamping.
 *
 * The raw [scaleFactor] (typically from a pinch gesture detector) is damped by
 * [sensitivity] to prevent overly aggressive scaling, then applied uniformly
 * to the current [scale].
 *
 * @param scale Current scale of the object.
 * @param scaleFactor Raw scale factor from gesture (1.0 = no change, >1 = grow, <1 = shrink).
 * @param sensitivity Damping factor in [0..1]. 0 = no effect, 1 = full gesture applied.
 * @param range Allowed range for each scale component.
 * @return New scale if within range, or null if the result would be out of range.
 */
@Deprecated(
    "Unused internally since #3739 — scale clamping now lives in the Android " +
        "sceneview module's internal evaluateScaleEdit(NodeEditingListener.kt), which " +
        "additionally reports which bound (min/max) rejected the edit. Kept here, " +
        "unchanged, for public binary compatibility — major version 4 is frozen, so " +
        "this can only be removed in 5.0.0.",
    level = DeprecationLevel.WARNING
)
fun applyScaleGesture(
    scale: Scale,
    scaleFactor: Float,
    sensitivity: Float = 0.75f,
    range: ClosedFloatingPointRange<Float> = 0.1f..10f
): Scale? {
    val damped = 1f + (scaleFactor - 1f) * sensitivity
    val newScale = Scale(
        x = scale.x * damped,
        y = scale.y * damped,
        z = scale.z * damped
    )
    return if (newScale.x in range && newScale.y in range && newScale.z in range) {
        newScale
    } else {
        null
    }
}

/**
 * Apply a rotation gesture with sensitivity damping.
 *
 * @param currentAngle Current rotation angle in degrees.
 * @param deltaAngle Raw rotation delta from gesture in degrees.
 * @param sensitivity Damping factor in [0..1].
 * @return New rotation angle in degrees.
 */
fun applyRotationGesture(
    currentAngle: Float,
    deltaAngle: Float,
    sensitivity: Float = 0.75f
): Float = currentAngle + deltaAngle * sensitivity
