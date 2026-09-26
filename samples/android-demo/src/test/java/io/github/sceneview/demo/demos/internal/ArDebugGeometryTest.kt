package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the geometry of the Rerun demo's in-app 3D view (#3950). The meshes go straight into
 * Filament vertex and index buffers, so the properties worth pinning are the ones a GPU punishes
 * silently: every index in range, no NaN vertex, each thing on the layer that colours it — and
 * sizes that stay screen-constant, which is what keeps a far trail from vanishing.
 */
class ArDebugGeometryTest {

    private val style = ArDebugStyle(0.002f)

    private fun layers(): Pair<Map<DebugLayer, DebugMesh>, (DebugLayer) -> DebugMesh> {
        val meshes = DebugLayer.entries.associateWith { DebugMesh() }
        return meshes to { layer: DebugLayer -> meshes.getValue(layer) }
    }

    private fun assertWellFormed(mesh: DebugMesh) {
        assertEquals(0, mesh.indexCount % 3)
        for (i in 0 until mesh.indexCount) {
            assertTrue("index ${mesh.indices[i]} out of ${mesh.vertexCount}", mesh.indices[i] in 0 until mesh.vertexCount)
        }
        for (i in 0 until mesh.vertexCount * 3) assertTrue(mesh.positions[i].isFinite())
    }

    private fun walk(n: Int, step: Float = 0.05f) = FloatArray(n * 3) { i ->
        when (i % 3) {
            0 -> (i / 3) * step
            1 -> 0.1f * kotlin.math.sin((i / 3) * 0.2f)
            else -> -(i / 3) * step * 0.5f
        }
    }

    @Test
    fun `the trail is spread over the gradient steps, with a brighter head`() {
        val (meshes, out) = layers()
        ArDebugGeometry.buildTrail(walk(200), style, out)

        DebugLayer.trailSteps.forEach { layer ->
            assertFalse("$layer is empty", meshes.getValue(layer).isEmpty)
            assertWellFormed(meshes.getValue(layer))
        }
        assertFalse(meshes.getValue(DebugLayer.TrailHead).isEmpty)
        assertWellFormed(meshes.getValue(DebugLayer.TrailHead))
    }

    @Test
    fun `a trail of one pose draws no tube`() {
        val (meshes, out) = layers()
        ArDebugGeometry.buildTrail(floatArrayOf(0f, 0f, 0f), style, out)

        assertTrue((DebugLayer.trailSteps + DebugLayer.TrailHead).all { meshes.getValue(it).isEmpty })
    }

    @Test
    fun `a trail that doubles back on itself stays finite`() {
        val back = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        val (meshes, out) = layers()
        ArDebugGeometry.buildTrail(back, style, out)

        meshes.values.forEach(::assertWellFormed)
    }

    @Test
    fun `the live camera draws a frustum and its face, history frustums their own layer`() {
        val trace = ArDebugTrace()
        for (i in 0..80) trace.addPose(1_000_000_000L + i * 100_000_000L, DebugPose(i * 0.05f, 0f, 0f))
        val (meshes, out) = layers()
        ArDebugGeometry.buildCamera(trace.frameAt(trace.duration), style, out)

        for (layer in listOf(DebugLayer.Frustum, DebugLayer.FrustumFace, DebugLayer.Keyframes)) {
            assertFalse("$layer is empty", meshes.getValue(layer).isEmpty)
            assertWellFormed(meshes.getValue(layer))
        }
    }

    @Test
    fun `planes land on the layer of their kind`() {
        val floor = DebugPlane(1, DebugPlaneKind.Floor, square(y = -1.3f))
        val wall = DebugPlane(2, DebugPlaneKind.Wall, floatArrayOf(0f, 0f, -2f, 1f, 0f, -2f, 1f, 1f, -2f, 0f, 1f, -2f))
        val (meshes, out) = layers()
        ArDebugGeometry.buildPlanes(listOf(floor, wall), style, out)

        for (layer in listOf(DebugLayer.PlaneFloor, DebugLayer.OutlineFloor, DebugLayer.PlaneWall, DebugLayer.OutlineWall)) {
            assertFalse("$layer is empty", meshes.getValue(layer).isEmpty)
            assertWellFormed(meshes.getValue(layer))
        }
        assertTrue(meshes.getValue(DebugLayer.PlaneOther).isEmpty)
        // ARCore's polygons are convex: one triangle per edge, fanned from the centroid.
        assertEquals(4, meshes.getValue(DebugLayer.PlaneFloor).triangleCount)
    }

    @Test
    fun `every map point and live point is a small closed solid`() {
        val points = walk(50)
        val map = DebugMesh()
        val live = DebugMesh()
        ArDebugGeometry.buildMapPoints(points, style, map)
        ArDebugGeometry.buildLivePoints(points, style, live)

        assertEquals(50 * 4, map.triangleCount) // tetrahedra
        assertEquals(50 * 8, live.triangleCount) // octahedra
        assertWellFormed(map)
        assertWellFormed(live)
    }

