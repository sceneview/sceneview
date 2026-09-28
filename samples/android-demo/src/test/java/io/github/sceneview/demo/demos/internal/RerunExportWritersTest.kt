package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * The `.ply` and `.glb` exporters, parsed back byte by byte and checked against the scene they
 * came from. Mirrors the iOS demo's `RerunPLYGLBWriterTests`, same fixture, same pinned header.
 */
class RerunExportWritersTest {
    // ── PLY ───────────────────────────────────────────────────────────────────

    @Test
    fun `the PLY header is the iOS header byte for byte`() {
        val expected = "ply\n" +
            "format binary_little_endian 1.0\n" +
            "comment SceneView capture \"Test room\", Y up, metres\n" +
            "element vertex 40\n" +
            "property float x\n" +
            "property float y\n" +
            "property float z\n" +
            "property uchar red\n" +
            "property uchar green\n" +
            "property uchar blue\n" +
            "end_header\n"
        val data = RerunPlyWriter.write(scene())
        assertArrayEquals(expected.toByteArray(), data.copyOf(expected.length))
    }

    @Test
    fun `a PLY is its header plus fifteen bytes per point`() {
        val scene = scene()
        val header = RerunPlyWriter.header(scene.title, scene.points.size)
        assertEquals(header.toByteArray().size + scene.points.size * 15, RerunPlyWriter.write(scene).size)
    }

    @Test
    fun `a PLY round-trips every point and colour`() {
        val scene = scene()
        val (count, records) = parsePly(RerunPlyWriter.write(scene))
        assertEquals(scene.points.size, count)
        for (index in scene.points.indices) {
            val record = ByteBuffer.wrap(records, index * 15, 15).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(scene.points[index], Vec3(record.float, record.float, record.float))
            val rgb = List(3) { record.get().toInt() and 0xFF }
            val color = scene.pointColors[index]
            assertEquals(listOf(color.r, color.g, color.b), rgb)
        }
    }

    @Test
    fun `an empty cloud is a header only, and line breaks stay in the comment`() {
        val empty = scene().copy(points = emptyList(), pointColors = emptyList())
        val data = RerunPlyWriter.write(empty)
        assertEquals(0 to 0, parsePly(data).let { it.first to it.second.size })
        assertTrue(String(data).endsWith("end_header\n"))

        val twoLines = scene().copy(title = "Two\nlines")
        assertEquals(40, parsePly(RerunPlyWriter.write(twoLines)).first)
        assertTrue(RerunPlyWriter.header(twoLines.title, 1).contains("\"Two lines\""))
    }

    // ── GLB ───────────────────────────────────────────────────────────────────

    @Test
    fun `a GLB container is well formed`() {
        val data = RerunGlbWriter.write(scene())
        assertEquals(0x46546C67, word(data, 0))
        assertEquals(2, word(data, 4))
        assertEquals(data.size, word(data, 8))
        val jsonLength = word(data, 12)
        assertEquals(0x4E4F534A, word(data, 16))
        assertEquals(0, jsonLength % 4)
        val binStart = 20 + jsonLength
        val binLength = word(data, binStart)
        assertEquals(0x004E4942, word(data, binStart + 4))
        assertEquals(0, binLength % 4)
        assertEquals(data.size, binStart + 8 + binLength)
        assertFalse(data.copyOfRange(20, binStart).contains(0))

        val (document, bin) = parseGlb(data)
        val buffers = document["buffers"] as JsonArray
        assertEquals(1, buffers.size)
        assertEquals(bin.size, buffers[0].jsonObject["byteLength"]!!.jsonPrimitive.int)
        val asset = document["asset"]!!.jsonObject
        assertEquals("2.0", asset["version"]!!.jsonPrimitive.content)
        assertEquals("SceneView", asset["generator"]!!.jsonPrimitive.content)
        for (view in document["bufferViews"] as JsonArray) {
            val offset = view.jsonObject["byteOffset"]?.jsonPrimitive?.int ?: 0
            assertEquals(0, offset % 4)
            assertTrue(offset + view.jsonObject["byteLength"]!!.jsonPrimitive.int <= bin.size)
        }
    }

