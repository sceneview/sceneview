package io.github.sceneview.demo.demos.internal

import kotlin.math.roundToInt

/** Where the capture is, of its three steps: the step row under the shutter says it. */
enum class ScanStep(val label: String) {
    Scan("Scan"),
    Review("Review"),
    Model("Model"),
    ;

    companion object {
        fun of(phase: ScanPhase): ScanStep = when (phase) {
            ScanPhase.Scanning, ScanPhase.Paused -> Scan
            ScanPhase.Reviewing -> Review
            ScanPhase.Saving, ScanPhase.SaveFailed -> Model
        }
    }
}

/**
 * What stops the scan from adding anything, and the one thing that gets out of it. Said as a
 * banner, never as a "Next" line: a blocker is not a step.
 */
enum class ScanBlocker(val cause: String, val wayOut: String) {
    TrackingLost("Tracking lost", "Point at an area you already scanned"),
    TooDark("Too dark to track", "Turn on a light or move somewhere brighter"),
    TooFast("Moving too fast", "Slow down until this goes away"),
    LowTexture("Too little detail here", "Move closer to the surface"),
    NoDepth("No depth yet", "Keep the lens clear and move slowly"),
    DepthLost("Depth lost", "Keep the lens clear and move slowly"),
    Full("This scan is full", "Tap Stop and review to keep what you have"),
}

/** The capture's one message at the bottom: what blocks it, or else where to go next. */
sealed interface CaptureCue {
    data class Blocked(val blocker: ScanBlocker) : CaptureCue
    data class Next(val text: String) : CaptureCue
}

/** What the capture's haptics follow: a blocker showing, and the room completing. */
data class CaptureChromeState(val blocker: ScanBlocker?, val complete: Boolean) {
    companion object {
        fun of(cue: CaptureCue?, complete: Boolean): CaptureChromeState =
            CaptureChromeState((cue as? CaptureCue.Blocked)?.blocker, complete)
    }
}

/** The haptic a change of [CaptureChromeState] plays: one per change that matters, never per line. */
enum class ScanHaptic { Blocked, Cleared, Complete }

/** How a room came to complete: its floor covered, or covered as far as the room's shape lets it. */
enum class RoomDone { Covered, Plateau }

/**
 * A room's completion over a scan, held once reached: a floor share wobbling round
 * [CaptureChrome.FLOOR_DONE_SHARE] does not take it back. The floor map's reference is a convex
 * hull, which an L-shaped room never fills (6 x 6 m short of a 4 x 4 m corner tops out near 71 %):
 * walls closed over a floor at least [CaptureChrome.FLOOR_PLATEAU_MIN] covered that has stopped
 * growing for [stallMs] of scanning complete it too, as [RoomDone.Plateau].
 */
class RoomCompletion(private val stallMs: Long = CaptureChrome.FLOOR_STALL_MS) {

    /** The completion reached, if any: held for the rest of the scan. */
    var done: RoomDone? = null
        private set

    private var best = 0f
    private var since: Long? = null

    /** One look at the room at [nowMs]; [scanning] is false while paused or without tracking. */
    fun update(wallsDone: Boolean, floorShare: Float, scanning: Boolean, nowMs: Long): RoomDone? {
        done?.let { return it }
        if (CaptureChrome.complete(wallsDone, floorShare)) return RoomDone.Covered.also { done = it }
        val started = since
        when {
            !wallsDone || !scanning || started == null || floorShare > best + PROGRESS -> since = nowMs
            floorShare >= CaptureChrome.FLOOR_PLATEAU_MIN && nowMs - started >= stallMs -> done = RoomDone.Plateau
        }
        best = maxOf(best, floorShare)
        return done
    }

    private companion object {
        /** Floor share gained that counts as the floor still growing. */
        const val PROGRESS = 0.01f
    }
}

