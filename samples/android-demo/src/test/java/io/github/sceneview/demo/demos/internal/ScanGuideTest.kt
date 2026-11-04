package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The guide of a room's scan (#4540, QA-24): the heading and distance to its target, the words for
 * its side, the line-up and its tick, the target reached, and the floor ribbon — each with the
 * hysteresis that keeps a swaying phone from flipping it.
 */
class ScanGuideTest {
    /** At the origin, looking down -z: right is +x. */
    private val phone = DebugPose(0f, 1.4f, 0f)

    /** At [x], [z], turned [yawDeg] to the left about +Y. */
    private fun at(x: Float, z: Float, yawDeg: Float) =
        RerunSyntheticRoom.lookPose(Vec3(x, 1.4f, z), Math.toRadians(yawDeg.toDouble()).toFloat(), 0f)

    @Test
    fun `the heading is clockwise from where the phone faces, in each quadrant`() {
        val cases = listOf(
            floatArrayOf(1f, -1f) to 45f, // ahead right
            floatArrayOf(-1f, -1f) to -45f, // ahead left
            floatArrayOf(1f, 1f) to 135f, // behind right
            floatArrayOf(-1f, 1f) to -135f, // behind left
        )
        for ((p, degrees) in cases) {
            val h = ScanGuide.heading(phone, p[0], p[1])
            assertEquals("to (${p[0]}, ${p[1]})", degrees, h.degrees, 1e-3f)
            assertEquals(Math.sqrt(2.0).toFloat(), h.distance, 1e-4f)
        }
        assertEquals(ScanTurn.Right, ScanGuide.turnOf(45f, null))
        assertEquals(ScanTurn.Left, ScanGuide.turnOf(-45f, null))
        assertEquals(ScanTurn.Behind, ScanGuide.turnOf(135f, null))
        assertEquals(ScanTurn.Behind, ScanGuide.turnOf(-135f, null))
        // Pitched down to the floor, the phone still faces where its forward points.
        val down = RerunSyntheticRoom.lookPose(Vec3(0f, 1.4f, 0f), 0f, Math.toRadians(-60.0).toFloat())
        assertEquals(45f, ScanGuide.heading(down, 1f, -1f).degrees, 1e-3f)
        // Turned a quarter to the left, what was on the left is ahead.
        assertEquals(0f, ScanGuide.heading(at(0f, 0f, 90f), -2f, 0f).degrees, 1e-3f)
    }

    @Test
    fun `a target right behind is half a turn, never minus half`() {
        val h = ScanGuide.heading(phone, 0f, 2f)
        assertEquals(180f, h.degrees, 1e-3f)
        assertEquals(2f, h.distance, 1e-5f)
        assertEquals(ScanTurn.Behind, ScanGuide.turnOf(h.degrees, null))
        val guide = ScanGuidance()
        guide.update(phone, ScanTarget(0f, 2.5f), 0L)
        assertEquals(ScanGuideHint(ScanTurn.Behind, 2.5f, fix = false), guide.hint)
        assertTrue(guide.ribbon != null)
    }

    @Test
    fun `the words keep their side through a sway across a boundary`() {
        assertEquals(ScanTurn.Ahead, ScanGuide.turnOf(35f, ScanTurn.Ahead))
        assertEquals(ScanTurn.Right, ScanGuide.turnOf(35f, null))
        assertEquals(ScanTurn.Right, ScanGuide.turnOf(41f, ScanTurn.Ahead))
        assertEquals(ScanTurn.Left, ScanGuide.turnOf(-41f, ScanTurn.Ahead))
        assertEquals(ScanTurn.Behind, ScanGuide.turnOf(130f, ScanTurn.Behind))
        assertEquals(ScanTurn.Right, ScanGuide.turnOf(130f, ScanTurn.Right))
        assertEquals(ScanTurn.Right, ScanGuide.turnOf(124f, ScanTurn.Behind))
        // A sway of ±35° round straight ahead says "ahead" all along, once it said it.
        var turn: ScanTurn? = null
        val said = (0..40).map { i ->
            val degrees = 35f * kotlin.math.sin(i / 3f)
            ScanGuide.turnOf(degrees, turn).also { turn = it }
        }
        assertEquals(setOf(ScanTurn.Ahead), said.toSet())
    }

