package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

class PoseContinuityTest {

    @Test
    fun `a walk and a turn at hand-held speeds always integrate`() {
        val guard = PoseContinuity()
        var t = 0L
        // 1.4 m/s walking, then a 100°/s turn on the spot, at 30 Hz.
        repeat(90) { i ->
            assertEquals(PoseStep.Continuous, guard.step(t, pose(x = 1.4f * i / FPS)))
            t += FRAME
        }
        repeat(90) { i ->
            assertEquals(PoseStep.Continuous, guard.step(t, pose(x = 1.4f * 89 / FPS, yawDegrees = 100f * i / FPS)))
            t += FRAME
        }
        assertEquals(0, guard.corrections + guard.slips)
    }

    @Test
    fun `a small one-frame shift is a drift correction and passes`() {
        val guard = PoseContinuity()
        guard.step(0L, pose())
        assertEquals(PoseStep.Corrected, guard.step(FRAME, pose(z = 0.18f, yawDegrees = 3f)))
        assertEquals(PoseStep.Continuous, guard.step(2 * FRAME, pose(z = 0.19f, yawDegrees = 3f)))
        assertFalse(guard.isSlipped)
    }

    @Test
    fun `a slip holds everything until a jump undoes it`() {
        val guard = PoseContinuity()
        var t = 0L
        guard.step(t, pose(x = 1f))
        t += FRAME
        // The world turns 86° about the origin in one frame: the phone lands 1.4 m away.
        val slipped = { p: Pose -> p.turnedAboutOrigin(86f) }
        assertEquals(PoseStep.Slipped, guard.step(t, slipped(Pose(x = 1f))))
        assertTrue(guard.isSlipped)
        // Walking on, in the slipped world: held, every frame.
        repeat(60) { i ->
            t += FRAME
            assertEquals(PoseStep.Held, guard.step(t, slipped(Pose(x = 1f, z = 0.01f * i))))
        }
        assertFalse("2 s held is not stuck yet", guard.isStuck)
        // The world comes back: the pose lands where the phone really is.
        t += FRAME
        assertEquals(PoseStep.Recovered, guard.step(t, pose(x = 1f, z = 0.6f)))
        assertFalse(guard.isSlipped)
        t += FRAME
        assertEquals(PoseStep.Continuous, guard.step(t, pose(x = 1f, z = 0.61f)))
    }

    @Test
    fun `a slip that never comes back is stuck after four seconds`() {
        val guard = PoseContinuity()
        var t = 0L
        guard.step(t, pose())
        t += FRAME
        assertEquals(PoseStep.Slipped, guard.step(t, pose(x = 0.5f, yawDegrees = 30f)))
        while (t < 3_900_000_000L) {
            t += FRAME
            assertEquals(PoseStep.Held, guard.step(t, pose(x = 0.5f, yawDegrees = 30f)))
            assertFalse(guard.isStuck)
        }
        t += 200_000_000L
        guard.step(t, pose(x = 0.5f, yawDegrees = 30f))
        assertTrue(guard.isStuck)
        guard.reset()
        assertFalse(guard.isSlipped)
        assertEquals(PoseStep.Continuous, guard.step(t + FRAME, pose(x = 3f)))
    }

    @Test
    fun `a second slip that does not undo the first keeps the scan held`() {
        val guard = PoseContinuity()
        guard.step(0L, pose())
        assertEquals(PoseStep.Slipped, guard.step(FRAME, pose(x = 0.6f)))
        assertEquals(PoseStep.Slipped, guard.step(2 * FRAME, pose(x = 1.2f)))
        assertEquals(PoseStep.Held, guard.step(3 * FRAME, pose(x = 1.2f)))
        // Both undone at once.
        assertEquals(PoseStep.Recovered, guard.step(4 * FRAME, pose(x = 0.01f)))
    }

