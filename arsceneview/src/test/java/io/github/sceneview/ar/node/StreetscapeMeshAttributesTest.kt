package io.github.sceneview.ar.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * [StreetscapeGeometryNode] vertex-attribute contract (#3215) — the same defect class as the
 * plane visualizers' (#3213), on the one remaining `VertexBuffer.Builder` site that took a lit
 * material with a POSITION-only mesh.
 *
 * The committed material metadata and the pure tangent/UV derivation are both JVM-reachable.
 */
class StreetscapeMeshAttributesTest {

    // ── 1. Contract ───────────────────────────────────────────────────────────────────────────

    /** The blobs `MaterialLoader.createColorInstance` resolves to, by alpha. They live in `:sceneview`. */
    private val coloredMaterials = listOf("opaque_colored", "transparent_colored")

    @Test
    fun `the colored materials require TANGENTS and UV0 — the reason this node derives them`() {
        coloredMaterials.forEach { material ->
            assertEquals(
                "$material.filamat is expected to require POSITION|TANGENTS|UV0 (0xB) — that " +
                    "is WHY StreetscapeGeometryNode derives tangents. If the requirement " +
                    "changed, revisit the node rather than relaxing this.",
                POSITION_BIT or TANGENTS_BIT or UV0_BIT,
                requiredAttributes(material)
            )
        }
    }

    // ── 2. Derivation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a flat quad facing +Y encodes a +Y normal on every vertex`() {
        // Two CCW triangles in the XZ plane, front face up (+Y).
        val positions = floats(
            0f, 0f, 0f,
            0f, 0f, 1f,
            1f, 0f, 1f,
            1f, 0f, 0f,
        )
        val indices = ints(0, 1, 2, 0, 2, 3)

        val tangents = computeStreetscapeTangents(positions, indices, vertexCount = 4)

        assertEquals(4 * STREETSCAPE_TANGENT_STRIDE, tangents.remaining())
        repeat(4) { v ->
            assertNormalCloseTo(Float3(0f, 1f, 0f), decodedNormal(tangents, v), "vertex $v")
        }
    }

    @Test
    fun `a vertical facade encodes its own sideways normal, not a constant up`() {
        // CCW when seen from +X: a wall in the YZ plane at x = 0 facing +X.
        val positions = floats(
            0f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, -1f,
        )
        val indices = ints(0, 1, 2)

        val tangents = computeStreetscapeTangents(positions, indices, vertexCount = 3)

        repeat(3) { v ->
            assertNormalCloseTo(Float3(1f, 0f, 0f), decodedNormal(tangents, v), "vertex $v")
        }
    }

    @Test
    fun `a shared vertex gets the area-weighted average of its faces`() {
        // Vertex 0 is shared by a +Y face and a +X face of equal area: the smooth normal is the
        // normalised (1, 1, 0) diagonal.
        val positions = floats(
            0f, 0f, 0f, // shared
            0f, 0f, 1f,
            1f, 0f, 1f, // +Y triangle: 0,1,2 (CCW from above)
            0f, 0f, -1f,
            0f, 1f, -1f, // +X triangle: 0,3,4 (CCW from +X)
        )
        val indices = ints(0, 1, 2, 0, 3, 4)

        val tangents = computeStreetscapeTangents(positions, indices, vertexCount = 5)

        assertNormalCloseTo(normalize(Float3(1f, 1f, 0f)), decodedNormal(tangents, 0), "shared vertex")
    }

    @Test
    fun `unreferenced vertices and out-of-range indices fall back without NaN`() {
        val positions = floats(
            0f, 0f, 0f,
            0f, 0f, 1f,
            1f, 0f, 1f,
            5f, 5f, 5f, // referenced by nothing
        )
        // Second triangle points past vertexCount and must be skipped, not thrown.
        val indices = ints(0, 1, 2, 0, 1, 42)

        val tangents = computeStreetscapeTangents(positions, indices, vertexCount = 4)

        val floats = tangents.asFloatBuffer()
        repeat(4 * 4) { i ->
            assertTrue("component $i is NaN", !floats.get(i).isNaN())
        }
        assertNormalCloseTo(Float3(0f, 1f, 0f), decodedNormal(tangents, 3), "orphan vertex")
    }

    @Test
    fun `the UV0 buffer is zero-filled with 8 bytes per vertex`() {
        val uvs = zeroStreetscapeUvs(vertexCount = 7)
        assertEquals(7 * STREETSCAPE_UV_STRIDE, uvs.remaining())
        repeat(7 * 2) { i -> assertEquals(0f, uvs.asFloatBuffer().get(i)) }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    private fun floats(vararg values: Float): FloatBuffer = ByteBuffer
        .allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(values).apply { rewind() }

    private fun ints(vararg values: Int): IntBuffer = ByteBuffer
        .allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        .asIntBuffer().put(values).apply { rewind() }

    /** The normal is the frame's +Z axis: rotate (0, 0, 1) by the stored quaternion. */
    private fun decodedNormal(tangents: ByteBuffer, vertex: Int): Float3 {
        val f = tangents.asFloatBuffer()
        val q = Quaternion(f.get(vertex * 4), f.get(vertex * 4 + 1), f.get(vertex * 4 + 2), f.get(vertex * 4 + 3))
        val m = rotation(q)
        return Float3(m.z.x, m.z.y, m.z.z)
    }

    private fun assertNormalCloseTo(expected: Float3, actual: Float3, label: String) {
        assertTrue(
            "$label: expected normal $expected, decoded $actual",
            dot(expected, actual) > 0.999f
        )
    }

    /** Required-attribute bitmask of a committed `:sceneview` blob, from its `MAT_REQA` chunk. */
    private fun requiredAttributes(material: String): Int {
        val blob = File("../sceneview/src/main/assets/materials/$material.filamat")
        assertTrue("Expected ${blob.absolutePath}", blob.exists())
        val chunk = filamatChunk(blob.readBytes(), "MAT_REQA")
        assertNotNull("$material.filamat has no MAT_REQA chunk", chunk)
        return readUInt32LE(chunk!!, 0)
    }

    /** `[8-byte reversed tag][uint32 LE size][payload]` chunks — see PlaneVisualizerAttributeContractTest. */
    private fun filamatChunk(bytes: ByteArray, tag: String): ByteArray? {
        val tagBytes = tag.reversed().toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + 12 <= bytes.size) {
            val size = readUInt32LE(bytes, i + 8)
            if (size < 0 || i + 12 + size > bytes.size) return null
            if (bytes.copyOfRange(i, i + 8).contentEquals(tagBytes)) {
                return bytes.copyOfRange(i + 12, i + 12 + size)
            }
            i += 12 + size
        }
        return null
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val POSITION_BIT = 1 shl 0
        const val TANGENTS_BIT = 1 shl 1
        const val UV0_BIT = 1 shl 3
    }
}
