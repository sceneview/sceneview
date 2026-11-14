package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Smallest edge-column gap at the last fully opaque yaw, independent of the live sweep. */
internal fun galleryCaptionSpacingPx(
    headOnSpacingPx: Float,
    cameraDistance: Float,
    ballSpacing: Float,
    columns: Int,
    yawDegrees: Float,
): Float {
    val yaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
    val halfSpan = (columns - 1) * ballSpacing / 2f
    if (cameraDistance <= halfSpan * abs(sin(yaw))) return 0f

    // Rotate the wall into the camera frame; positive depth is farther from the eye.
    fun projectX(wallX: Float): Float =
        headOnSpacingPx * cameraDistance * wallX * cos(yaw) /
            (ballSpacing * (cameraDistance + wallX * sin(yaw)))

    val leftGap = abs(projectX(-halfSpan + ballSpacing) - projectX(-halfSpan))
    val rightGap = abs(projectX(halfSpan) - projectX(halfSpan - ballSpacing))
    return minOf(leftGap, rightGap)
}

/** Keep gallery captions within their narrowest opaque column width when zooming out. */
internal fun galleryCaptionWidthPx(
    spacingPx: Float?,
    gapPx: Int,
    maximumWidthPx: Int,
): Int = spacingPx?.let { (it - gapPx).toInt().coerceIn(0, maximumWidthPx) } ?: maximumWidthPx

/** Width shared by two centred captions; a pair narrower than the minimum needs two rows. */
internal fun compareCaptionWidthPx(
    firstAnchorX: Float,
    secondAnchorX: Float,
    gapPx: Int,
    minimumWidthPx: Int,
): Int = (abs(secondAnchorX - firstAnchorX) - gapPx).toInt().coerceAtLeast(minimumWidthPx)

internal data class MaterialCaptionPlacement(val x: Int, val y: Int, val height: Int) {
    val captionBottomY: Float get() = (y + height).toFloat()
}

/** Keep each caption centred on its sphere, stacking the pair when their boxes overlap. */
internal fun compareCaptionPlacements(
    first: MaterialCaptionPlacement,
    second: MaterialCaptionPlacement,
    widthPx: Int,
    gapPx: Int,
): List<MaterialCaptionPlacement> {
    val tooClose = first.x < second.x + widthPx + gapPx && second.x < first.x + widthPx + gapPx
    return listOf(
        first,
        if (tooClose) second.copy(y = maxOf(second.y, first.y + first.height + gapPx)) else second,
    )
}

/** Fade caption bottoms before they reach the sheet's glass, independently of chrome. Distances are pixels. */
internal fun materialCaptionCoverAlpha(
    captionBottomY: Float,
    sceneHeightPx: Float,
    sheetCoverPx: Float,
    fadeBandPx: Float,
): Float {
    if (sheetCoverPx <= 0f) return 1f
    val coverEdgeY = sceneHeightPx - sheetCoverPx
    return ((coverEdgeY - captionBottomY) / fadeBandPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}
