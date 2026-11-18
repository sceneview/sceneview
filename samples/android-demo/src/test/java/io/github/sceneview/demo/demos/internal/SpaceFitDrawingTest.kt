package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * A space's measure as it is drawn on its model ([SpaceFitDrawing]): the same boxes the card's
 * figures say, stood where the scan found them.
 */
class SpaceFitDrawingTest {
    private val pipe = SpaceBox(0.26f, 0.24f, 0.1f, 0.08f, 0.32f, 0.08f)
    private val box = SpaceBox(0.01f, 0f, 0.22f, 0.58f, 0.55f, 0.28f)

    private fun fit(fits: SpaceBox? = box, obstacles: List<SpaceBox> = listOf(pipe)) =
        SpaceFit(WIDTH, HEIGHT, DEPTH, setOf(SpaceFace.Front), obstacles, fits, ORIGIN_X, ORIGIN_Y, ORIGIN_Z, YAW_DEGREES)

    @Test
    fun `a box's corners stand where the fit puts them in the scan`() {
        val fit = fit()
        val corners = SpaceFitDrawing.corners(fit, box)
        assertEquals(CORNERS, corners.size)
        assertNear(fit.world(box.x, box.y, box.z), corners[0])
        assertNear(fit.world(box.x + box.width, box.y, box.z), corners[1])
        assertNear(fit.world(box.x, box.y + box.height, box.z), corners[2])
        assertNear(fit.world(box.x, box.y, box.z + box.depth), corners[4])
        assertNear(fit.world(box.x + box.width, box.y + box.height, box.z + box.depth), corners[CORNERS - 1])
        // Turned with the space, never stretched: its sides keep their lengths.
        assertEquals(box.width, (corners[1] - corners[0]).length(), EPS)
        assertEquals(box.height, (corners[2] - corners[0]).length(), EPS)
        assertEquals(box.depth, (corners[4] - corners[0]).length(), EPS)
    }

    @Test
    fun `the inside, each thing in the way and the box are drawn, each on its own layer`() {
        val meshes = SpaceFitDrawing.meshes(fit(), RADIUS)
        // The same count of triangles for every box's twelve edges, whatever its size.
        val edges = meshes.inside.triangleCount
        assertTrue(edges > 0)
        assertEquals(edges, meshes.obstacles.triangleCount)
        assertEquals(edges, meshes.boxEdges.triangleCount)
        assertEquals(FACES * 2, meshes.boxFaces.triangleCount)
        assertEquals(edges * 2, SpaceFitDrawing.meshes(fit(obstacles = listOf(pipe, pipe)), RADIUS).obstacles.triangleCount)
    }

    @Test
    fun `its result is framed on its inside, seen from its opening`() {
        val fit = fit()
        val frame = SpaceFitDrawing.frame(fit)
        val margin = SpaceFitDrawing.FRAME_MARGIN_M
        assertEquals(WIDTH + 2 * margin, frame.local[3] - frame.local[0], EPS)
        assertEquals(HEIGHT + margin, frame.local[4] - frame.local[1], EPS)
        assertEquals(DEPTH + 2 * margin, frame.local[5] - frame.local[2], EPS)
        // Its floor is the space's own bottom: nothing is shown under it.
        assertEquals(ORIGIN_Y, frame.local[1], EPS)
        val middle = fit.world(WIDTH / 2f, 0f, DEPTH / 2f)
        assertEquals(middle.x, frame.worldCentre.first, EPS)
        assertEquals(middle.z, frame.worldCentre.second, EPS)
        // Square on, the eye stands where the space's +Z points: outside its opening.
        val pose = frame.pose(ScanResultFraming.OBJECT_ELEVATION, 0f, FOV, ASPECT, FramingInsets.None)
        val azimuth = Math.toRadians(pose.azimuthDegrees.toDouble())
        val out = fit.world(0f, 0f, 1f) - fit.world(0f, 0f, 0f)
        assertEquals(out.x, sin(azimuth).toFloat(), EPS)
        assertEquals(out.z, cos(azimuth).toFloat(), EPS)
        // And it looks at the space itself.
        assertEquals(middle.x, pose.target.x, LOOK)
        assertEquals(middle.z, pose.target.z, LOOK)
    }

    @Test
    fun `no box that fits, no box drawn`() {
        val meshes = SpaceFitDrawing.meshes(fit(fits = null, obstacles = emptyList()), RADIUS)
        assertTrue(meshes.boxEdges.isEmpty)
        assertTrue(meshes.boxFaces.isEmpty)
        assertTrue(meshes.obstacles.isEmpty)
        assertFalse(meshes.inside.isEmpty)
    }

    @Test
    fun `every edge stays within its own width of the box it outlines`() {
        val mesh = SpaceFitDrawing.meshes(fit(), RADIUS).inside
        val reach = RADIUS * REACH
        val yaw = Math.toRadians(YAW_DEGREES.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        for (i in 0 until mesh.vertexCount) {
            // Back into the space's own frame, the turn of SpaceFit.world undone.
            val dx = mesh.positions[i * 3] - ORIGIN_X
            val dz = mesh.positions[i * 3 + 2] - ORIGIN_Z
            val x = dx * c - dz * s
            val y = mesh.positions[i * 3 + 1] - ORIGIN_Y
            val z = dx * s + dz * c
            assertTrue("vertex $i is at $x across", x >= -reach && x <= WIDTH + reach)
            assertTrue("vertex $i is at $y up", y >= -reach && y <= HEIGHT + reach)
            assertTrue("vertex $i is at $z deep", z >= -reach && z <= DEPTH + reach)
        }
    }

    private fun assertNear(expected: Vec3, actual: Vec3) {
        assertEquals(expected.x, actual.x, EPS)
        assertEquals(expected.y, actual.y, EPS)
        assertEquals(expected.z, actual.z, EPS)
    }

    private companion object {
        const val WIDTH = 0.6f
        const val HEIGHT = 0.56f
        const val DEPTH = 0.5f
        const val ORIGIN_X = 1.2f
        const val ORIGIN_Y = 0.12f
        const val ORIGIN_Z = -0.8f
        const val YAW_DEGREES = 37f
        const val RADIUS = 0.004f
        const val CORNERS = 8
        const val FACES = 6
        const val EPS = 1e-4f
        const val LOOK = 0.2f
        const val FOV = 45.0
        const val ASPECT = 0.5f

        /** An edge runs its half-width past each corner and is as wide across, with room for its corners. */
        const val REACH = 3f
    }
}