    @Test
    fun `the target is reached within a metre and a half, and left past two`() {
        val guide = ScanGuidance()
        val target = ScanTarget(0f, -3f)
        guide.update(at(0f, 0f, 0f), target, 0L)
        assertFalse(guide.reached)
        assertEquals(3f, guide.hint!!.metres!!, 0f)
        assertNotNull(guide.ribbon)
        guide.update(at(0f, -1.6f, 0f), target, 100L) // 1.4 m off
        assertTrue(guide.reached)
        assertNull("no distance once there", guide.hint!!.metres)
        assertNull("no ribbon once there", guide.ribbon)
        guide.update(at(0f, -1.2f, 0f), target, 200L) // 1.8 m: still there
        assertTrue(guide.reached)
        guide.update(at(0f, -0.9f, 0f), target, 300L) // 2.1 m: left
        assertFalse(guide.reached)
        assertNotNull(guide.ribbon)
        // A fresh target 1.8 m off is not reached.
        assertFalse(ScanGuide.reached(1.8f, was = false))
    }

    @Test
    fun `lining up ticks once, holds through a sway, and ticks at most once a second`() {
        assertTrue(ScanGuide.aligned(10f, was = false))
        assertFalse(ScanGuide.aligned(20f, was = false))
        assertTrue(ScanGuide.aligned(20f, was = true))
        assertFalse(ScanGuide.aligned(26f, was = true))

        val guide = ScanGuidance()
        val target = ScanTarget(0f, -3f)
        // Swaying between 5° and 20° off, every 200 ms for 10 s: lined up once, one tick.
        var ticks = 0
        for (i in 0 until 50) {
            val yaw = if (i % 2 == 0) -5f else -20f // right of the target: a turn to the left of it
            if (guide.update(at(0f, 0f, yaw), target, i * 200L) != null) ticks++
            if (i > 0) assertTrue("still lined up at frame $i", guide.aligned)
        }
        assertEquals(1, ticks)

        // Without the guide's hysteresis, a line-up that flips every 200 ms ticks once a second at most.
        val tick = AlignTick()
        val events = (0 until 50).mapNotNull { i -> tick.update(i % 2 == 0, i * 200L)?.let { i * 200L } }
        assertTrue(events.zipWithNext().all { (a, b) -> b - a >= AlignTick.MIN_GAP_MS })
        assertEquals("rising at 0, 1.2, 2.4 … 9.6 s", 9, events.size)
        assertEquals(MeasureHapticEvent.AimLocked, AlignTick().update(true, 0L))
        assertNull("no tick while it stays lined up", AlignTick().apply { update(true, 0L) }.update(true, 5_000L))
    }

    @Test
    fun `turning off the line-up and back ticks again past the second`() {
        val guide = ScanGuidance()
        val target = ScanTarget(0f, -3f)
        val off = at(0f, 0f, 60f)
        val on = at(0f, 0f, 0f)
        assertNotNull(guide.update(on, target, 0L))
        assertNull(guide.update(off, target, 300L))
        assertTrue("the arrow shows again", !guide.aligned)
        assertNull("within the second", guide.update(on, target, 600L))
        assertNull(guide.update(off, target, 900L))
        assertNotNull("past the second", guide.update(on, target, 1_700L))
    }

    @Test
    fun `the distance said moves in strides, and holds on a boundary`() {
        assertEquals(2f, ScanGuide.stepped(2.24f, Float.NaN), 0f)
        assertEquals(2f, ScanGuide.stepped(2.3f, 2f), 0f)
        assertEquals(2.5f, ScanGuide.stepped(2.4f, 2f), 0f)
        assertEquals(2.5f, ScanGuide.stepped(2.2f, 2.5f), 0f)
        assertEquals(2f, ScanGuide.stepped(2.1f, 2.5f), 0f)
        assertEquals("never under a stride", 0.5f, ScanGuide.stepped(0.1f, Float.NaN), 0f)
    }

