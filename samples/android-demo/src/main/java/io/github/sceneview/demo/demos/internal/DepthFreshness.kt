package io.github.sceneview.demo.demos.internal

/**
 * Which of ARCore's raw depth images the dense scan fuses.
 *
 * ARCore makes about ten new raw-depth estimates a second. Between two estimates,
 * `Frame.acquireRawDepthImage16Bits()` hands back the last one reprojected to the current camera
 * pose, with the **same** `Image.getTimestamp()`: ARCore's documented way to tell a new estimate
 * is a timestamp that differs from the previous one. A reprojection has holes where the camera
 * now sees around an edge and smears the edges it moves, so the scan fuses each estimate once,
 * the first frame it shows up, and never its reprojections.
 *
 * The estimate's timestamp is the camera frame it was computed from, not the frame that hands it
 * over. With depth from motion it is computed off the camera thread and lands a frame or more
 * later, so `depth == frame` almost never holds: requiring it fused nothing on a Pixel 4a
 * (#4221: 97k points before, 0 after). A new estimate is therefore accepted up to
 * [MAX_AGE_NANOS] behind the frame — one raw-depth period, about three camera frames at 30 fps.
 * An estimate older than that has been reprojected over several frames already (the scan was
 * busy fusing, or ARCore stalled), and is dropped like any reprojection.
 *
 * The clock can also run the other way by a hair: on a Pixel 4a (#4729) every raw depth image
 * is stamped 0 to 2 ms **after** the camera frame that hands it over: a skew between two clocks
 * of the same capture, not a depth of the future. Rejecting it fused nothing in a 37 s room
 * scan. A depth up to [MAX_LEAD_NANOS] after its frame is therefore judged as lag 0: fused when
 * new, still never twice ([Verdict.ALREADY_FUSED]). Only a depth further ahead than that is
 * [Verdict.AHEAD], a clock the scan cannot trust.
 */
object DepthFreshness {
    /** 100 ms: ARCore's raw-depth period on a Pixel, ~3 camera frames at 30 fps. */
    const val MAX_AGE_NANOS = 100_000_000L

    /**
     * 5 ms: how far after its frame a depth may be stamped and still count as that frame's. The
     * Pixel 4a leads by up to 2 ms; 5 ms leaves headroom for that jitter while staying under a
     * third of a camera frame even at 60 fps (16.7 ms), so a depth stamped a frame ahead is
     * still refused.
     */
    const val MAX_LEAD_NANOS = 5_000_000L

    /**
     * Half a second with no fresh depth: five estimates refused in a row is ARCore's depth running
     * late, not a hiccup — [DepthWindow] then fuses new estimates past the window.
     */
    const val LATE_AFTER_NANOS = 500_000_000L

    /** Two seconds without depth to fuse: twenty missed keyframes, the scan is stuck. */
    const val STARVED_AFTER_NANOS = 2_000_000_000L

    /** Longer than this between two tracked frames, the scan was paused or lost track. */
    const val FRAME_GAP_NANOS = 500_000_000L

    /** What the scan does with one depth image, and why. */
    enum class Verdict {
        /** A new estimate, recent enough: fuse it. */
        FRESH,

        /** Not newer than the depth last fused: the same estimate reprojected, or an older one. */
        ALREADY_FUSED,

        /** A new estimate, but more than [MAX_AGE_NANOS] behind the frame. */
        STALE,

        /** Stamped more than [MAX_LEAD_NANOS] after the frame that hands it over. */
        AHEAD,
    }

    /**
     * The verdict on a depth stamped [depthNanos] acquired from the frame stamped [frameNanos],
     * the last fused depth being [lastFusedNanos] (`null` before the first). A depth up to
     * [maxLeadNanos] after its frame counts as lag 0. A frame clock that went back behind the last
     * fused depth by more than that lead (a session restarted into the same scan) forgets it,
     * rather than refusing every depth until the new clock catches up; within the lead it is the
     * same frame handing the same depth again, which stays [Verdict.ALREADY_FUSED].
     */
    fun judge(
        frameNanos: Long,
        depthNanos: Long,
        lastFusedNanos: Long?,
        maxAgeNanos: Long = MAX_AGE_NANOS,
        maxLeadNanos: Long = MAX_LEAD_NANOS,
    ): Verdict {
        val last = lastFusedNanos?.takeIf { frameNanos + maxLeadNanos >= it }
        return when {
            depthNanos - frameNanos > maxLeadNanos -> Verdict.AHEAD
            last != null && depthNanos <= last -> Verdict.ALREADY_FUSED
            frameNanos - depthNanos > maxAgeNanos -> Verdict.STALE
            else -> Verdict.FRESH
        }
    }
}