/**
 * The top and bottom of the 3D Scan capture screen, said in one voice: the state at the top, one
 * cue at the bottom, the primary action named after the step it opens, and a room "complete" only
 * when the floor map has nothing left hatched as still to scan.
 */
object CaptureChrome {

    /**
     * The floor share ([CoverageGrid.coveredShareOf] of the map's extent) past which the map's
     * hatching is down to scraps: the room can be called complete once its walls also close it.
     */
    const val FLOOR_DONE_SHARE = 0.8f

    /** Floor share under which a floor that stopped growing is still too bare to call the room done. */
    const val FLOOR_PLATEAU_MIN = 0.5f

    /** How long a floor past [FLOOR_PLATEAU_MIN] stays still, while scanning, before the room is done. */
    const val FLOOR_STALL_MS = 10_000L

    /** The least time between two haptics, the room's completion included. */
    const val HAPTIC_GAP_MS = 1_000L

    /** The gauge's top short of a complete room: never full, never green, before it is. */
    private const val SHORT_OF_DONE = 0.95f

    const val NEXT = "Next"
    const val STOP_AND_REVIEW = "Stop and review"
    const val BUILD_MODEL = "Build 3D model"
    const val REVIEW_DETAIL = "Build the 3D model and save it on this phone, or keep scanning to add more."
    const val PAUSED_NEXT = "Tap Resume to scan again — nothing is captured while paused"
    const val IN_REVIEW = "In review"
    const val BUILDING = "Building the model"
    const val NOT_SAVED = "Not saved"
    const val NOTHING_COVERED = "Nothing covered yet"
    const val FLOOR_MOSTLY = "Floor mostly covered — tap Stop and review"

    /** A room the walls close and the floor covers: the moment to stop, and the only one. */
    fun complete(wallsDone: Boolean, floorShare: Float): Boolean = wallsDone && floorShare >= FLOOR_DONE_SHARE

    /**
     * The room gauge's share: [completion] ([ScanGuide.completion]) short of full until the room is
     * [complete]. Walls closed over a floor still hatched read the walls' part plus the floor
     * covered so far.
     */
    fun share(
        completion: Float,
        wallsDone: Boolean,
        floorShare: Float,
        complete: Boolean = complete(wallsDone, floorShare),
    ): Float = when {
        complete -> 1f
        wallsDone -> {
            val floor = (floorShare / FLOOR_DONE_SHARE).coerceIn(0f, 1f)
            (ScanGuide.WALLS_SHARE + (1f - ScanGuide.WALLS_SHARE) * floor).coerceAtMost(SHORT_OF_DONE)
        }
        else -> completion.coerceIn(0f, SHORT_OF_DONE)
    }

    /**
     * The coaching line ([ScanCopy.coaching], [ScanScopeCopy.coaching]) as the capture's one cue:
     * what stops the scan is a [ScanBlocker], everything else the next step. [dark] is ARCore's
     * own reason for losing tracking: too little light.
     */
    fun cue(line: String?, dark: Boolean = false): CaptureCue? = when (line) {
        null -> null
        ScanCopy.TRACKING_LOST -> CaptureCue.Blocked(if (dark) ScanBlocker.TooDark else ScanBlocker.TrackingLost)
        ScanCopy.COACH_SLOWER -> CaptureCue.Blocked(ScanBlocker.TooFast)
        ScanCopy.COACH_LOW_TEXTURE -> CaptureCue.Blocked(ScanBlocker.LowTexture)
        ScanCopy.COACH_NO_DEPTH -> CaptureCue.Blocked(ScanBlocker.NoDepth)
        ScanCopy.COACH_DEPTH_LOST -> CaptureCue.Blocked(ScanBlocker.DepthLost)
        ScanCopy.SCAN_FULL -> CaptureCue.Blocked(ScanBlocker.Full)
        ScanCopy.COACH_PAUSED -> CaptureCue.Next(PAUSED_NEXT)
        else -> CaptureCue.Next(line)
    }

