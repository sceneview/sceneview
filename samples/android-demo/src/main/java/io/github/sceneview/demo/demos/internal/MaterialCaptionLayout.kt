package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/** Width shared by two centred captions; a pair narrower than the minimum needs two rows. */
internal fun compareCaptionWidthPx(
    firstAnchorX: Float,
    secondAnchorX: Float,
    gapPx: Int,
    minimumWidthPx: Int,
): Int = (abs(secondAnchorX - firstAnchorX) - gapPx).toInt().coerceAtLeast(minimumWidthPx)

/** Fade above the cover edge so captions do not show through the glass. All distances are pixels. */
internal fun materialCaptionCoverAlpha(
    anchorY: Float,
    sceneHeightPx: Float,
    bottomCoverPx: Float,
    fadeBandPx: Float,
): Float {
    if (bottomCoverPx <= 0f) return 1f
    val coverEdgeY = sceneHeightPx - bottomCoverPx
    return ((coverEdgeY - anchorY) / fadeBandPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}
