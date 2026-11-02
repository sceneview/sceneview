package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.model.ModelGlb
import io.github.sceneview.demo.demos.internal.model.ScanTidy
import io.github.sceneview.demo.demos.internal.model.WorkMesh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The scan result's picture: the turntable it opens on, the dollhouse cut of a room, the QA
 * shading A/B, the finish's tidy-ups and the `.glb` cache's pipeline key.
 */
class ScanResultRenderTest {
    private val fov = 46.4
    private val zone = FramingInsets(left = 0.04f, top = 0.14f, right = 0.04f, bottom = 0.22f)
    private val room = ScanResultFrame(20f, floatArrayOf(-2f, 0f, -1.5f, 2f, 2.5f, 1.5f), 0f, 1f)

    // ── Turntable ─────────────────────────────────────────────────────────────

    @Test
    fun `the turntable turns slowly from the opening, at its distance, until it is stopped`() {
        val turntable = ScanResultTurntable()
        val area = RerunResultView.area(zone)
        val opening = room.pose(40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, area)
        assertEquals("not turned yet", opening, turntable.pose(room, opening, 40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, zone))
        // The first frame only starts the clock; a second later it has turned a few degrees.
        turntable.advanceTo(1_000_000_000L)
        assertEquals(0f, turntable.angle, 0f)
        turntable.advanceTo(1_016_000_000L)
        repeat(61) { turntable.advanceTo(1_016_000_000L + (it + 1) * 16_000_000L) }
        assertEquals(ScanResultTurntable.DEGREES_PER_SECOND, turntable.angle, 0.1f)
        val turned = turntable.pose(room, opening, 40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, zone)
        assertEquals(opening.azimuthDegrees + turntable.angle, turned.azimuthDegrees, 1e-3f)
        assertEquals("gravity kept: the same pitch", opening.elevationDegrees, turned.elevationDegrees, 1e-3f)
        assertTrue("never closer than the opening", turned.distance >= opening.distance - 1e-4f)
        // A pause — the app away — is not time to turn through.
        val before = turntable.angle
        turntable.advanceTo(60_000_000_000L)
        assertEquals(before, turntable.angle, 0f)
        turntable.stop()
        assertFalse(turntable.running)
        assertEquals(opening, turntable.pose(room, opening, 40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, zone))
        turntable.advance(1f)
        assertEquals("stopped for good", 0f, turntable.angle, 0f)
    }

    @Test
    fun `all the way round the turntable keeps the room whole in the zone, at a steady distance`() {
        val turntable = ScanResultTurntable(degreesPerSecond = 15f)
        val opening = room.pose(40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, RerunResultView.area(zone))
        val distances = ArrayList<Float>()
        repeat(24) {
            turntable.advance(1f)
            distances += turntable.pose(room, opening, 40f, ScanResultFraming.THREE_QUARTER, fov, 0.46f, zone).distance
        }
        // Backed off at most where the box needs it to stay in the zone: within a fifth of the opening's.
        assertTrue("distances ${distances.min()}..${distances.max()}", distances.max() <= opening.distance * 1.25f)
    }

    // ── The dollhouse cut ─────────────────────────────────────────────────────

