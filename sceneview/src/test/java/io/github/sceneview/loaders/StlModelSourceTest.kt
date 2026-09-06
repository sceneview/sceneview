package io.github.sceneview.loaders

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StlModelSourceTest {
    @Test
    fun binaryDetectionUsesRemainingRangeAndLittleEndianCountWithoutMovingTheBuffer() {
        val bytes = ByteArray(134)
        "solid misleading facet".encodeToByteArray().copyInto(bytes)
        bytes[80] = 1
        val buffer = ByteBuffer.allocateDirect(160).order(ByteOrder.BIG_ENDIAN)
        buffer.position(7)
        buffer.put(bytes)
        buffer.limit(buffer.position())
        buffer.position(7)
        buffer.mark()
        val readonly = buffer.asReadOnlyBuffer()
        assertTrue(readonly.looksLikeStlPayload())
        val result = readonly.convertStlToGlb() as ByteBuffer
        assertTrue(result.isDirect)
        assertEquals('g'.code.toByte(), result.get(0))
        assertEquals(7, readonly.position())
        assertEquals(141, readonly.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, readonly.order())
        buffer.reset()
        assertEquals(7, buffer.position())
    }

    @Test
    fun asciiNeedsBothTokensAndAcceptsLeadingWhitespace() {
        val ascii = """  solid part
            facet normal 0 0 0
            outer loop
            vertex 0 0 0
            vertex 70 0 0
            vertex 0 40 0
            endloop
            endfacet
            endsolid part
        """.trimIndent()
        val buffer = ByteBuffer.wrap(ascii.encodeToByteArray())
        assertTrue(buffer.looksLikeStlPayload())
        assertEquals('g'.code.toByte(), (buffer.convertStlToGlb() as ByteBuffer).get(0))
        assertEquals(0, buffer.position())
    }

    @Test
    fun otherFormatsAreReturnedByIdentity() {
        val otherFormats = listOf(
            "glTF", "{\"asset\":{}}", "PK\u0003\u0004", "solid", "solidarity facet",
            "solid x\n multifacet"
        )
        for (text in otherFormats) {
            val buffer = ByteBuffer.wrap(text.encodeToByteArray())
            assertFalse(buffer.looksLikeStlPayload())
            assertSame(buffer, buffer.convertStlToGlb())
        }
        val largeGlb = ByteBuffer.allocate(100_084)
        largeGlb.putInt(0, 0x676c5446)
        assertSame(largeGlb, largeGlb.convertStlToGlb())
        val floats = ByteBuffer.allocate(12).asFloatBuffer()
        assertSame(floats, floats.convertStlToGlb())
    }

    @Test
    fun mismatchedOrUnsignedBinaryCountsDoNotPassTheCheapGate() {
        val bytes = ByteArray(134)
        bytes[80] = 2
        assertFalse(ByteBuffer.wrap(bytes).looksLikeStlPayload())
        repeat(4) { bytes[80 + it] = -1 }
        assertFalse(ByteBuffer.wrap(bytes).looksLikeStlPayload())
    }
}