/**
 * The scan's side of [DepthFreshness] over time: which depth estimate it took last, and how long
 * it has gone without one ([starved]).
 *
 * The time window alone froze a Pixel 9 room scan for two minutes: from 1:32 on, the count stood
 * at 274,778 and "No depth yet" stayed up while the walls kept coming. ARCore still handed over
 * new estimates, but each was judged [DepthFreshness.Verdict.STALE] or
 * [DepthFreshness.Verdict.AHEAD] — depth from motion falling behind under load, or its clock
 * drifting from the camera's — and nothing in the scan ever widened the window again. Only a
 * tracking reset, which restarts ARCore's depth, had brought such a scan back ([DepthIntake]).
 *
 * So the window is a preference, not a wall: once the scan has waited [lateAfterNanos] with no
 * depth taken, a **new** estimate the window refused — its timestamp differs from the last one
 * taken, ARCore's own test of novelty — is taken anyway, and so is every new one after it until a
 * fresh one comes back ([late]). ARCore hands the estimate reprojected onto the current frame, so
 * it fuses at the current pose like any other; an estimate is still never taken twice.
 */
class DepthWindow(
    private val lateAfterNanos: Long = DepthFreshness.LATE_AFTER_NANOS,
    private val starvedAfterNanos: Long = DepthFreshness.STARVED_AFTER_NANOS,
    private val frameGapNanos: Long = DepthFreshness.FRAME_GAP_NANOS,
) {
    /** The timestamp of the last depth estimate taken, `null` before the first. */
    var lastTakenNanos: Long? = null
        private set

    /** Whether the scan is taking estimates the window refused: ARCore's depth runs late. */
    var late: Boolean = false
        private set

    /** Estimates taken past the window so far: a device log's figure. */
    var lateTaken: Int = 0
        private set

    private var lastFrameNanos = 0L
    private var waitingFromNanos = 0L

    /**
     * Notes a tracked frame at [frameNanos]. After a gap longer than [frameGapNanos] — a pause, a
     * lost track: no frame came, so no depth could — the wait for depth starts again, and "No
     * depth" does not flash on resuming.
     */
    fun frame(frameNanos: Long) {
        val gap = lastFrameNanos != 0L && frameNanos - lastFrameNanos > frameGapNanos
        lastFrameNanos = frameNanos
        if (waitingFromNanos == 0L || gap) waitingFromNanos = frameNanos
    }

    /**
     * Whether the depth stamped [depthNanos], handed over by the frame at [frameNanos], is taken;
     * [verdict] is [DepthFreshness.judge]'s on it against [lastTakenNanos].
     */
    fun take(verdict: DepthFreshness.Verdict, frameNanos: Long, depthNanos: Long): Boolean {
        val taken = when (verdict) {
            DepthFreshness.Verdict.FRESH -> true
            DepthFreshness.Verdict.ALREADY_FUSED -> false
            DepthFreshness.Verdict.STALE, DepthFreshness.Verdict.AHEAD ->
                depthNanos != lastTakenNanos && (late || frameNanos - waitingFromNanos >= lateAfterNanos)
        }
        if (!taken) return false
        late = verdict != DepthFreshness.Verdict.FRESH
        if (late) lateTaken++
        lastTakenNanos = depthNanos
        waitingFromNanos = frameNanos
        return true
    }

    /**
     * Whether the scan has gone [starvedAfterNanos] without a depth estimate to take, at the last
     * frame seen: ARCore gives none, so nothing is fused whatever the phone points at.
     */
    val starved: Boolean
        get() = waitingFromNanos != 0L && lastFrameNanos - waitingFromNanos > starvedAfterNanos
}