    @Test
    fun `a room's walls are cut at a fixed height in the renderer's copy, the model left whole`() {
        // Two triangles: one on the floor, one high on a wall.
        val mesh = RerunMesh(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 0f, 1f, 2f, 0f, 0f, 2.4f, 0f),
            normals = FloatArray(18) { if (it % 3 == 1) 1f else 0f },
            colors = IntArray(6) { -1 },
            indices = intArrayOf(0, 1, 2, 3, 4, 5),
        )
        val glb = RerunMeshGlb.write(mesh)
        val copy = ByteBuffer.allocate(glb.size).order(ByteOrder.LITTLE_ENDIAN).put(glb).also { it.rewind() }
        assertEquals(1, ScanResultCut.cut(copy, ScanResultCut.HEIGHT_M))
        val read = assertNotNullAnd(RerunGlbMesh.read(copy.array()))
        // The wall's triangle degenerate on its first corner, the floor's as it was.
        assertEquals(listOf(0, 1, 2), read.indices.take(3))
        assertEquals(read.indices[3], read.indices[4])
        assertEquals(read.indices[3], read.indices[5])
        assertEquals("the model's own bytes untouched", 6, assertNotNullAnd(RerunGlbMesh.read(glb)).indices.toSet().size)
        assertEquals(listOf(0, 1, 2), ScanResultCut.kept(mesh.positions, mesh.indices, ScanResultCut.HEIGHT_M).toList())
    }

    @Test
    fun `a room is framed on its cut box, an object's box stays whole`() {
        val cut = room.cappedAt(ScanResultCut.HEIGHT_M)
        assertEquals(ScanResultCut.HEIGHT_M, cut.local[4], 1e-6f)
        assertEquals(room.local[1], cut.local[1], 0f)
        val shelf = ScanResultFrame(0f, floatArrayOf(0f, 0f, 0f, 0.3f, 0.4f, 0.3f), 0f, 1f)
        assertTrue(shelf.cappedAt(ScanResultCut.HEIGHT_M) === shelf)
    }

    // ── Shading A/B ───────────────────────────────────────────────────────────

    @Test
    fun `the QA shading turns every material unlit or lit, the binary untouched`() {
        val glb = RerunMeshGlb.write(
            RerunMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), FloatArray(9), IntArray(3) { -1 }, intArrayOf(0, 1, 2)),
        )
        val unlit = assertNotNullAnd(ModelGlb.withShading(glb, lit = false))
        assertTrue(String(unlit, Charsets.ISO_8859_1).contains(ModelGlb.UNLIT))
        val lit = assertNotNullAnd(ModelGlb.withShading(unlit, lit = true))
        assertFalse(String(lit, Charsets.ISO_8859_1).contains(ModelGlb.UNLIT))
        assertEquals(RerunGlbMesh.read(glb)!!.indices.toList(), RerunGlbMesh.read(lit)!!.indices.toList())
        assertEquals(ResultShading.Lit, ResultShading.of("scan_lit"))
        assertEquals(ResultShading.AsBuilt, ResultShading.of("anything"))
        assertEquals(ResultShading.AsBuilt, ResultShading.Unlit.next)
    }

    // ── The finish's tidy-ups ─────────────────────────────────────────────────

    @Test
    fun `a piece seen through a window is cut off with the scan's box, the walls kept`() {
        val mesh = grid(0f, 0f, 4f, 3f, step = 0.1f)
        val withStray = mesh.plus(grid(9f, 9f, 9.2f, 9.2f, step = 0.1f))
        val (cropped, cut) = ScanTidy.crop(withStray)
        assertEquals("the stray piece's triangles", withStray.triangleCount - mesh.triangleCount, cut)
        assertEquals(mesh.triangleCount, cropped.triangleCount)
    }

    @Test
    fun `holes in a room's floor are closed under its plane in its colour, an object's base is not`() {
        val floor = grid(0f, 0f, 3f, 3f, step = 0.05f, colour = 0xFF806040.toInt()) { x, z ->
            // A 40 cm hole the scan missed in the middle.
            x in 1.3f..1.7f && z in 1.3f..1.7f
        }
        val (closed, cells) = ScanTidy.closeFloor(floor)
        assertTrue("$cells cells closed", cells in 36..100)
        val added = closed.vertexCount - floor.vertexCount
        assertEquals(cells * 4, added)
        for (v in floor.vertexCount until closed.vertexCount) {
            assertEquals(-ScanTidy.FILL_DROP_M, closed.positions[v * 3 + 1], 1e-4f)
            assertEquals(0xFF806040.toInt(), closed.colors[v])
        }
        // Nothing closed outside the floor.
        for (v in floor.vertexCount until closed.vertexCount) {
            assertTrue(closed.positions[v * 3] in 1.1f..1.9f && closed.positions[v * 3 + 2] in 1.1f..1.9f)
        }
        val base = grid(0f, 0f, 0.5f, 0.5f, step = 0.05f) { x, z -> x in 0.2f..0.3f && z in 0.2f..0.3f }
        assertEquals(0, ScanTidy.closeFloor(base).second)
    }

    // ── The cache's key ───────────────────────────────────────────────────────

    @Test
    fun `a model an earlier finish built is read as stale, built again, and this one's is not`() {
        val glb = RerunMeshGlb.write(
            RerunMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), FloatArray(9), IntArray(3) { -1 }, intArrayOf(0, 1, 2)),
        )
        val model = RerunScanModel(RerunScanModel.Kind.Mesh, glb, floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f), 1, 3)
        val info = model.infoJson()
        assertTrue(info.contains("\"pipeline\": ${RerunScanModel.PIPELINE}"))
        assertFalse(assertNotNullAnd(RerunScanModel.parse(info, glb)).stalePipeline)
        val earlier = info.replace("\"pipeline\": ${RerunScanModel.PIPELINE},", "")
        assertFalse(earlier.contains("pipeline"))
        val stale = assertNotNullAnd(RerunScanModel.parse(earlier, glb))
        assertTrue(stale.stalePipeline)
        assertTrue("still shown as it is", stale.finished)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** A flat, up-facing grid on y = 0 over [x0]..[x1] × [z0]..[z1], less the cells [hole] marks. */
    private fun grid(
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        step: Float,
        colour: Int = -1,
        hole: (Float, Float) -> Boolean = { _, _ -> false },
    ): WorkMesh {
        val cols = Math.round((x1 - x0) / step)
        val rows = Math.round((z1 - z0) / step)
        val positions = ArrayList<Float>()
        for (r in 0..rows) for (c in 0..cols) positions += listOf(x0 + c * step, 0f, z0 + r * step)
        val indices = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (hole(x0 + (c + 0.5f) * step, z0 + (r + 0.5f) * step)) continue
                val v = r * (cols + 1) + c
                indices += listOf(v, v + cols + 1, v + 1, v + 1, v + cols + 1, v + cols + 2)
            }
        }
        return WorkMesh(positions.toFloatArray(), IntArray(positions.size / 3) { colour }, indices.toIntArray())
    }

    private fun WorkMesh.plus(other: WorkMesh): WorkMesh = WorkMesh(
        positions + other.positions,
        colors + other.colors,
        indices + other.indices.map { it + vertexCount },
    )
}
