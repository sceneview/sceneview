package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/** Keep gallery captions readable without overlapping their projected neighbours. */
internal fun galleryCaptionWidthPx(
    neighbourDistancePx: Float?,
    gapPx: Int,
    minimumWidthPx: Int,
    maximumWidthPx: Int,
): Int {
    if (neighbourDistancePx == null || minimumWidthPx > maximumWidthPx) return maximumWidthPx
    return (neighbourDistancePx - gapPx).toInt().coerceIn(minimumWidthPx, maximumWidthPx)
}

/** Width shared by two centred captions; a pair narrower than the minimum needs two rows. */
internal fun compareCaptionWidthPx(
    firstAnchorX: Float,
    secondAnchorX: Float,
    gapPx: Int,
    minimumWidthPx: Int,
): Int = (abs(secondAnchorX - firstAnchorX) - gapPx).toInt().coerceAtLeast(minimumWidthPx)

/** Fade caption bottoms before they reach the sheet's glass, leaving resting chrome alone. Distances are pixels. */
internal fun materialCaptionCoverAlpha(
    captionBottomY: Float,
    sceneHeightPx: Float,
    bottomCoverPx: Float,
    restingBottomCoverPx: Float,
    fadeBandPx: Float,
): Float {
    if (bottomCoverPx <= restingBottomCoverPx) return 1f
    val coverEdgeY = sceneHeightPx - bottomCoverPx
    return ((coverEdgeY - captionBottomY) / fadeBandPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}
