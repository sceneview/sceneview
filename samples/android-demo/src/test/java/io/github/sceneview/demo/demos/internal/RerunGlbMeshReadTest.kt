package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

class RerunGlbMeshReadTest {
    @Test
    fun `bulk reads match the previous reader bit for bit for both index widths`() {
        val mesh = RerunMesh(
            floatArrayOf(-0f, 0.125f, -2f, 1.25f, 0f, -2f, 1.25f, 1.5f, -2f, 0f, 1.5f, -2f),
            FloatArray(12) { if (it % 3 == 2) 1f else -0f },
            intArrayOf(0xFF123456.toInt(), 0xFFABCDEF.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            intArrayOf(0, 1, 2, 0, 2, 3),
        )
        val shortGlb = RerunMeshGlb.write(mesh)
        // The same small GLB with 32-bit indices, without a 65k-vertex fixture.
        val file = RerunGlbFile.parse(shortGlb, "fixture")
        val indexView = file.json.objects("bufferViews")[3]
        val indexAt = (indexView["byteOffset"] as Number).toInt()
        val binSize = indexAt + mesh.indices.size * 4
        indexView["byteLength"] = mesh.indices.size * 4
        file.json.objects("accessors")[3]["componentType"] = 5125
        file.json.objects("buffers")[0]["byteLength"] = binSize
        val intGlb = RerunMeshGlb.glb(RerunJson.write(file.json).toByteArray(), binSize) {
            put(checkNotNull(file.bin), 0, indexAt)
            mesh.indices.forEach { putInt(it) }
        }
        for (glb in listOf(shortGlb, intGlb)) {
            val expected = checkNotNull(LegacyRerunGlbMesh.read(glb))
            val actual = checkNotNull(RerunGlbMesh.read(glb))
            assertArrayEquals(expected.positions.map { it.toRawBits() }.toIntArray(), actual.positions.map { it.toRawBits() }.toIntArray())
            assertArrayEquals(expected.normals.map { it.toRawBits() }.toIntArray(), actual.normals.map { it.toRawBits() }.toIntArray())
            assertArrayEquals(expected.colors, actual.colors)
            assertArrayEquals(expected.indices, actual.indices)
            assertArrayEquals(RerunMeshGlb.write(expected), RerunMeshGlb.write(actual))
            val geometry = checkNotNull(RerunGlbMesh.readGeometry(glb))
            assertArrayEquals(expected.positions, geometry.positions, 0f)
            assertArrayEquals(expected.normals, checkNotNull(geometry.normals), 0f)
            assertFalse(geometry.shellQuads)
            val positions = checkNotNull(RerunGlbMesh.readGeometry(glb, withNormals = false))
            assertArrayEquals(expected.positions, positions.positions, 0f)
            assertNull(positions.normals)
        }
    }

    @Test
    fun `invalid input still returns null`() {
        for (bytes in listOf(ByteArray(0), ByteArray(100))) {
            assertNull(RerunGlbMesh.read(bytes))
            assertNull(RerunGlbMesh.readGeometry(bytes))
        }
    }
}

/** Frozen pre-optimisation reader: the compatibility oracle, including its colour conversion. */
private object LegacyRerunGlbMesh {
    @Suppress("ReturnCount", "MagicNumber")
    fun read(glb: ByteArray): RerunMesh? = runCatching {
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != GLB_MAGIC) return null
        val jsonLength = buffer.getInt(HEADER)
        val json = String(glb, HEADER + CHUNK_HEADER, jsonLength, Charsets.UTF_8)
        val binStart = HEADER + CHUNK_HEADER + jsonLength + CHUNK_HEADER
        val root = lenient.parseToJsonElement(json) as JsonObject
        val accessors = root["accessors"] as JsonArray
        val views = root["bufferViews"] as JsonArray
        fun Any?.int(key: String) = ((this as JsonObject)[key] as? JsonPrimitive)?.intOrNull ?: 0
        fun offsetOf(accessor: Int): Int = binStart + views[accessors[accessor].int("bufferView")].int("byteOffset")
        val n = accessors[0].int("count")
        val indexCount = accessors[3].int("count")
        val positions = FloatArray(n * 3) { buffer.getFloat(offsetOf(0) + it * 4) }
        val normals = FloatArray(n * 3) { buffer.getFloat(offsetOf(1) + it * 4) }
        val colorAt = offsetOf(2)
        val colors = IntArray(n) { v ->
            val r = SRGB[glb[colorAt + v * 4].toInt() and 0xFF]
            val g = SRGB[glb[colorAt + v * 4 + 1].toInt() and 0xFF]
            val b = SRGB[glb[colorAt + v * 4 + 2].toInt() and 0xFF]
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val indexAt = offsetOf(3)
        // 16-bit when the mesh has few enough vertices ([RerunMeshGlb]), 32-bit otherwise.
        val indices = if (accessors[3].int("componentType") == UNSIGNED_SHORT) {
            IntArray(indexCount) { buffer.getShort(indexAt + it * 2).toInt() and 0xFFFF }
        } else {
            IntArray(indexCount) { buffer.getInt(indexAt + it * 4) }
        }
        if (indices.any { it !in 0 until n }) return null
        RerunMesh(positions, normals, colors, indices)
    }.getOrNull()

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Linear byte → sRGB byte, the inverse of [RerunMeshGlb]'s. */
    private val SRGB = IntArray(256) { i ->
        val c = i / 255.0
        val s = if (c <= LINEAR_KNEE) c * SRGB_LINEAR_SLOPE else (1 + SRGB_OFFSET) * c.pow(1 / SRGB_GAMMA) - SRGB_OFFSET
        (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    private const val LINEAR_KNEE = 0.0031308
    private const val SRGB_LINEAR_SLOPE = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_GAMMA = 2.4
    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val GLB_MAGIC = 0x46546C67
    private const val UNSIGNED_SHORT = 5123
}

