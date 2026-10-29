package io.github.sceneview.demo.demos.internal

/** The four haptics Measure plays: the matching calls of `SceneViewHaptic`. */
enum class MeasureHaptic { Selection, Light, Success, Warning }

/** Every moment Measure answers with a haptic. */
enum class MeasureHapticEvent {
    /** The reticle locks onto something new, or onto the first point to close. */
    AimLocked,

    /** A point is added. */
    PointAdded,

    /** The tap closes an outline on its first point. */
    OutlineClosed,

    /** A tap that cannot act: the reason shows under the reticle. */
    Refused,

    /** Set height locks the rising walls. */
    HeightLocked,

    /** Done ends a Distance chain. */
    ChainEnded,

    /** Another mode is picked on the pill. */
    ModeChanged,

    /** The first corner of an opening is placed on a wall. */
    OpeningCorner,

    /** The second corner places the opening. */
    OpeningAdded,
}

/** The haptic of each [event]. */
fun measureHaptic(event: MeasureHapticEvent): MeasureHaptic = when (event) {
    MeasureHapticEvent.AimLocked, MeasureHapticEvent.ModeChanged -> MeasureHaptic.Selection
    MeasureHapticEvent.PointAdded, MeasureHapticEvent.OpeningCorner -> MeasureHaptic.Light
    MeasureHapticEvent.OutlineClosed, MeasureHapticEvent.HeightLocked, MeasureHapticEvent.ChainEnded,
    MeasureHapticEvent.OpeningAdded -> MeasureHaptic.Success
    MeasureHapticEvent.Refused -> MeasureHaptic.Warning
}

/**
 * The tick of a new aim: the reticle locked onto a snap of [kind], or onto the first point
 * ([closes]). Played each time either changes, so `null` aims and plain hits stay silent.
 * [covered]: a page is open over the camera, which stays silent under it.
 */
fun measureAimHapticEvent(kind: MeasureSnapKind?, closes: Boolean?, covered: Boolean = false): MeasureHapticEvent? =
    MeasureHapticEvent.AimLocked.takeIf {
        !covered && (closes == true || (kind ?: MeasureSnapKind.None) != MeasureSnapKind.None)
    }

/** The haptic event of a tap that placed a point: closing the outline, or a plain point. */
fun measurePointHapticEvent(closes: Boolean): MeasureHapticEvent =
    if (closes) MeasureHapticEvent.OutlineClosed else MeasureHapticEvent.PointAdded