    @Test
    fun `anchors draw rings`() {
        val mesh = DebugMesh()
        ArDebugGeometry.buildAnchors(listOf(DebugAnchor(1, DebugPose(0f, -1f, -1f), 0f)), style, mesh)

        assertFalse(mesh.isEmpty)
        assertWellFormed(mesh)
        val bounds = mesh.bounds()!!
        assertTrue(bounds[3] - bounds[0] <= ArDebugGeometry.ANCHOR_RING_M * 2f + 0.05f)
    }

    @Test
    fun `the stage grid covers the content with a margin, on whole cells, at floor height`() {
        val (meshes, out) = layers()
        ArDebugGeometry.buildStage(floatArrayOf(-0.3f, 0f, -2.2f, 1.1f, 0f, 0.4f), -1.2f, style, out)

        val minor = meshes.getValue(DebugLayer.GridMinor)
        val major = meshes.getValue(DebugLayer.GridMajor)
        assertWellFormed(minor)
        assertWellFormed(major)
        val b = major.bounds()!!
        assertTrue(b[0] <= -1.3f && b[3] >= 2.1f)
        assertTrue(b[2] <= -3.2f && b[5] >= 1.4f)
        assertEquals(-1.2f, b[1], 0.01f)
        for (axis in listOf(DebugLayer.AxisX, DebugLayer.AxisY, DebugLayer.AxisZ)) assertFalse(meshes.getValue(axis).isEmpty)
    }

    @Test
    fun `the floor is the lowest floor plane, else below the first pose`() {
        val withFloor = frame(planes = listOf(DebugPlane(1, DebugPlaneKind.Floor, square(-1.4f)), DebugPlane(2, DebugPlaneKind.Floor, square(-0.7f))))
        assertEquals(-1.4f, ArDebugGeometry.floorHeight(withFloor), 1e-4f)

        val walkOnly = frame(trail = floatArrayOf(0f, 0.2f, 0f))
        assertEquals(0.2f - 1.3f, ArDebugGeometry.floorHeight(walkOnly), 1e-4f)
    }

    @Test
    fun `framing bounds ignore feature points, so one outlier cannot zoom the view out`() {
        val frame = frame(trail = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f), mapPoints = floatArrayOf(50f, 50f, 50f))
        val bounds = ArDebugGeometry.contentBounds(frame)!!

        assertEquals(1f, bounds[3], 1e-4f)
        assertNull(ArDebugGeometry.contentBounds(frame()))
        assertNotNull(ArDebugGeometry.contentBounds(frame(anchors = listOf(DebugAnchor(1, DebugPose(2f, 0f, 0f), 0f)))))
    }

    @Test
    fun `sizes are screen-constant, and quantised so a pinch does not rebuild every frame`() {
        val near = ArDebugStyle.forOrbit(distance = 1f, verticalFovDegrees = 45.0, viewportHeightPx = 2000)
        val far = ArDebugStyle.forOrbit(distance = 8f, verticalFovDegrees = 45.0, viewportHeightPx = 2000)
        assertTrue(far.trailRadius > near.trailRadius)

        val a = ArDebugStyle.forOrbit(distance = 2.00f, verticalFovDegrees = 45.0, viewportHeightPx = 2000)
        val b = ArDebugStyle.forOrbit(distance = 2.02f, verticalFovDegrees = 45.0, viewportHeightPx = 2000)
        assertEquals(a, b)

        val degenerate = ArDebugStyle.forOrbit(distance = 2f, verticalFovDegrees = 45.0, viewportHeightPx = 0)
        assertTrue(degenerate.trailRadius.isFinite() && degenerate.trailRadius > 0f)
    }

    @Test
    fun `a mesh cleared and rebuilt holds only the new geometry`() {
        val mesh = DebugMesh()
        ArDebugGeometry.buildMapPoints(walk(10), style, mesh)
        mesh.clear()
        ArDebugGeometry.buildMapPoints(walk(2), style, mesh)

        assertEquals(8, mesh.triangleCount)
        assertEquals(8, mesh.vertexCount)
    }

    private fun square(y: Float) = floatArrayOf(-1f, y, -1f, 1f, y, -1f, 1f, y, 1f, -1f, y, 1f)

    private fun frame(
        trail: FloatArray = FloatArray(0),
        mapPoints: FloatArray = FloatArray(0),
        planes: List<DebugPlane> = emptyList(),
        anchors: List<DebugAnchor> = emptyList(),
    ) = ArDebugFrame(
        time = 0f,
        trail = trail,
        camera = null,
        mapPoints = mapPoints,
        livePoints = FloatArray(0),
        planes = planes,
        anchors = anchors,
    )
}
