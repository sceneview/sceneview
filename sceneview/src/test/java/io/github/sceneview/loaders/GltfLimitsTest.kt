package io.github.sceneview.loaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The glTF files Filament aborts on instead of refusing them: a skin of more than 256 joints
 * (`RenderableManager::Builder::build`, from `AssetLoader.createAsset`) and a texture larger than
 * the GPU's largest (`Texture::Builder::build`, from `ResourceLoader.asyncBeginLoad`). Both are
 * `PreconditionPanic`s, so [ModelLoader] has to find them in the file first.
 */
@RunWith(RobolectricTestRunner::class) // org.json is an Android API
class GltfLimitsTest {

    @Test
    fun `a skin of more than 256 joints is refused`() {
        val gltf = text("""{"asset":{"version":"2.0"},"skins":[{"joints":${joints(16)}},{"joints":${joints(300)}}]}""")

        assertEquals(
            listOf("skin 1 has 300 joints, more than the 256 Filament can skin"),
            GltfLimits.violations(gltf, MAX),
        )
    }

    @Test
    fun `a skin of exactly 256 joints loads`() {
        val gltf = text("""{"skins":[{"joints":${joints(256)}}]}""")

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a texture embedded in a GLB larger than the GPU's is refused`() {
        val small = png(1024, 1024)
        val huge = png(8192, 4096)
        val json = """{"images":[{"bufferView":0,"mimeType":"image/png"},{"bufferView":1,"name":"albedo"}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${small.size}},""" +
            """{"buffer":0,"byteOffset":${small.size},"byteLength":${huge.size}}],""" +
            """"buffers":[{"byteLength":${small.size + huge.size}}]}"""

        assertEquals(
            listOf("image 1 (albedo) is 8192x4096, larger than the 4096x4096 textures this GPU supports"),
            GltfLimits.violations(glb(json, small + huge), MAX),
        )
    }

    @Test
    fun `an external texture is measured once it is loaded`() {
        val gltf = text("""{"images":[{"uri":"albedo.jpg"},{"uri":"normal.png"}]}""")
        val resources = mapOf("albedo.jpg" to ByteBuffer.wrap(jpeg(4096, 8192)), "normal.png" to ByteBuffer.wrap(png(512, 512)))

        assertEquals(
            listOf("image 0 (albedo.jpg) is 4096x8192, larger than the 4096x4096 textures this GPU supports"),
            GltfLimits.violations(gltf, MAX) { resources[it] },
        )
        assertEquals("not loaded yet: nothing to measure", emptyList<String>(), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a texture in an external buffer is measured`() {
        val huge = ktx2(8192, 8192)
        val gltf = text(
            """{"images":[{"bufferView":0}],"bufferViews":[{"buffer":0,"byteOffset":4,"byteLength":${huge.size}}],""" +
                """"buffers":[{"uri":"scene.bin","byteLength":${huge.size + 4}}]}""",
        )
        val bin = ByteBuffer.wrap(ByteArray(4) + huge)

        assertEquals(1, GltfLimits.violations(gltf, MAX) { if (it == "scene.bin") bin else null }.size)
    }

    @Test
    fun `textures within the limit load`() {
        val image = png(4096, 4096)
        val json = """{"images":[{"bufferView":0}],"bufferViews":[{"buffer":0,"byteLength":${image.size}}],"buffers":[{}]}"""

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json, image), MAX))
    }

    @Test
    fun `an unknown GPU limit measures nothing`() {
        val gltf = text("""{"images":[{"uri":"a.png"}]}""")

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, 0) { ByteBuffer.wrap(png(65536, 1)) })
    }

    @Test
    fun `a payload that is not glTF passes, for gltfio to report`() {
        assertEquals(emptyList<String>(), GltfLimits.violations(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"skins": [ broken"""), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"meshes":[]}"""), MAX))
    }

    @Test
    fun `a broken buffer view is skipped, not read out of bounds`() {
        val json = """{"images":[{"bufferView":0}],"bufferViews":[{"buffer":0,"byteOffset":100,"byteLength":1000}],"buffers":[{}]}"""

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json, png(8192, 8192)), MAX))
    }

    @Test
    fun `check throws an IllegalArgumentException naming every violation`() {
        val gltf = text("""{"skins":[{"joints":${joints(257)}}]}""")

        try {
            GltfLimits.check(gltf, MAX)
            fail("expected a GltfLimitException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error is GltfLimitException)
            assertTrue(error.message!!.contains("skin 0 has 257 joints"))
        }
    }

    @Test
    fun `the headers of PNG, JPEG and KTX2 are read`() {
        assertEquals(640 to 480, GltfLimits.imageSize(ByteBuffer.wrap(png(640, 480))))
        assertEquals(640 to 480, GltfLimits.imageSize(ByteBuffer.wrap(jpeg(640, 480))))
        assertEquals(640 to 480, GltfLimits.imageSize(ByteBuffer.wrap(ktx2(640, 480))))
        assertEquals(null, GltfLimits.imageSize(ByteBuffer.wrap("RIFF....WEBP".toByteArray())))
    }

    private companion object {
        const val MAX = 4096

        fun joints(count: Int) = (0 until count).joinToString(",", "[", "]")

        fun text(json: String): ByteBuffer = ByteBuffer.wrap(json.toByteArray())

        fun png(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN).apply {
            put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            putInt(13)
            put("IHDR".toByteArray())
            putInt(width)
            putInt(height)
            put(byteArrayOf(8, 6, 0, 0, 0))
            putInt(0) // CRC, not checked
        }.array()

        fun jpeg(width: Int, height: Int): ByteArray = ByteArrayOutputStream().apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            // An APP0 segment first, as JFIF files have, so the walk has a marker to skip.
            write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16))
            write(ByteArray(14))
            write(byteArrayOf(0xFF.toByte(), 0xC2.toByte(), 0, 17, 8))
            write(byteArrayOf((height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte()))
            write(ByteArray(10))
            write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }.toByteArray()

        fun ktx2(width: Int, height: Int): ByteArray = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(byteArrayOf(0xAB.toByte(), 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
            putInt(0) // vkFormat
            putInt(1) // typeSize
            putInt(width)
            putInt(height)
        }.array()

        fun glb(json: String, bin: ByteArray): ByteBuffer {
            val jsonBytes = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
            val binBytes = bin + ByteArray((4 - bin.size % 4) % 4)
            val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
            return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x46546C67)
                putInt(2)
                putInt(total)
                putInt(jsonBytes.size)
                putInt(0x4E4F534A)
                put(jsonBytes)
                putInt(binBytes.size)
                putInt(0x004E4942)
                put(binBytes)
                flip()
            }
        }
    }
}