    /**
     * The room's coaching [line] once [done]: a room complete as far as its floor goes says so,
     * instead of [ScanCopy.ROOM_DONE]'s "complete".
     */
    fun roomLine(line: String?, done: RoomDone?): String? =
        if (done == RoomDone.Plateau && line == ScanCopy.ROOM_DONE) FLOOR_MOSTLY else line

    /** `Next: Walk over the hatched floor`. */
    fun nextLine(text: String): String = "$NEXT: $text"

    /** The step row, said aloud: `Step 2 of 3: Review`. */
    fun stepDescription(step: ScanStep): String = "Step ${step.ordinal + 1} of ${ScanStep.entries.size}: ${step.label}"

    /**
     * The capture's top line, the useful state: `Room · 72 % scanned`, `Area · 12 m² covered`,
     * `Room · Stuck`, `Room · Tracking lost` while [trackingLost] holds the capture; past the
     * capture, what is happening (`Room · Building the model`).
     */
    fun headline(
        label: String?,
        phase: ScanPhase,
        activity: ScanActivity,
        roomShare: Float? = null,
        covered: Float? = null,
        trackingLost: Boolean = false,
    ): String {
        val state = when {
            phase == ScanPhase.Reviewing -> IN_REVIEW
            phase == ScanPhase.Saving -> BUILDING
            phase == ScanPhase.SaveFailed -> NOT_SAVED
            trackingLost -> ScanBlocker.TrackingLost.cause
            activity == ScanActivity.Paused -> ScanCopy.PAUSED
            activity == ScanActivity.Stuck -> ScanCopy.STUCK
            roomShare != null -> "${(roomShare.coerceIn(0f, 1f) * PERCENT).roundToInt()} % scanned"
            covered != null && covered > 0f -> "${ScanAreaCopy.area(covered)} covered"
            covered != null -> NOTHING_COVERED
            else -> ScanCopy.SCANNING
        }
        return label?.let { "$it · $state" } ?: state
    }

    /** The second line: `3 of 4 walls · 0:42` on a room, `0:42 · 1,234 points captured` elsewhere. */
    fun detail(clock: String, walls: Int?, points: Int): String = when {
        walls == null -> "$clock · ${ScanCopy.pointsCaptured(points)}"
        walls <= 0 -> "${ScanCopy.WALLS_NONE} · $clock"
        walls <= ScanGuide.ROOM_WALLS -> "$walls of ${ScanGuide.ROOM_WALLS} walls · $clock"
        else -> "${ScanCopy.wallsFound(walls)} · $clock"
    }

    /** The haptic from [before] to [after]: a blocker showing or changing, clearing, the room completing. */
    fun haptic(before: CaptureChromeState, after: CaptureChromeState): ScanHaptic? = when {
        after.blocker != null && after.blocker != before.blocker -> ScanHaptic.Blocked
        after.blocker == null && before.blocker != null -> ScanHaptic.Cleared
        after.complete && !before.complete -> ScanHaptic.Complete
        else -> null
    }

    /**
     * The haptic to play now, if any: [haptic], at least [HAPTIC_GAP_MS] after the last one played
     * at [playedAtMs] — the room's completion included — and none in QA mode.
     */
    fun hapticToPlay(
        before: CaptureChromeState,
        after: CaptureChromeState,
        nowMs: Long,
        playedAtMs: Long,
        qaMode: Boolean,
    ): ScanHaptic? = haptic(before, after)?.takeIf { !qaMode && nowMs - playedAtMs >= HAPTIC_GAP_MS }

    /** How long a haptic due at [nowMs] waits for [HAPTIC_GAP_MS] to pass since [playedAtMs]. */
    fun hapticWaitMs(nowMs: Long, playedAtMs: Long): Long = (HAPTIC_GAP_MS - (nowMs - playedAtMs)).coerceAtLeast(0L)

    private const val PERCENT = 100
}
