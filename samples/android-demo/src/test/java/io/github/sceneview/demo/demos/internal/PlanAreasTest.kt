package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A floor plan's areas, volumes and totals (#4540): `PlanAreas` over `measureFloorArea`. */
class PlanAreasTest {

    /** An outline from x/z pairs, on the floor. */
    private fun outline(vararg xz: Float): List<Position> =
        xz.toList().chunked(2).map { (x, z) -> Position(x, 0f, z) }

    private fun measured(value: Float, spread: Float) = SurveyFigure(value, spread)

    // ── Floor area ──────────────────────────────────────────────────────────

    @Test
    fun `a rectangle's area is its length times its width, whichever way it is walked`() {
        val room = outline(0f, 0f, 4f, 0f, 4f, 3.6f, 0f, 3.6f)

        assertEquals(14.4f, PlanAreas.floorArea(room)!!, 1e-4f)
        assertEquals(14.4f, PlanAreas.floorArea(room.reversed())!!, 1e-4f)
        // Where it stands in the scan's frame does not change it.
        val moved = room.map { Position(it.x - 7f, 1.2f, it.z + 3f) }
        assertEquals(14.4f, PlanAreas.floorArea(moved)!!, 1e-4f)
    }

    @Test
    fun `an L-shaped room counts its floor, not the rectangle around it`() {
        // 5 x 4 with a 2 x 2 corner cut out: 20 - 4.
        val l = outline(0f, 0f, 5f, 0f, 5f, 2f, 3f, 2f, 3f, 4f, 0f, 4f)

        assertEquals(16f, PlanAreas.floorArea(l)!!, 1e-4f)
        assertEquals(16f, PlanAreas.floorArea(l.reversed())!!, 1e-4f)
    }

    @Test
    fun `a survey's walls give the same area as the outline they draw`() {
        val walls = listOf(
            wall("A", 0f, 0f, 5f, 0f),
            wall("B", 5f, 0f, 5f, 2f),
            wall("C", 5f, 2f, 3f, 2f),
            wall("D", 3f, 2f, 3f, 4f),
            wall("E", 3f, 4f, 0f, 4f),
            wall("F", 0f, 4f, 0f, 0f),
        )

        assertEquals(16f, PlanAreas.wallsArea(walls)!!, 1e-4f)
    }

    @Test
    fun `a degenerate outline encloses no floor`() {
        assertNull(PlanAreas.floorArea(emptyList()))
        assertNull(PlanAreas.floorArea(outline(0f, 0f)))
        assertNull(PlanAreas.floorArea(outline(0f, 0f, 4f, 0f)))
        // Three corners on one line.
        assertNull(PlanAreas.floorArea(outline(0f, 0f, 2f, 0f, 4f, 0f)))
        // The same corner over and over.
        assertNull(PlanAreas.floorArea(outline(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)))
        // A wall walked out and back.
        assertNull(PlanAreas.floorArea(outline(0f, 0f, 3f, 0f, 3f, 0f, 0f, 0f)))
        assertNull(PlanAreas.wallsArea(emptyList()))
    }

    @Test
    fun `a repeated corner does not change a room's area`() {
        val room = outline(0f, 0f, 4f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)

        assertEquals(12f, PlanAreas.floorArea(room)!!, 1e-4f)
    }

    // ── Volume ──────────────────────────────────────────────────────────────

    @Test
    fun `a room's volume is its floor area times its ceiling height`() {
        val volume = PlanAreas.volume(measured(14.4f, 0.4f), measured(2.6f, 0.05f))

        assertNotNull(volume)
        assertEquals(37.44f, volume!!.value, 1e-4f)
        // First order: the area's scatter over the height, the height's over the area.
        assertEquals(14.4f * 0.05f + 2.6f * 0.4f, volume.plusMinus, 1e-4f)
        assertFalse(volume.estimate)
        assertEquals("37.4 m³ ± 1.8", SurveyReport.volume(volume))
    }

    @Test
    fun `no volume without both an area and a height, and an estimate makes one`() {
        assertNull(PlanAreas.volume(null, measured(2.6f, 0.05f)))
        assertNull(PlanAreas.volume(measured(14.4f, 0.4f), null))

        val rough = PlanAreas.volume(SurveyFigure.of(14.4f, 0.4f, estimate = true), measured(2.6f, 0.05f))!!
        assertTrue(rough.estimate)
        assertEquals(0f, rough.plusMinus, 0f)
        assertEquals("≈ 37.4 m³", SurveyReport.volume(rough))
    }