    @Test
    fun `tracking found again where the phone really is ends a slip`() {
        val guard = PoseContinuity()
        guard.step(0L, pose())
        assertEquals(PoseStep.Slipped, guard.step(FRAME, pose(x = 0.6f, yawDegrees = 40f)))
        // Two seconds without a tracked frame, then ARCore puts the phone back, slowly enough
        // over the gap not to read as a jump.
        assertEquals(PoseStep.Recovered, guard.step(FRAME + 2_000_000_000L, pose(x = 0.05f)))
    }

    @Test
    fun `the same frame twice is read once`() {
        val guard = PoseContinuity()
        guard.step(FRAME, pose())
        assertEquals(PoseStep.Continuous, guard.step(FRAME, pose(x = 5f)))
        assertEquals(PoseStep.Continuous, guard.step(2 * FRAME, pose(x = 0.01f)))
    }

    /**
     * The walk of 2026-11-10 (Pixel 9, 132 s) replayed, for a machine that has it:
     * `SCENEVIEW_SCAN_CORPUS` names a folder holding its `capture-session.jsonl`. ARCore
     * teleported the pose in one frame four times while reporting TRACKING.
     */
    @Test
    fun `the recorded walk flags its four jumps and nothing else`() {
        val corpus = System.getenv("SCENEVIEW_SCAN_CORPUS")?.let(::File)
        val log = corpus?.walkTopDown()?.firstOrNull { it.name == "capture-session.jsonl" }
        assumeTrue("SCENEVIEW_SCAN_CORPUS has no capture-session.jsonl", log != null)
        val poses = checkNotNull(log).useLines { lines ->
            parseArDebugLog(lines).filterIsInstance<ArDebugEvent.CameraPose>()
        }
        assumeTrue("not the walk of 2026-11-10", poses.size == WALK_POSES)
        val start = poses.first().nanos
        val guard = PoseContinuity()
        val flagged = ArrayList<Pair<Float, PoseStep>>()
        val held = ArrayList<Float>()
        var stuck = false
        for (event in poses) {
            val seconds = (event.nanos - start) / 1e9f
            val step = guard.step(event.nanos, event.pose)
            if (step != PoseStep.Continuous && step != PoseStep.Held) flagged += seconds to step
            if (!step.integrates) held += seconds
            stuck = stuck || guard.isStuck
        }
        println("PoseContinuity on the walk: $flagged, ${held.size} frames held")
        val expected = listOf(
            33.3f to PoseStep.Corrected,
            58.2f to PoseStep.Slipped,
            61.1f to PoseStep.Recovered,
            128.8f to PoseStep.Slipped,
        )
        assertEquals("$flagged", expected.size, flagged.size)
        for ((at, step) in expected) {
            assertTrue("$step at $at s in $flagged", flagged.any { it.second == step && kotlin.math.abs(it.first - at) < 0.1f })
        }
        // Held from 58.2 s to 61.1 s, and from 128.8 s to the end, nowhere else.
        assertTrue(held.all { it in 58.2f..61.1f || it >= 128.7f })
        assertTrue("about 3 s of a slip held", held.count { it < 62f } in 150..180)
        assertFalse("never held long enough to offer a restart", stuck)
    }

    /** A pose: position, and a yaw about the vertical. */
    private data class Pose(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val yawDegrees: Float = 0f) {
        fun debug(): DebugPose {
            val half = Math.toRadians(yawDegrees / 2.0)
            return DebugPose(x, y, z, 0f, sin(half).toFloat(), 0f, cos(half).toFloat())
        }

        /** The same pose with the whole world turned [degrees] about the vertical through the origin. */
        fun turnedAboutOrigin(degrees: Float): DebugPose {
            val a = Math.toRadians(degrees.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            return Pose(c * x + s * z, y, -s * x + c * z, yawDegrees + degrees).debug()
        }
    }

    private fun pose(x: Float = 0f, y: Float = 0f, z: Float = 0f, yawDegrees: Float = 0f) =
        Pose(x, y, z, yawDegrees).debug()

    private companion object {
        const val FPS = 30f
        const val FRAME = 33_333_333L
        const val WALK_POSES = 7491
    }
}
