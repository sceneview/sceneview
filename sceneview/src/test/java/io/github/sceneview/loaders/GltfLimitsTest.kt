package io.github.sceneview.loaders

import android.util.Base64
import org.json.JSONArray
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
 * The glTF files Filament's release build crashes on instead of refusing them, which [ModelLoader]
 * has to find in the file first: a skinned mesh of more than 256 joints, a decoded texture larger
 * than the GPU's largest, accessors and buffer views that reach past their data, primitives whose
 * attributes disagree on their vertex count, and morph weights animated past 256 targets.
 */
@RunWith(RobolectricTestRunner::class) // org.json is an Android API
class GltfLimitsTest {

    @Test
    fun `a skinned mesh of more than 256 joints is refused`() {
        val gltf = text(
            """{"asset":{"version":"2.0"},"meshes":[{"primitives":[{"attributes":{}}]}],""" +
                """"nodes":[{"mesh":0,"skin":0},{"mesh":0,"skin":1}],""" +
                """"skins":[{"joints":${joints(16)}},{"joints":${joints(300)}}]}""",
        )

        assertEquals(
            listOf("skin 1 has 300 joints, more than the 256 Filament can skin"),
            GltfLimits.violations(gltf, MAX),
        )
    }

    @Test
    fun `a skin no mesh node uses is never built, whatever its joints`() {
        val gltf = text(
            """{"meshes":[{"primitives":[{"attributes":{}}]}],"nodes":[{"mesh":0},{"skin":0}],""" +
                """"skins":[{"joints":${joints(300)}}]}""",
        )

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a skin of exactly 256 joints loads`() {
        val gltf = text("""{"meshes":[{"primitives":[]}],"nodes":[{"mesh":0,"skin":0}],"skins":[{"joints":${joints(256)}}]}""")

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a texture embedded in a GLB larger than the GPU's is refused`() {
        val small = png(1024, 1024)
        val huge = png(8192, 4096)
        val json = textured(
            images = """[{"bufferView":0,"mimeType":"image/png"},{"bufferView":1,"name":"albedo"}]""",
            textures = """[{"source":0},{"source":1}]""",
            rest = """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":${small.size}},""" +
                """{"buffer":0,"byteOffset":${small.size},"byteLength":${huge.size}}],""" +
                """"buffers":[{"byteLength":${small.size + huge.size}}]""",
        )

        assertEquals(
            listOf("image 1 (albedo) is 8192x4096, larger than the 4096x4096 textures this GPU supports"),
            GltfLimits.violations(glb(json, small + huge), MAX),
        )
    }

    @Test
    fun `an image no material uses is never decoded, whatever its size`() {
        val gltf = text(textured(images = """[{"uri":"a.png"},{"uri":"unused.png"}]""", textures = """[{"source":0}]"""))
        val resources = mapOf("a.png" to ByteBuffer.wrap(png(512, 512)), "unused.png" to ByteBuffer.wrap(png(16384, 16384)))

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX) { resources[it] })
    }

    @Test
    fun `a material no mesh node draws is never built, whatever its textures`() {
        val gltf = text(
            """{"meshes":[{"primitives":[{"attributes":{},"material":0}]}],"nodes":[],""" +
                """"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],""" +
                """"textures":[{"source":0}],"images":[{"uri":"a.png"}]}""",
        )

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX) { ByteBuffer.wrap(png(16384, 1)) })
    }

    @Test
    fun `a PNG fallback next to a KTX2 gltfio decodes is not measured`() {
        val gltf = text(
            textured(
                images = """[{"uri":"fallback.png"},{"uri":"albedo.ktx2"}]""",
                textures = """[{"source":0,"extensions":{"KHR_texture_basisu":{"source":1}}}]""",
            ),
        )
        val fits = mapOf("fallback.png" to ByteBuffer.wrap(png(8192, 8192)), "albedo.ktx2" to ByteBuffer.wrap(ktx2(2048, 2048)))
        val tooLarge = mapOf("fallback.png" to ByteBuffer.wrap(png(512, 512)), "albedo.ktx2" to ByteBuffer.wrap(ktx2(8192, 8192)))

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX) { fits[it] })
        assertEquals(1, GltfLimits.violations(gltf, MAX) { tooLarge[it] }.size)
    }

    @Test
    fun `the textures of every material extension gltfio binds are measured`() {
        val gltf = text(
            """{"meshes":[{"primitives":[{"attributes":{},"material":0,"extensions":{"KHR_materials_variants":""" +
                """{"mappings":[{"material":1,"variants":[0]}]}}}]}],"nodes":[{"mesh":0}],""" +
                """"materials":[{"extensions":{"KHR_materials_clearcoat":{"clearcoatNormalTexture":{"index":0}}}},""" +
                """{"occlusionTexture":{"index":1}}],""" +
                """"textures":[{"source":0},{"source":1}],"images":[{"uri":"coat.png"},{"uri":"variant.png"}]}""",
        )

        assertEquals(2, GltfLimits.violations(gltf, MAX) { ByteBuffer.wrap(png(8192, 8)) }.size)
    }

    @Test
    fun `an external texture is measured once it is loaded`() {
        val gltf = text(textured(images = """[{"uri":"albedo.jpg"},{"uri":"normal.png"}]""", textures = """[{"source":0},{"source":1}]"""))
        val resources = mapOf("albedo.jpg" to ByteBuffer.wrap(jpeg(4096, 8192)), "normal.png" to ByteBuffer.wrap(png(512, 512)))

        assertEquals(
            listOf("image 0 (albedo.jpg) is 4096x8192, larger than the 4096x4096 textures this GPU supports"),
            GltfLimits.violations(gltf, MAX) { resources[it] },
        )
        assertEquals("not loaded yet: nothing to measure", emptyList<String>(), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a texture in a data URI is measured from its head`() {
        val huge = Base64.encodeToString(png(8192, 16) + ByteArray(200_000), Base64.NO_WRAP)
        val gltf = text(textured(images = """[{"uri":"data:image/png;base64,$huge"}]""", textures = """[{"source":0}]"""))

        assertEquals(
            listOf("image 0 is 8192x16, larger than the 4096x4096 textures this GPU supports"),
            GltfLimits.violations(gltf, MAX),
        )
    }

    @Test
    fun `a texture in an external buffer is measured`() {
        val huge = ktx2(8192, 8192)
        val gltf = text(
            textured(
                images = """[{"bufferView":0}]""",
                textures = """[{"source":0}]""",
                rest = """"bufferViews":[{"buffer":0,"byteOffset":4,"byteLength":${huge.size}}],""" +
                    """"buffers":[{"uri":"scene.bin","byteLength":${huge.size + 4}}]""",
            ),
        )
        val bin = ByteBuffer.wrap(ByteArray(4) + huge)

        assertEquals(1, GltfLimits.violations(gltf, MAX) { if (it == "scene.bin") bin else null }.size)
    }

    @Test
    fun `textures within the limit load`() {
        val image = png(4096, 4096)
        val json = textured(
            images = """[{"bufferView":0}]""",
            textures = """[{"source":0}]""",
            rest = """"bufferViews":[{"buffer":0,"byteLength":${image.size}}],"buffers":[{"byteLength":${image.size}}]""",
        )

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json, image), MAX))
    }

    @Test
    fun `an unknown GPU limit measures nothing`() {
        val gltf = text(textured(images = """[{"uri":"a.png"}]""", textures = """[{"source":0}]"""))

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, 0) { ByteBuffer.wrap(png(65536, 1)) })
    }

    @Test
    fun `an accessor past the end of its buffer view is refused`() {
        val gltf = text(
            """{"accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":4},""" +
                """{"bufferView":0,"byteOffset":12,"componentType":5126,"type":"VEC3","count":2}],""" +
                """"bufferViews":[{"buffer":0,"byteLength":36}],"buffers":[{"byteLength":36}]}""",
        )

        assertEquals(listOf("accessor 0 reaches byte 48 of a 36-byte buffer view"), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a strided accessor is measured with its stride`() {
        val interleaved = """"bufferViews":[{"buffer":0,"byteLength":%d,"byteStride":24}],"buffers":[{"byteLength":100}]"""
        fun gltf(viewLength: Int) = text(
            """{"accessors":[{"bufferView":0,"byteOffset":12,"componentType":5126,"type":"VEC3","count":3}],""" +
                interleaved.format(viewLength) + "}",
        )

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf(72), MAX))
        assertEquals(listOf("accessor 0 reaches byte 72 of a 71-byte buffer view"), GltfLimits.violations(gltf(71), MAX))
    }

    @Test
    fun `a count that overflows is refused rather than wrapped`() {
        val gltf = text(
            """{"accessors":[{"bufferView":0,"componentType":5126,"type":"MAT4","count":2147483647,""" +
                """"byteOffset":9223372036854775807}],"bufferViews":[{"buffer":0,"byteLength":64}],"buffers":[{"byteLength":64}]}""",
        )

        assertEquals(listOf("accessor 0 reaches byte ∞ of a 64-byte buffer view"), GltfLimits.violations(gltf, MAX))
    }

    @Test
    fun `a buffer view past the end of its buffer is refused`() {
        val json = """{"bufferViews":[{"buffer":0,"byteOffset":100,"byteLength":1000}],"buffers":[{"byteLength":1024}]}"""

        assertEquals(
            listOf("buffer view 0 reaches byte 1100 of a 1024-byte buffer"),
            GltfLimits.violations(glb(json, ByteArray(1024)), MAX),
        )
    }

    @Test
    fun `an external buffer shorter than it declares is refused once loaded`() {
        val gltf = text("""{"buffers":[{"uri":"scene.bin","byteLength":1024}]}""")

        assertEquals(
            listOf("buffer 0 (scene.bin) is 16 bytes, shorter than the 1024 it declares"),
            GltfLimits.violations(gltf, MAX) { ByteBuffer.wrap(ByteArray(16)) },
        )
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf, MAX) { ByteBuffer.wrap(ByteArray(1024)) })
    }

    @Test
    fun `a sparse accessor past its views or its own count is refused`() {
        fun gltf(indexView: Int, index: Int): Pair<ByteBuffer, ByteArray> {
            val bin = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putShort(index.toShort()).array()
            return glb(
                """{"accessors":[{"componentType":5126,"type":"SCALAR","count":4,"sparse":{"count":1,""" +
                    """"indices":{"bufferView":0,"componentType":5123},"values":{"bufferView":1}}}],""" +
                    """"bufferViews":[{"buffer":0,"byteLength":$indexView},{"buffer":0,"byteOffset":4,"byteLength":4}],""" +
                    """"buffers":[{"byteLength":16}]}""",
                bin,
            ) to bin
        }

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf(indexView = 2, index = 3).first, MAX))
        assertEquals(
            listOf("the sparse part of accessor 0 reaches past its buffer views"),
            GltfLimits.violations(gltf(indexView = 1, index = 3).first, MAX),
        )
        assertEquals(
            listOf("a sparse index of accessor 0 is 4, past its 4 elements"),
            GltfLimits.violations(gltf(indexView = 2, index = 4).first, MAX),
        )
    }

    @Test
    fun `attributes and morph targets that disagree on the vertex count are refused`() {
        val gltf = text(
            """{"accessors":[{"componentType":5126,"type":"VEC3","count":8},{"componentType":5126,"type":"VEC3","count":8},""" +
                """{"componentType":5126,"type":"VEC3","count":3},{"componentType":5126,"type":"VEC2","count":5}],""" +
                """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"targets":[{"POSITION":1},{"POSITION":2}]},""" +
                """{"attributes":{"POSITION":0,"TEXCOORD_0":3}}]}]}""",
        )

        assertEquals(
            listOf(
                "morph target 1 of primitive 0 of mesh 0 does not have its 8 vertices",
                "the attributes of primitive 1 of mesh 0 disagree on their vertex count",
            ),
            GltfLimits.violations(gltf, MAX),
        )
    }

    @Test
    fun `indices that are not unsigned integers, tightly packed, are refused`() {
        fun gltf(indices: String, stride: Int = 0) = text(
            """{"accessors":[{"componentType":5126,"type":"VEC3","count":3},$indices],""" +
                """"bufferViews":[{"buffer":0,"byteLength":12${if (stride > 0) ""","byteStride":$stride""" else ""}}],""" +
                """"buffers":[{"byteLength":12}],"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}""",
        )
        val refused = listOf("the indices of primitive 0 of mesh 0 are not tightly packed unsigned integers")

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("""{"bufferView":0,"componentType":5123,"type":"SCALAR","count":3}"""), MAX))
        assertEquals(refused, GltfLimits.violations(gltf("""{"bufferView":0,"componentType":5126,"type":"SCALAR","count":3}"""), MAX))
        assertEquals(refused, GltfLimits.violations(gltf("""{"bufferView":0,"componentType":5121,"type":"VEC3","count":1}"""), MAX))
        assertEquals(refused, GltfLimits.violations(gltf("""{"bufferView":0,"componentType":5121,"type":"SCALAR","count":3}""", stride = 4), MAX))
    }

    @Test
    fun `an accessor of an unknown type is refused`() {
        val gltf = text("""{"accessors":[{"componentType":5124,"type":"SCALAR","count":1},{"componentType":5126,"type":"VEC5","count":1}]}""")

        assertEquals(
            listOf("accessor 0 has an unknown componentType or type", "accessor 1 has an unknown componentType or type"),
            GltfLimits.violations(gltf, MAX),
        )
    }

    @Test
    fun `morph weights animated past 256 targets are refused`() {
        fun gltf(targets: Int, path: String = "weights") = text(
            """{"accessors":[{"componentType":5126,"type":"VEC3","count":1}],""" +
                """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"targets":""" +
                List(targets) { """{"POSITION":0}""" }.joinToString(",", "[", "]") + "}]}]," +
                """"nodes":[{"mesh":0}],"animations":[{"channels":[{"sampler":0,"target":{"node":0,"path":"$path"}}]}]}""",
        )

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf(256), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf(300, path = "rotation"), MAX))
        assertEquals(
            listOf("mesh 0 animates 300 morph targets, more than the 256 Filament can weigh"),
            GltfLimits.violations(gltf(300), MAX),
        )
    }

    @Test
    fun `a payload that is not glTF passes, for gltfio to report`() {
        assertEquals(emptyList<String>(), GltfLimits.violations(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"skins": [ broken"""), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"meshes":[]}"""), MAX))
    }

    @Test
    fun `a JSON nested too deeply to check is refused rather than passed unchecked`() {
        val deep = text("""{"extras":""" + "[".repeat(200_000) + "]".repeat(200_000) + "}")

        assertEquals(
            listOf("its glTF JSON nests too deeply to be checked"),
            GltfLimits.violations(deep, MAX),
        )
    }

    @Test
    fun `an image whose view reaches past the BIN is refused, not read out of bounds`() {
        val json = textured(
            images = """[{"bufferView":0}]""",
            textures = """[{"source":0}]""",
            rest = """"bufferViews":[{"buffer":0,"byteOffset":100,"byteLength":1000}],"buffers":[{"byteLength":40}]""",
        )

        assertEquals(
            listOf("buffer view 0 reaches byte 1100 of a 40-byte buffer"),
            GltfLimits.violations(glb(json, png(8192, 8192)), MAX),
        )
    }

    @Test
    fun `check throws an IllegalArgumentException naming every violation`() {
        val gltf = text("""{"meshes":[{"primitives":[]}],"nodes":[{"mesh":0,"skin":0}],"skins":[{"joints":${joints(257)}}]}""")

        try {
            GltfLimits.check(gltf, MAX)
            fail("expected a GltfLimitException")
        } catch (error: IllegalArgumentException) {
            assertTrue(error is GltfLimitException)
            assertTrue(error.message!!.contains("skin 0 has 257 joints"))
        }
    }

    @Test
    fun `one parse checks again as the resources arrive`() {
        val parsed = GltfLimits.parse(text(textured(images = """[{"uri":"a.png"}]""", textures = """[{"source":0}]""")))

        assertEquals(emptyList<String>(), parsed.violations(MAX))
        assertEquals(1, parsed.violations(MAX) { ByteBuffer.wrap(png(8192, 1)) }.size)
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

        /** One node, one mesh, one primitive, one material whose base colour uses each texture. */
        fun textured(images: String, textures: String, rest: String = ""): String {
            val count = JSONArray(textures).length()
            val material = (0 until count).joinToString(",", "[", "]") { """{"pbrMetallicRoughness":{"baseColorTexture":{"index":$it}}}""" }
            val primitives = (0 until count).joinToString(",", "[", "]") { """{"attributes":{},"material":$it}""" }
            return """{"nodes":[{"mesh":0}],"meshes":[{"primitives":$primitives}],"materials":$material,""" +
                """"textures":$textures,"images":$images${if (rest.isEmpty()) "" else ",$rest"}}"""
        }

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
