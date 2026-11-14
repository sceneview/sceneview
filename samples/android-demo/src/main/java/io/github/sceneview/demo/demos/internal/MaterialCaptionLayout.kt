package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/** Width shared by two centred captions; a pair narrower than the minimum needs two rows. */
internal fun compareCaptionWidthPx(
    firstAnchorX: Float,
    secondAnchorX: Float,
    gapPx: Int,
    minimumWidthPx: Int,
): Int = (abs(secondAnchorX - firstAnchorX) - gapPx).toInt().coerceAtLeast(minimumWidthPx)
