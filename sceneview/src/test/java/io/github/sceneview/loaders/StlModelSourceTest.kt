package io.github.sceneview.loaders

import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.core.stl.StlLoader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
        val result = readonly.toModelGlb() as ByteBuffer
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
        assertEquals('g'.code.toByte(), (buffer.toModelGlb() as ByteBuffer).get(0))
        assertEquals(0, buffer.position())
    }

    @Test
    fun nativeFormatsAreReturnedByIdentityAndUnknownInputsHaveTypedErrors() {
        for (text in listOf("glTF", "{\"asset\":{}}")) {
            val buffer = ByteBuffer.wrap(text.encodeToByteArray())
            assertSame(buffer, buffer.toModelGlb())
        }
        for (text in listOf("PK\u0003\u0004", "solid", "solidarity facet", "solid x\n multifacet")) {
            assertThrows(UnsupportedModelFormat::class.java) { ByteBuffer.wrap(text.encodeToByteArray()).toModelGlb() }
        }
        val largeGlb = ByteBuffer.allocate(100_084)
        largeGlb.putInt(0, 0x676c5446)
        assertSame(largeGlb, largeGlb.toModelGlb())
        val floats = ByteBuffer.allocate(12).asFloatBuffer()
        assertThrows(UnsupportedModelFormat::class.java) { floats.toModelGlb() }
    }

    @Test
    fun mismatchedOrUnsignedBinaryCountsDoNotPassTheCheapGate() {
        val bytes = ByteArray(134)
        bytes[80] = 2
        assertFalse(StlLoader.isStl(bytes))
        repeat(4) { bytes[80 + it] = -1 }
        assertFalse(StlLoader.isStl(bytes))
    }
}
