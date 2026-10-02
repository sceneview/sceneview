package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.PlacementPhase
import kotlin.math.roundToInt

/*
 * The status pill of the `ar-splat-room` demo — the user's own Rerun recording stood on a table
 * as a dollhouse (#4075, first a stock capture in #4023) — kept out of the composable so a JVM
 * test can pin it without an AR session: the emulator cannot run ARCore (#2754).
 */

/** The placement scale as a percentage of its size before any pinch (100 = unpinched). */
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
 * Priorities, highest first: the recording is still opening; an action card owns the screen
 * ([cardShown]); the SDK coaching owns it ([coaching] — its card names the reason, "Too dark"
 * included, so the pill has nothing to add); a rejected move; then the placement phase.
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
    coaching -> null
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
