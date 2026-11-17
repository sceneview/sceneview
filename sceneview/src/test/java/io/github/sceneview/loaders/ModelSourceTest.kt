package io.github.sceneview.loaders

import io.github.sceneview.core.model.ModelConverter
import io.github.sceneview.core.model.ModelFormat
import io.github.sceneview.core.model.ModelUnit
import io.github.sceneview.core.model.UnsupportedModelFormat
import io.github.sceneview.core.stl.StlLoader
import io.github.sceneview.core.stl.StlParseException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
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
    fun objWithAPreambleCutMidKeywordAtTheSniffLimitLoadsWithoutAName() {
        // 4,094 bytes of comments, then `mtllib`: the 4,097-byte prefix ends on `mtl`.
        val comments = "# comment\n".repeat(409) + "#ab\n"
        val bytes = (comments + "mtllib part.mtl\n").encodeToByteArray() + obj
        assertEquals(4094, comments.length)
        assertEquals('g'.code.toByte(), (ByteBuffer.wrap(bytes).toModelGlb() as ByteBuffer).get(0))
    }

    @Test
    fun threeMfLoadsByContentWhateverItsDottedNameSays() {
        val threeMf = threeMf()
        // A shared download or a `content://` URI: neither name says 3MF, as on `main` (#5093).
        for (name in listOf("content://com.android.providers.downloads/document/0.1",
            "https://host/download.php?id=7", "part.zip", null)) {
            val glb = ByteBuffer.wrap(threeMf).toModelGlb(name) as ByteBuffer
            assertEquals(name, 'g'.code.toByte(), glb.get(0))
        }
        // A ZIP with no 3MF part is refused under its own name; a `.usdz` (see the probe below) is
        // refused before any copy.
        val usdz = zipOf("scene.usdc" to "PXR-USDC".encodeToByteArray())
        for ((name, extension) in listOf("scene.usdz" to "usdz", "https://host/download.php?id=7" to "php")) {
            val error = assertThrows(UnsupportedModelFormat::class.java) { ByteBuffer.wrap(usdz).toModelGlb(name) }
            assertEquals(extension, error.extension)
        }
    }

    private fun threeMf(): ByteArray {
        val model = """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
            <resources><object id="1" type="model"><mesh>
            <vertices><vertex x="0" y="0" z="0"/><vertex x="10" y="0" z="0"/><vertex x="0" y="10" z="0"/></vertices>
            <triangles><triangle v1="0" v2="1" v3="2"/></triangles>
            </mesh></object></resources>
            <build><item objectid="1"/></build>
            </model>""".trimIndent()
        // An incompressible thumbnail puts the central directory past the 4 KB sniffing prefix,
        // as in any real 3MF: the prefix alone cannot tell it from another ZIP.
        val thumbnail = Random(3489).nextBytes(8192)
        return zipOf("3D/3dmodel.model" to model.encodeToByteArray(), "Metadata/thumbnail.png" to thumbnail)
            .also { assertTrue(it.size > 8192) }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
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
