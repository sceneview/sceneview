package io.github.sceneview.ar.recording

import com.google.ar.core.TrackingFailureReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [ARRecordInterpreterCore] — the pure folding core behind the
 * AR Record interpretation feature (#1441).
 *
 * These target [ARRecordInterpreterCore.fold] directly against hand-built [FrameSample]s,
 * so they need neither a device nor a mocked native ARCore [com.google.ar.core.Frame].
 * The ARCore-facing [ARRecordInterpreter.ingest] is a thin reader that converts a real
 * `Frame` into a [FrameSample] and delegates here — any interpretation bug surfaces in
 * these tests.
 */
class ARRecordInterpreterCoreTest {

    private val core = ARRecordInterpreterCore()

    private fun frame(
        timestampNanos: Long = 0L,
        tracking: Boolean = true,
        failureReason: TrackingFailureReason? = null,
        x: Float = 0f,
        y: Float = 0f,
        z: Float = 0f,
        planes: List<PlaneSample> = emptyList(),
    ) = FrameSample(
        timestampNanos = timestampNanos,
        tracking = tracking,
        failureReason = failureReason,
        cameraX = x, cameraY = y, cameraZ = z,
        planes = planes,
    )

    @Test
    fun `empty core yields the EMPTY interpretation`() {
        assertEquals(ARRecordInterpretation.EMPTY, ARRecordInterpreterCore().run {
            // no fold call — read the canonical empty value
            ARRecordInterpretation.EMPTY
        })
    }

    @Test
    fun `frame count and tracked count accumulate`() {
        core.fold(frame(tracking = true))
        core.fold(frame(tracking = false, failureReason = TrackingFailureReason.INSUFFICIENT_LIGHT))
        val result = core.fold(frame(tracking = true))

        assertEquals(3, result.frameCount)
        assertEquals(2, result.trackedFrameCount)
    }

    @Test
    fun `tracked frame ratio reflects tracked over total`() {
        repeat(3) { core.fold(frame(tracking = true)) }
        val result = core.fold(frame(tracking = false))

        // 3 tracked of 4 total
        assertEquals(0.75, result.trackedFrameRatio, 1e-9)
    }

    @Test
    fun `tracked frame ratio is zero for an empty interpretation`() {
        assertEquals(0.0, ARRecordInterpretation.EMPTY.trackedFrameRatio, 1e-9)
    }

    @Test
    fun `duration is the span between first and last timestamp`() {
        core.fold(frame(timestampNanos = 1_000_000_000L))
        core.fold(frame(timestampNanos = 2_500_000_000L))
        val result = core.fold(frame(timestampNanos = 4_000_000_000L))

        // 4.0s - 1.0s = 3.0s
        assertEquals(3.0, result.durationSeconds, 1e-9)
    }

    @Test
    fun `non-monotonic timestamps never produce a negative duration`() {
        core.fold(frame(timestampNanos = 5_000_000_000L))
        // an out-of-order earlier timestamp must not make duration negative
        val result = core.fold(frame(timestampNanos = 1_000_000_000L))

        assertTrue("duration must never be negative", result.durationSeconds >= 0.0)
        assertEquals(0.0, result.durationSeconds, 1e-9)
    }

    @Test
    fun `trajectory length sums distance over consecutive tracked frames`() {
        core.fold(frame(x = 0f, y = 0f, z = 0f))
        core.fold(frame(x = 3f, y = 0f, z = 0f)) // +3
        val result = core.fold(frame(x = 3f, y = 4f, z = 0f)) // +4

        assertEquals(7f, result.trajectoryLengthMeters, 1e-4f)
    }

    @Test
    fun `a lost frame breaks the trajectory so the jump is not counted as travel`() {
        core.fold(frame(x = 0f, y = 0f, z = 0f, tracking = true))
        // tracking lost — camera teleports while relocalizing
        core.fold(frame(x = 100f, y = 0f, z = 0f, tracking = false))
        // tracking regained at a far position — the 100m jump must NOT count
        core.fold(frame(x = 100f, y = 0f, z = 0f, tracking = true))
        val result = core.fold(frame(x = 102f, y = 0f, z = 0f, tracking = true)) // +2

        assertEquals(2f, result.trajectoryLengthMeters, 1e-4f)
    }

    @Test
    fun `trajectory extent is the diagonal of the bounding box of tracked positions`() {
        core.fold(frame(x = 0f, y = 0f, z = 0f))
        core.fold(frame(x = 1f, y = 2f, z = 2f))
        val result = core.fold(frame(x = -1f, y = 0f, z = 0f))

        // bbox: x in [-1,1] (2), y in [0,2] (2), z in [0,2] (2) -> sqrt(12)
        assertEquals(kotlin.math.sqrt(12f), result.trajectoryExtentMeters, 1e-4f)
    }

    @Test
    fun `failure reasons are counted per reason`() {
        core.fold(frame(tracking = false, failureReason = TrackingFailureReason.EXCESSIVE_MOTION))
        core.fold(frame(tracking = false, failureReason = TrackingFailureReason.EXCESSIVE_MOTION))
        val result = core.fold(
            frame(tracking = false, failureReason = TrackingFailureReason.INSUFFICIENT_FEATURES)
        )

        assertEquals(2, result.failureReasonFrameCounts[TrackingFailureReason.EXCESSIVE_MOTION])
        assertEquals(1, result.failureReasonFrameCounts[TrackingFailureReason.INSUFFICIENT_FEATURES])
    }

    @Test
    fun `a fully tracked dataset has no failure reasons`() {
        repeat(5) { core.fold(frame(tracking = true)) }
        val result = core.fold(frame(tracking = true))

        assertTrue(result.failureReasonFrameCounts.isEmpty())
    }

    @Test
    fun `planes are counted once across frames and split by orientation`() {
        val floor = PlaneSample(id = 1, extentX = 2f, extentZ = 3f, vertical = false)
        val wall = PlaneSample(id = 2, extentX = 1f, extentZ = 4f, vertical = true)

        // same planes reappear every frame they update
        core.fold(frame(planes = listOf(floor)))
        core.fold(frame(planes = listOf(floor, wall)))
        val result = core.fold(frame(planes = listOf(floor, wall)))

        assertEquals(1, result.horizontalPlaneCount)
        assertEquals(1, result.verticalPlaneCount)
        assertEquals(2, result.planeCount)
    }

    @Test
    fun `plane area uses the largest observed extent so a growing plane is not double-counted`() {
        // same plane id, growing extents across frames
        core.fold(frame(planes = listOf(PlaneSample(id = 1, extentX = 1f, extentZ = 1f, vertical = false))))
        core.fold(frame(planes = listOf(PlaneSample(id = 1, extentX = 2f, extentZ = 3f, vertical = false))))
        val result = core.fold(
            frame(planes = listOf(PlaneSample(id = 1, extentX = 1.5f, extentZ = 2f, vertical = false)))
        )

        // largest X = 2, largest Z = 3 -> area 6, counted once
        assertEquals(6f, result.planeAreaMeters2, 1e-4f)
        assertEquals(1, result.planeCount)
    }

    @Test
    fun `reset clears all accumulators back to EMPTY`() {
        core.fold(frame(x = 5f, tracking = true, planes = listOf(
            PlaneSample(id = 1, extentX = 2f, extentZ = 2f, vertical = false)
        )))
        core.fold(frame(tracking = false, failureReason = TrackingFailureReason.BAD_STATE))

        core.reset()
        val afterReset = core.fold(frame(tracking = true))

        assertEquals(1, afterReset.frameCount)
        assertEquals(1, afterReset.trackedFrameCount)
        assertEquals(0f, afterReset.trajectoryLengthMeters, 1e-4f)
        assertEquals(0, afterReset.planeCount)
        assertTrue(afterReset.failureReasonFrameCounts.isEmpty())
    }

    @Test
    fun `interpretation snapshot is a stable immutable value`() {
        val first = core.fold(frame(tracking = true))
        core.fold(frame(tracking = false, failureReason = TrackingFailureReason.INSUFFICIENT_LIGHT))

        // the earlier snapshot must not mutate after a later fold
        assertEquals(1, first.frameCount)
        assertTrue(first.failureReasonFrameCounts.isEmpty())
    }
}
