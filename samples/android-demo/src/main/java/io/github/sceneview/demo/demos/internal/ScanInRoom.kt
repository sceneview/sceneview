package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.PlacementPhase
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.math.Position
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The arithmetic behind the `ar-splat-room` demo ("Your scan, in your room", #4023), kept out of
 * the composable so a JVM test can pin it without an AR session: the emulator cannot run ARCore
 * (#2754), so this is the part of the demo that CI can actually check.
 */

/**
 * Where a scan stands and how big it is, measured from its own points.
 *
 * A phone capture is already in metres. It is not centred on anything useful, though: its origin
 * is wherever the capture app put it, and there is no "bottom" to rest on a floor. [groundPoint]
 * is the point, in the scan's own coordinates, that should sit on the detected plane, so drawing
 * the cloud at `-groundPoint` under the placement anchor stands the subject on the real floor at
 * its real size.
 *
 * @property groundPoint Median x and z of the points (the subject's centre, robust to the fringe
 *   of grass a capture trails off into) and the [SCAN_GROUND_QUANTILE] of y (the captured ground,
 *   robust to the few stray splats that always float below it).
 * @property height Metres from the ground to the [SCAN_TOP_QUANTILE] of y.
 * @property width Metres across x between the [SCAN_SPREAD_QUANTILE] tails.
 * @property depth Metres across z between the [SCAN_SPREAD_QUANTILE] tails.
 */
internal data class ScanFootprint(
    val groundPoint: Position,
    val height: Float,
    val width: Float,
    val depth: Float,
)

/**
 * Fraction of the points allowed below the ground. Not the minimum: every capture carries a few
 * floaters under the real ground, and resting the lowest one on the floor would hover the whole
 * scan above it.
 */
internal const val SCAN_GROUND_QUANTILE = 0.02f

/** Fraction of the points allowed above the measured top, for the same floater reason. */
internal const val SCAN_TOP_QUANTILE = 0.98f

/** Fraction trimmed from each side of x and z before measuring the width and depth. */
internal const val SCAN_SPREAD_QUANTILE = 0.05f

/** Measures [cloud]. Sorts three copies of the coordinates, so call it off the main thread. */
internal fun scanFootprint(cloud: SplatCloud): ScanFootprint {
    require(cloud.count > 0) { "An empty scan has no footprint" }
    val xs = axis(cloud, 0)
    val ys = axis(cloud, 1)
    val zs = axis(cloud, 2)
    val ground = ys.quantile(SCAN_GROUND_QUANTILE)
    return ScanFootprint(
        groundPoint = Position(xs.quantile(0.5f), ground, zs.quantile(0.5f)),
        height = max(ys.quantile(SCAN_TOP_QUANTILE) - ground, 0f),
        width = xs.quantile(1f - SCAN_SPREAD_QUANTILE) - xs.quantile(SCAN_SPREAD_QUANTILE),
        depth = zs.quantile(1f - SCAN_SPREAD_QUANTILE) - zs.quantile(SCAN_SPREAD_QUANTILE),
    )
}

/** Height rounded to the nearest 5 cm for the sheet: a scan's top is a fringe, not a ruler mark. */
internal fun scanHeightCentimetres(footprint: ScanFootprint): Int =
    max(((footprint.height * 100f) / 5f).roundToInt() * 5, 5)

/** The placement scale as a percentage of the scan's real size (100 = life size). */
internal fun realSizePercent(scaleFactor: Float): Int = (scaleFactor * 100f).roundToInt()

/** What the status pill says. The composable maps each value to a string resource. */
internal enum class ScanRoomStatus {
    OpeningScan,
    MoveSlowly,
    KeepOnSurface,
    TrackingPaused,
    TrackingPausedLowLight,
    FindingPlacement,
    GestureHint,
    Scale,
}

/**
 * The status pill for the current frame, or null when it should stay quiet.
 *
 * Priorities, highest first: the scan is still opening; an action card owns the screen
 * ([cardShown]); the SDK coaching owns it ([coaching], except that a tracking loss caused by
 * darkness is still worth saying); a rejected move; then the placement phase.
 */
internal fun scanRoomStatus(
    phase: PlacementPhase,
    scanReady: Boolean,
    cardShown: Boolean,
    coaching: Boolean,
    invalidMove: Boolean,
    showGestureHint: Boolean,
    lowLight: Boolean,
): ScanRoomStatus? = when {
    !scanReady -> ScanRoomStatus.OpeningScan
    cardShown -> null
    coaching && !(phase == PlacementPhase.TRACKING_LOST && lowLight) -> null
    invalidMove -> ScanRoomStatus.KeepOnSurface
    else -> when (phase) {
        PlacementPhase.SCANNING -> ScanRoomStatus.MoveSlowly
        PlacementPhase.TRACKING_LOST ->
            if (lowLight) ScanRoomStatus.TrackingPausedLowLight else ScanRoomStatus.TrackingPaused
        PlacementPhase.RECOVERING -> ScanRoomStatus.FindingPlacement
        PlacementPhase.PLACED -> if (showGestureHint) ScanRoomStatus.GestureHint else null
        PlacementPhase.ADJUSTING -> ScanRoomStatus.Scale
        else -> null
    }
}

private fun axis(cloud: SplatCloud, component: Int): FloatArray =
    FloatArray(cloud.count) { cloud.positions[it * 3 + component] }.apply { sort() }

/** Nearest-rank quantile of an already sorted array. */
private fun FloatArray.quantile(q: Float): Float = this[((size - 1) * q).roundToInt()]
