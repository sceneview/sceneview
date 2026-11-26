package io.github.sceneview.demo.demos.internal

import io.github.sceneview.FitMode
import io.github.sceneview.fitCameraToBounds
import io.github.sceneview.math.Position
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.toAabb
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

internal fun viewerEnvelopeFraming(
    envelope: AnimationEnvelope,
    center: Position,
    viewportWidth: Float,
    viewportHeight: Float,
    topInset: Float,
    bottomInset: Float,
    verticalFovDegrees: Double,
    fill: Float = DemoMath.VIEWER_FILL,
    horizontalFill: Float = DemoMath.VIEWER_HORIZONTAL_FILL,
    pitchDegrees: Float,
): DemoMath.ViewerFraming? {
    fun safe(value: Float) = if (value.isFinite() && value > 0f) value else 0f
    val width = safe(viewportWidth).takeIf { it > 0f } ?: 1f
    val height = safe(viewportHeight).takeIf { it > 0f } ?: 1f
    val top = safe(topInset)
    val bottom = safe(bottomInset)
    val visibleHeight = (height - top - bottom).coerceAtLeast(height * 0.25f)
    val safeFill = fill.takeIf { it.isFinite() && it > 0f } ?: DemoMath.VIEWER_FILL
    val safeHorizontalFill = horizontalFill.takeIf { it.isFinite() && it > 0f }
        ?: DemoMath.VIEWER_HORIZONTAL_FILL
    val fov = verticalFovDegrees.takeIf { it.isFinite() }?.coerceIn(1.0, 179.0)
        ?: DemoMath.DEFAULT_VERTICAL_FOV_DEGREES
    val pitch = Math.toRadians(pitchDegrees.toDouble())
    val sinPitch = sin(pitch).toFloat()
    val cosPitch = cos(pitch).toFloat()
    val fit = fitCameraToBounds(
        bounds = envelope.bounds.toAabb(),
        direction = Position(0f, -sinPitch, -cosPitch),
        verticalFovDegrees = fov,
        aspect = width.toDouble() / height,
        widthFill = safeHorizontalFill,
        heightFill = safeFill * visibleHeight / height,
        fitMode = FitMode.Silhouette,
        animationEnvelope = envelope,
    ) ?: return null

    val halfTan = tan(Math.toRadians(fov) / 2.0).toFloat()
    val bandOffset = ((top - bottom) / 2f) / (height / 2f)
    val shift = bandOffset * fit.distance * halfTan
    val shiftVector = Position(0f, shift * cosPitch, -shift * sinPitch)
    val target = fit.target + shiftVector
    val eye = fit.eye + shiftVector
    val targetOffset = target - center
    val eyeOffset = eye - center
    return DemoMath.ViewerFraming(
        distance = fit.distance,
        targetOffset = Triple(targetOffset.x, targetOffset.y, targetOffset.z),
        eyeOffset = Triple(eyeOffset.x, eyeOffset.y, eyeOffset.z),
    )
}
