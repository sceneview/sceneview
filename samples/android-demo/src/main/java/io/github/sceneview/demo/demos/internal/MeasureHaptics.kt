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

/**
 * The tick of a phone lining up with what it is led to — 3D Scan's guide ([ScanGuidance]), on the
 * same [MeasureHapticEvent.AimLocked] as Measure's reticle locking on. Played as [aligned] turns on,
 * never twice within [minGapMs]: a phone swaying on the edge of the line-up, which the guide's
 * hysteresis already holds, still ticks at most once a second.
 */
class AlignTick(private val minGapMs: Long = MIN_GAP_MS) {
    private var was = false
    private var lastMs = Long.MIN_VALUE

    /** [aligned] at [nowMs]: [MeasureHapticEvent.AimLocked] when it just turned on, past the gap since the last tick. */
    fun update(aligned: Boolean, nowMs: Long): MeasureHapticEvent? {
        val rising = aligned && !was
        was = aligned
        if (!rising || (lastMs != Long.MIN_VALUE && nowMs - lastMs < minGapMs)) return null
        lastMs = nowMs
        return MeasureHapticEvent.AimLocked
    }

    companion object {
        /** At most one tick a second. */
        const val MIN_GAP_MS = 1_000L
    }
}
