package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * What the replay draws round a tapped object (#4485): the box's edges, the tint over its points,
 * the written sizes — and the camera a tap is cast through, pinned to the one that draws.
 */
class ScanObjectMeshesTest {

    @Test
    fun `a box is drawn as twelve tubes that run past its corners`() {
        val box = ScanBox(centerX = 1f, centerZ = -2f, baseY = 0.1f, yaw = 0f, length = 2f, depth = 1f, height = 0.8f)
        val mesh = ScanObjectMeshes.edges(box, RADIUS)

        assertEquals(12 * 8, mesh.vertexCount)
        val bounds = mesh.bounds()!!
        // Each edge ends a radius past its corners; its square section reaches √2 radii at most.
        val reach = RADIUS * sqrt(2f) + EPSILON
        val expected = floatArrayOf(0f, 0.1f, -2.5f, 2f, 0.9f, -1.5f)
        for (axis in 0 until 3) {
            assertTrue("min of axis $axis", bounds[axis] in expected[axis] - reach..expected[axis] - RADIUS + EPSILON)
            assertTrue(
                "max of axis $axis",
                bounds[axis + 3] in expected[axis + 3] + RADIUS - EPSILON..expected[axis + 3] + reach,
            )
        }
    }

    @Test
    fun `the box's mesh is rebuilt in place`() {
        val box = ScanBox(centerX = 0f, centerZ = 0f, baseY = 0f, yaw = 0.4f, length = 1f, depth = 0.5f, height = 0.5f)
        val mesh = DebugMesh()
        ScanObjectMeshes.edges(box, RADIUS, mesh)
        val indices = mesh.indexCount

        assertEquals(mesh, ScanObjectMeshes.edges(box, RADIUS * 2, mesh))
        assertEquals(12 * 8, mesh.vertexCount)
        assertEquals(indices, mesh.indexCount)
    }

    @Test
    fun `the tint is the picked surfels' own quads, and only theirs`() {
        val cloud = DenseCloud(
            positions = FloatArray(5 * 3) { it * 0.1f },
            colors = IntArray(5),
            normals = FloatArray(5 * 3) { if (it % 3 == 1) 1f else 0f },
        )
        val drawn = DenseSurfels.mesh(cloud, 0.02f)

        // Surfel 7 is not in the cloud: left out, not read past the end.
        val tint = ScanObjectMeshes.surfels(drawn, intArrayOf(1, 3, 7))

        assertEquals(2 * ScanObjectMeshes.VERTICES_PER_SURFEL, tint.vertexCount)
        assertEquals(4, tint.triangleCount)
        for ((slot, surfel) in listOf(1, 3).withIndex()) {
            for (k in 0 until ScanObjectMeshes.VERTICES_PER_SURFEL * 3) {
                assertEquals(
                    drawn.positions[surfel * ScanObjectMeshes.VERTICES_PER_SURFEL * 3 + k],
                    tint.positions[slot * ScanObjectMeshes.VERTICES_PER_SURFEL * 3 + k],
                    0f,
                )
            }
        }
        assertTrue(ScanObjectMeshes.surfels(drawn, IntArray(0)).isEmpty)
    }

    @Test
    fun `a box's sizes are written in metres to the centimetre`() {
        val box = ScanBox(0f, 0f, 0f, 0f, length = 1.824f, depth = 0.856f, height = 0.7f)

        assertEquals(Triple("1.82", "0.86", "0.70"), box.sizes())
    }

    // ── The label never goes under the chrome ─────────────────────────────────

    @Test
    fun `the label sits over the box while the chrome leaves it room there`() {
        // The free band is rows 600..1800; a box drawn from 900 down to 1300.
        assertEquals(900 - GAP - LABEL, ScanObjectMeshes.labelTop(900f, 1300f, LABEL, GAP, BAND))
        // Its top just clears the band: still over it, to the pixel.
        assertEquals(600, ScanObjectMeshes.labelTop(600f + GAP + LABEL, 1300f, LABEL, GAP, BAND))
    }

    @Test
    fun `a box whose top is under the cards gets its label under it`() {
        assertEquals(1300 + GAP, ScanObjectMeshes.labelTop(640f, 1300f, LABEL, GAP, BAND))
        // Under the cards altogether: the label waits at the top of the band.
        assertEquals(600, ScanObjectMeshes.labelTop(200f, 400f, LABEL, GAP, BAND))
    }

    @Test
    fun `a box that fills the band keeps its label in the band, across its foot`() {
        assertEquals(1800 - LABEL, ScanObjectMeshes.labelTop(620f, 1790f, LABEL, GAP, BAND))
        // Drawn below the band, behind the dock: the label stops at the band's last rows.
        assertEquals(1800 - LABEL, ScanObjectMeshes.labelTop(2000f, 2300f, LABEL, GAP, BAND))
        // A band shorter than the label: its top, never a negative row.
        assertEquals(600, ScanObjectMeshes.labelTop(900f, 1300f, LABEL, GAP, 600..640))
    }

    // ── The tap's camera is the one that draws ────────────────────────────────

    @Test
    fun `the tap camera stands where the orbit draws from and sees its pivot at the centre`() {
        val orbit = orbit(lift = 0f)
        val camera = orbit.scanCamera()

        val eye = orbit.getTransform().position
        assertEquals(eye.x, camera.eye.x, EPSILON)
        assertEquals(eye.y, camera.eye.y, EPSILON)
        assertEquals(eye.z, camera.eye.z, EPSILON)
        val (x, y) = camera.project(TARGET)!!
        assertEquals(WIDTH / 2f, x, PIXEL)
        assertEquals(HEIGHT / 2f, y, PIXEL)
    }

    @Test
    fun `a lifted stage is tapped where it is drawn`() {
        val orbit = orbit(lift = LIFT)
        val camera = orbit.scanCamera()

        val eye = orbit.getTransform().position
        assertEquals(eye.x, camera.eye.x, EPSILON)
        assertEquals(eye.y, camera.eye.y, EPSILON)
        assertEquals(eye.z, camera.eye.z, EPSILON)
        // The pivot is drawn LIFT of the view's height above its centre.
        val (x, y) = camera.project(TARGET)!!
        assertEquals(WIDTH / 2f, x, PIXEL)
        assertEquals(HEIGHT / 2f - LIFT * HEIGHT, y, PIXEL)
        // And a tap there is a ray back through the pivot.
        val ray = camera.ray(x, y)
        val toPivot = (TARGET - camera.eye).normalized()
        assertEquals(1f, ray.dot(toPivot), 1e-5f)
    }

    private fun orbit(lift: Float) = ArDebugOrbitCamera(
        initialPose = OrbitPose(
            target = Position(TARGET.x, TARGET.y, TARGET.z),
            azimuthDegrees = 35f,
            elevationDegrees = 28f,
            distance = 6f,
        ),
        drift = false,
        lift = lift,
    ).apply { setViewport(WIDTH, HEIGHT) }

    private companion object {
        const val RADIUS = 0.01f
        const val EPSILON = 1e-4f
        const val PIXEL = 0.5f
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val LIFT = 0.12f
        const val LABEL = 150
        const val GAP = 20
        val BAND = 600..1800
        val TARGET = Vec3(0.4f, 0.6f, -1.2f)
    }
}
