package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.model.ModelGlb
import io.github.sceneview.demo.demos.internal.model.ModelMesh
import io.github.sceneview.demo.demos.internal.model.PhotoTexture
import io.github.sceneview.demo.demos.internal.model.PhotoTextureStats
import io.github.sceneview.demo.demos.internal.model.ScanTidy
import io.github.sceneview.demo.demos.internal.model.WorkMesh
import io.github.sceneview.demo.demos.resultFramedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scan result's picture: the turntable it opens on, the dollhouse cut of a room, the QA
 * shading A/B, the finish's crop, the model the result is framed by and the `.glb` cache's
 * pipeline key.
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
        assertTurnsSteadily(room, elevation = 40f, offset = ScanResultFraming.THREE_QUARTER)
    }

    @Test
    fun `a room turns on the screen's own settings, square on and from above, on its cut box`() {
        val elevation = ScanResultFraming.elevation(ScanScope.Room)
        val offset = ScanResultFraming.offset(ScanScope.Room)
        assertEquals(38f, elevation, 0f)
        assertEquals(0f, offset, 0f)
        // As the screen frames it: the box cut where its walls are, opened on its widest side.
        val shown = room.cappedAt(ScanResultCut.HEIGHT_M).widestSide(elevation, fov, 0.46f, RerunResultView.area(zone))
        assertTurnsSteadily(shown, elevation, offset)
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

    @Test
    fun `a textured model's two primitives are cut, its texture and vertices untouched`() {
        // Four triangles over shared vertices: a textured pair (floor, high wall), a plain pair the same.
        val positions = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, // floor
            0f, 2f, 0f, 1f, 2f, 0f, 0f, 2.4f, 0f, // high on a wall
        )
        val chart = intArrayOf(0, 0, -1, -1)
        val model = ModelMesh(
            positions = positions,
            normals = FloatArray(positions.size) { if (it % 3 == 1) 1f else 0f },
            colors = IntArray(6) { -1 },
            uv = FloatArray(12) { 0.5f },
            texturedIndices = intArrayOf(0, 1, 2, 3, 4, 5),
            plainIndices = intArrayOf(2, 1, 0, 5, 4, 3),
            texture = PhotoTexture(chart, FloatArray(24), IntArray(4) { -1 }, 2, 2, PhotoTextureStats(1, 1, 1, 0.5f, 1f, 1f, 0f, 0f, 0f, 1f)),
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val glb = ModelGlb.write(model, encoder = { _, _, _ -> jpeg })
        assertTrue("textured", ModelGlb.isTextured(glb))
        val copy = ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb).also { it.rewind() }
        assertEquals("the high triangle of each primitive", 2, ScanResultCut.cut(copy, ScanResultCut.HEIGHT_M))
        val bytes = ByteArray(glb.size).also { copy.duplicate().apply { rewind() }.get(it) }
        val read = assertNotNullAnd(RerunGlbMesh.read(bytes))
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 2, 1, 0, 5, 5, 5), read.indices.toList())
        assertEquals("vertices as they were", positions.toList(), read.positions.toList())
        assertTrue("the texture still there", ModelGlb.isTextured(bytes))
        val changed = glb.indices.filter { glb[it] != bytes[it] }
        assertTrue("only index bytes written: $changed", changed.size <= 8)
    }

    @Test
    fun `a buffer of interleaved vertices is cut on its stride`() {
        // Position and normal interleaved (byteStride 24), indices behind them at an accessor offset.
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 2f, 0f, 1f, 2f, 0f, 0f, 2.4f, 0f)
        val indices = shortArrayOf(0, 1, 2, 3, 4, 5)
        val vertexBytes = 6 * 24
        val indexOffset = 4
        val json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"mode":4}]}],
             "buffers":[{"byteLength":${vertexBytes + indexOffset + 12}}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$vertexBytes,"byteStride":24},
                            {"buffer":0,"byteOffset":$vertexBytes,"byteLength":${indexOffset + 12}}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":6,"type":"VEC3"},
                          {"bufferView":0,"byteOffset":12,"componentType":5126,"count":6,"type":"VEC3"},
                          {"bufferView":1,"byteOffset":$indexOffset,"componentType":5123,"count":6,"type":"SCALAR"}]}
        """.trimIndent()
        val glb = RerunMeshGlb.glb(json.toByteArray(Charsets.UTF_8), vertexBytes + indexOffset + 12) {
            for (v in 0 until 6) {
                for (k in 0 until 3) putFloat(positions[v * 3 + k])
                // The normal, up: a reader that took it for the next position would read y = 1.
                putFloat(0f).putFloat(1f).putFloat(0f)
            }
            repeat(indexOffset) { put(0) }
            indices.forEach { putShort(it) }
        }
        val copy = ByteBuffer.wrap(glb.copyOf()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, ScanResultCut.cut(copy, ScanResultCut.HEIGHT_M))
        val jsonChunk = copy.getInt(12)
        val indexAt = 12 + 8 + jsonChunk + 8 + vertexBytes + indexOffset
        assertEquals(listOf(0, 1, 2, 3, 3, 3), (0 until 6).map { copy.getShort(indexAt + it * 2).toInt() })
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
    fun `a stray piece apart from the scan goes, a small one standing in it stays`() {
        val floor = grid(0f, 0f, 4f, 3f, step = 0.1f)
        val lamp = grid(2f, 1f, 2.2f, 1.2f, step = 0.1f).lifted(1.5f)
        val tuft = grid(4.5f, 0f, 4.7f, 0.2f, step = 0.1f)
        val (tidy, dropped) = ScanTidy.dropIsolated(floor.plus(lamp).plus(tuft))
        assertEquals("the tuft past the floor's edge", 1, dropped)
        assertEquals(floor.triangleCount + lamp.triangleCount, tidy.triangleCount)
        assertEquals(0, ScanTidy.dropIsolated(floor).second)
    }

    @Test
    fun `a thin stem standing over a tray is never cut from above`() {
        // A tray dense with vertices, and a stem too thin to hold one in a hundred of them.
        val tray = grid(0f, 0f, 0.5f, 0.5f, step = 0.01f)
        val stem = strip(x = 0.25f, z = 0.25f, width = 0.01f, height = 0.6f, rows = 10)
        val plant = tray.plus(stem)
        assertTrue("the stem under the tail", stem.vertexCount < plant.vertexCount * ScanTidy.TAIL)
        val (cropped, cut) = ScanTidy.crop(plant)
        assertEquals("nothing cut", 0, cut)
        assertEquals(plant.triangleCount, cropped.triangleCount)
        assertEquals(0.6f, (1 until cropped.positions.size step 3).maxOf { cropped.positions[it] }, 1e-6f)
        // What a mirror puts under the floor is still cut.
        val mirrored = plant.plus(strip(x = 0.25f, z = 0.25f, width = 0.01f, height = 0.6f, rows = 10).lifted(-1.2f))
        assertEquals(stem.triangleCount, ScanTidy.crop(mirrored).second)
    }

    @Test
    fun `the crop stands square to the same way the result is framed, the footprint's with no walls`() {
        // A long bed running at 30 degrees, no wall to say which way: the footprint says it.
        val bed = grid(0f, 0f, 4f, 0.5f, step = 0.05f).turned(30f)
        val faces = bed.indices.size / 3
        assertTrue(ScanResultFraming.wallCoherence(FloatArray(faces * 3) { if (it % 3 == 1) 1f else 0f }) < ScanResultFraming.MIN_WALL_COHERENCE)
        assertEquals(30f, ScanTidy.yawDegrees(bed.positions, FloatArray(faces * 3) { if (it % 3 == 1) 1f else 0f }), 0.5f)
        // A stray in the bed's world box, a metre off its long side: kept by a box square to the world.
        val stray = grid(0.3f, 2f, 0.4f, 2.1f, step = 0.1f)
        val (cropped, cut) = ScanTidy.crop(bed.plus(stray))
        assertEquals("the stray's triangles", stray.triangleCount, cut)
        assertEquals(bed.triangleCount, cropped.triangleCount)
        // A stray metres away does not turn the footprint the box is squared on.
        val far = grid(9f, 9f, 9.2f, 9.2f, step = 0.1f)
        assertEquals(30f, ScanTidy.yawDegrees(bed.plus(far).positions, FloatArray(faces * 3) { if (it % 3 == 1) 1f else 0f }), 0.5f)
    }

    // ── Which model frames the result ─────────────────────────────────────────

    @Test
    fun `a stale model built again frames the result on the new one, a raw surface keeps its frame`() {
        fun model() = RerunScanModel(
            RerunScanModel.Kind.Mesh,
            RerunMeshGlb.write(RerunMesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f), FloatArray(9), IntArray(3) { -1 }, intArrayOf(0, 1, 2))),
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 1f),
            1,
            3,
        )
        val stale = model()
        val rebuilt = model()
        assertTrue("the rebuilt model's own frame", resultFramedBy(rebuilt, shownFirst = stale, stale = stale) === rebuilt)
        assertTrue("nothing rebuilt: the stale one stays", resultFramedBy(stale, shownFirst = stale, stale = stale) === stale)
        val raw = model()
        val finished = model()
        assertTrue("a big scan's raw surface keeps the camera", resultFramedBy(finished, shownFirst = raw, stale = null) === raw)
        assertTrue(resultFramedBy(finished, shownFirst = null, stale = null) === finished)
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

    /**
     * Turns [frame] all the way round from the opening at [elevation], [offset] off its front: at
     * the opening's pitch, never closer than it, and backed off at most where the box needs it to
     * stay in the zone — within a fifth of the opening's distance.
     */
    private fun assertTurnsSteadily(frame: ScanResultFrame, elevation: Float, offset: Float) {
        val turntable = ScanResultTurntable(degreesPerSecond = 15f)
        val opening = frame.pose(elevation, offset, fov, 0.46f, RerunResultView.area(zone))
        val distances = ArrayList<Float>()
        repeat(24) {
            turntable.advance(1f)
            val turned = turntable.pose(frame, opening, elevation, offset, fov, 0.46f, zone)
            assertEquals("gravity kept", opening.elevationDegrees, turned.elevationDegrees, 1e-3f)
            assertEquals(opening.azimuthDegrees + turntable.angle, turned.azimuthDegrees, 1e-3f)
            distances += turned.distance
        }
        assertTrue("never closer: ${distances.min()} < ${opening.distance}", distances.min() >= opening.distance - 1e-4f)
        assertTrue("distances ${distances.min()}..${distances.max()}", distances.max() <= opening.distance * 1.2f)
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** A flat, up-facing grid on y = 0 over [x0]..[x1] × [z0]..[z1]. */
    private fun grid(
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        step: Float,
    ): WorkMesh {
        val cols = Math.round((x1 - x0) / step)
        val rows = Math.round((z1 - z0) / step)
        val positions = ArrayList<Float>()
        for (r in 0..rows) for (c in 0..cols) positions += listOf(x0 + c * step, 0f, z0 + r * step)
        val indices = ArrayList<Int>()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val v = r * (cols + 1) + c
                indices += listOf(v, v + cols + 1, v + 1, v + 1, v + cols + 1, v + cols + 2)
            }
        }
        return WorkMesh(positions.toFloatArray(), IntArray(positions.size / 3) { -1 }, indices.toIntArray())
    }

    /** A thin upright strip, [width] across in X at ([x], [z]), from the floor to [height], [rows] quads high. */
    private fun strip(x: Float, z: Float, width: Float, height: Float, rows: Int): WorkMesh {
        val positions = FloatArray((rows + 1) * 2 * 3)
        for (r in 0..rows) {
            for (c in 0..1) {
                val v = r * 2 + c
                positions[v * 3] = x + c * width
                positions[v * 3 + 1] = height * r / rows
                positions[v * 3 + 2] = z
            }
        }
        val indices = IntArray(rows * 6)
        for (r in 0 until rows) {
            val v = r * 2
            intArrayOf(v, v + 2, v + 1, v + 1, v + 2, v + 3).copyInto(indices, r * 6)
        }
        return WorkMesh(positions, IntArray(positions.size / 3) { -1 }, indices)
    }

    /** Turned [degrees] about Y, from +X towards +Z ([ScanResultFrame]'s convention). */
    private fun WorkMesh.turned(degrees: Float): WorkMesh {
        val c = cos(Math.toRadians(degrees.toDouble())).toFloat()
        val s = sin(Math.toRadians(degrees.toDouble())).toFloat()
        val out = positions.copyOf()
        for (v in 0 until vertexCount) {
            val x = positions[v * 3]
            val z = positions[v * 3 + 2]
            out[v * 3] = x * c - z * s
            out[v * 3 + 2] = x * s + z * c
        }
        return WorkMesh(out, colors, indices)
    }

    private fun WorkMesh.lifted(by: Float): WorkMesh =
        WorkMesh(FloatArray(positions.size) { if (it % 3 == 1) positions[it] + by else positions[it] }, colors, indices)

    private fun WorkMesh.plus(other: WorkMesh): WorkMesh = WorkMesh(
        positions + other.positions,
        colors + other.colors,
        indices + other.indices.map { it + vertexCount },
    )
}
