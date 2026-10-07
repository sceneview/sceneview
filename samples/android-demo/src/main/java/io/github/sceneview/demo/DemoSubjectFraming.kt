package io.github.sceneview.demo

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.theme.SceneViewTokens
import kotlin.math.cos

/**
 * What a demo whose scene runs edge to edge under the glass needs to keep its subject clear of
 * it (#4326).
 *
 * @property contentPadding Hand it to `SceneView(contentPadding = …)`: the chrome, the settings
 *   sheet where it is right now, and the window's side insets.
 * @property restingAspect Width over height of the area left free with the sheet closed — the
 *   aspect to fit the camera distance for. See [LocalDemoSceneRestingCover] for why the fit is
 *   made once, at rest, and not for the live padding.
 * @property compactHeight Whether the window is a phone held sideways ([isDemoCompactHeight]): the
 *   free area is then a strip, and a demo that composes its shot differently there asks this.
 */
internal class DemoSceneFrame(
    val contentPadding: PaddingValues,
    val restingAspect: Float,
    val compactHeight: Boolean,
)

/**
 * Whether the window is short enough to be a phone held sideways — Material's compact height
 * class. The one test the demos lay out and frame by: the scaffold's title stops counting as a
 * band over the scene ([demoContentPadding]), Rolling Balls puts its two rows of controls on one
 * line, and the demos whose scene is a strip there compose their shot for it.
 */
@Composable
@ReadOnlyComposable
internal fun isDemoCompactHeight(): Boolean =
    LocalConfiguration.current.screenHeightDp < DEMO_COMPACT_HEIGHT_DP

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
    val compactHeight = isDemoCompactHeight()
    val resting = demoContentPadding(
        LocalDemoSceneRestingCover.current, maxHeight, left, right, statusBar, compactHeight,
    )
    return DemoSceneFrame(
        contentPadding = demoContentPadding(cover, maxHeight, left, right, statusBar, compactHeight),
        restingAspect = demoFreeAspect(maxWidth, maxHeight, resting),
        compactHeight = compactHeight,
    )
}

/**
 * What a demo hands to `contentPadding`, from what the scaffold reports as covering its scene.
 *
 * The bottom is taken as it is: the controls and the settings sheet are where the subject must
 * not be. The sides are the window's safe insets — a display cutout on a phone held sideways, a
 * navigation bar on the short edge — absolute, because a cutout does not change sides in RTL.
 *
 * The top is a row of glass chips over a live stage, and it gives way in two cases. One rule for
 * every demo whose scene runs under the glass: Rolling Balls (#4310), Geometry (#4335) and the
 * three of #4326 all call this.
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
 * A scrim that runs along the top or the bottom edge of the screen: its [height], the fraction
 * of it, from that edge, that is flat ([plateau]), and how the rest fades out — in a straight
 * line, or [eased] at both ends so that neither end reads as an edge.
 */
internal data class DemoScrim(val height: Dp, val plateau: Float, val eased: Boolean = false) {

    /**
     * The gradient, from the screen edge inwards: where along the band, and how much of the
     * scrim's own alpha is left there.
     */
    fun stops(): List<Pair<Float, Float>> {
        if (!eased) return listOf(0f to 1f, plateau to 1f, 1f to 0f)
        // Smoothstep, sampled: a straight ramp bends twice, once where it leaves the flat part
        // and once where it lands, and the eye finds a line at each bend.
        return listOf(0f to 1f) + (0..EASED_SCRIM_STEPS).map { step ->
            val t = step.toFloat() / EASED_SCRIM_STEPS
            (plateau + (1f - plateau) * t) to (1f - t * t * (3f - 2f * t))
        }
    }
}

private const val EASED_SCRIM_STEPS = 8

/** How far past what it grounds an eased scrim takes to fade out on a phone held sideways. */
private val COMPACT_SCRIM_FADE = SceneViewTokens.Space.x2l

/**
 * The top scrim is the ground of what stands on it, and is sized by that — the same rule
 * [demoContentPadding] frames the subject by.
 *
 * - **Upright**, the identity row is a band nothing else occupies: the scrim is the token band,
 *   flat under the row and the status bar.
 * - **A phone held sideways** ([compactHeight]): the row is two chips in a corner and the subject
 *   is framed right up to the status bar. The token band would be more than a third of the
 *   picture, flat over the top of the subject. Only the [statusBarInset] needs a full-width
 *   ground — the clock and the battery are drawn on the scene with nothing of their own. The
 *   scrim is whole behind the upper half of the status bar, where those glyphs start, and from
 *   there eases out well past the bar: a band that stopped at the bar drew a line across the
 *   picture. The chips carry their own ground (`GlassSurface(ground = …)`). No status bar, no
 *   scrim.
 */
internal fun demoTopScrim(compactHeight: Boolean, statusBarInset: Dp): DemoScrim = when {
    !compactHeight -> DemoScrim(
        height = SceneViewTokens.Glass.scrimTopHeight,
        plateau = SceneViewTokens.Glass.scrimPlateau,
    )
    statusBarInset <= 0.dp -> DemoScrim(height = 0.dp, plateau = 0f)
    else -> (statusBarInset + COMPACT_SCRIM_FADE).let { height ->
        DemoScrim(height = height, plateau = statusBarInset / 2 / height, eased = true)
    }
}

