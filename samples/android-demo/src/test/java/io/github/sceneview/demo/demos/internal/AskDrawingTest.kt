package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AskDrawingTest {

    private fun assertNear(expected: Float3, actual: Float3, tolerance: Float = 1e-4f) {
        val ok = abs(expected.x - actual.x) <= tolerance && abs(expected.y - actual.y) <= tolerance &&
            abs(expected.z - actual.z) <= tolerance
        assertTrue("expected $expected, got $actual", ok)
    }

    // ── Floor ────────────────────────────────────────────────────────────────────────────────

    @Test fun `the floor is the lowest upward plane well below the phone`() {
        val floor = AskFloor.pick(cameraY = 1.5f, upwardPlaneHeights = listOf(0.75f, 0.02f, 0.05f))
        assertEquals(0.02f, floor.y, 0f)
        assertTrue(floor.measured)
    }

    @Test fun `with no plane under the phone, the floor is the lowest point near the bottom of the screen`() {
        val floor = AskFloor.pick(cameraY = 1.2f, upwardPlaneHeights = emptyList(), lowPointHeights = listOf(0.4f, 0.03f, 0.1f))
        assertEquals(0.03f, floor.y, 0f)
        assertTrue(floor.measured)
        assertEquals(FloorSource.Points, floor.source)
        // A plane wins over points.
        assertEquals(FloorSource.Plane, AskFloor.pick(1.2f, listOf(0.05f), listOf(0.0f)).source)
        // Points within reach of the phone, or far below the floor, are noise.
        assertEquals(FloorSource.Fallback, AskFloor.pick(1.2f, emptyList(), listOf(1.0f, -1.5f)).source)
    }

    @Test fun `with nothing found under the phone, the floor is a flagged fallback`() {
        val nothing = AskFloor.pick(cameraY = 1.5f, upwardPlaneHeights = listOf(1.3f))
        assertFalse(nothing.measured)
        assertEquals(FloorSource.Fallback, nothing.source)
        assertEquals(1.5f - AskFloor.FALLBACK_BELOW_CAMERA, nothing.y, 1e-6f)
        assertFalse(AskFloor.pick(1.5f, emptyList()).measured)
    }

    // ── The wall the centre ray hit ──────────────────────────────────────────────────────────

    @Test fun `the wall is a plane before a depth point before a feature point, never nearer than 80 cm`() {
        val hits = listOf(
            WallHitKind.FeaturePoint to 0.3f,
            WallHitKind.FeaturePoint to 2.1f,
            WallHitKind.Depth to 2.6f,
            null to 1.2f,
            WallHitKind.VerticalPlane to 2.5f,
        )
        assertEquals(4, WallPick.best(hits))
        assertEquals(2, WallPick.best(hits.take(4)))
        assertEquals(1, WallPick.best(hits.take(2)))
        // A lone feature point a hand's length away is no wall.
        assertNull(WallPick.best(hits.take(1)))
        assertNull(WallPick.best(listOf(WallHitKind.VerticalPlane to 9f)))
    }

    @Test fun `a measured surface counts from arm's length, a feature point does not`() {
        // A radiator's bleed screw, asked about from 40 cm.
        assertEquals(0, WallPick.best(listOf(WallHitKind.Depth to 0.4f)))
        assertEquals(0, WallPick.best(listOf(WallHitKind.VerticalPlane to 0.3f)))
        assertNull(WallPick.best(listOf(WallHitKind.FeaturePoint to 0.4f)))
        assertNull(WallPick.best(listOf(WallHitKind.Depth to 0.2f)))
    }

    @Test fun `the ink thickens with the wall's distance, the line stays true to size`() {
        assertEquals(1f, HangPicture.inkScale(HangPicture.REFERENCE_DISTANCE), 1e-6f)
        assertEquals(1.5f, HangPicture.inkScale(3f), 1e-6f)
        assertEquals(1f, HangPicture.inkScale(null), 1e-6f)
        val near = HangPicture.plan(wallAhead.copy(distance = 2f), 0f).sheets.single().writing
        val far = HangPicture.plan(wallAhead.copy(distance = 3f), 0f).sheets.single().writing
        assertEquals(1.5f, far.strokes[0].radii.max() / near.strokes[0].radii.max(), 0.02f)
        val length = { w: Handwriting -> w.strokes[0].points.last().x - w.strokes[0].points.first().x }
        assertEquals(length(near), length(far), 1e-5f)
        assertTrue(far.textBounds.height > near.textBounds.height * 1.3f)
    }

    // ── Where should I hang this picture? ────────────────────────────────────────────────────

    private val wallAhead = WallSpot(point = Float3(0.3f, 1.1f, -2f), normal = Float3(0f, 0f, 1f))

    @Test fun `the level line is 80 cm, centred on the ray's hit, 145 cm above the floor`() {
        val (left, right) = HangPicture.levelLine(wallAhead, floorY = -0.2f)
        val y = -0.2f + 1.45f
        val z = -2f + HangPicture.WALL_LIFT
        assertNear(Float3(0.3f - 0.4f, y, z), left)
        assertNear(Float3(0.3f + 0.4f, y, z), right)
        val (nailLeft, nailRight) = HangPicture.nails(wallAhead, floorY = -0.2f)
        assertNear(Float3(0.1f, y, z), nailLeft)
        assertNear(Float3(0.5f, y, z), nailRight)
    }

    @Test fun `on a wall facing +x, the line runs along z, left to right as seen from the room`() {
        val wall = WallSpot(Float3(2f, 1f, 0f), Float3(1f, 0f, 0f))
        val (left, right) = HangPicture.levelLine(wall, floorY = 0f)
        // Facing the wall means looking down -x: the right hand is -z.
        assertNear(Float3(2f + HangPicture.WALL_LIFT, 1.45f, 0.4f), left)
        assertNear(Float3(2f + HangPicture.WALL_LIFT, 1.45f, -0.4f), right)
    }

    @Test fun `the drawn level line ends exactly on the level line, wobbling a few millimetres between`() {
        val floorY = -0.2f
        val plan = HangPicture.plan(wallAhead, floorY)
        val sheet = plan.sheets.single()
        assertEquals(SheetFacing.Wall, sheet.facing)
        val stroke = sheet.writing.strokes.first()
        val (left, right) = HangPicture.levelLine(wallAhead, floorY)
        assertNear(left, plan.toWorld(sheet, stroke.points.first()))
        assertNear(right, plan.toWorld(sheet, stroke.points.last()))
        val wobble = stroke.points.maxOf { abs(it.y) }
        assertTrue("wobble $wobble", wobble > 0.0005f && wobble < 0.004f)
        // The sheet faces out of the wall.
        assertNear(wallAhead.normal, plan.toWorld(Float3(0f, 0f, 1f)) - plan.origin)
    }

    @Test fun `each nail is a cross of two strokes on the line`() {
        val plan = HangPicture.plan(wallAhead, floorY = 0f)
        val strokes = plan.sheets.single().writing.strokes
        // The two crosses come right after the line and its centre tick, so the picture can hang on them.
        val crosses = strokes.subList(2, 6)
        for ((i, x) in listOf(-0.2f, 0.2f).withIndex()) {
            val pair = crosses.subList(2 * i, 2 * i + 2)
            val centreX = pair.flatMap { it.points }.let { (it.minOf { p -> p.x } + it.maxOf { p -> p.x }) / 2f }
            val centreY = pair.flatMap { it.points }.let { (it.minOf { p -> p.y } + it.maxOf { p -> p.y }) / 2f }
            assertEquals(x, centreX, 0.006f)
            assertEquals(0f, centreY, 0.006f)
            // Two strokes that cross: one falls left to right, the other rises.
            val (a, b) = pair
            assertTrue((a.points.last().y - a.points.first().y) * (a.points.last().x - a.points.first().x) < 0f)
            assertTrue((b.points.last().y - b.points.first().y) * (b.points.last().x - b.points.first().x) > 0f)
        }
    }

    @Test fun `the wall's normal comes from the hit when upright, else from the camera, and faces the room`() {
        val point = Float3(0f, 1f, -2f)
        val camera = Float3(0f, 1.5f, 0f)
        // A plane's normal pointing into the wall is turned to the room.
        assertNear(Float3(0f, 0f, 1f), HangPicture.wallNormal(Float3(0f, 0f, -1f), point, camera))
        // A slanted one is flattened.
        assertNear(Float3(0.6f, 0f, 0.8f), HangPicture.wallNormal(Float3(0.48f, 0.2f, 0.64f), point, camera), 1e-3f)
        // A floor-like normal, or none, falls back on the way to the camera.
        assertNear(Float3(0f, 0f, 1f), HangPicture.wallNormal(Float3(0f, 1f, 0f), point, camera))
        assertNear(Float3(0f, 0f, 1f), HangPicture.wallNormal(null, point, camera))
    }

    @Test fun `the hang answer is drawn on the wall, or asks for one`() {
        val room = AskRoom(Float3(0f, 1.5f, 0f), Float3(0f, 0f, -1f), FloorRead(0f, true), wall = wallAhead)
        val answer = ScriptedAnswerProvider.hangAnswer(room)
        assertNotNull(answer.plan)
        assertEquals(HangPicture.NOTE, answer.text)
        val noWall = ScriptedAnswerProvider.hangAnswer(room.copy(wall = null))
        assertNull(noWall.plan)
        assertTrue(noWall.text.contains("wall"))
    }

    // ── Will a 2 m sofa fit here? ────────────────────────────────────────────────────────────

    private val camera = Float3(0f, 1.4f, 0f)
    private val north = Float3(0f, 0f, -1f)

    @Test fun `the footprint goes where the phone points at the floor, if that is in range`() {
        assertNear(Float3(0.1f, 0f, -2f), SofaFit.centre(camera, north, 0f, Float3(0.1f, 0.01f, -2f)))
        // Too far, too near, or nothing: 1.8 m straight ahead.
        assertNear(Float3(0f, 0f, -1.8f), SofaFit.centre(camera, north, 0f, Float3(0f, 0f, -4f)))
        assertNear(Float3(0f, 0f, -1.8f), SofaFit.centre(camera, north, 0f, Float3(0f, 0f, -0.5f)))
        assertNear(Float3(0f, 0f, -1.8f), SofaFit.centre(camera, north, 0f, null))
    }

    @Test fun `a wall close ahead turns the footprint square to it, 10 cm off it`() {
        // The phone looks north, a little east of square, at a wall 1 m ahead facing south.
        val heading = Float3(0.2f, 0f, -0.98f).let { it / kotlin.math.sqrt(it.x * it.x + it.z * it.z) }
        val wall = WallSpot(Float3(0.2f, 0.4f, -1f), Float3(0f, 0f, 1f), kind = WallHitKind.VerticalPlane)
        val (centre, facing) = SofaFit.place(camera, heading, 0f, null, wall)
        assertNear(Float3(0f, 0f, -1f), facing)
        assertEquals(-1f + SofaFit.WALL_CLEARANCE + SofaFit.DEPTH / 2f, centre.z, 1e-4f)
        assertEquals(0f, centre.y, 0f)
        // Its far side 10 cm off the wall, every corner in front of it.
        assertTrue(SofaFit.corners(centre, facing).all { it.z >= -1f + SofaFit.WALL_CLEARANCE - 1e-4f })
        // A feature point is not trusted to move it; a far wall leaves it alone.
        assertEquals(SofaFit.centre(camera, heading, 0f, null) to heading,
            SofaFit.place(camera, heading, 0f, null, wall.copy(kind = WallHitKind.FeaturePoint)))
        val far = wall.copy(point = Float3(0f, 0.4f, -4f))
        assertEquals(SofaFit.centre(camera, heading, 0f, null) to heading, SofaFit.place(camera, heading, 0f, null, far))
    }

    @Test fun `the footprint is 200 by 90, square to the heading`() {
        val c = Float3(0f, 0f, -1.8f)
        val corners = SofaFit.corners(c, north)
        assertNear(Float3(-1f, 0f, -1.35f), corners[0])
        assertNear(Float3(1f, 0f, -1.35f), corners[1])
        assertNear(Float3(1f, 0f, -2.25f), corners[2])
        assertNear(Float3(-1f, 0f, -2.25f), corners[3])
        // Looking down +x, the long side runs along z, the right hand being +z.
        val east = SofaFit.corners(Float3(2f, 0f, 0f), Float3(1f, 0f, 0f))
        assertNear(Float3(1.55f, 0f, -1f), east[0])
        assertNear(Float3(1.55f, 0f, 1f), east[1])
    }

    @Test fun `the drawn footprint lands on those corners`() {
        val spot = SofaSpot(Float3(0f, 0f, -1.8f))
        val plan = SofaFit.plan(spot, north)
        val floor = plan.sheets.first { it.facing == SheetFacing.Floor }
        val lift = Float3(0f, SofaFit.FLOOR_LIFT, 0f)
        val corners = SofaFit.corners(spot.centre, north)
        assertNear(corners[0] + lift, plan.toWorld(floor, InkPoint(-1f, -0.45f)))
        assertNear(corners[2] + lift, plan.toWorld(floor, InkPoint(1f, 0.45f)))
        // The first dash leaves the near-left corner along the near side.
        val first = floor.writing.strokes.first()
        assertNear(corners[0] + lift, plan.toWorld(floor, first.points.first()), 1e-3f)
        assertEquals(-0.45f, first.points.last().y, 0.003f)
        // Every dash of the footprint lies on its outline.
        val dashCount = 2 * SofaFit.dashes(2f).size + 2 * SofaFit.dashes(0.9f).size
        for (stroke in floor.writing.strokes.take(dashCount)) {
            for (p in listOf(stroke.points.first(), stroke.points.last())) {
                val onEdge = abs(abs(p.x) - 1f) < 0.004f || abs(abs(p.y) - 0.45f) < 0.004f
                assertTrue("dash end $p is off the outline", onEdge)
            }
        }
    }

    @Test fun `the length line is the footprint's own length, ticked at both ends, its label inside it`() {
        val floor = SofaFit.plan(SofaSpot(Float3(0f, 0f, -1.8f)), north).sheets.first { it.facing == SheetFacing.Floor }
        val dashCount = 2 * SofaFit.dashes(2f).size + 2 * SofaFit.dashes(0.9f).size
        // Tick, left half, "2 m", right half, tick.
        val strokes = floor.writing.strokes.drop(dashCount)
        val leftTick = strokes.first()
        assertTrue(leftTick.points.all { abs(it.x + 1f) < 1e-4f })
        val line = strokes.filter { s -> s.points.all { abs(it.y - s.points.first().y) < 0.01f } && s.points.size > 10 }
        val y = line.first().points.first().y
        val ink = line.flatMap { it.points }
        assertEquals(-1f, ink.minOf { it.x }, 1e-4f)
        assertEquals(1f, ink.maxOf { it.x }, 1e-4f)
        val rightTick = strokes.first { s -> s.points.all { abs(it.x - 1f) < 1e-4f } }
        assertTrue(rightTick.points.any { it.y > y } && rightTick.points.any { it.y < y })
        // The label sits in the break, on the line.
        val label = strokes.filter { s -> s.kind == InkKind.Text && s.points.all { abs(it.y - y) < 0.15f } }.flatMap { it.points }
        assertTrue(label.isNotEmpty())
        val gap = SofaFit.lengthGap()
        assertTrue(label.all { abs(it.x) < gap })
        assertTrue(label.minOf { it.y } < y && label.maxOf { it.y } > y)
        assertTrue(ink.none { abs(it.x) < gap - 1e-4f })
    }

    @Test fun `dashes start and end on the corners, evenly spaced`() {
        val long = SofaFit.dashes(2f, dash = 0.12f, gap = 0.07f)
        assertEquals(11, long.size)
        assertEquals(0f, long.first().first, 0f)
        assertEquals(2f, long.last().second, 1e-5f)
        val length = long.first().second - long.first().first
        for (i in long.indices) {
            assertEquals(length, long[i].second - long[i].first, 1e-5f)
            if (i > 0) assertEquals(0.07f, long[i].first - long[i - 1].second, 1e-5f)
        }
        assertEquals(5, SofaFit.dashes(0.9f, dash = 0.12f, gap = 0.07f).size)
        // An edge shorter than one dash is one dash.
        assertEquals(listOf(0f to 0.05f), SofaFit.dashes(0.05f))
    }

    @Test fun `the nearest wall or depth hit within reach counts, nothing else`() {
        val hits = listOf(
            SofaFit.SideHit(0.9f, SofaFit.HitKind.Other),
            SofaFit.SideHit(3.5f, SofaFit.HitKind.Depth),
            SofaFit.SideHit(1.4f, SofaFit.HitKind.VerticalPlane),
        )
        assertEquals(1.4f, SofaFit.nearest(hits)!!, 0f)
        assertEquals(3.5f, SofaFit.nearest(hits.filter { it.kind == SofaFit.HitKind.Depth })!!, 0f)
        // Past 3 m beyond the sofa's end, or grazing its own centre: not counted.
        assertNull(SofaFit.nearest(listOf(SofaFit.SideHit(4.1f, SofaFit.HitKind.VerticalPlane))))
        assertNull(SofaFit.nearest(listOf(SofaFit.SideHit(0.05f, SofaFit.HitKind.Depth))))
        assertNull(SofaFit.nearest(emptyList()))
    }

    @Test fun `the verdict writes a number only when one was measured`() {
        // Walls 1.2 m left and 1.1 m right of the centre: 2.3 m across, 30 cm more than the sofa.
        assertEquals("Fits. *30 cm* to spare.", SofaFit.verdict(1.2f, 1.1f))
        assertEquals("Too tight: *15 cm* short.", SofaFit.verdict(0.9f, 0.95f))
        assertEquals("Fits, *only just.*", SofaFit.verdict(1.01f, 1.0f))
        // Nothing hit, or one wall clear of the sofa's end: no number.
        assertEquals("2 m by 90 cm, *right here.*", SofaFit.verdict(null, null))
        assertEquals("Fits. *Lots of room.*", SofaFit.verdict(1.5f, null))
        // One wall cutting into it: how far to slide it.
        assertEquals("Fits. Slide it *20 cm* over.", SofaFit.verdict(null, 0.8f))
    }

    @Test fun `the sofa answer writes its verdict above the footprint, upright`() {
        val room = AskRoom(
            camera, north, FloorRead(0f, true),
            sofa = SofaSpot(Float3(0f, 0f, -1.8f), left = 1.2f, right = 1.1f),
        )
        val answer = ScriptedAnswerProvider.sofaAnswer(room)
        assertEquals("Fits. *30 cm* to spare.", answer.text)
        val plan = answer.plan!!
        val verdict = plan.sheets.single { it.facing == SheetFacing.Billboard }
        assertTrue(verdict.writing.lines.flatten().contains("30"))
        assertTrue(verdict.offset.y > 0.3f)
        // The verdict comes last, once the footprint is drawn.
        val floor = plan.sheets.single { it.facing == SheetFacing.Floor }
        assertTrue(verdict.writing.starts.first() >= floor.writing.totalSeconds)
        // The measures on the floor are the sofa's own, given in the question.
        assertTrue(floor.writing.lines.flatten().containsAll(listOf("2", "m", "90", "cm")))
    }

    // ── How do I bleed this radiator? ────────────────────────────────────────────────────────

    @Test fun `the turn arrow sweeps a quarter turn anticlockwise just outside the circle`() {
        val (shaft, head) = RadiatorBleed.turnArrow()
        fun angle(p: InkPoint) = kotlin.math.atan2(p.y, p.x)
        assertEquals(RadiatorBleed.ARROW_FROM, angle(shaft.first()), 1e-4f)
        assertEquals(RadiatorBleed.ARROW_FROM + RadiatorBleed.ARROW_SWEEP, angle(shaft.last()), 1e-4f)
        assertEquals((Math.PI / 2).toFloat(), RadiatorBleed.ARROW_SWEEP, 1e-6f)
        for (i in 1 until shaft.size) {
            // Anticlockwise: each point a little further round, never back.
            val a = shaft[i - 1]
            val b = shaft[i]
            assertTrue(a.x * b.y - a.y * b.x > 0f)
        }
        for (p in shaft) {
            val r = kotlin.math.sqrt(p.x * p.x + p.y * p.y)
            assertEquals(RadiatorBleed.ARROW_RADIUS, r, RadiatorBleed.ARROW_RADIUS * 0.025f)
            assertTrue("the arrow cuts into the circle", r > RadiatorBleed.CIRCLE_RADIUS * 1.15f)
        }
        // A two-stroke head on the shaft's end, its barbs behind the tip, one either side.
        assertEquals(3, head.size)
        assertEquals(shaft.last(), head[1])
        val end = RadiatorBleed.ARROW_FROM + RadiatorBleed.ARROW_SWEEP
        val tangent = InkPoint(-kotlin.math.sin(end), kotlin.math.cos(end))
        val tip = head[1]
        val sides = listOf(head[0], head[2]).map { barb ->
            val dx = barb.x - tip.x
            val dy = barb.y - tip.y
            assertTrue("a barb points ahead of the tip", dx * tangent.x + dy * tangent.y < 0f)
            dx * tangent.y - dy * tangent.x
        }
        assertTrue("both barbs on one side", sides[0] * sides[1] < 0f)
    }

    @Test fun `a clockwise sweep points its head the other way`() {
        val (shaft, head) = RadiatorBleed.turnArrow(sweep = -RadiatorBleed.ARROW_SWEEP)
        val a = shaft[0]
        val b = shaft[1]
        assertTrue(a.x * b.y - a.y * b.x < 0f)
        val end = RadiatorBleed.ARROW_FROM - RadiatorBleed.ARROW_SWEEP
        // Clockwise, the pen goes along -tangent: the barbs trail on the +tangent side.
        val tangent = InkPoint(-kotlin.math.sin(end), kotlin.math.cos(end))
        val barb = head[0]
        assertTrue((barb.x - head[1].x) * tangent.x + (barb.y - head[1].y) * tangent.y > 0f)
    }

    @Test fun `the bleed drawing is anchored on the hit, its circle on the surface, the steps beside it`() {
        val screw = WallSpot(Float3(0.4f, 0.5f, -1f), Float3(0f, 0f, 1f))
        val plan = RadiatorBleed.plan(screw, camera = Float3(0f, 1.2f, 0f))
        assertNear(Float3(0.4f, 0.5f, -1f + RadiatorBleed.SURFACE_LIFT), plan.origin)
        val surface = plan.sheets.first()
        assertEquals(SheetFacing.Wall, surface.facing)
        // Two loops round the screw's cap, about 4 cm out.
        val circle = surface.writing.strokes.first().points
        val radii = circle.map { kotlin.math.sqrt(it.x * it.x + it.y * it.y) }
        val r = RadiatorBleed.CIRCLE_RADIUS
        assertTrue(radii.all { it in r * 0.9f..r * 1.12f })
        val turns = circle.zipWithNext { p, q -> kotlin.math.atan2(p.x * q.y - p.y * q.x, p.x * q.x + p.y * q.y) }.sum() /
            (2f * Math.PI.toFloat())
        assertTrue("$turns turns", turns in 1.6f..2f)
        // The words stand upright, the steps to one side, every word in order.
        val steps = plan.sheets.last()
        assertEquals(SheetFacing.Billboard, steps.facing)
        assertEquals(RadiatorBleed.STEPS_GAP, abs(steps.offset.x), 1e-6f)
        assertEquals(RadiatorBleed.STEPS.flatMap { it.split(' ') }, steps.writing.lines.flatten())
        assertTrue(plan.sheets.drop(1).all { it.facing == SheetFacing.Billboard })
    }

    @Test fun `the arrow goes opposite the steps, its label above the circle, everything within 25 cm of the screw`() {
        for (camera in listOf(Float3(0f, 1.2f, 0f), Float3(-0.6f, 1.2f, 0f))) {
            val screw = WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f))
            val side = RadiatorBleed.stepsSide(screw, camera)
            val plan = RadiatorBleed.plan(screw, camera)
            val (surface, label, steps) = plan.sheets
            fun xs(sheet: InkSheet) = sheet.writing.strokes.flatMap { s -> s.points.map { sheet.offset.x + it.x } }
            // The arrow is the surface's second stroke: its middle on the other side.
            val shaft = surface.writing.strokes[1].points
            assertTrue("arrow on the steps' side", shaft[shaft.size / 2].x * side < 0f)
            // Centred over the screw, and wholly above the arrow's head.
            val labelXs = xs(label)
            assertEquals(0f, (labelXs.min() + labelXs.max()) / 2f, 0.01f)
            val arrowTop = surface.writing.strokes.drop(1).flatMap { s -> s.points.map { it.y } }.max()
            val labelBottom = label.writing.strokes.flatMap { s -> s.points.map { label.offset.y + it.y } }.min()
            assertTrue("label at $labelBottom cuts the arrow at $arrowTop", labelBottom > arrowTop)
            assertTrue("label reaches the steps", labelXs.all { abs(it) < RadiatorBleed.STEPS_GAP })
            assertTrue("steps on the wrong side", xs(steps).all { it * side > 0f })
            val all = plan.sheets.flatMap(::xs)
            val half = RadiatorBleed.HALF_SPAN + 0.005f
            assertTrue("spans ${all.min()}..${all.max()}", all.all { it in -half..half })
        }
    }

    @Test fun `the steps go to the side the phone stands toward, right when square on`() {
        val screw = WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f))
        assertEquals(1f, RadiatorBleed.stepsSide(screw, Float3(0f, 1.2f, 0f)), 0f)
        assertEquals(1f, RadiatorBleed.stepsSide(screw, Float3(0.5f, 1.2f, 0f)), 0f)
        assertEquals(-1f, RadiatorBleed.stepsSide(screw, Float3(-0.5f, 1.2f, 0f)), 0f)
    }

    @Test fun `near one end of the wall ARCore found, the steps go the other way`() {
        val screw = WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f))
        val squareOn = Float3(0f, 1.2f, 0f)
        assertEquals(-1f, RadiatorBleed.stepsSide(screw.copy(edgeLeft = 0.6f, edgeRight = 0.1f), squareOn), 0f)
        assertEquals(-1f, RadiatorBleed.stepsSide(screw.copy(edgeRight = 0.1f), squareOn), 0f)
        // Near the left end, even with the phone off to the left.
        assertEquals(1f, RadiatorBleed.stepsSide(screw.copy(edgeLeft = 0.05f), Float3(-0.6f, 1.2f, 0f)), 0f)
        // Near both ends, or neither: the phone decides.
        assertEquals(1f, RadiatorBleed.stepsSide(screw.copy(edgeLeft = 0.1f, edgeRight = 0.1f), squareOn), 0f)
        assertEquals(1f, RadiatorBleed.stepsSide(screw.copy(edgeLeft = 0.5f, edgeRight = 0.5f), squareOn), 0f)
    }

    @Test fun `a plane's outline ends where it crosses the level through the hit`() {
        // A 1 m wide, 0.8 m tall wall patch, the hit 0.1 m from its right end.
        val outline = listOf(InkPoint(-0.9f, -0.3f), InkPoint(0.1f, -0.3f), InkPoint(0.1f, 0.5f), InkPoint(-0.9f, 0.5f))
        val (left, right) = WallEdges.around(outline)
        assertEquals(0.9f, left!!, 1e-5f)
        assertEquals(0.1f, right!!, 1e-5f)
        // A slanted edge is met where it crosses, not at its corners.
        val (_, slanted) = WallEdges.around(listOf(InkPoint(-1f, -1f), InkPoint(0f, -1f), InkPoint(0.4f, 1f), InkPoint(-1f, 1f)))
        assertEquals(0.2f, slanted!!, 1e-5f)
        assertEquals(null to null, WallEdges.around(listOf(InkPoint(0f, 0f), InkPoint(1f, 1f))))
    }

    @Test fun `the radiator answer needs a spot to anchor on`() {
        val room = AskRoom(camera, north, FloorRead(0f, true), wall = WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f)))
        assertNotNull(ScriptedAnswerProvider.radiatorAnswer(room).plan)
        assertNull(ScriptedAnswerProvider.radiatorAnswer(room.copy(wall = null)).plan)
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────────────────

    @Test fun `both drawings take a few seconds, one stroke after the other`() {
        val plans = listOf(
            HangPicture.plan(wallAhead, 0f),
            SofaFit.plan(SofaSpot(Float3(0f, 0f, -1.8f), 1.2f, 1.1f), north),
            RadiatorBleed.plan(WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f)), camera),
        )
        for (plan in plans) {
            // The drawing, then its real object: still a short watch.
            assertTrue("takes ${plan.totalSeconds} s", plan.totalSeconds in 4f..15f)
            val timeline = plan.sheets.flatMap { sheet ->
                sheet.writing.strokes.indices.map { sheet.writing.starts[it] to sheet.writing.starts[it] + sheet.writing.strokes[it].duration }
            }
            for (i in 1 until timeline.size) {
                assertTrue("stroke $i starts before $i-1 ends", timeline[i].first >= timeline[i - 1].second - 1e-4f)
            }
        }
    }

    @Test fun `a pen stroke eases in and out but always moves on`() {
        assertEquals(0f, Sketch.penTime(0f), 1e-6f)
        assertEquals(1f, Sketch.penTime(1f), 1e-5f)
        assertEquals(0.5f, Sketch.penTime(0.5f), 1e-5f)
        var last = -1f
        for (i in 0..100) {
            val t = Sketch.penTime(i / 100f)
            assertTrue(t > last)
            last = t
        }
    }

    @Test fun `each drawing brings in its real object on the plan's clock`() {
        val plans = mapOf(
            PropKind.PictureFrame to HangPicture.plan(wallAhead, 0f),
            PropKind.Sofa to SofaFit.plan(SofaSpot(Float3(0f, 0f, -1.8f), 1.2f, 1.1f), north),
            PropKind.BleedKey to RadiatorBleed.plan(WallSpot(Float3(0f, 0.5f, -1f), Float3(0f, 0f, 1f)), camera),
        )
        for ((kind, plan) in plans) {
            val prop = plan.props.single()
            assertEquals(kind, prop.kind)
            val inkDone = plan.sheets.maxOf { it.writing.totalSeconds }
            val firstMark = plan.sheets.minOf { s -> s.writing.starts.indices.minOf { s.writing.starts[it] + s.writing.strokes[it].times.last() } }
            assertTrue("$kind starts at ${prop.start}, before the first mark is down at $firstMark", prop.start >= firstMark)
            if (kind == PropKind.Sofa) {
                // The sofa stands on the finished footprint, under the finished verdict.
                assertTrue("sofa starts at ${prop.start} before the ink is done at $inkDone", prop.start >= inkDone)
            } else {
                // The frame and the key come in while the pen writes on, so the camera still sees them.
                assertTrue("$kind waits for the pen: ${prop.start} >= $inkDone", prop.start < inkDone)
            }
            assertEquals(maxOf(inkDone, prop.start + prop.seconds), plan.totalSeconds, 1e-4f)
            assertEquals(0f, prop.progress(prop.start), 0f)
            assertEquals(1f, prop.progress(prop.start + prop.seconds), 1e-5f)
            assertEquals(1f, prop.progress(plan.totalSeconds + 5f), 0f)
        }
    }

    @Test fun `the picture is hung once the nails are marked`() {
        val plan = HangPicture.plan(wallAhead, 0f)
        val prop = plan.props.single()
        val wall = plan.sheets.single().writing
        // The nails are the four short strokes just before the frame comes in.
        val nailsDone = wall.starts.indices.filter { wall.starts[it] < prop.start }.maxOf { wall.starts[it] + wall.strokes[it].times.last() }
        assertTrue(prop.start >= nailsDone)
        assertEquals(4 + 2, wall.starts.count { it < prop.start })
    }

    @Test fun `the picture hangs flush on the wall, centred on the level line`() {
        val prop = HangPicture.plan(wallAhead, 0f).props.single()
        assertEquals(0f, prop.offset.x, 0f)
        assertEquals(0f, prop.offset.y, 0f)
        // Its back on the wall, its front just under the ink.
        assertEquals(-HangPicture.WALL_LIFT, prop.offset.z - prop.size.z / 2f, 1e-5f)
        assertTrue(prop.offset.z + prop.size.z / 2f <= 0f + 1e-5f)
    }

    @Test fun `the sofa rises inside its footprint, under the verdict`() {
        val plan = SofaFit.plan(SofaSpot(Float3(0f, 0f, -1.8f)), north)
        val prop = plan.props.single()
        assertEquals(SofaFit.LENGTH, prop.size.x, 0f)
        assertEquals(SofaFit.DEPTH, prop.size.z, 0f)
        val verdict = plan.sheets.single { it.facing == SheetFacing.Billboard }
        assertTrue("verdict at ${verdict.offset.y} inside the sofa", verdict.offset.y > prop.size.y)
    }

    @Test fun `the build eases start and end where they should`() {
        assertEquals(0f, PropMotion.easeOut(0f), 0f)
        assertEquals(1f, PropMotion.easeOut(1f), 1e-6f)
        assertEquals(0f, PropMotion.easeOutBack(0f), 1e-6f)
        assertEquals(1f, PropMotion.easeOutBack(1f), 1e-6f)
        assertTrue("overshoots", (1..99).any { PropMotion.easeOutBack(it / 100f) > 1f })
        assertEquals(0f, PropMotion.phase(0.1f, 0.2f, 0.4f), 0f)
        assertEquals(0.5f, PropMotion.phase(0.3f, 0.2f, 0.4f), 1e-6f)
        assertEquals(1f, PropMotion.phase(0.9f, 0.2f, 0.4f), 0f)
    }
}
