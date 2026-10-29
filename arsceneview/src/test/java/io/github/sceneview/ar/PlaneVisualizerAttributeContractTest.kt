package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contracts for the compiled plane-material attribute metadata. */
class PlaneVisualizerAttributeContractTest {

    @Test
    fun `plane_renderer_shadow requires TANGENTS`() {
        assertEquals(POSITION_BIT or TANGENTS_BIT, requiredAttributes("plane_renderer_shadow"))
    }

    @Test
    fun `the REQA parser distinguishes a POSITION-only material from a TANGENTS one`() {
        assertEquals(POSITION_BIT, requiredAttributes("plane_renderer"))
    }

    private fun requiredAttributes(material: String): Int {
        val blob = File("src/main/assets/materials/$material.filamat")
        assertTrue("Expected ${blob.absolutePath}", blob.exists())
        val chunk = filamatChunk(blob.readBytes(), "MAT_REQA")
        assertNotNull("$material.filamat has no MAT_REQA chunk", chunk)
        return readUInt32LE(chunk!!, 0)
    }

    private fun filamatChunk(bytes: ByteArray, tag: String): ByteArray? {
        val tagBytes = tag.reversed().toByteArray(Charsets.US_ASCII)
        var offset = 0
        while (offset + 12 <= bytes.size) {
            val size = readUInt32LE(bytes, offset + 8)
            if (size < 0 || offset + 12 + size > bytes.size) return null
            if (bytes.copyOfRange(offset, offset + 8).contentEquals(tagBytes)) {
                return bytes.copyOfRange(offset + 12, offset + 12 + size)
            }
            offset += 12 + size
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
    }
}
