package io.github.sceneview.demo.ui.home

import io.github.sceneview.demo.theme.SceneViewTokens

/** The navigation bar is included in the lower 42%, not subtracted before the fraction. */
internal fun homeSheetPeekHeight(windowHeight: Float, bottomBarHeight: Float): Float =
    (windowHeight * (1f - SceneViewTokens.Home.sheetRestFraction) - bottomBarHeight)
        .coerceAtLeast(0f)

/** Same 0..1 input as the 4.68 scroll-driven camera; offsets are relative to the top bar. */
internal fun homeSheetCameraProgress(offset: Float, restOffset: Float): Float =
    if (restOffset <= 0f || !offset.isFinite()) 0f
    else (1f - offset / restOffset).coerceIn(0f, 1f)