    @Test
    fun `the gap led to is kept while it is nearly as good, and a part to fix wins`() {
        val back = floatArrayOf(-2f, 1f, 2f, 1f)
        val right = floatArrayOf(2f, -2.6f, 2f, 1f)
        val first = ScanGuide.cue(at(0f, -1.5f, 0f), listOf(back, right))!!
        assertTrue(first.edge === back)
        // A step to the right makes the right gap a little better: the back one stays.
        val stepped = ScanGuide.cue(at(0.5f, -1.5f, 0f), listOf(back.copyOf(), right.copyOf()), first.edge)!!
        assertEquals(0f, stepped.x, 1e-6f)
        assertEquals(1f, stepped.z, 1e-6f)
        // Next to the right gap, it wins.
        val near = ScanGuide.cue(at(1.9f, -0.8f, 0f), listOf(back, right), first.edge)!!
        assertTrue(near.edge === right)

        val guide = ScanGuidance()
        guide.update(at(0f, 0f, 0f), ScanTarget(0f, -3f), 0L)
        guide.update(at(0f, 0f, 0f), ScanTarget(3f, 0f, fix = true), 100L)
        assertEquals(ScanGuideHint(ScanTurn.Right, 3f, fix = true), guide.hint)
        guide.update(null, ScanTarget(3f, 0f), 200L)
        assertNull(guide.hint)
        assertNull(guide.ribbon)
    }

    @Test
    fun `the ribbon is chevrons from just ahead of the feet to the target, flowing towards it`() {
        val mesh = DebugMesh()
        val ribbon = floatArrayOf(0f, 0f, 0f, -3f)
        val count = ScanRibbon.chevrons(mesh, ribbon, y = 0f)
        assertEquals(8, count)
        assertEquals(count * 8, mesh.vertexCount)
        val zs = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + 2] }
        assertTrue("starts ahead of the feet", zs.max() <= -ScanRibbon.START_M + ScanRibbon.STROKE_M + 1e-5f)
        assertTrue("ends at the target", zs.min() >= -3f - 1e-5f)
        // Flowing, every chevron moved towards the target by the phase.
        val moved = DebugMesh()
        ScanRibbon.chevrons(moved, ribbon, y = 0f, phase = 0.1f)
        assertEquals(mesh.positions[2] - 0.1f, moved.positions[2], 1e-5f)
        // Too short to draw.
        assertEquals(0, ScanRibbon.chevrons(DebugMesh(), floatArrayOf(0f, 0f, 0f, -0.3f), 0f))
    }

    @Test
    fun `the line says the turn and the distance, in the phone's units`() {
        val fr = Locale.FRANCE
        assertEquals(
            "Turn left — no wall yet at the dashed line, 2,5 m away",
            ScanCopy.guideHint(ScanGuideHint(ScanTurn.Left, 2.5f, fix = false), fr),
        )
        assertEquals(
            "Walk 2,0 m ahead — no wall yet at the dashed line",
            ScanCopy.guideHint(ScanGuideHint(ScanTurn.Ahead, 2f, fix = false), fr),
        )
        assertEquals(ScanCopy.turnHint(ScanTurn.Behind), ScanCopy.guideHint(ScanGuideHint(ScanTurn.Behind, null, false), fr))
        assertEquals(
            "Turn right — the part to fix is 1,5 m away",
            ScanCopy.guideHint(ScanGuideHint(ScanTurn.Right, 1.5f, fix = true), fr),
        )
        assertEquals(ScanCopy.FIX_AHEAD, ScanCopy.guideHint(ScanGuideHint(ScanTurn.Ahead, null, fix = true), fr))
        val us = ScanCopy.guideHint(ScanGuideHint(ScanTurn.Right, 2.5f, fix = false), Locale.US)
        assertTrue(us, us.endsWith("${scanPathLength(2.5f, Locale.US)} away"))
        // Fix now leads even once the room's walls are closed.
        val room = ScanScope.Room
        assertEquals(
            ScanCopy.guideHint(ScanGuideHint(ScanTurn.Left, 2f, fix = true)),
            ScanScopeCopy.coaching(room, ScanCopy.COACH_SCANNING, walls = 4, done = true, guide = ScanGuideHint(ScanTurn.Left, 2f, fix = true)),
        )
    }
}
