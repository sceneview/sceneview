package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/** Keep gallery captions within their head-on column width when zooming out. */
internal fun galleryCaptionWidthPx(
    headOnSpacingPx: Float?,
    gapPx: Int,
    maximumWidthPx: Int,
): Int = headOnSpacingPx?.let { (it - gapPx).toInt().coerceIn(0, maximumWidthPx) } ?: maximumWidthPx

/** Width shared by two centred captions; a pair narrower than the minimum needs two rows. */
internal fun compareCaptionWidthPx(
    firstAnchorX: Float,
    secondAnchorX: Float,
    gapPx: Int,
    minimumWidthPx: Int,
): Int = (abs(secondAnchorX - firstAnchorX) - gapPx).toInt().coerceAtLeast(minimumWidthPx)

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
