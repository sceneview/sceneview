package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.demo.demos.RerunModelSource
import io.github.sceneview.demo.demos.buildScanModel
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * The 3D Scan result (#4597): the model kept next to its scan and read back on reopen, the
 * coloured-points fallback, the `.glb` read back for the export thumbnail, and the still,
 * three-quarter view fitted to the geometry's own bounds.
 */
class RerunScanModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fov = 46.4

    private companion object {
        /** The share of the screen's narrower side the model's bounds span. */
        val NARROW_FILL = 0.78f..0.82f

        /** A model this close to the screen's edge along its long side fills that side. */
        const val LONG_EDGE = 0.06f
    }

    // ── Kept with the scan ────────────────────────────────────────────────────

    @Test
    fun `a model saved with its scan reads back the same after a restart`() {
        val root = folder.newFolder("sessions")
        val saved = RerunSessionStore(root).save(bundled(), "Kitchen", RerunSessionSource.Recorded, 1_000_000L)
        val model = floorModel()
        RerunSessionStore(root).saveModel(saved.id, model)

        // A new store on the same directory: the app killed and opened again.
        val reopened = RerunSessionStore(root)
        val back = assertNotNull(reopened.model(saved.id))
        assertEquals(RerunScanModel.Kind.Points, back.kind)
        assertArrayEquals(model.glb, back.glb)
        assertArrayEquals(model.bounds, back.bounds, 0f)
        assertEquals(model.triangles, back.triangles)
        assertEquals(model.vertices, back.vertices)
        assertEquals(listOf(saved), reopened.list())
    }

    @Test
    fun `a model handed to save is kept in the same call`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val model = floorModel(kind = RerunScanModel.Kind.Mesh, budgetReached = true)
        val saved = store.save(bundled(), "Hall", RerunSessionSource.Recorded, 1_000_000L, model = model)

        val back = assertNotNull(store.model(saved.id))
        assertEquals(RerunScanModel.Kind.Mesh, back.kind)
        assertTrue(back.budgetReached)
    }

    @Test
    fun `a scan kept before results has no model, and still lists and opens`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Old scan", RerunSessionSource.Recorded, 1_000_000L)

        assertNull(store.model(saved.id))
        assertEquals(listOf(saved), store.list())
        assertEquals(bundled(), store.capture(saved.id))
    }

    @Test
    fun `a model cut short or damaged reads as none, so the scan builds it again`() {
        val root = folder.newFolder("sessions")
        val store = RerunSessionStore(root)
        val saved = store.save(bundled(), "Room", RerunSessionSource.Recorded, 1_000_000L)
        val dir = File(root, saved.id)
        val model = floorModel()

        // The .glb written, the save cut before model.json: no model.
        File(dir, "model.glb").writeBytes(model.glb)
        assertNull(store.model(saved.id))

        // model.json present but its .glb gone, or not a .glb, or the json itself broken.
        File(dir, "model.json").writeText(model.infoJson())
        File(dir, "model.glb").delete()
        assertNull(store.model(saved.id))
        File(dir, "model.glb").writeBytes("not a glb at all".toByteArray())
        assertNull(store.model(saved.id))
        File(dir, "model.glb").writeBytes(model.glb)
        File(dir, "model.json").writeText("{\"kind\":")
        assertNull(store.model(saved.id))

        // Saved again over the damage: whole.
        store.saveModel(saved.id, model)
        assertNotNull(store.model(saved.id))
    }

    @Test
    fun `deleting a scan deletes its model, and a model never outlives its scan`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Room", RerunSessionSource.Recorded, 1_000_000L, model = floorModel())
        assertTrue(store.delete(saved.id))
        assertNull(store.model(saved.id))
        val error = runCatching { store.saveModel(saved.id, floorModel()) }.exceptionOrNull()
        assertTrue("a model for a scan that is gone is refused", error is IllegalStateException)
    }

    @Test
    fun `renaming a scan keeps its model and shows in the list`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val saved = store.save(bundled(), "Room", RerunSessionSource.Recorded, 1_000_000L, model = floorModel())

        assertEquals("Living room", store.rename(saved.id, "  Living room ")?.title)
        assertEquals("Living room", store.list().single().title)
        assertNotNull(store.model(saved.id))
        assertNull("a blank name is refused", store.rename(saved.id, "   "))
        assertNull("a scan that is gone", store.rename("nope", "x"))
    }

    @Test
    fun `a save cut short is swept once it is old, never a save under way or a whole scan`() {
        val root = folder.newFolder("sessions")
        val store = RerunSessionStore(root)
        val whole = store.save(bundled(), "Room", RerunSessionSource.Recorded, 1_000_000L, model = floorModel())
        val now = System.currentTimeMillis()
        val grace = RerunSessionStore.ORPHAN_GRACE_MS

        // Capture files and a model, but the save died before session.json.
        val old = File(root, "OLD").apply { mkdirs() }
        File(old, RerunCapturePack.MEDIA).writeBytes(ByteArray(64))
        File(old, RerunSessionStore.MODEL_GLB).writeBytes(ByteArray(64))
        old.listFiles()!!.forEach { it.setLastModified(now - grace - 1_000) }
        old.setLastModified(now - grace - 1_000)
        // The same, written a moment ago: a save under way.
        val fresh = File(root, "FRESH").apply { mkdirs() }
        File(fresh, RerunCapturePack.MEDIA).writeBytes(ByteArray(64))
        File(root, whole.id).listFiles()!!.forEach { it.setLastModified(now - grace * 2) }

        assertEquals(1, store.sweepOrphans(nowMillis = now))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertEquals(listOf(whole), store.list())
        assertNotNull(store.model(whole.id))
    }

    @Test
    fun `a model json from a newer app, or with no version, is refused`() {
        val model = floorModel()
        val info = model.infoJson()
        assertTrue(info.contains("\"version\": ${RerunScanModel.VERSION}"))
        val version = "\"version\": ${RerunScanModel.VERSION}"
        val newer = info.replace(version, "\"version\": ${RerunScanModel.VERSION + 1}")
        assertNull(RerunScanModel.parse(newer, model.glb))
        assertNull(RerunScanModel.parse(info.replace("\"version\": ${RerunScanModel.VERSION},", ""), model.glb))
        assertNotNull(RerunScanModel.parse(info, model.glb))
    }

    @Test
    fun `a model from the raw-surface format opens as it is, and its scan finishes it again`() {
        val root = folder.newFolder("sessions")
        val store = RerunSessionStore(root)
        val saved = store.save(bundled(), "Room", RerunSessionSource.Recorded, 1_000_000L)
        val dir = File(root, saved.id)
        val model = floorModel()
        // An earlier app's model.json: the raw surface, kept as its model.
        val first = model.infoJson().replace("\"version\": ${RerunScanModel.VERSION}", "\"version\": 1")
        File(dir, "model.json").writeText(first)
        File(dir, "model.glb").writeBytes(model.glb)

        // Opened at once on it, whether its recording still reads or not: refused, a scan whose
        // recording no longer reads had nothing to open on.
        val earlier = assertNotNull(store.model(saved.id))
        assertArrayEquals(model.glb, earlier.glb)
        assertFalse("an earlier app's raw surface is not a finished model", earlier.finished)
        assertTrue("finished again from its recording", earlier.retriesFinish)
        assertFalse("kept as it was, not saved over as this app's", earlier.keptWithScan)
        // A finish that fails leaves it shown, still to finish at the next open.
        assertTrue(earlier.finishedAgain(null).retriesFinish)
        assertFalse(earlier.finishedAgain(null).finished)
    }

    @Test
    fun `model json is refused unless whole`() {
        val model = floorModel()
        assertNotNull(RerunScanModel.parse(model.infoJson(), model.glb))
        assertNull(RerunScanModel.parse(model.infoJson(), ByteArray(4)))
        assertNull(RerunScanModel.parse(model.infoJson().replace("\"points\"", "\"cloud\""), model.glb))
        val fiveBounds = """{"kind":"mesh","bounds":[0,0,0,1,1],"triangles":2,"vertices":4}"""
        assertNull(RerunScanModel.parse(fiveBounds, model.glb))
        val noTriangles = """{"kind":"mesh","bounds":[0,0,0,1,1,1],"triangles":0,"vertices":4}"""
        assertNull(RerunScanModel.parse(noTriangles, model.glb))
        assertNull(RerunScanModel.parse("[]", model.glb))
    }

    // ── The points fallback ───────────────────────────────────────────────────

    @Test
    fun `each point becomes a square facing its normal`() {
        val cloud = floorCloud(side = 4)
        val mesh = RerunPointsMesh.of(cloud)
        assertEquals(cloud.count * 4, mesh.vertexCount)
        assertEquals(cloud.count * 2, mesh.triangleCount)
        for (t in 0 until mesh.triangleCount) {
            val a = vertex(mesh, mesh.indices[t * 3])
            val b = vertex(mesh, mesh.indices[t * 3 + 1])
            val c = vertex(mesh, mesh.indices[t * 3 + 2])
            val first = mesh.indices[t * 3]
            val n = Float3(mesh.normals[first * 3], mesh.normals[first * 3 + 1], mesh.normals[first * 3 + 2])
            assertTrue("triangle $t winds counter-clockwise around its normal", dot(cross(b - a, c - a), n) > 0f)
        }
    }

    @Test
    fun `a cloud over the cap keeps every few points, and an uncoloured one is grey`() {
        val cloud = DenseCloud(
            positions = FloatArray(30) { it * 0.01f },
            colors = IntArray(10),
            normals = FloatArray(30) { if (it % 3 == 1) 1f else 0f },
        )
        val mesh = RerunPointsMesh.of(cloud, maxPoints = 4)
        assertEquals(4 * 4, mesh.vertexCount)
        assertTrue(mesh.colors.all { it == RerunPointsMesh.UNCOLOURED })
    }

    @Test
    fun `a black point stays black, only a point with no colour is grey`() {
        val cloud = DenseCloud(
            positions = FloatArray(6),
            colors = intArrayOf(0xFF000000.toInt(), 0),
            normals = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f),
        )
        val mesh = RerunPointsMesh.of(cloud)
        assertEquals(0xFF000000.toInt(), mesh.colors[0])
        assertEquals(RerunPointsMesh.UNCOLOURED, mesh.colors[4])
    }

    @Test
    fun `a points model is framed by its points, not by a few strays far off`() {
        val room = floorCloud(side = 40) // 0.78 m a side
        val strays = 3
        val positions = room.positions.copyOf(room.positions.size + strays * 3)
        for (k in 0 until strays) {
            positions[room.positions.size + k * 3] = 25f + k
            positions[room.positions.size + k * 3 + 1] = -9f
            positions[room.positions.size + k * 3 + 2] = 14f
        }
        val cloud = DenseCloud(
            positions = positions,
            colors = room.colors.copyOf(room.count + strays),
            normals = FloatArray(positions.size) { if (it % 3 == 1) 1f else 0f },
        )
        val mesh = RerunPointsMesh.of(cloud)
        val all = mesh.bounds()
        val framed = RerunPointsMesh.framingBounds(mesh)
        assertTrue("min/max reaches the strays", all[3] > 25f)
        assertTrue("framed x ${framed.toList()}", framed[0] >= -0.05f && framed[3] <= 0.85f)
        assertTrue("framed y", framed[1] >= -0.05f && framed[4] <= 0.05f)
        assertTrue("framed z", framed[2] >= -0.05f && framed[5] <= 0.85f)
        assertTrue("still the whole room", framed[3] - framed[0] > 0.7f && framed[5] - framed[2] > 0.7f)
        val empty = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0))
        assertArrayEquals(FloatArray(6), RerunPointsMesh.framingBounds(empty), 0f)
    }

    // ── Building it ───────────────────────────────────────────────────────────

    @Test
    fun `a build that is cancelled stops at its next progress and says so`() {
        var calls = 0
        val error = runCatching {
            buildScanModel(RerunModelSource.Synthetic()) {
                calls++
                throw CancellationException("left the screen")
            }
        }.exceptionOrNull()
        assertTrue("cancellation goes through, not a points fallback: $error", error is CancellationException)
        assertEquals(1, calls)
    }

    // ── Read back for the thumbnail ───────────────────────────────────────────

    @Test
    fun `the model glb is one well-formed container`() {
        val mesh = RerunPointsMesh.of(floorCloud(side = 5))
        val glb = RerunMeshGlb.write(mesh)
        val head = java.nio.ByteBuffer.wrap(glb).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, head.getInt(0))
        assertEquals(2, head.getInt(4))
        assertEquals(glb.size, head.getInt(8))
        val jsonLength = head.getInt(12)
        assertEquals(0, jsonLength % 4)
        val binLength = head.getInt(20 + jsonLength)
        assertEquals(0x004E4942, head.getInt(24 + jsonLength))
        assertEquals(glb.size, 28 + jsonLength + binLength)
        val vertices = mesh.vertexCount
        // 16-bit indices: the mesh has fewer than 65,535 vertices.
        assertEquals(vertices * 28 + mesh.indices.size * 2, binLength)
        val json = String(glb, 20, jsonLength, Charsets.UTF_8)
        assertTrue(json, json.contains("\"byteLength\":$binLength"))
    }

    @Test
    fun `a model glb reads back into the mesh it was written from`() {
        val mesh = RerunPointsMesh.of(floorCloud(side = 3))
        val back = assertNotNull(RerunGlbMesh.read(RerunMeshGlb.write(mesh)))
        assertArrayEquals(mesh.positions, back.positions, 1e-6f)
        assertArrayEquals(mesh.normals, back.normals, 1e-6f)
        assertArrayEquals(mesh.indices, back.indices)
        for (v in 0 until mesh.vertexCount) {
            for (shift in listOf(16, 8, 0)) {
                val want = mesh.colors[v] shr shift and 0xFF
                val got = back.colors[v] shr shift and 0xFF
                assertTrue("vertex $v channel $shift: $want vs $got", abs(want - got) <= 2)
            }
        }
        assertNull(RerunGlbMesh.read("glTF but not really".toByteArray()))
    }

    @Test
    fun `the thumbnail draws the model in its middle on a clear background`() {
        val size = 64
        val pixels = assertNotNull(RerunResultView.thumbnail(floorModel(), size, fov))
        assertEquals(size * size, pixels.size)
        assertEquals("the middle is the model", 0xFF, pixels[size / 2 * size + size / 2] ushr 24)
        assertEquals("a corner is background", 0, pixels[0] ushr 24)
        assertTrue("the model's red shows", (pixels[size / 2 * size + size / 2] shr 16 and 0xFF) > 100)
    }

    // ── The view ──────────────────────────────────────────────────────────────

    @Test
    fun `the result frames the geometry to most of the screen's narrower side, where it is`() {
        // A scan's world is wherever ARCore started: rooms far from the origin, long and short,
        // a desk's corner, and a shelf scanned on its own.
        val rooms = listOf(
            floatArrayOf(2f, -0.1f, -7f, 6f, 2.4f, -4f),
            floatArrayOf(-12f, 0f, 3f, -3f, 2.6f, 5.5f),
            floatArrayOf(0.3f, 0.7f, 0.2f, 0.9f, 1.1f, 0.6f),
            floatArrayOf(1f, 0.8f, -2f, 1.4f, 1.1f, -1.7f),
        )
        // The bar above and the sheet below it, raised or at rest over most of a phone's height.
        val chromes = listOf(
            FramingInsets(left = 0.04f, top = 0.14f, right = 0.04f, bottom = 0.22f),
            FramingInsets(left = 0.04f, top = 0.12f, right = 0.04f, bottom = 0.62f),
        )
        val slack = 2e-3f
        for (bounds in rooms) {
            for (chrome in chromes) {
                for (aspect in listOf(0.46f, 1f, 2.17f)) {
                    val pose = RerunResultView.home(bounds, fov, aspect, chrome)
                    assertEquals(RerunResultView.AZIMUTH, pose.azimuthDegrees, 1e-3f)
                    assertEquals(RerunResultView.ELEVATION, pose.elevationDegrees, 1e-3f)
                    val seen = projected(bounds, pose, aspect)
                    val where = "bounds ${bounds.toList()}, $chrome, aspect $aspect: seen ${seen.toList()}"
                    assertTrue("$where: off the screen", seen.all { it in -1f - slack..1f + slack })
                    // The narrower side: across a portrait screen, up a landscape one.
                    val across = aspect <= 1f
                    val narrow = if (across) (seen[2] - seen[0]) / 2f else (seen[3] - seen[1]) / 2f
                    val longLo = if (across) seen[1] else seen[0]
                    val longHi = if (across) seen[3] else seen[2]
                    // Taller than the screen lets it be at that size: it fills its long side instead.
                    val longFull = longLo <= -1f + 2f * LONG_EDGE || longHi >= 1f - 2f * LONG_EDGE
                    assertTrue("$where: fills $narrow of the narrower side", narrow <= NARROW_FILL.endInclusive)
                    assertTrue("$where: fills $narrow of the narrower side", longFull || narrow in NARROW_FILL)
                    // Within the chrome along the long side whenever it fits there.
                    val bandLo = if (across) -1f + 2f * chrome.bottom else -1f + 2f * chrome.left
                    val bandHi = if (across) 1f - 2f * chrome.top else 1f - 2f * chrome.right
                    if (longHi - longLo <= bandHi - bandLo) {
                        assertTrue("$where: under the chrome", longLo >= bandLo - slack && longHi <= bandHi + slack)
                    }
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun vertex(mesh: RerunMesh, v: Int) =
        Float3(mesh.positions[v * 3], mesh.positions[v * 3 + 1], mesh.positions[v * 3 + 2])

    /** A flat [side]×[side] grid of red points on the floor, normals up, 2 cm apart. */
    private fun floorCloud(side: Int = 20): DenseCloud {
        val n = side * side
        return DenseCloud(
            positions = FloatArray(n * 3) { i ->
                when (i % 3) {
                    0 -> (i / 3 % side) * 0.02f
                    1 -> 0f
                    else -> (i / 3 / side) * 0.02f
                }
            },
            colors = IntArray(n) { 0xFFD03020.toInt() },
            normals = FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f },
        )
    }

    private fun floorModel(
        kind: RerunScanModel.Kind = RerunScanModel.Kind.Points,
        budgetReached: Boolean = false,
    ): RerunScanModel {
        val mesh = RerunPointsMesh.of(floorCloud())
        return RerunScanModel(
            kind = kind,
            glb = RerunMeshGlb.write(mesh),
            bounds = mesh.bounds(),
            triangles = mesh.triangleCount,
            vertices = mesh.vertexCount,
            budgetReached = budgetReached,
        )
    }

    /** The extent of [bounds] on screen from [pose], in NDC: `[minX, minY, maxX, maxY]`. */
    private fun projected(bounds: FloatArray, pose: OrbitPose, aspect: Float): FloatArray {
        val eye = CameraRig.eye(pose)
        val forward = normalize(pose.target - eye)
        val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
        val up = cross(right, forward)
        val tanV = tan(Math.toRadians(fov / 2.0)).toFloat()
        val tanH = tanV * aspect
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (corner in 0 until 8) {
            val point = Float3(
                bounds[if (corner and 1 == 0) 0 else 3],
                bounds[if (corner and 2 == 0) 1 else 4],
                bounds[if (corner and 4 == 0) 2 else 5],
            ) - eye
            val depth = dot(point, forward)
            assertTrue("a corner is behind the lens", depth > 0f)
            out[0] = min(out[0], dot(point, right) / (depth * tanH))
            out[1] = min(out[1], dot(point, up) / (depth * tanV))
            out[2] = max(out[2], dot(point, right) / (depth * tanH))
            out[3] = max(out[3], dot(point, up) / (depth * tanV))
        }
        return out
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    private fun bundled(): RerunCapturePack {
        val dir = listOf(
            File("src/main/assets/${RerunReplayAssets.DIR}"),
            File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
        ).first { it.isDirectory }
        return RerunCapturePack(
            manifest = File(dir, "showcase-manifest.json").readBytes(),
            log = File(dir, "showcase-session.jsonl").readBytes(),
            media = File(dir, "showcase-media.bin").readBytes(),
        )
    }
}
