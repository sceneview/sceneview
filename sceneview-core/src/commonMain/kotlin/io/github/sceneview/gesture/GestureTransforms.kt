package io.github.sceneview.gesture

/**
 * Pure gesture transform utilities — no platform dependencies.
 *
 * These functions implement the math behind gesture-driven transform editing
 * (scale damping, range clamping) in a platform-independent way.
 */

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
