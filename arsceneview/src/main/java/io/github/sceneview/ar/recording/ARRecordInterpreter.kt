package io.github.sceneview.ar.recording

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * Replays an **AR Record** dataset and interprets what happened during the session —
 * the analysis half of the record/playback loop (#1441).
 *
 * ### What "interpretation" means here
 *
 * [ARRecorder] captures a real ARCore session into an MP4 and
 * [io.github.sceneview.ar.ARSceneView]'s `playbackDataset` parameter replays it 1:1.
 * Replay alone just re-renders the camera feed — it does not tell you *whether the
 * session tracked well*. `ARRecordInterpreter` closes that gap: feed it every frame of
 * a replay and it accumulates an [ARRecordInterpretation] — a quantified summary of the
 * camera trajectory, the tracking-quality timeline, and the planes ARCore discovered.
 *
 * This is the deterministic, desk-side counterpart to on-device QA: record a hard
 * tracking stress-case once (e.g. the in-car drive of #1441), then replay + interpret it
 * on every CI run and assert the metrics never regress.
 *
 * ### What it computes
 *
 * From each replayed [Frame] the interpreter reads only the camera pose and the
 * trackables ARCore already attached to the frame — no extra ARCore work, no I/O:
 *
 *  - **Trajectory** — total path length the device camera travelled and the axis-aligned
 *    bounding extent of that path. Surfaces a stationary capture vs a walk-through vs a
 *    fast drive at a glance.
 *  - **Tracking quality** — how many frames tracked vs were lost, and, for the lost
 *    frames, a per-[TrackingFailureReason] breakdown. A high `INSUFFICIENT_FEATURES`
 *    share points at a textureless scene; a high `EXCESSIVE_MOTION` share points at a
 *    capture that moved too fast (the classic in-car failure mode).
 *  - **Planes** — the count of distinct horizontal / vertical planes seen and their
 *    combined area, so a "did ARCore find the ground?" question has a numeric answer.
 *
 * ### Usage
 *
 * ```kotlin
 * val interpreter = rememberARRecordInterpreter()
 * ARSceneView(
 *     playbackDataset = recordedDataset,
 *     onSessionUpdated = { session, frame -> interpreter.ingest(session, frame) },
 *     content = { /* … */ }
 * )
 * // When the dataset reaches its end (rememberARPlaybackStatus == FINISHED):
 * val report = interpreter.interpretation
 * println("tracked ${report.trackedFrameRatio * 100}% of ${report.frameCount} frames")
 * ```
 *
 * The same instance can interpret several datasets in a row — call [reset] between them.
 *
 * ### Threading
 *
 * [ingest] must be called from `onSessionUpdated`, i.e. the render thread that owns the
 * ARCore [Frame]. The accumulated [interpretation] is published through a Compose
 * `MutableState`, so a UI-thread reader observing it recomposes correctly. The interpreter
 * does **no** Filament JNI work, so there is no main-thread constraint beyond the one
 * `onSessionUpdated` already imposes.
 *
 * @see ARRecorder
 * @see io.github.sceneview.ar.rerun.RerunBridge
 */
public class ARRecordInterpreter {

    private val core = ARRecordInterpreterCore()

    private var _interpretation by mutableStateOf(ARRecordInterpretation.EMPTY)

    /**
     * The interpretation accumulated so far. Backed by a Compose `MutableState`, so a
     * composable reading it recomposes as new frames are ingested. Equals
     * [ARRecordInterpretation.EMPTY] until the first [ingest] call (or after [reset]).
     */
    public val interpretation: ARRecordInterpretation
        get() = _interpretation

    /**
     * Ingest one replayed AR frame. Call from `onSessionUpdated` with the `session` and
     * `frame` ARCore hands you.
     *
     * Only meaningful while a `playbackDataset` is bound — on a **live** session this
     * still works (it just interprets the live feed), but the point of the API is the
     * deterministic replay. The call is cheap: it reads the camera pose and the frame's
     * already-attached plane trackables, then folds them into the running summary. No new
     * ARCore acquire calls, no allocation beyond the immutable summary snapshot.
     *
     * JNI-backed getters ([Frame.getCamera], [Frame.getUpdatedTrackables]) can throw if the
     * session handle closes concurrently; such [RuntimeException]s are swallowed and the
     * frame is skipped. JVM [Error]s propagate unchanged.
     *
     * @param session the ARCore session (currently unused beyond presence; accepted to
     *   match the stateless `recordFrame(session, frame)` / `logFrame(session, frame)`
     *   side-channel shape used elsewhere in `arsceneview`).
     * @param frame the frame from `onSessionUpdated`.
     */
    @Suppress("UNUSED_PARAMETER")
    public fun ingest(session: Session, frame: Frame) {
        val sample: FrameSample = try {
            frameSampleOf(frame)
        } catch (_: RuntimeException) {
            // A JNI-backed getter threw because the session handle closed concurrently —
            // skip this frame. JVM Errors are not caught and propagate unchanged.
            return
        }
        _interpretation = core.fold(sample)
    }

    /**
     * Discard all accumulated state so the next [ingest] starts a fresh interpretation.
     * Use between datasets when one [ARRecordInterpreter] interprets several recordings.
     * After this, [interpretation] is [ARRecordInterpretation.EMPTY] again.
     */
    public fun reset() {
        core.reset()
        _interpretation = ARRecordInterpretation.EMPTY
    }

    /**
     * Read the camera pose + plane trackables off a [Frame] into a plain
     * [FrameSample]. Isolated so [ingest]'s pure folding logic ([ARRecordInterpreterCore])
     * never touches an un-mockable native ARCore type — the fold is unit-tested directly
     * against hand-built [FrameSample]s.
     */
    private fun frameSampleOf(frame: Frame): FrameSample {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val pose = camera.pose
        val failure: TrackingFailureReason? =
            camera.trackingFailureReason.takeIf { it != TrackingFailureReason.NONE }
        val planes: List<PlaneSample> = frame
            .getUpdatedTrackables(com.google.ar.core.Plane::class.java)
            .asSequence()
            .filter { it.trackingState == TrackingState.TRACKING }
            // Subsumed planes are merged into their parent — counting both double-counts
            // area and plane count, so drop the subsumed child (matches RerunBridge).
            .filter { it.subsumedBy == null }
            .map { plane ->
                PlaneSample(
                    id = System.identityHashCode(plane),
                    extentX = plane.extentX,
                    extentZ = plane.extentZ,
                    vertical = plane.type != com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING &&
                        plane.type != com.google.ar.core.Plane.Type.HORIZONTAL_DOWNWARD_FACING,
                )
            }
            .toList()
        return FrameSample(
            timestampNanos = frame.timestamp,
            tracking = tracking,
            failureReason = failure,
            cameraX = pose.tx(),
            cameraY = pose.ty(),
            cameraZ = pose.tz(),
            planes = planes,
        )
    }
}

/**
 * Creates and remembers an [ARRecordInterpreter] tied to the composable lifecycle (#1441).
 *
 * The interpreter holds no native resources, so there is nothing to release on dispose —
 * the [DisposableEffect] is purely a marker so the lifecycle ownership reads the same as
 * [rememberARRecorder].
 */
@Composable
public fun rememberARRecordInterpreter(): ARRecordInterpreter {
    val interpreter = remember { ARRecordInterpreter() }
    DisposableEffect(interpreter) {
        onDispose { /* no native resources to free */ }
    }
    return interpreter
}

/**
 * A single replayed AR frame reduced to the plain values [ARRecordInterpreterCore] needs.
 *
 * Decouples the pure folding logic from ARCore's final native [Frame] / [com.google.ar.core.Camera]
 * / [com.google.ar.core.Pose] types (which can't be mocked without mockito-inline), so the
 * interpretation math is unit-testable on plain JVM.
 *
 * @property timestampNanos the frame timestamp in nanoseconds (`Frame.getTimestamp()`).
 * @property tracking `true` if the camera was [TrackingState.TRACKING] this frame.
 * @property failureReason the [TrackingFailureReason] when not tracking, or `null` when
 *   tracking (ARCore reports `NONE` while tracking; this maps `NONE` to `null`).
 * @property cameraX camera world-space X translation.
 * @property cameraY camera world-space Y translation.
 * @property cameraZ camera world-space Z translation.
 * @property planes the planes ARCore had attached to the frame, already de-subsumed.
 */
public data class FrameSample(
    val timestampNanos: Long,
    val tracking: Boolean,
    val failureReason: TrackingFailureReason?,
    val cameraX: Float,
    val cameraY: Float,
    val cameraZ: Float,
    val planes: List<PlaneSample> = emptyList(),
)

/**
 * A single ARCore plane reduced to the values the interpreter aggregates.
 *
 * @property id a stable identity for the plane within one interpretation, used to count
 *   *distinct* planes across frames (a plane reappears every frame it is updated).
 * @property extentX the plane's X extent in metres (`Plane.getExtentX()`).
 * @property extentZ the plane's Z extent in metres (`Plane.getExtentZ()`).
 * @property vertical `true` for a vertical plane, `false` for a horizontal one.
 */
public data class PlaneSample(
    val id: Int,
    val extentX: Float,
    val extentZ: Float,
    val vertical: Boolean,
)

/**
 * Quantified interpretation of a replayed AR Record (#1441) — the output of
 * [ARRecordInterpreter].
 *
 * Every field is a plain number so it can be asserted in a CI regression test, printed in
 * a debug overlay, or logged. [EMPTY] is the zero value before any frame is ingested.
 *
 * @property frameCount total frames ingested.
 * @property trackedFrameCount frames where the camera was [TrackingState.TRACKING].
 * @property durationSeconds wall-clock span of the dataset (last minus first timestamp).
 * @property trajectoryLengthMeters total path length the camera travelled, summed over
 *   consecutive **tracked** frames (lost-frame jumps are not counted as travel).
 * @property trajectoryExtentMeters the diagonal of the axis-aligned bounding box of every
 *   tracked camera position — how far the capture roamed end to end.
 * @property failureReasonFrameCounts per-[TrackingFailureReason] count of lost frames.
 *   Only non-zero reasons appear. Empty when the whole dataset tracked.
 * @property horizontalPlaneCount distinct horizontal planes ARCore discovered.
 * @property verticalPlaneCount distinct vertical planes ARCore discovered.
 * @property planeAreaMeters2 combined area of every distinct plane, using each plane's
 *   largest observed extent.
 */
public data class ARRecordInterpretation(
    val frameCount: Int,
    val trackedFrameCount: Int,
    val durationSeconds: Double,
    val trajectoryLengthMeters: Float,
    val trajectoryExtentMeters: Float,
    val failureReasonFrameCounts: Map<TrackingFailureReason, Int>,
    val horizontalPlaneCount: Int,
    val verticalPlaneCount: Int,
    val planeAreaMeters2: Float,
) {
    /**
     * Fraction of ingested frames that tracked, in `0.0 .. 1.0`. `0.0` for an empty
     * interpretation. A value well below `1.0` flags a hard tracking dataset — inspect
     * [failureReasonFrameCounts] for the dominant cause.
     */
    public val trackedFrameRatio: Double
        get() = if (frameCount == 0) 0.0 else trackedFrameCount.toDouble() / frameCount

    /** Total distinct planes discovered — [horizontalPlaneCount] + [verticalPlaneCount]. */
    public val planeCount: Int
        get() = horizontalPlaneCount + verticalPlaneCount

    public companion object {
        /** The zero interpretation — before any frame is ingested, or after a [reset]. */
        public val EMPTY: ARRecordInterpretation = ARRecordInterpretation(
            frameCount = 0,
            trackedFrameCount = 0,
            durationSeconds = 0.0,
            trajectoryLengthMeters = 0f,
            trajectoryExtentMeters = 0f,
            failureReasonFrameCounts = emptyMap(),
            horizontalPlaneCount = 0,
            verticalPlaneCount = 0,
            planeAreaMeters2 = 0f,
        )
    }
}

/**
 * Pure, ARCore-free folding core behind [ARRecordInterpreter] (#1441).
 *
 * Accumulates [FrameSample]s into an [ARRecordInterpretation]. Holds no Android or ARCore
 * type, so it is fully unit-testable on plain JVM — the value of splitting it out is that
 * every trajectory / tracking-ratio / plane-area calculation gets a deterministic test
 * without needing a device or a mocked native [Frame].
 *
 * Not thread-safe: [fold] is called from the single render thread that owns
 * `onSessionUpdated`, matching how [ARRecorder] and `RerunBridge` are driven.
 */
public class ARRecordInterpreterCore {

    private var frameCount = 0
    private var trackedFrameCount = 0
    private var firstTimestampNanos: Long? = null
    private var lastTimestampNanos: Long = 0L

    private var trajectoryLength = 0f
    private var hasPreviousTrackedPosition = false
    private var prevX = 0f
    private var prevY = 0f
    private var prevZ = 0f

    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY
    private var hasAnyTrackedPosition = false

    private val failureCounts = LinkedHashMap<TrackingFailureReason, Int>()

    /** Per-plane-id largest observed extent, so a plane that grows is not double-counted. */
    private val planeExtents = LinkedHashMap<Int, PlaneExtent>()

    /**
     * Fold one [sample] into the running interpretation and return the updated snapshot.
     *
     * The returned [ARRecordInterpretation] is an immutable value — callers may publish it
     * straight into a Compose `State` without defensive copying.
     */
    public fun fold(sample: FrameSample): ARRecordInterpretation {
        frameCount++

        if (firstTimestampNanos == null) firstTimestampNanos = sample.timestampNanos
        // Guard against a dataset whose timestamps are not monotonically increasing —
        // duration is the span of the extremes, never a negative diff.
        lastTimestampNanos = maxOf(lastTimestampNanos, sample.timestampNanos)

        if (sample.tracking) {
            trackedFrameCount++
            accumulateTrajectory(sample.cameraX, sample.cameraY, sample.cameraZ)
        } else {
            hasPreviousTrackedPosition = false
            sample.failureReason?.let { reason ->
                failureCounts[reason] = (failureCounts[reason] ?: 0) + 1
            }
        }

        for (plane in sample.planes) {
            val current = planeExtents[plane.id]
            planeExtents[plane.id] = PlaneExtent(
                extentX = maxOf(current?.extentX ?: 0f, plane.extentX),
                extentZ = maxOf(current?.extentZ ?: 0f, plane.extentZ),
                vertical = plane.vertical,
            )
        }

        return snapshot()
    }

    /** Reset every accumulator so the core can interpret a fresh dataset. */
    public fun reset() {
        frameCount = 0
        trackedFrameCount = 0
        firstTimestampNanos = null
        lastTimestampNanos = 0L
        trajectoryLength = 0f
        hasPreviousTrackedPosition = false
        prevX = 0f; prevY = 0f; prevZ = 0f
        minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY
        maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
        hasAnyTrackedPosition = false
        failureCounts.clear()
        planeExtents.clear()
    }

    private fun accumulateTrajectory(x: Float, y: Float, z: Float) {
        if (hasPreviousTrackedPosition) {
            val dx = x - prevX
            val dy = y - prevY
            val dz = z - prevZ
            trajectoryLength += sqrt(dx * dx + dy * dy + dz * dz)
        }
        prevX = x; prevY = y; prevZ = z
        hasPreviousTrackedPosition = true

        minX = minOf(minX, x); minY = minOf(minY, y); minZ = minOf(minZ, z)
        maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); maxZ = maxOf(maxZ, z)
        hasAnyTrackedPosition = true
    }

    private fun snapshot(): ARRecordInterpretation {
        val durationSeconds = firstTimestampNanos?.let { first ->
            (lastTimestampNanos - first).coerceAtLeast(0L) / 1_000_000_000.0
        } ?: 0.0

        val extent = if (hasAnyTrackedPosition) {
            val dx = maxX - minX
            val dy = maxY - minY
            val dz = maxZ - minZ
            sqrt(dx * dx + dy * dy + dz * dz)
        } else {
            0f
        }

        var horizontal = 0
        var vertical = 0
        var area = 0f
        for (plane in planeExtents.values) {
            if (plane.vertical) vertical++ else horizontal++
            area += plane.extentX * plane.extentZ
        }

        return ARRecordInterpretation(
            frameCount = frameCount,
            trackedFrameCount = trackedFrameCount,
            durationSeconds = durationSeconds,
            trajectoryLengthMeters = trajectoryLength,
            trajectoryExtentMeters = extent,
            failureReasonFrameCounts = LinkedHashMap(failureCounts),
            horizontalPlaneCount = horizontal,
            verticalPlaneCount = vertical,
            planeAreaMeters2 = area,
        )
    }

    private data class PlaneExtent(val extentX: Float, val extentZ: Float, val vertical: Boolean)
}
