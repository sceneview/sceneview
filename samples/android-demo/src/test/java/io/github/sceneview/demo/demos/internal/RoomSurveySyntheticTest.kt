package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The survey read end to end on rooms ray-cast into depth frames, as a phone's scan would give
 * them: depth back-projected, fused into the dense cloud, then surveyed. Every figure lands within
 * [TOLERANCE] of the room the frames were cast from — a rectangle, an L and a T. The plan a
 * bounding rectangle would give is held next to it: on an L or a T it misses by a fifth or more,
 * which is why the survey traces the walls instead.
 */
class RoomSurveySyntheticTest {

    /** The scan's input: the fused cloud with its normals, and the camera path. */
    private fun scan(scene: SyntheticScene, stops: List<Vec3>): RoomSurveyInput {
        val fusion = DenseFusion()
        val poses = RerunSyntheticRoom.poses(stops)
        for (pose in poses) fusion.add(DepthBackProjection.project(scene.render(pose)))
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        val trail = FloatArray(poses.size * 3)
        poses.forEachIndexed { i, p ->
            trail[i * 3] = p.x
            trail[i * 3 + 1] = p.y
            trail[i * 3 + 2] = p.z
        }
        return RoomSurveyInput(cloud.positions, cloud.normals, trail)
    }

    /** An empty room: the union of [boxes] (min, max), floor at 0, ceiling at [HEIGHT]. */
    private fun rooms(vararg boxes: Pair<Vec3, Vec3>) = SyntheticScene(
        listOf(SyntheticShape.Rooms(boxes.map { (lo, hi) -> SyntheticShape.Room(lo, hi, ::paint) }, ::paint)),
    )

    @Suppress("UNUSED_PARAMETER")
    private fun paint(x: Float, y: Float, z: Float) = PAINT

    private fun box(x0: Float, z0: Float, x1: Float, z1: Float) = Vec3(x0, 0f, z0) to Vec3(x1, HEIGHT, z1)

    private fun assertWithin(what: String, expected: Float, actual: Float?) {
        assertNotNull("$what: no figure", actual)
        val error = abs(actual!! - expected) / expected
        assertTrue("$what: $actual for $expected (${"%.1f".format(error * 100)} %)", error <= TOLERANCE)
    }

    /** Each wall's length against [expected], both sorted: the outline, whatever wall it starts on. */
    private fun assertWalls(survey: RoomSurvey, vararg expected: Float) {
        val lengths = survey.walls.map { it.length.value }.sorted()
        assertEquals("walls ${survey.walls.map { "${it.tag} ${it.length.value}" }}", expected.size, lengths.size)
        expected.sorted().zip(lengths).forEachIndexed { i, (e, a) -> assertWithin("wall #$i", e, a) }
    }

    /** The area of the rectangle around the cloud's floor: the figure a bounding box gives. */
    private fun boundingArea(input: RoomSurveyInput, floorY: Float): Float {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        val p = input.positions
        for (i in 0 until p.size / 3) {
            if (abs(p[i * 3 + 1] - floorY) > FLOOR_BAND_M) continue
            minX = minOf(minX, p[i * 3]); maxX = maxOf(maxX, p[i * 3])
            minZ = minOf(minZ, p[i * 3 + 2]); maxZ = maxOf(maxZ, p[i * 3 + 2])
        }
        return (maxX - minX) * (maxZ - minZ)
    }

    @Test
    fun `the furnished room reads 4 by 3_6 m and 14_4 m2`() {
        val input = scan(RerunSyntheticRoom.scene, RerunSyntheticRoom.STOPS)
        val survey = RoomSurveyor.survey(input)
        assertWithin("area", 14.4f, survey.area?.value)
        assertWithin("length", 4f, survey.length?.value)
        assertWithin("width", 3.6f, survey.width?.value)
        assertWalls(survey, 4f, 3.6f, 4f, 3.6f)
        assertWithin("height", 2.6f, survey.ceilingHeight?.value)
    }

    @Test
    fun `an L-shaped room reads its six walls and 18 m2, where a bounding box reads 21_6`() {
        // A 4 × 3.6 m room and a 2 × 1.8 m wing off one end of its long side.
        val scene = rooms(box(-2f, -1.8f, 2f, 1.8f), box(-2f, 1.7f, 0f, 3.6f))
        val input = scan(scene, listOf(Vec3(0.4f, 1.4f, 0f), Vec3(-1.1f, 1.4f, 2.7f), Vec3(1.2f, 1.5f, -0.9f)))
        val survey = RoomSurveyor.survey(input)
        assertWithin("area", 18f, survey.area?.value)
        assertWalls(survey, 4f, 3.6f, 2f, 1.8f, 2f, 5.4f)
        assertWithin("perimeter", 18.8f, survey.perimeter?.value)
        assertTrue(boundingArea(input, survey.floorY) > 18f * (1f + 0.15f))
    }

    @Test
    fun `an open plan with an alcove reads its eight walls and 22 m2, where a bounding box reads 30`() {
        // A 6 × 3 m living space and a 2 × 2 m alcove off the middle of one long side: a T.
        val scene = rooms(box(-3f, -1.5f, 3f, 1.5f), box(-1f, 1.4f, 1f, 3.5f))
        val input = scan(scene, listOf(Vec3(-1.8f, 1.4f, 0f), Vec3(1.8f, 1.4f, -0.3f), Vec3(0f, 1.5f, 2.5f)))
        val survey = RoomSurveyor.survey(input)
        assertWithin("area", 22f, survey.area?.value)
        assertWalls(survey, 6f, 3f, 2f, 2f, 2f, 2f, 2f, 3f)
        assertWithin("perimeter", 22f, survey.perimeter?.value)
        assertTrue(boundingArea(input, survey.floorY) > 22f * (1f + 0.3f))
    }

    private companion object {
        /** The issue's bar: every figure within 3 % of the room it was cast from. */
        const val TOLERANCE = 0.03f
        const val HEIGHT = 2.6f
        const val FLOOR_BAND_M = 0.05f
        const val PAINT = 0xFFE8E2D6.toInt()
    }
}