    @Test
    fun `a GLB names its nodes as the wire format does`() {
        val (document, _) = parseGlb(RerunGlbWriter.write(scene()))
        val names = (document["nodes"] as JsonArray).mapNotNull {
            (it.jsonObject["name"] as? JsonPrimitive)?.contentOrNull
        }.toSet()
        val expected = listOf(
            "world", "world/points", "world/camera/path", "world/planes/1", "world/planes/2", "world/anchors/7",
        )
        for (name in expected) {
            assertTrue("missing node $name in $names", name in names)
        }
        val extras = document["asset"]!!.jsonObject["extras"]!!.jsonObject
        assertEquals("Test room", extras["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an empty scene has no buffer`() {
        val empty = scene().copy(
            title = "Empty",
            lens = null,
            points = emptyList(),
            pointColors = emptyList(),
            cameraPath = emptyList(),
            keyframes = emptyList(),
            planes = emptyList(),
            anchors = emptyList(),
        )
        val data = RerunGlbWriter.write(empty)
        assertEquals(data.size, word(data, 8))
        assertEquals(data.size, 20 + word(data, 12))
        val (document, bin) = parseGlb(data)
        assertTrue(bin.isEmpty())
        assertFalse("buffers" in document)
    }

    @Test
    fun `a textured plane carries its photo through the codec`() {
        val (document, bin) = parseGlb(RerunGlbWriter.write(scene(), codec = FakeRerunImageCodec))
        val images = document["images"] as JsonArray
        assertEquals(1, images.size)
        val view = images[0].jsonObject["bufferView"]!!.jsonPrimitive.int
        val span = (document["bufferViews"] as JsonArray)[view].jsonObject
        val offset = span["byteOffset"]?.jsonPrimitive?.int ?: 0
        val length = span["byteLength"]!!.jsonPrimitive.int
        assertArrayEquals(PNG, bin.copyOfRange(offset, offset + length))
        assertEquals("image/png", images[0].jsonObject["mimeType"]!!.jsonPrimitive.content)
    }

    // ── Fixture and parsing ───────────────────────────────────────────────────

    private companion object {
        /** Not a real PNG: only its magic matters to the GLB writer, which embeds it as is. */
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4)

        /** 40 coloured points, a 3-sample path, 2 keyframes, a textured floor, an untextured wall and a bare anchor. */
        fun scene(): RerunExportScene {
            val points = List(40) { i -> Vec3(i * 0.1f - 2f, sin(i.toFloat()) * 0.5f, (i % 7) * -0.3f) }
            val colors = List(40) { i -> RerunExportScene.Rgb(i * 6 and 0xFF, 255 - i * 3, i * 37 % 256) }
            val identity = RerunExportScene.Quat.Identity
            val path = listOf(
                RerunExportScene.CameraSample(0.0, Vec3(0f, 1.4f, 0f), identity),
                RerunExportScene.CameraSample(0.5, Vec3(0.2f, 1.5f, -0.3f), identity),
                RerunExportScene.CameraSample(1.0, Vec3(0.4f, 1.45f, -0.7f), yaw(0.4f)),
            )
            val floor = listOf(Vec3(-1f, 0f, -2f), Vec3(1f, 0f, -2f), Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f))
            val wall = listOf(Vec3(-1f, 0f, -2f), Vec3(-1f, 2f, -2f), Vec3(1f, 2f, -2f), Vec3(1f, 0f, -2f))
            return RerunExportScene(
                title = "Test room",
                lens = RerunExportScene.Lens(480, 640, 463.5f, 463.5f, 240f, 320f),
                points = points,
                pointColors = colors,
                cameraPath = path,
                keyframes = listOf(
                    RerunExportScene.Keyframe(0.0, "frames/000.webp", path[0]),
                    RerunExportScene.Keyframe(1.0, "frames/001.webp", path[2]),
                ),
                images = emptyMap(),
                planes = listOf(
                    RerunExportScene.Plane(
                        1,
                        "horizontal_upward",
                        floor,
                        RerunExportScene.PlaneTexture(PNG, Vec3(-1f, 0f, -2f), Vec3(2f, 0f, 0f), Vec3(0f, 0f, 2f)),
                    ),
                    RerunExportScene.Plane(2, "vertical", wall, null),
                ),
                anchors = listOf(RerunExportScene.Anchor(7, Vec3(0.5f, 0f, -1f), yaw(0.8f), null)),
            )
        }

        fun yaw(angle: Float) = RerunExportScene.Quat(0f, sin(angle / 2), 0f, cos(angle / 2))

        fun word(data: ByteArray, offset: Int): Int =
            ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

        /** The vertex count and the binary records after `end_header`. */
        fun parsePly(data: ByteArray): Pair<Int, ByteArray> {
            val marker = "end_header\n".toByteArray()
            val start = (0..data.size - marker.size).first { at -> marker.indices.all { data[at + it] == marker[it] } }
            val end = start + marker.size
            val header = String(data, 0, end)
            assertTrue(header.startsWith("ply\nformat binary_little_endian 1.0\n"))
            val line = header.lines().first { it.startsWith("element vertex ") }
            val count = line.removePrefix("element vertex ").toInt()
            val records = data.copyOfRange(end, data.size)
            assertEquals(count * 15, records.size)
            return count to records
        }

        /** The JSON document and the BIN chunk (empty when absent). */
        fun parseGlb(data: ByteArray): Pair<JsonObject, ByteArray> {
            val jsonLength = word(data, 12)
            val document = Json.parseToJsonElement(String(data, 20, jsonLength)).jsonObject
            val binStart = 20 + jsonLength
            if (binStart >= data.size) return document to ByteArray(0)
            return document to data.copyOfRange(binStart + 8, binStart + 8 + word(data, binStart))
        }
    }
}
