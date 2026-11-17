package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RoomFigures]: a room's figures are called the room's only when the scan saw a wall at each
 * end, both ways, and those walls close the same room as the figures drawn.
 */
class RoomFiguresTest {

    private fun measure(width: Float, depth: Float) =
        RoomMeasure(floatArrayOf(0f, 0f, width, 0f, width, depth, 0f, depth), width, depth, yaw = 0f)

    /** A room ray-cast and fused as a phone scans it, [keep] the points of it the scan holds. */
    private fun survey(keep: (x: Float, z: Float) -> Boolean = { _, _ -> true }): Pair<RoomSurvey, FloatArray> {
        val fusion = DenseFusion()
        for (pose in RerunSyntheticRoom.poses(stops = RerunSyntheticRoom.STOPS)) {
            fusion.add(DepthBackProjection.project(RerunSyntheticRoom.scene.render(pose)))
        }
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val normals = cloud.normals!!
        val kept = (0 until cloud.count).filter { keep(cloud.positions[it * 3], cloud.positions[it * 3 + 2]) }
        val positions = FloatArray(kept.size * 3) { cloud.positions[kept[it / 3] * 3 + it % 3] }
        val keptNormals = FloatArray(kept.size * 3) { normals[kept[it / 3] * 3 + it % 3] }
        return RoomSurveyor.survey(RoomSurveyInput(positions, keptNormals)) to positions
    }

    @Test
    fun `no walls read, the figures span what was scanned`() {
        assertNull(RoomFigures.wallsOf(null))
        assertNull(RoomFigures.wallsOf(RoomSurvey.empty(SurveyGap.NoDepth)))
        assertEquals(RoomFigures.Scanned, RoomFigures.of(measure(2.83f, 4.06f), walls = null))
    }

    @Test
    fun `walls that close the same room make the figures the room's`() {
        assertEquals(RoomFigures.Room, RoomFigures.of(measure(4.08f, 3.66f), walls = measure(4f, 3.6f)))
        // The same room, its sides named the other way round.
        assertEquals(RoomFigures.Room, RoomFigures.of(measure(3.66f, 4.08f), walls = measure(4f, 3.6f)))
    }

    @Test
    fun `points that go past the walls are not the room`() {
        // 1.2 m more one way: what a doorway let the phone film of the next room.
        assertEquals(RoomFigures.Scanned, RoomFigures.of(measure(5.2f, 3.6f), walls = measure(4f, 3.6f)))
        assertEquals(RoomFigures.Scanned, RoomFigures.of(measure(4f, 3.2f), walls = measure(4f, 3.6f)))
    }

    @Test
    fun `a room scanned all round has its walls and its figures agree with them`() {
        val (survey, positions) = survey()
        val walls = RoomFigures.wallsOf(survey)
        assertNotNull("no walls for a room seen all round", walls)
        val shown = RoomMeasure.ofPoints(positions, survey.structure!!.yaw)!!
        assertEquals(
            "points ${shown.width} × ${shown.depth}, walls ${walls!!.width} × ${walls.depth}",
            RoomFigures.Room, RoomFigures.of(shown, walls),
        )
    }

    @Test
    fun `a room with one end never filmed has no walls to call its figures the room's`() {
        // The scan stops a metre short of one wall: nothing of that end is in it.
        val (survey, _) = survey { x, _ -> x < CUT_X }
        assertNull("length ${survey.length?.value} estimate ${survey.length?.estimate}, width ${survey.width?.value} " +
            "estimate ${survey.width?.estimate}", RoomFigures.wallsOf(survey))
    }

    private companion object {
        const val CUT_X = 1f
    }
}
