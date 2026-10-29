package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * The floor plan when the depth stalled (QA 2026-10-22): the capture counted ten walls on
 * ARCore's planes, the dense cloud had kept a floor and no wall, and the export said "No plan
 * yet: scan the walls to get one". The planes the capture counted now give the plan.
 */
class PlaneRoomSurveyTest {
    private val floorY = -1.4f

    private fun turned(a: Float, b: Float, yaw: Float) =
        floatArrayOf(a * cos(yaw) - b * sin(yaw), a * sin(yaw) + b * cos(yaw))

    /** A wall plane from ([a0], [b0]) to ([a1], [b1]) in a room turned [yaw], floor to 2.5 m. */
    private fun wall(id: Int, a0: Float, b0: Float, a1: Float, b1: Float, yaw: Float): DebugPlane {
        val (x0, z0) = turned(a0, b0, yaw).let { it[0] to it[1] }
        val (x1, z1) = turned(a1, b1, yaw).let { it[0] to it[1] }
        val top = floorY + 2.5f
        return DebugPlane(id, DebugPlaneKind.Wall, floatArrayOf(x0, floorY, z0, x1, floorY, z1, x1, top, z1, x0, top, z0))
    }

    private fun floorPlane(id: Int, w: Float, d: Float, yaw: Float): DebugPlane {
        val corners = listOf(0f to 0f, w to 0f, w to d, 0f to d).map { (a, b) -> turned(a, b, yaw) }
        return DebugPlane(id, DebugPlaneKind.Floor, corners.flatMap { listOf(it[0], floorY, it[1]) }.toFloatArray())
    }

    /** A 6 × 5 m room turned [yaw], its four walls a little short of the corners, as ARCore finds them. */
    private fun room(yaw: Float, withLastWall: Boolean = true) = listOfNotNull(
        floorPlane(10, 6f, 5f, yaw),
        wall(1, 0.2f, 0f, 5.8f, 0f, yaw),
        wall(2, 6f, 0.3f, 6f, 4.7f, yaw),
        wall(3, 0.1f, 5f, 5.9f, 5f, yaw),
        wall(4, 0f, 0.2f, 0f, 4.8f, yaw).takeIf { withLastWall },
    )

    /** The dense cloud of a stalled depth: the floor only, every surfel facing up, the phone in the middle. */
    private fun floorOnlyCloud(yaw: Float): RoomSurveyInput {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        var a = 0.5f
        while (a < 5.5f) {
            var b = 0.5f
            while (b < 4.5f) {
                val p = turned(a, b, yaw)
                positions += listOf(p[0], floorY, p[1])
                normals += listOf(0f, 1f, 0f)
                b += 0.05f
            }
            a += 0.05f
        }
        val centre = turned(3f, 2.5f, yaw)
        return RoomSurveyInput(positions.toFloatArray(), normals.toFloatArray(), floatArrayOf(centre[0], floorY + 1.4f, centre[1]))
    }

    @Test
    fun `a floor-only cloud has no plan of its own - the bug as it shipped`() {
        val survey = RoomSurveyor.survey(floorOnlyCloud(0.3f))
        assertFalse(survey.hasPlan)
        assertTrue(SurveyGap.NoWalls in survey.gaps)
        assertEquals(PlanOffer.NoWalls, PlanOffer.of(survey))
    }

    @Test
    fun `the walls the capture counted give the plan the export offers`() {
        val yaw = 0.3f
        val survey = RoomSurveyor.survey(floorOnlyCloud(yaw)).orPlanes(room(yaw), floorY)
        assertTrue(survey.hasPlan)
        assertEquals(PlanOffer.Ready, PlanOffer.of(survey))
        assertEquals(4, survey.walls.size)
        assertTrue(survey.walls.all { it.seen })
        assertEquals(6f, survey.planLength, 1e-2f)
        assertEquals(5f, survey.planWidth, 1e-2f)
        assertEquals(30f, survey.area!!.value, 0.1f)
        // Read off planes, not measured: every figure is an estimate, with no scatter.
        assertTrue(survey.area!!.estimate)
        assertTrue(survey.walls.all { it.length.estimate && it.length.plusMinus == 0f })
        assertFalse(SurveyGap.MissingWall in survey.gaps)
        // The plan's DXF is written from it.
        val out = ByteArrayOutputStream()
        RoomPlanDxf.write(out, survey, RoomPlanLayoutTest.WORDING, DXF_WORDS)
        assertTrue(out.toString(Charsets.US_ASCII.name()).contains("LWPOLYLINE"))
    }

    @Test
    fun `a side no counted wall stands on is dashed, and no area is given`() {
        val yaw = -0.2f
        val survey = RoomSurveyor.survey(floorOnlyCloud(yaw)).orPlanes(room(yaw, withLastWall = false), floorY)
        assertTrue(survey.hasPlan)
        assertEquals(1, survey.walls.count { !it.seen })
        assertNull(survey.area)
        assertTrue(SurveyGap.MissingWall in survey.gaps)
        val dashed = survey.walls.single { !it.seen }
        assertEquals(1, dashed.unseen.size)
    }

    @Test
    fun `one wall is no room - the plan still waits`() {
        val planes = listOf(floorPlane(10, 6f, 5f, 0f), wall(1, 0.2f, 0f, 5.8f, 0f, 0f))
        assertNull(PlaneRoomSurvey.of(planes, floorY))
        val cloud = RoomSurveyor.survey(floorOnlyCloud(0f))
        assertSame(cloud, cloud.orPlanes(planes, floorY))
    }

    @Test
    fun `a plan the cloud drew is kept, whatever the planes say`() {
        val drawn = RoomSurvey(
            listOf(SurveyWall("A", 0f, 0f, 1f, 0f, SurveyFigure(1f, 0f), 1f, true, emptyList())),
            1f, 1f, null, null, null, null, null, listOf(SurveyGap.MissingWall), floorY, 0f, 0f,
        )
        assertSame(drawn, drawn.orPlanes(room(0f), floorY))
    }
}

private val DXF_WORDS = RoomPlanDxf.Words(
    legend = listOf("Room plan", "Indicative plan: verify every figure on site.", "Drawing in millimetres, figures in metres."),
    door = "Door %1\$s",
    window = "Window %1\$s",
    sill = "sill %1\$s",
)
