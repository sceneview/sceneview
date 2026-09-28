package io.github.sceneview.demo.demos.internal

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.PlacementPhase

/**
 * What the Wall Placement demo tells the user while it looks for a wall (#4070).
 *
 * The SDK's coaching glyph shows *where* to aim; it cannot say *why* nothing is happening.
 * Before this, the demo hid all copy while the glyph played, so a user aiming at a plain wall
 * watched a glyph for half a minute: ARCore finds no features on a blank wall, tracking never
 * starts, the flow stays in [PlacementPhase.INITIALIZING], and the "No surface found" card —
 * which only counts *tracked* scanning time — never comes up.
 *
 * Pure (no Compose, no ARCore session), so `WallCoachingTest` pins it on the JVM.
 */
internal enum class WallCoachingHint {
    /** Nothing to add: the glyph, or another banner, already covers the moment. */
    NONE,

    /** Still searching after a few seconds: stand closer and move sideways. */
    MOVE_SIDEWAYS,

    /** ARCore sees too little detail to track: aim at something textured. */
    PLAIN_WALL,

    /** Too dark to track. */
    TOO_DARK,

    /** The phone moves too fast to track. */
    SLOW_DOWN,
}

/** How long a search runs before [WallCoachingHint.MOVE_SIDEWAYS] speaks up. */
internal const val WALL_COACHING_LINGER_MS = 3_000L

/**
 * The hint for the current frame.
 *
 * @param phase The placement phase.
 * @param cameraLive Whether a camera frame has arrived. Before that the camera scrim is up and
 *   there is nothing to coach.
 * @param trackingFailure ARCore's latest reason for not tracking, `null` when unknown.
 * @param lingered Whether the search has run for [WALL_COACHING_LINGER_MS] without a wall.
 */
internal fun wallCoachingHint(
    phase: PlacementPhase,
    cameraLive: Boolean,
    trackingFailure: TrackingFailureReason?,
    lingered: Boolean,
): WallCoachingHint {
    // Only while searching. TRACKING_LOST has its own "Tracking paused" copy and NO_SURFACE
    // its own card.
    if (!isSearchingForWall(phase, cameraLive)) return WallCoachingHint.NONE
    return when (trackingFailure) {
        // A named cause is worth saying at once: the user can fix it right now.
        TrackingFailureReason.INSUFFICIENT_FEATURES -> WallCoachingHint.PLAIN_WALL
        TrackingFailureReason.INSUFFICIENT_LIGHT -> WallCoachingHint.TOO_DARK
        TrackingFailureReason.EXCESSIVE_MOTION -> WallCoachingHint.SLOW_DOWN
        else -> if (lingered) WallCoachingHint.MOVE_SIDEWAYS else WallCoachingHint.NONE
    }
}

/**
 * Whether the demo is still looking for a wall: a live camera and no placement yet. This is
 * what runs the [WALL_COACHING_LINGER_MS] clock, so INITIALIZING → SCANNING does not reset it.
 */
internal fun isSearchingForWall(phase: PlacementPhase, cameraLive: Boolean): Boolean =
    cameraLive && (phase == PlacementPhase.INITIALIZING || phase == PlacementPhase.SCANNING)
