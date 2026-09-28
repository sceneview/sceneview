package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.RerunCaptureBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The `.svscan` v2 file end to end: a scan with a dense cloud writes `dense/points.bin` and
 * declares it in the manifest, a sparse one writes none, a v1 file still reads, and every
 * export of a v2 scan carries the dense points — never a `.ply` of `element vertex 0`.
 */
class RerunScanV2Test {
    private val showcase = FakeRerunImageCodec.showcase()
    private val events = parseArDebugLog(String(showcase.log).lineSequence())
    private val lens = ReplayManifest.parse(String(showcase.manifest))!!.lens
    private val depthDevice = ScanDevice("android", "Pixel 9", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
    private val sparseDevice =
        ScanDevice("android", "Pixel 4a", ScanDevice.TIER_SPARSE, ScanDevice.SOURCE_FEATURE_POINTS)

    private fun build(device: ScanDevice?, dense: DenseCloud?): RerunCapturePack = runBlocking {
        RerunCaptureBuilder.build(events, lens, emptyList(), device, dense, DenseFusion.VOXEL_M, denseMs = 42L)
    }

    @Test
    fun `the bundled v1 sample reads as version 1, with no device and no dense cloud`() {
        val manifest = ReplayManifest.parse(String(showcase.manifest))!!
        assertEquals(1, manifest.version)
        assertNull(manifest.device)
        assertNull(manifest.dense)
        val opened = showcase.open()!!
        assertTrue(opened.trace.frameAt(opened.trace.duration).mapPointCount > 0)
        assertEquals(-1, opened.trace.denseCountAt(1f))
    }

    @Test
    fun `a depth scan writes its dense cloud as an SVPC blob the manifest declares`() {
        val cloud = cloud(3_000)
        val pack = build(depthDevice, cloud)
        val root = Json.parseToJsonElement(String(pack.manifest)).jsonObject
        assertEquals(2, root["version"]!!.jsonPrimitive.int)
        assertEquals("depth", root["device"]!!.jsonObject["tier"]!!.jsonPrimitive.content)
        assertEquals("arcore_raw_depth", root["device"]!!.jsonObject["depthSource"]!!.jsonPrimitive.content)
        val dense = root["dense"]!!.jsonObject
        assertEquals("dense/points.bin", dense["path"]!!.jsonPrimitive.content)
        assertEquals(3_000, dense["count"]!!.jsonPrimitive.int)
        assertEquals(6, dense["bounds"]!!.jsonArray.size)
        assertEquals(42, root["built"]!!.jsonObject["denseMs"]!!.jsonPrimitive.int)

        // Through the .svscan file and back, as the sessions list and a share would.
        val reread = RerunScanFile.read(RerunScanFile.write(pack))!!
        val opened = reread.open()!!
        assertEquals(2, opened.manifest.version)
        assertEquals(depthDevice, opened.manifest.device)
        assertEquals(3_000, opened.manifest.dense!!.count)
        val blob = opened.bytesOf("dense/points.bin")!!
        assertEquals(SvpcCodec.HEADER_BYTES + 12 * 3_000, blob.size)
        val back = SvpcCodec.decode(blob)!!
        assertEquals(3_000, back.count)
        assertArrayEquals(cloud.colors, back.colors)
        // The v1 content is all still there.
        assertEquals(showcase.open()!!.trace.imageCount, opened.trace.imageCount)
    }

    @Test
    fun `a sparse scan is v2 with no dense key and no blob, and an empty cloud is never written`() {
        for (pack in listOf(build(sparseDevice, null), build(depthDevice, DenseCloud.Empty))) {
            val root = Json.parseToJsonElement(String(pack.manifest)).jsonObject
            assertEquals(2, root["version"]!!.jsonPrimitive.int)
            assertFalse(root.containsKey("dense"))
            val opened = pack.open()!!
            assertNull(opened.manifest.dense)
            assertNull(opened.bytesOf("dense/points.bin"))
        }
        // Without a device, the builder writes the v1 manifest: no version key at all.
        val v1 = Json.parseToJsonElement(String(build(null, cloud(10)).manifest)).jsonObject
        assertFalse(v1.containsKey("version"))
        assertFalse(v1.containsKey("dense"))
    }

    @Test
    fun `the dense map's growth rides the log as depth_stats, and the replay reads it back`() {
        val line = ArDebugLogWriter.line(ArDebugEvent.DepthStats(2_000_000_000L, 120, 150, 900))!!
        assertEquals("""{"t":2000000000,"type":"depth_stats","new":120,"kept":150,"total":900}""", line)
        val trace = ArDebugTrace.of(
            listOf(
                ArDebugEvent.CameraPose(0L, DebugPose(0f, 0f, 0f)),
                parseArDebugEvent(line)!!,
                ArDebugEvent.DepthStats(3_000_000_000L, 100, 100, 1_000),
            ),
        )
        assertTrue(trace.hasDepthStats)
        assertEquals(0, trace.denseCountAt(1f))
        assertEquals(900, trace.denseCountAt(2.5f))
        assertEquals(1_000, trace.denseCountAt(9f))
        assertNull(parseArDebugEvent("""{"t":1,"type":"depth_stats"}"""))
    }

    @Test
    fun `a v2 scan's PLY is its dense cloud with normals, never an empty vertex list`() {
        val scene = RerunExportAdapter.scene(build(depthDevice, cloud(2_000)), "Dense room")!!
        assertEquals(2_000, scene.dense!!.count)
        val ply = RerunPlyWriter.write(scene)
        val header = String(ply, 0, headerEnd(ply))
        assertTrue(header, header.contains("element vertex 2000\n"))
        assertTrue(header.contains("property float nx\nproperty float ny\nproperty float nz\n"))
        assertEquals(headerEnd(ply) + 2_000 * RerunPlyWriter.BYTES_PER_DENSE_POINT, ply.size)
        // The first record is the first surfel, to the SVPC millimetre.
        val record = ByteBuffer.wrap(ply, headerEnd(ply), 27).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(scene.dense!!.positions[0], record.float, 1e-6f)

        // The v1 sample still exports its sparse map, not an empty file.
        val sparse = RerunPlyWriter.write(RerunExportAdapter.scene(showcase, "Sample")!!)
        assertFalse(String(sparse, 0, headerEnd(sparse)).contains("element vertex 0\n"))
    }

    @Test
    fun `a v2 scan's GLB and RRD carry the dense cloud as world-dense`() {
        val scene = RerunExportAdapter.scene(build(depthDevice, cloud(2_000)), "Dense room")!!

        val glb = RerunGlbWriter.write(scene)
        val jsonLength = ByteBuffer.wrap(glb, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val gltf = Json.parseToJsonElement(String(glb, 20, jsonLength)).jsonObject
        val node = gltf["nodes"]!!.jsonArray.map { it.jsonObject }
            .first { it["name"]?.jsonPrimitive?.content == "world/dense" }
        val mesh = gltf["meshes"]!!.jsonArray[node["mesh"]!!.jsonPrimitive.int].jsonObject
        val primitive = mesh["primitives"]!!.jsonArray[0].jsonObject
        assertEquals(0, primitive["mode"]!!.jsonPrimitive.int)
        val attributes = primitive["attributes"]!!.jsonObject
        val accessors = gltf["accessors"]!!.jsonArray
        for (name in listOf("POSITION", "NORMAL", "COLOR_0")) {
            val accessor = accessors[attributes[name]!!.jsonPrimitive.int].jsonObject
            assertEquals(2_000, accessor["count"]!!.jsonPrimitive.int)
        }

        // Rerun's world/dense: one static Points3D row — positions, colours, a half-voxel radius.
        val chunk = RerunRrdWriter.chunks(scene, FakeRerunImageCodec).single { it.entityPath == "/world/dense" }
        assertEquals(1, chunk.rowCount)
        assertEquals(
            listOf("Points3D:positions", "Points3D:radii", "Points3D:colors"),
            chunk.components.map { it.fieldName },
        )
        // 2 000 positions (12 bytes) and colours (4 bytes) more than the same scan without them.
        val rrd = RerunRrdWriter.write(scene, FakeRerunImageCodec)
        val withoutDense = RerunRrdWriter.write(scene.copy(dense = null), FakeRerunImageCodec)
        assertTrue(rrd.size - withoutDense.size >= 2_000 * 16)
        // The app reopens its own export with the dense cloud: world/dense comes back as the
        // pack's `dense/points.bin` and a v2 manifest entry, not only the sparse map.
        val reopened = RerunRrdReader.recording(rrd, FakeRerunImageCodec).pack.open()
        assertNotNull(reopened)
        val manifest = reopened!!.manifest
        assertEquals(2, manifest.version)
        val dense = manifest.dense!!
        assertEquals("dense/points.bin", dense.path)
        assertEquals(2_000, dense.count)
        assertEquals(DenseFusion.VOXEL_M, dense.voxelM, 1e-6f)
        val cloud = SvpcCodec.decode(reopened.bytesOf(dense.path)!!)!!
        assertEquals(2_000, cloud.count)
        assertArrayEquals(scene.dense!!.colors, cloud.colors)
        for (i in 0 until cloud.count * 3) {
            // Quantised to the millimetre twice (SVPC, then SVPC again from the RRD's floats).
            assertEquals(scene.dense!!.positions[i], cloud.positions[i], 1.5e-3f)
        }
        // And its exports carry it again: a reimported .rrd's PLY is still the dense cloud.
        val again = RerunExportAdapter.scene(RerunRrdReader.recording(rrd, FakeRerunImageCodec).pack, "Again")!!
        assertEquals(2_000, again.dense!!.count)
        val ply = RerunPlyWriter.write(again)
        assertTrue(String(ply, 0, headerEnd(ply)).contains("element vertex 2000\n"))

        // A scan without a dense cloud still reopens as v1, with no dense entry.
        val sparse = RerunRrdReader.recording(withoutDense, FakeRerunImageCodec).pack.open()!!
        assertEquals(1, sparse.manifest.version)
        assertNull(sparse.manifest.dense)
    }

    private fun headerEnd(data: ByteArray): Int {
        val marker = "end_header\n".toByteArray()
        val at = (0..data.size - marker.size).first { at -> marker.indices.all { data[at + it] == marker[it] } }
        return at + marker.size
    }

    private fun cloud(n: Int) = DenseCloud(
        positions = FloatArray(n * 3) { i -> (i / 3 % 100) * 0.02f + (i % 3) * 0.3f },
        colors = IntArray(n) { (0xFF shl 24) or (it * 997 and 0xFFFFFF) },
        normals = FloatArray(n * 3) { if (it % 3 == 1) 1f else 0f },
        confidences = ByteArray(n) { 200.toByte() },
    )
}
