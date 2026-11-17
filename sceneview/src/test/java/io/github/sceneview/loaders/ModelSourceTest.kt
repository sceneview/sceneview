package io.github.sceneview.loaders

import io.github.sceneview.core.model.ModelConverter
import io.github.sceneview.core.model.ModelFormat
import io.github.sceneview.core.model.ModelUnit
import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlParseException
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSourceTest {
    private val obj = "v 0 0 0\nv 20 0 0\nv 0 30 0\nf 1 2 3\n".encodeToByteArray()

    @Test
    fun nativeFormatsKeepTheirBufferAndPositionEvenWithTheWrongExtension() {
        for (text in listOf("glTF", "{\"asset\":{\"version\":\"2.0\"}}")) {
            val buffer = ByteBuffer.allocateDirect(512)
            buffer.position(7)
            buffer.put(text.encodeToByteArray())
            buffer.flip().position(7)
            val readOnly = buffer.asReadOnlyBuffer()
            assertSame(readOnly, readOnly.toModelGlb("wrong.stl", ModelUnit.Inches))
            assertEquals(7, readOnly.position())
        }
    }

    @Test
    fun meshConversionUsesOnlyRemainingBytesAndTheExplicitUnitWithoutMovingTheSource() {
        val buffer = ByteBuffer.allocateDirect(obj.size + 20)
        buffer.position(7)
        buffer.put(obj)
        buffer.flip().position(7)
        buffer.mark()
        val result = buffer.asReadOnlyBuffer().toModelGlb("part.bin", ModelUnit.Inches) as ByteBuffer
        assertTrue(result.isDirect)
        val actual = ByteArray(result.remaining()).also { result.get(it) }
        assertArrayEquals(ModelConverter.toGlb(obj, ModelFormat.OBJ, ModelUnit.Inches), actual)
        assertEquals(7, buffer.position())
        assertEquals(obj.size + 7, buffer.limit())
        buffer.reset()
    }

    @Test
    fun objWithLongHeaderLoadsWithoutANameOrWithAMisleadingExtension() {
        val longHeader = ("# comment\n".repeat(500)).encodeToByteArray() + obj
        val source = ByteBuffer.wrap(longHeader)
        assertEquals('g'.code.toByte(), (source.toModelGlb() as ByteBuffer).get(0))
        assertEquals('g'.code.toByte(), (source.toModelGlb("wrong.glb") as ByteBuffer).get(0))
        assertEquals('g'.code.toByte(), (source.toModelGlb("part.OBJ") as ByteBuffer).get(0))
    }

    @Test
    fun gltfWithLateAssetAndBomKeepsItsNativeBuffer() {
        val json = "\uFEFF{\"extras\":\"" + "x".repeat(70000) + "\",\"asset\":{\"version\":\"2.0\"}}"
        val buffer = ByteBuffer.wrap(json.encodeToByteArray()).asReadOnlyBuffer()
        assertSame(buffer, buffer.toModelGlb("wrong.stl"))
        assertEquals(0, buffer.position())
        assertThrows(UnsupportedModelFormat::class.java) {
            ByteBuffer.wrap("{\"extras\":{\"asset\":{}}}".encodeToByteArray()).toModelGlb()
        }
    }

    @Test
    fun oversizedStlIsRejectedBeforeAHeapCopyCanExhaustASmallHeap() {
        val classpath = listOf(ModelSourceTest::class.java, ModelLoader::class.java,
            ModelConverter::class.java, kotlin.Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx32m", "-XX:MaxDirectMemorySize=64m", "-cp", classpath,
            "io.github.sceneview.loaders.OversizedStlProbe",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("Size-limit probe timed out", finished)
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("Rejected binary and ASCII STL before copying"))
        assertTrue(output, output.contains("Rejected FBX and USDZ before copying"))
    }

    @Test
    fun unknownAndSplatInputsAreRefusedBeforeJniWithTypedErrors() {
        val error = assertThrows(UnsupportedModelFormat::class.java) {
            ByteBuffer.wrap(byteArrayOf()).toModelGlb("https://host/part.STEP?token=none")
        }
        assertEquals("step", error.extension)
        assertThrows(UnsupportedModelFormat::class.java) { ByteBuffer.wrap("NGSP".encodeToByteArray()).toModelGlb("part.spz") }
        // A `.glb` nothing recognises reaches gltfio, for its own parse error, rather than "unknown".
        val corrupt = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 0, 5))
        assertSame(corrupt, corrupt.toModelGlb("broken.glb"))
        // Not a ByteBuffer: handed to gltfio untouched, as before this entry point existed.
        val floats = ByteBuffer.allocate(16).asFloatBuffer()
        assertSame(floats, floats.toModelGlb())
    }
}

/** A separate VM makes a premature 32 MiB heap allocation observable as a failing process. */
object OversizedStlProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val triangles = StlLoader.DEFAULT_MAX_BYTES / 50 + 1
        val buffer = ByteBuffer.allocateDirect(84 + triangles * 50)
        repeat(4) { buffer.put(80 + it, (triangles ushr (it * 8)).toByte()) }
        assertRefused(buffer)
        repeat(84) { buffer.put(it, 32) }
        buffer.put(0, 11)
        "solid ".encodeToByteArray().forEachIndexed { index, byte -> buffer.put(1 + index, byte) }
        // A long ASCII header also has to be detected without copying the full payload.
        repeat(5000) { buffer.put(7 + it, 32) }
        "facet ".encodeToByteArray().forEachIndexed { index, byte -> buffer.put(5007 + index, byte) }
        assertRefused(buffer)
        buffer.put(0, 12)
        assertRefused(buffer)
        println("Rejected binary and ASCII STL before copying")
        // Neither the prefix nor the extension names a supported format: no heap copy either.
        repeat(84) { buffer.put(it, 0) }
        "Kaydara FBX Binary  ".encodeToByteArray().forEachIndexed { index, byte -> buffer.put(index, byte) }
        assertUnsupported(buffer, "huge.fbx", "fbx")
        "PK\u0003\u0004".encodeToByteArray().forEachIndexed { index, byte -> buffer.put(index, byte) }
        assertUnsupported(buffer, "huge.usdz", "usdz")
        println("Rejected FBX and USDZ before copying")
    }

    private fun assertUnsupported(buffer: ByteBuffer, name: String, extension: String) {
        try {
            buffer.toModelGlb(name)
            error("$name was accepted")
        } catch (error: UnsupportedModelFormat) {
            check(error.extension == extension)
            check(buffer.position() == 0)
        }
    }

    private fun assertRefused(buffer: ByteBuffer) {
        try {
            buffer.toModelGlb()
            error("Oversized STL was accepted")
        } catch (error: StlParseException) {
            check(error.message!!.contains("exceeds"))
            check(buffer.position() == 0)
        }
    }
}
