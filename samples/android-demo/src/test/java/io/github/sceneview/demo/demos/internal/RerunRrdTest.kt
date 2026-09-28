package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Random
import java.util.UUID
import kotlin.math.abs

/**
 * The `.rrd` writer and reader together: the bundled showcase, exported to `.rrd` and read back,
 * replays the same session; a file that is not a readable `.rrd` fails with a typed error, never
 * a crash. Mirrors the iOS demo's `RerunRRDReaderTests` and `RerunRRDWriterTests`.
 */
class RerunRrdTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val codec = FakeRerunImageCodec
    private val recordingId = UUID(0x0123_4567_89AB_CDEFL, 0x0FED_CBA9_8765_4321L)

    private fun exported(): RerunExportScene =
        RerunExportAdapter.scene(FakeRerunImageCodec.showcase(), "Recorded room")!!

    private fun rrd(scene: RerunExportScene = exported()): ByteArray =
        RerunRrdWriter.write(scene, codec, recordingId = recordingId)

    private fun reread(scene: RerunExportScene): Pair<RerunRrdReader.Recording, RerunExportScene> {
        val recording = RerunRrdReader.recording(rrd(scene), codec)
        val lens = RerunExportAdapter.lensOf(String(recording.pack.manifest))
        val opened = recording.pack.open()!!
        return recording to RerunExportAdapter.scene(opened, recording.title ?: "", lens)
    }

    // ── Writer ────────────────────────────────────────────────────────────────

    @Test
    fun `the stream starts with the RRF2 header for Rerun 0_38_1, uncompressed protobuf`() {
        val data = rrd()
        assertArrayEquals(byteArrayOf(0x52, 0x52, 0x46, 0x32, 0, 38, 1, 0, 0, 2, 0, 0), data.copyOf(12))
        assertArrayEquals(data.copyOf(12), RerunRrdWriter.streamHeader())
    }

    @Test
    fun `messages tile the file, the first one the store info`() {
        val data = rrd()
        val bytes = RrdBytes(data)
        var position = 12
        var messages = 0
        while (position < data.size) {
            val kind = bytes.u64(position)
            if (messages == 0) assertEquals(1L, kind) else assertEquals(2L, kind)
            position += 16 + bytes.u64(position + 8).toInt()
            messages++
        }
        assertEquals(data.size, position)
        assertTrue(messages > 5)
    }

    @Test
    fun `one scene and one recording id always give the same bytes`() {
        assertArrayEquals(rrd(), rrd())
    }

    @Test
    fun `point colours that do not match the points are refused`() {
        val scene = exported().let { it.copy(pointColors = it.pointColors.drop(1)) }
        try {
            RerunRrdWriter.write(scene, codec)
            fail("expected PointColorCountMismatch")
        } catch (expected: RerunRrdWriter.PointColorCountMismatch) {
            assertEquals(scene.points.size, expected.points)
        }
    }

    @Test
    fun `colours pack as RGBA and TUIDs print as Rerun does`() {
        assertEquals(0x0A141EFF, RerunRrdWriter.packedColor(10, 20, 30))
        assertEquals(0xFF0000FF.toInt(), RerunRrdWriter.packedColor(255, 0, 0))
        assertEquals("0000000000000ABC0000000000000def", RerunTuid(0xABC, 0xDEF).toString())
        assertEquals(1_500_000_000L, RerunRrdWriter.nanoseconds(1.5))
    }

    // ── Round trip ────────────────────────────────────────────────────────────

    @Test
    fun `the showcase round trip replays the same session`() {
        val exported = exported()
        val (recording, replayed) = reread(exported)
        assertEquals("Recorded room", recording.title)

        // The path, pose for pose: the writer's per-photo rows are not taken for path poses.
        assertEquals(exported.cameraPath.size, replayed.cameraPath.size)
        for ((a, b) in exported.cameraPath.zip(replayed.cameraPath)) {
            assertEquals(a.time, b.time, 1e-4)
            assertEquals(a.position, b.position)
            assertEquals(a.orientation, b.orientation)
        }

        // The keyframes, each with the photo the `.rrd` carried (WebP became "JPEG" on export).
        assertTrue(exported.keyframes.isNotEmpty())
        assertEquals(exported.keyframes.size, replayed.keyframes.size)
        for ((a, b) in exported.keyframes.zip(replayed.keyframes)) {
            assertEquals(a.time, b.time, 1e-4)
            assertEquals(a.pose.position, b.pose.position)
            val written = codec.photo(exported.images.getValue(a.imagePath))!!.data
            assertArrayEquals(written, replayed.images[b.imagePath])
        }

        // The map, bit for bit.
        assertEquals(exported.points, replayed.points)
        assertEquals(exported.pointColors, replayed.pointColors)

        assertEquals(exported.planes.map { it.id }, replayed.planes.map { it.id })
        for ((a, b) in exported.planes.zip(replayed.planes)) {
            assertEquals("plane ${a.id}", a.kind, b.kind)
            assertEquals("plane ${a.id}", a.polygon, b.polygon)
            assertEquals("plane ${a.id}", a.texture == null, b.texture == null)
            val ta = a.texture ?: continue
            val tb = b.texture!!
            for ((x, y) in listOf(ta.origin to tb.origin, ta.u to tb.u, ta.v to tb.v)) {
                assertTrue("plane ${a.id}: $x vs $y", (x - y).length() < 1e-3f)
            }
        }
        assertTrue(exported.planes.any { it.texture != null })

        assertEquals(exported.anchors.map { it.id }, replayed.anchors.map { it.id })
        for ((a, b) in exported.anchors.zip(replayed.anchors)) {
            assertEquals(a.position, b.position)
            assertEquals(a.orientation, b.orientation)
        }

        // The Pinhole is in photo pixels: the lens keeps its proportions.
        val lensA = exported.lens!!
        val lensB = replayed.lens!!
        assertEquals(lensA.fx / lensA.width, lensB.fx / lensB.width, 1e-4f)
        assertEquals(lensA.fy / lensA.height, lensB.fy / lensB.height, 1e-4f)
    }

    @Test
    fun `the map is static in an rrd, there from the first frame`() {
        val exported = exported()
        val opened = RerunRrdReader.recording(rrd(exported), codec).pack.open()!!
        val first = opened.trace.frameAt(0f)
        assertEquals(exported.points.size, first.mapPointCount)
        assertEquals(exported.planes.size, first.planes.size)
        assertEquals(exported.anchors.size, first.anchors.size)
        assertEquals(-1.281f, opened.manifest.floorY!!, 1e-2f)
    }

    @Test
    fun `a second round trip changes nothing`() {
        val once = reread(exported()).second
        val twice = reread(once).second
        assertEquals(once.cameraPath, twice.cameraPath)
        assertEquals(once.points, twice.points)
        assertEquals(once.pointColors, twice.pointColors)
        assertEquals(once.keyframes.map { it.imagePath }, twice.keyframes.map { it.imagePath })
        assertEquals(once.images.keys, twice.images.keys)
        for ((path, bytes) in once.images) assertArrayEquals(path, bytes, twice.images[path])
        // As on iOS: polygons and texels are exact; the texture frame is a least-squares fit from
        // the mesh's texture coordinates, so it only drifts by float noise.
        val shape = { p: RerunExportScene.Plane -> Triple(p.id, p.kind, p.polygon) }
        assertEquals(once.planes.map(shape), twice.planes.map(shape))
        for ((a, b) in once.planes.zip(twice.planes)) {
            val ta = a.texture
            val tb = b.texture
            assertEquals("plane ${a.id} texture", ta == null, tb == null)
            if (ta == null || tb == null) continue
            assertArrayEquals(
                "plane ${a.id} texels",
                FakeRerunImageCodec.rgbPixels(ta.imageData, 512)?.data,
                FakeRerunImageCodec.rgbPixels(tb.imageData, 512)?.data,
            )
            for ((va, vb) in listOf(ta.origin to tb.origin, ta.u to tb.u, ta.v to tb.v)) {
                assertEquals("plane ${a.id} frame x", va.x, vb.x, FRAME_DRIFT)
                assertEquals("plane ${a.id} frame y", va.y, vb.y, FRAME_DRIFT)
                assertEquals("plane ${a.id} frame z", va.z, vb.z, FRAME_DRIFT)
            }
        }
        assertEquals(once.anchors, twice.anchors)
    }

    @Test
    fun `the session store keeps an opened rrd as a session from Rerun`() {
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val imported = store.import(
            "kitchen.rrd",
            rrd(),
            readRrd = { RerunRrdReader.capturePack(it, "kitchen.rrd", codec) },
        )
        assertEquals("kitchen", imported.title)
        assertEquals(RerunSessionSource.Rrd, imported.source)
        assertEquals(listOf(imported.id), store.list().map { it.id })
        assertTrue(store.capture(imported.id)!!.open()!!.trace.poseCount > 0)
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    private inline fun <reified T : RerunRrdReader.Failure> assertFails(data: ByteArray): T {
        try {
            RerunRrdReader.recording(data, codec)
        } catch (failure: RerunRrdReader.Failure) {
            assertTrue("expected ${T::class.simpleName}, got $failure", failure is T)
            return failure as T
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    private inline fun <reified T : RerunImportFailure> assertImportFails(data: ByteArray) {
        try {
            RerunRrdReader.capturePack(data, "take.rrd", codec)
        } catch (failure: RerunImportFailure) {
            assertTrue("expected ${T::class.simpleName}, got $failure", failure is T)
            return
        }
        fail("expected ${T::class.simpleName}")
    }

    @Test
    fun `a wrong magic is not an rrd`() {
        val data = rrd().also { it[0] = 'X'.code.toByte() }
        assertFails<RerunRrdReader.Failure.NotAnRrd>(data)
        assertFails<RerunRrdReader.Failure.NotAnRrd>("{\"t\":0}".toByteArray())
        assertFails<RerunRrdReader.Failure.NotAnRrd>(ByteArray(0))
        assertImportFails<RerunImportFailure.Unreadable>(data)
    }

    @Test
    fun `a truncated file fails`() {
        val data = rrd()
        assertFails<RerunRrdReader.Failure.Truncated>(data.copyOf(8))
        assertFails<RerunRrdReader.Failure.Truncated>(data.copyOf(data.size / 2))
        assertFails<RerunRrdReader.Failure.Truncated>(data.copyOf(data.size - 1))
    }

    @Test
    fun `a compressed stream is refused as compressed`() {
        val data = rrd().also { it[8] = 1 } // The LZ4 option.
        assertFails<RerunRrdReader.Failure.Compressed>(data)
        assertImportFails<RerunImportFailure.RrdCompressed>(data)
    }

    @Test
    fun `another major version is refused as newer`() {
        val data = rrd().also { it[4] = 1 }
        val failure = assertFails<RerunRrdReader.Failure.UnsupportedVersion>(data)
        assertEquals(listOf(1, 38, 1), listOf(failure.major, failure.minor, failure.patch))
        assertImportFails<RerunImportFailure.RrdNewerVersion>(data)
    }

    @Test
    fun `a header alone has nothing to replay`() {
        val data = rrd().copyOf(12)
        assertFails<RerunRrdReader.Failure.NothingToReplay>(data)
        assertImportFails<RerunImportFailure.Empty>(data)
    }

    /** Random damage anywhere must end in a typed failure or a capture, never another exception. */
    @Test
    fun `damaged files never crash`() {
        val scene = exported().let { whole ->
            whole.copy(
                points = whole.points.take(40),
                pointColors = whole.pointColors.take(40),
                cameraPath = whole.cameraPath.take(12),
                keyframes = emptyList(),
                images = emptyMap(),
                planes = whole.planes.map { it.copy(texture = null) },
            )
        }
        val data = rrd(scene)
        val random = Random(0x5CE1E1EL)
        repeat(DAMAGE_RUNS) { run ->
            val damaged = if (run % 5 == 0) {
                data.copyOf(random.nextInt(data.size))
            } else {
                data.copyOf().also { copy ->
                    repeat(run % 4 + 1) { copy[random.nextInt(copy.size)] = random.nextInt().toByte() }
                }
            }
            try {
                RerunRrdReader.recording(damaged, codec)
            } catch (expected: RerunRrdReader.Failure) {
                // A typed failure is the point.
            }
        }
    }

    @Test
    fun `the writer's keyframe rows are told apart from the path`() {
        val identity = RerunExportScene.Quat.Identity
        val path = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f))
        val rows = listOf(
            RerunRrdReader.PoseRow(0, path[0], identity),
            RerunRrdReader.PoseRow(5, path[1], identity),
            RerunRrdReader.PoseRow(5, Vec3(9f, 9f, 9f), identity), // The photo's own pose.
            RerunRrdReader.PoseRow(10, path[2], identity),
        )
        val kept = RerunRrdReader.pathPoses(rows, path, photoTimes = listOf(5))
        assertEquals(path, kept.map { it.position })
        // Another writer's file: every row is a pose.
        val elsewhere = listOf(Vec3(7f, 7f, 7f), Vec3(8f, 8f, 8f))
        assertEquals(rows, RerunRrdReader.pathPoses(rows, elsewhere, listOf(5)))
        val square = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(1f, 0f, 1f), Vec3(0f, 0f, 1f))
        assertTrue(abs(RerunRrdContents.area(square) - 1f) < 1e-6f)
    }

    private companion object {
        const val DAMAGE_RUNS = 2_000

        /** Metres: a millimetre of the texture frame's fit, far below one texel. */
        const val FRAME_DRIFT = 1e-3f
    }
}
