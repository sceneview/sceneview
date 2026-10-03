package io.github.sceneview.demo

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * What a demo whose scene runs edge to edge under the glass needs to keep its subject clear of
 * it (#4326).
 *
 * @property contentPadding Hand it to `SceneView(contentPadding = …)`: the chrome, the settings
 *   sheet where it is right now, and the window's side insets.
 * @property restingAspect Width over height of the area left free with the sheet closed — the
 *   aspect to fit the camera distance for. See [LocalDemoSceneRestingCover] for why the fit is
 *   made once, at rest, and not for the live padding.
 */
internal class DemoSceneFrame(val contentPadding: PaddingValues, val restingAspect: Float)

/**
 * The [DemoSceneFrame] of a scene that fills this box, itself filling the scaffold's `scene` slot.
 *
 * It reads the sheet's live position, so the calling scope recomposes on every frame of a sheet
 * drag: keep what is derived from [DemoSceneFrame.restingAspect] in a `remember` keyed on it.
 */
@Composable
internal fun BoxWithConstraintsScope.demoSceneFrame(
    cover: PaddingValues = LocalDemoSceneCover.current,
): DemoSceneFrame {
    val direction = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val left = safe.calculateLeftPadding(direction)
    val right = safe.calculateRightPadding(direction)
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val compactHeight = LocalConfiguration.current.screenHeightDp < DEMO_COMPACT_HEIGHT_DP
    val resting = demoContentPadding(
        LocalDemoSceneRestingCover.current, maxHeight, left, right, statusBar, compactHeight,
    )
    return DemoSceneFrame(
        contentPadding = demoContentPadding(cover, maxHeight, left, right, statusBar, compactHeight),
        restingAspect = demoFreeAspect(maxWidth, maxHeight, resting),
    )
}

/**
 * What a demo hands to `contentPadding`, from what the scaffold reports as covering its scene.
 *
 * The bottom is taken as it is: the controls and the settings sheet are where the subject must
 * not be. The sides are the window's safe insets — a display cutout on a phone held sideways, a
 * navigation bar on the short edge — absolute, because a cutout does not change sides in RTL.
 *
 * The top is a row of glass chips over a live stage, and it gives way in two cases — the rule
 * Rolling Balls already frames its board by (`trayContentPadding`, #4310).
 *
 * - **A phone held sideways** ([compactHeight]): the title is a chip in a corner a centred subject
 *   never reaches, and counting its row as a band leaves a strip a seventh of the screen tall
 *   between it and the controls. Only the [statusBar] is kept clear — and nothing at all in a slot
 *   the scaffold has already inset, whose cover has no top.
 * - **A sheet dragged all the way up**: what is left under the title row is thinner than the tenth
 *   of the view the SDK keeps visible ([DEMO_MIN_VISIBLE_FRACTION]), and the SDK takes the
 *   difference from both edges in proportion — most of it from the sheet's side, which puts the
 *   subject under the glass. The top yields the whole difference instead, and the band above the
 *   sheet stays whole for as long as the sheet alone leaves a tenth of the view.
 */
internal fun demoContentPadding(
    cover: PaddingValues,
    sceneHeight: Dp,
    left: Dp = 0.dp,
    right: Dp = 0.dp,
    statusBar: Dp = 0.dp,
    compactHeight: Boolean = false,
): PaddingValues {
    val bottom = cover.calculateBottomPadding()
    val room = sceneHeight * (1f - DEMO_MIN_VISIBLE_FRACTION) - bottom
    val chromeTop = cover.calculateTopPadding()
    val wanted = if (compactHeight) minOf(chromeTop, statusBar) else chromeTop
    val top = minOf(wanted, room.coerceAtLeast(0.dp))
    return PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)
}

/**
 * Width over height of the area [padding] leaves free in a scene of [sceneWidth] × [sceneHeight],
 * with the SDK's own floor on each axis so the fit and the projection agree. `1` for a scene that
 * has not been measured yet.
 */
internal fun demoFreeAspect(sceneWidth: Dp, sceneHeight: Dp, padding: PaddingValues): Float {
    if (sceneWidth <= 0.dp || sceneHeight <= 0.dp) return 1f
    // The direction only matters for start/end padding; [demoContentPadding]'s sides are absolute.
    val sides = padding.calculateLeftPadding(LayoutDirection.Ltr) +
        padding.calculateRightPadding(LayoutDirection.Ltr)
    val bands = padding.calculateTopPadding() + padding.calculateBottomPadding()
    val width = (sceneWidth - sides).coerceAtLeast(sceneWidth * DEMO_MIN_VISIBLE_FRACTION)
    val height = (sceneHeight - bands).coerceAtLeast(sceneHeight * DEMO_MIN_VISIBLE_FRACTION)
    return width / height
}

/** The floor the SDK applies to `contentPadding`: a tenth of the view stays visible on each axis. */
internal const val DEMO_MIN_VISIBLE_FRACTION = 0.1f

/**
 * Below this window height, in dp, the title row stops counting as a band over the scene: a phone
 * held sideways. The same threshold Rolling Balls lays its controls out by.
 */
internal const val DEMO_COMPACT_HEIGHT_DP = 480

/**
 * Diameter of the disc a double pendulum can reach around its hinge: both arms in line, out to
 * the far side of the tip bob. The tip's centre alone stops a bob's radius short of what is drawn.
 */
internal fun pendulumSwingExtent(length1: Float, length2: Float, bobRadius: Float): Float =
    2f * (length1 + length2 + bobRadius)

/**
 * What the pendulum's camera fits upright and where it aims.
 *
 * @property extentY Height to fit, in metres.
 * @property centerY Height of the aim point above the floor, in metres.
 */
internal class PendulumSubject(val extentY: Float, val centerY: Float)

/**
 * The upright span the pendulum's camera fits, for a free area of [freeAspect] (width over height).
 *
 * In an area taller than wide the width is what limits: the swing disc is fitted around the hinge
 * and the stand's foot has room below it. In a strip the height limits, and a disc fitted to it
 * leaves the foot outside — behind whatever closes the strip. There the span runs from the floor
 * to the top of the swing, so the whole stand is drawn in the free area.
 */
internal fun pendulumSubject(pivotHeight: Float, swingExtent: Float, freeAspect: Float): PendulumSubject {
    val reach = swingExtent / 2f
    val top = pivotHeight + reach
    val hang = pivotHeight - reach
    val foot = if (freeAspect > 1f) minOf(0f, hang) else hang
    return PendulumSubject(extentY = top - foot, centerY = (top + foot) / 2f)
}

/**
 * Height a modal sheet covers from the bottom edge once it settles.
 *
 * Material rests a sheet taller than half its window at half of it, the rest below the edge, until
 * it is dragged up: its measured size then says more than what is on screen. [expanded] is whether
 * the sheet is heading for its full height.
 */
internal fun settledSheetCover(measured: Dp, windowHeight: Dp, expanded: Boolean): Dp =
    if (expanded || windowHeight <= 0.dp) measured else minOf(measured, windowHeight / 2)
