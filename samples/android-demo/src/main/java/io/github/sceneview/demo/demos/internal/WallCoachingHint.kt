package io.github.sceneview.demo.demos.internal

import com.google.ar.core.TrackingFailureReason
import io.github.sceneview.ar.PlacementPhase

/**
 * What the Wall Placement demo tells the user while it looks for a wall (#4070).
 *
 * The SDK's coaching glyph shows *where* to aim; on its own it cannot say *why* nothing is
 * happening. Before #4070 a user aiming at a plain wall watched the glyph for half a minute:
 * ARCore finds no features on a blank wall, tracking never starts, the flow stays in
 * [PlacementPhase.INITIALIZING], and the "No surface found" card — which only counts
 * *tracked* scanning time — never comes up.
 *
 * The hint is spoken by the glyph itself, as its caption (#4190): the glyph is on screen the
 * whole time the demo searches, and a second sentence in the bottom pill put two instructions
 * on screen at once. See [wallStatus] for the pill.
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

/** What the Wall demo's bottom pill says, when the coaching glyph is silent. */
internal enum class WallStatus {
    SCANNING,
    TRACKING_PAUSED,
    TRACKING_PAUSED_LOW_LIGHT,
    FINDING_PLACEMENT,
    KEEP_ON_WALL,
    GESTURE_HINT,
    SCALE,
}

/**
 * The bottom pill for the current frame, or `null` for "say nothing".
 *
 * One instruction on screen at a time (#4190): while the coaching glyph speaks ([coaching],
 * i.e. `ArGuidanceState.isCoaching`) the pill steps aside, and it comes back only once the
 * glyph is gone. The glyph carries the search hints ([wallCoachingHint]) and its own
 * low-light sentence, so the pill has nothing to add while it is up. A card that offers a
 * choice ([cardShown]) never shares the screen with the pill either.
 */
internal fun wallStatus(
    phase: PlacementPhase,
    cardShown: Boolean,
    coaching: Boolean,
    invalidMove: Boolean,
    showGestureHint: Boolean,
    lowLight: Boolean,
): WallStatus? = when {
    cardShown || coaching -> null
    invalidMove -> WallStatus.KEEP_ON_WALL
    else -> when (phase) {
        PlacementPhase.SCANNING -> WallStatus.SCANNING
        PlacementPhase.TRACKING_LOST ->
            if (lowLight) WallStatus.TRACKING_PAUSED_LOW_LIGHT else WallStatus.TRACKING_PAUSED
        PlacementPhase.RECOVERING -> WallStatus.FINDING_PLACEMENT
        PlacementPhase.PLACED -> if (showGestureHint) WallStatus.GESTURE_HINT else null
        PlacementPhase.ADJUSTING -> WallStatus.SCALE
        else -> null
    }
}