    // ── Rooms and totals ────────────────────────────────────────────────────

    @Test
    fun `a plan of several rooms adds their areas and their volumes`() {
        val kitchen = RoomAreas("Kitchen", measured(12f, 0.3f), measured(30f, 1f))
        val living = RoomAreas("Living room", measured(20f, 0.4f), measured(50f, 1.5f))
        val hall = RoomAreas("Hall", measured(4.5f, 0.1f), measured(11.25f, 0.3f))

        val plan = PlanAreas.of(listOf(kitchen, living, hall))

        assertEquals(3, plan.rooms.size)
        assertEquals(36.5f, plan.area!!.value, 1e-4f)
        assertEquals(0.8f, plan.area!!.plusMinus, 1e-4f)
        assertEquals(91.25f, plan.volume!!.value, 1e-4f)
        assertEquals(2.8f, plan.volume!!.plusMinus, 1e-4f)
        assertFalse(plan.area!!.estimate)
    }

    @Test
    fun `rooms measured from their outlines total the whole floor`() {
        val rooms = listOf(
            outline(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f),
            outline(4f, 0f, 9f, 0f, 9f, 2f, 7f, 2f, 7f, 4f, 4f, 4f),
        ).mapIndexed { i, room ->
            val area = PlanAreas.floorArea(room)?.let { measured(it, 0f) }
            RoomAreas("Room ${i + 1}", area, PlanAreas.volume(area, measured(2.5f, 0f)))
        }

        val plan = PlanAreas.of(rooms)

        assertEquals(12f, rooms[0].area!!.value, 1e-4f)
        assertEquals(16f, rooms[1].area!!.value, 1e-4f)
        assertEquals(28f, plan.area!!.value, 1e-4f)
        assertEquals(70f, plan.volume!!.value, 1e-4f)
    }

    @Test
    fun `one unmeasured room leaves the plan without a total`() {
        val plan = PlanAreas.of(
            listOf(
                RoomAreas("Kitchen", measured(12f, 0.3f), null),
                RoomAreas("Cellar", null, null),
            ),
        )

        assertNull(plan.area)
        assertNull(plan.volume)
        assertNull(PlanAreas.of(emptyList()).area)
    }

    @Test
    fun `one estimated room makes the total an estimate`() {
        val plan = PlanAreas.of(
            listOf(
                RoomAreas("Kitchen", measured(12f, 0.3f), measured(30f, 1f)),
                RoomAreas("Attic", SurveyFigure.of(9f, 0.2f, estimate = true), SurveyFigure.of(18f, 0.5f, true)),
            ),
        )

        assertTrue(plan.area!!.estimate)
        assertEquals(0f, plan.area!!.plusMinus, 0f)
        assertEquals(21f, plan.area!!.value, 1e-4f)
    }

    @Test
    fun `the room rows give each room and the total, the same marks as the survey`() {
        val wording = RoomPlanLayoutTest.WORDING
        val plan = PlanAreas.of(
            listOf(
                RoomAreas("Room 1", measured(14.4f, 0.4f), measured(37.44f, 1.76f)),
                RoomAreas("Room 2", null, null),
            ),
        )

        val rows = SurveyReport.roomRows(plan, wording)

        assertEquals(listOf("Room 1", "Room 2", "Total"), rows.map { it.label })
        assertEquals("14.4 m² ± 0.4 · 37.4 m³ ± 1.8", rows[0].value)
        assertEquals("not measured · not measured", rows[1].value)
        assertTrue(rows[1].muted)
        assertTrue(rows[2].muted)
    }

    private fun wall(tag: String, ax: Float, ay: Float, bx: Float, by: Float) = SurveyWall(
        tag = tag, ax = ax, ay = ay, bx = bx, by = by,
        length = SurveyFigure(kotlin.math.abs(bx - ax) + kotlin.math.abs(by - ay), 0.02f),
        seenShare = 1f, seen = true, unseen = emptyList(),
    )
}