/**
 * The bottom scrim, by the same rule as [demoTopScrim].
 *
 * - **Upright**, the dock and the overlays a demo stacks above it share one band: the scrim is
 *   the token band, or the measured stack ([dockReserve] plus [overlayBand]) when that is taller.
 * - **A phone held sideways** ([compactHeight]): that stack is more than half the picture, and a
 *   scrim under all of it leaves a strip of scene at the top. The scrim keeps to the dock: whole
 *   under the system bar and the lower half of the [dockBand], eased out a little above the
 *   dock. What a demo stacks higher carries its own ground ([LocalGlassGround]).
 *
 * @param dockBand The dock and its gutter, without the [navigationBarInset] below them.
 */
internal fun demoBottomScrim(
    compactHeight: Boolean,
    dockReserve: Dp,
    overlayBand: Dp,
    dockBand: Dp,
    navigationBarInset: Dp,
): DemoScrim {
    if (!compactHeight) {
        return DemoScrim(
            height = maxOf(SceneViewTokens.Glass.scrimBottomHeight, dockReserve + overlayBand),
            plateau = SceneViewTokens.Glass.scrimPlateau,
        )
    }
    val height = navigationBarInset + dockBand + COMPACT_SCRIM_FADE / 2
    return DemoScrim(height = height, plateau = (navigationBarInset + dockBand / 2) / height, eased = true)
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
 * Below this window height, in dp, a window is a phone held sideways — Material's compact height
 * class. Read through [isDemoCompactHeight].
 */
internal const val DEMO_COMPACT_HEIGHT_DP = 480

/**
 * Diameter of the disc a double pendulum can reach around its hinge: both arms in line, out to
 * the far side of the tip bob. The tip's centre alone stops a bob's radius short of what is drawn.
 */
internal fun pendulumSwingExtent(length1: Float, length2: Float, bobRadius: Float): Float =
    2f * (length1 + length2 + bobRadius)

/**
 * How far above its hinge a double pendulum let go at rest can be drawn, in metres: the top of
 * whichever bob can climb higher.
 *
 * Nothing adds energy after the release — the damping only takes some away — so the weighted sum
 * of the two bobs' heights never passes its value at the release, whatever the gravity. Each bob
 * is highest when the other is as low as the arms allow: the joint with the tip hanging straight
 * under it, the tip with the joint straight under it. That is a ceiling, not a trajectory — the
 * motion is chaotic — and it moves with the arm lengths, which is why it is computed.
 *
 * Angles are measured from the downward vertical, as in `DoublePendulumLink`.
 */
internal fun pendulumCeiling(
    length1: Float,
    length2: Float,
    mass1: Float,
    mass2: Float,
    releaseAngle1: Float,
    releaseAngle2: Float,
    jointBobRadius: Float,
    tipBobRadius: Float,
): Float {
    // Heights above the hinge.
    val releasedJoint = -length1 * cos(releaseAngle1)
    val releasedTip = releasedJoint - length2 * cos(releaseAngle2)
    val budget = mass1 * releasedJoint + mass2 * releasedTip
    val total = mass1 + mass2
    val joint = (budget + mass2 * length2) / total
    // The tip straight above the joint — unless that asks the joint to hang lower than its arm.
    val balanced = (budget + mass1 * length2) / total
    val tip = if (balanced - length2 >= -length1) balanced else (budget + mass1 * length1) / mass2
    val jointCeiling = minOf(joint + PENDULUM_INTEGRATION_ALLOWANCE, length1)
    val tipCeiling = minOf(tip + PENDULUM_INTEGRATION_ALLOWANCE, length1 + length2)
    return maxOf(jointCeiling + jointBobRadius, tipCeiling + tipBobRadius)
}

/**
 * What the stepped simulation may add to the exact ceiling, in metres. The integrator is
 * semi-implicit Euler: its energy oscillates around the true one instead of drifting, by a few
 * millimetres of height at the strongest gravity and the longest lead arm.
 */
private const val PENDULUM_INTEGRATION_ALLOWANCE = 0.03f

/**
 * What the pendulum's camera fits upright and where it aims.
 *
 * @property extentY Height to fit, in metres.
 * @property centerY Height of the aim point above the floor, in metres.
 */
internal class PendulumSubject(val extentY: Float, val centerY: Float)

/**
 * The upright span the pendulum's camera fits.
 *
 * In an area taller than wide the width is what limits: the swing disc is fitted around the hinge
 * and the stand's foot has room below it. In a [strip] the height limits, and every metre fitted
 * that nothing is ever drawn in is taken from the size of the subject. There the span runs from
 * the floor, so the whole stand is drawn in the free area, to the [ceiling] the bobs can reach
 * above the hinge ([pendulumCeiling]) — not to the top of the disc, which only a pendulum thrown
 * upwards would touch.
 */
internal fun pendulumSubject(
    pivotHeight: Float,
    swingExtent: Float,
    ceiling: Float,
    strip: Boolean,
): PendulumSubject {
    val reach = swingExtent / 2f
    if (!strip) return PendulumSubject(extentY = swingExtent, centerY = pivotHeight)
    // Never below the hinge itself, never past what the arms can reach.
    val top = pivotHeight + ceiling.coerceIn(0f, reach)
    val foot = minOf(0f, pivotHeight - reach)
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
