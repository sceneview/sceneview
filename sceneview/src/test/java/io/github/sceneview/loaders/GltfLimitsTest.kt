package io.github.sceneview.loaders

import android.util.Base64
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The glTF files Filament's release build crashes on instead of refusing them, which [ModelLoader]
 * has to find in the file first: a skinned mesh of more than 256 joints, a decoded texture larger
 * than the GPU's largest, accessors and buffer views that reach past their data, buffers cgltf
 * cannot load, primitives whose attributes disagree on their vertex count, keyframe times in no
 * buffer view, and morph weights animated past 256 targets — and the valid files that must pass.
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

    // The collection before the parse makes the heap peak a measure of the parse alone.
    @Suppress("ExplicitGarbageCollectionCall")
    @Test
    fun `a 50 MB embedded texture is checked without copying its payload`() {
        // The head carries the PNG header; the rest is base64 zeros, as a 50 MB embedded texture.
        val head = Base64.encodeToString(png(8192, 16), Base64.NO_WRAP)
        val (before, after) = textured(
            images = """[{"uri":"data:image/png;base64,$head@"}]""",
            textures = """[{"source":0}]""",
        ).split("@")
        val payload = 50 * 1024 * 1024
        val gltf = ByteBuffer.allocateDirect(before.length + payload + after.length).apply {
            put(before.toByteArray())
            repeat(payload) { put('A'.code.toByte()) }
            put(after.toByteArray())
            flip()
        }
        System.gc()
        val heap = HeapPeak()
        val allocatedBefore = allocatedBytes()
        val started = System.nanoTime()

        val parsed = GltfLimits.parse(gltf)

        val millis = (System.nanoTime() - started) / 1_000_000
        val allocated = allocatedBytes() - allocatedBefore
        val peak = heap.stop()
        println("parse of a ${gltf.limit() shr 20} MiB glTF: $millis ms, ${allocated shr 10} KiB allocated, heap peak +${peak shr 10} KiB")
        assertEquals(
            listOf("image 0 is 8192x16, larger than the 4096x4096 textures this GPU supports"),
            parsed.violations(MAX),
        )
        assertTrue("the parse allocated ${allocated shr 20} MiB", allocated < 4L * 1024 * 1024)
    }

    @Test
    fun `a data URI is read through escaped slashes, and quotes before it`() {
        val head = Base64.encodeToString(png(8192, 16) + byteArrayOf(-1, -1, -1), Base64.NO_WRAP).replace("/", "\\/")
        assertTrue(head.contains("\\/"))
        val json = textured(
            images = """[{"name":"a \"data:\" \\ name","uri":"data:image\/png;base64,$head"}]""",
            textures = """[{"source":0}]""",
        )
        val gltf = text(json)

        val refused = listOf("image 0 (a \"data:\" \\ name) is 8192x16, larger than the 4096x4096 textures this GPU supports")

        assertEquals(refused, GltfLimits.violations(gltf, MAX))
        assertEquals("the JSON chunk of a GLB too", refused, GltfLimits.violations(glb(json, ByteArray(0)), MAX))
    }

    @Test
    fun `a data URI buffer shorter than it declares is refused`() {
        fun gltf(base64: String, length: Int = 4) =
            text("""{"buffers":[{"uri":"data:application/octet-stream;base64,$base64","byteLength":$length}]}""")

        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("AAAAAA=="), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("AAAAAAAA"), MAX))
        assertEquals(
            listOf("buffer 0 (data: URI) is 3 bytes, shorter than the 4 it declares"),
            GltfLimits.violations(gltf("AAAA"), MAX),
        )
        // Unpadded, six characters are 36 bits: four bytes, not five.
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("AAAAAA"), MAX))
        assertEquals(
            listOf("buffer 0 (data: URI) is 4 bytes, shorter than the 5 it declares"),
            GltfLimits.violations(gltf("AAAAAA", length = 5), MAX),
        )
    }

    @Test
    fun `a data URI buffer cgltf cannot decode is refused`() {
        fun gltf(uri: String) = text("""{"buffers":[{"uri":"$uri","byteLength":4}]}""")
        val notBase64 = listOf("buffer 0 is a data: URI that is not base64")

        assertEquals(notBase64, GltfLimits.violations(gltf("data:application/octet-stream,AAAAAA=="), MAX))
        assertEquals("no comma", notBase64, GltfLimits.violations(gltf("data:application/octet-stream;base64"), MAX))
        assertEquals(
            listOf("buffer 0 is a data: URI that is not valid base64 at character 2"),
            GltfLimits.violations(gltf("data:application/octet-stream;base64,AA AAA=="), MAX),
        )
        assertEquals(
            listOf("buffer 0 is a data: URI that is not valid base64 at character 0"),
            GltfLimits.violations(gltf("data:application/octet-stream;base64,\\nAAAAAA="), MAX),
        )
        // cgltf decodes the declared bytes only: what comes after them is never read.
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("data:application/octet-stream;base64,AAAAAA!!"), MAX))
        assertEquals(
            "an escape that stands for a base64 character is one",
            emptyList<String>(),
            GltfLimits.violations(gltf("data:application\\/octet-stream;base64,\\u0041AAAAA\\/=="), MAX),
        )
    }

    @Test
    fun `a GLB whose BIN chunk is missing or shorter than buffer 0 is refused`() {
        val json = """{"buffers":[{"byteLength":16}]}"""
        val fallback = """{"buffers":[{"byteLength":16,"extensions":{"EXT_meshopt_compression":{"fallback":true}}}]}"""

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json, ByteArray(16)), MAX))
        assertEquals(
            listOf("the BIN chunk of this GLB is 8 bytes, shorter than the 16 buffer 0 declares"),
            GltfLimits.violations(glb(json, ByteArray(8)), MAX),
        )
        assertEquals(
            listOf("this GLB has no BIN chunk for the 16 bytes of buffer 0"),
            GltfLimits.violations(glb(json, null), MAX),
        )
        assertEquals("a meshopt fallback is never read", emptyList<String>(), GltfLimits.violations(glb(fallback, null), MAX))
    }

    @Test
    fun `a negative offset is refused, never thrown`() {
        val indices = """{"accessors":[{"componentType":5126,"type":"VEC3","count":3},""" +
            """{"bufferView":0,"byteOffset":-4,"componentType":5123,"type":"SCALAR","count":3}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":8}],"buffers":[{"byteLength":8}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}"""
        val view = """{"bufferViews":[{"buffer":0,"byteOffset":-4,"byteLength":8}],"buffers":[{"byteLength":8}]}"""

        assertEquals(listOf("accessor 1 has a negative byteOffset"), GltfLimits.violations(glb(indices, ByteArray(8)), MAX))
        assertEquals(
            listOf("buffer view 0 has a negative byteOffset or byteLength"),
            GltfLimits.violations(glb(view, ByteArray(8)), MAX),
        )
    }

    @Test
    fun `a check that throws is reported as a malformed glTF, never thrown`() {
        val gltf = text("""{"buffers":[{"uri":"scene.bin","byteLength":4}]}""")

        assertEquals(
            listOf("malformed glTF: unreadable"),
            GltfLimits.violations(gltf, MAX) { throw IllegalStateException("unreadable") },
        )
    }

    @Test
    fun `index values past the vertex count are refused when their bytes are at hand`() {
        fun json(uri: String) =
            """{"accessors":[{"componentType":5126,"type":"VEC3","count":3},""" +
                """{"bufferView":0,"byteOffset":2,"componentType":5123,"type":"SCALAR","count":3}],""" +
                """"bufferViews":[{"buffer":0,"byteLength":8}],"buffers":[{$uri"byteLength":8}],""" +
                """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}"""
        fun indices(vararg values: Int) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(99) // before the accessor's byteOffset: not an index
            values.forEach { putShort(it.toShort()) }
        }.array()
        val refused = listOf("primitive 0 of mesh 0 indexes vertex 3 of its 3")

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json(""), indices(0, 1, 2)), MAX))
        assertEquals(refused, GltfLimits.violations(glb(json(""), indices(0, 3, 2)), MAX))
        val external = text(json(""""uri":"mesh.bin","""))
        assertEquals(refused, GltfLimits.violations(external, MAX) { ByteBuffer.wrap(indices(2, 3, 1)) })
        assertEquals("not loaded yet: nothing to read", emptyList<String>(), GltfLimits.violations(external, MAX))
    }

    @Test
    fun `indices in a meshopt-compressed view are not read`() {
        val json = """{"accessors":[{"componentType":5126,"type":"VEC3","count":3},""" +
            """{"bufferView":0,"componentType":5123,"type":"SCALAR","count":3}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":6,"extensions":{"EXT_meshopt_compression":{"buffer":0,""" +
            """"byteLength":6,"byteStride":2,"count":3,"mode":"TRIANGLES"}}}],"buffers":[{"byteLength":8}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}"""
        val compressed = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(500).array()

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(json, compressed), MAX))
    }

    @Test
    fun `an animation sampler whose output does not match its keyframes is left to gltfio's Animator`() {
        // The Animator checks it in release builds too and only leaves the animations off.
        fun gltf(path: String, interpolation: String, inputs: Int, outputs: Int, targets: Int = 2) = glb(
            """{"accessors":[{"componentType":5126,"type":"VEC3","count":3},""" +
                """{"bufferView":0,"componentType":5126,"type":"SCALAR","count":$inputs},""" +
                """{"componentType":5126,"type":"SCALAR","count":$outputs}],""" +
                """"bufferViews":[{"buffer":0,"byteLength":${4 * inputs}}],"buffers":[{"byteLength":${4 * inputs}}],""" +
                """"meshes":[{"primitives":[{"attributes":{"POSITION":0},"targets":""" +
                List(targets) { """{"POSITION":0}""" }.joinToString(",", "[", "]") + "}]}]," +
                """"nodes":[{"mesh":0}],"animations":[{"samplers":[{"input":1,"output":2,"interpolation":"$interpolation"}],""" +
                """"channels":[{"sampler":0,"target":{"node":0,"path":"$path"}}]}]}""",
            ByteArray(4 * inputs),
        )

        assertEquals("five outputs for four keyframes", emptyList<String>(), GltfLimits.violations(gltf("translation", "LINEAR", 4, 5), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("translation", "LINEAR", 4, 3), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("scale", "CUBICSPLINE", 4, 4), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("weights", "LINEAR", 4, 4), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("rotation", "CUBICSPLINE", 4, 12), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(gltf("weights", "CUBICSPLINE", 4, 24), MAX))
    }

    @Test
    fun `an animation sampler whose keyframe times are in no buffer view is refused`() {
        fun gltf(input: Int, output: Int) = glb(
            """{"accessors":[{"componentType":5126,"type":"SCALAR","count":2},""" +
                """{"bufferView":0,"componentType":5126,"type":"SCALAR","count":2}],""" +
                """"bufferViews":[{"buffer":0,"byteLength":8}],"buffers":[{"byteLength":8}],""" +
                """"nodes":[{}],"animations":[{"samplers":[{"input":$input,"output":$output}],""" +
                """"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}]}""",
            ByteArray(8),
        )

        assertEquals(
            listOf("the keyframe times of sampler 0 of animation 0 are in no buffer view"),
            GltfLimits.violations(gltf(input = 0, output = 1), MAX),
        )
        assertEquals("an output in no buffer view reads as zeros", emptyList<String>(), GltfLimits.violations(gltf(input = 1, output = 0), MAX))
    }

    @Test
    fun `the textures of a node outside every scene are measured, as gltfio instantiates it`() {
        val gltf = text(
            textured(images = """[{"uri":"a.png"}]""", textures = """[{"source":0}]""").replace(
                """"nodes":[{"mesh":0}]""",
                """"scenes":[{"nodes":[0]}],"nodes":[{},{"mesh":0}]""",
            ),
        )

        assertEquals(1, GltfLimits.violations(gltf, MAX) { ByteBuffer.wrap(png(8192, 8)) }.size)
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
    fun `valid quantized, sparse and interleaved files pass`() {
        val quantized = """{"extensionsUsed":["KHR_mesh_quantization"],"extensionsRequired":["KHR_mesh_quantization"],""" +
            """"accessors":[{"bufferView":0,"componentType":5122,"normalized":true,"type":"VEC3","count":3},""" +
            """{"bufferView":0,"byteOffset":6,"componentType":5120,"normalized":true,"type":"VEC3","count":3}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":36,"byteStride":12}],"buffers":[{"byteLength":36}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1}}]}],"nodes":[{"mesh":0}]}"""
        val sparse = """{"accessors":[{"componentType":5126,"type":"VEC3","count":4,"sparse":{"count":2,""" +
            """"indices":{"bufferView":0,"componentType":5121},"values":{"bufferView":1}}}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":2},{"buffer":0,"byteOffset":4,"byteLength":24}],""" +
            """"buffers":[{"byteLength":28}],"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],"nodes":[{"mesh":0}]}"""
        val sparseBin = ByteArray(28).also {
            it[0] = 1
            it[1] = 3
        }
        fun interleaved(buffer: String) = """{"accessors":[{"bufferView":0,"componentType":5126,"type":"VEC3","count":3},""" +
            """{"bufferView":0,"byteOffset":12,"componentType":5126,"type":"VEC3","count":3},""" +
            """{"bufferView":1,"componentType":5123,"type":"SCALAR","count":3},""" +
            """{"bufferView":2,"componentType":5126,"type":"SCALAR","count":2},{"componentType":5126,"type":"VEC3","count":2}],""" +
            """"bufferViews":[{"buffer":0,"byteLength":72,"byteStride":24},{"buffer":0,"byteOffset":72,"byteLength":6},""" +
            """{"buffer":0,"byteOffset":80,"byteLength":8}],"buffers":[{$buffer"byteLength":88}],""" +
            """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2}]}],"nodes":[{"mesh":0}],""" +
            """"animations":[{"samplers":[{"input":3,"output":4}],"channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}]}"""
        val interleavedBin = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(72)
            putShort(0).putShort(1).putShort(2)
            position(80)
            putFloat(0f).putFloat(1f)
        }.array()
        val embedded = "data:application/octet-stream;base64," + Base64.encodeToString(interleavedBin, Base64.NO_WRAP)

        assertEquals(emptyList<String>(), GltfLimits.violations(glb(quantized, ByteArray(36)), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(glb(sparse, sparseBin), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(glb(interleaved(""), interleavedBin), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text(interleaved(""""uri":"$embedded",""")), MAX))
        assertEquals(
            emptyList<String>(),
            GltfLimits.violations(text(interleaved(""""uri":"scene.bin",""")), MAX) { ByteBuffer.wrap(interleavedBin) },
        )
    }

    @Test
    fun `every glTF model of the repository passes, but those Filament cannot load`() {
        // The demo models tools/fetch-assets.sh brings are checked too when they are there.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "settings.gradle").isFile }
        val models = root?.walkTopDown()
            ?.onEnter { it.name !in setOf("build", ".git", ".gradle", "node_modules") }
            ?.filter { it.isFile && it.extension.lowercase() in setOf("glb", "gltf") }
            ?.toList()
            .orEmpty()
        assumeTrue("no glTF model in this checkout", models.isNotEmpty())

        val refused = models.associate { model ->
            val resources = { uri: String -> File(model.parentFile, uri).takeIf { it.isFile }?.let { ByteBuffer.wrap(it.readBytes()) } }
            // The structure only: the largest texture side a GPU may have, so none is too large.
            model.relativeTo(root!!).invariantSeparatorsPath to
                GltfLimits.violations(ByteBuffer.wrap(model.readBytes()), 16384, resources)
        }.filterValues { it.isNotEmpty() }
        assertEquals(KNOWN_REFUSED.filterKeys { it in refused }, refused)
    }

    @Test
    fun `a payload that is not glTF passes, for gltfio to report`() {
        assertEquals(emptyList<String>(), GltfLimits.violations(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"skins": [ broken"""), MAX))
        assertEquals(emptyList<String>(), GltfLimits.violations(text("""{"meshes":[]}"""), MAX))
    }

    @Test
    fun `a JSON after a byte order mark is checked`() {
        val gltf = text("\uFEFF" + """{"meshes":[{"primitives":[]}],"nodes":[{"mesh":0,"skin":0}],"skins":[{"joints":${joints(257)}}]}""")

        assertEquals(listOf("skin 0 has 257 joints, more than the 256 Filament can skin"), GltfLimits.violations(gltf, MAX))
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
            rest = """"bufferViews":[{"buffer":0,"byteOffset":100,"byteLength":1000}],"buffers":[{"byteLength":36}]""",
        )

        assertEquals(
            listOf("buffer view 0 reaches byte 1100 of a 36-byte buffer"),
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

    /** The bytes this thread allocated so far, by reflection: `java.lang.management` is not in android.jar. */
    private fun allocatedBytes(): Long {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        return Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes").invoke(threads) as Long
    }

    /** The most heap in use above its level at construction, sampled every millisecond until [stop]. */
    private class HeapPeak {
        private val runtime = Runtime.getRuntime()
        private val base = used()
        @Volatile private var peak = 0L
        @Volatile private var running = true
        private val sampler = Thread {
            while (running) {
                peak = maxOf(peak, used() - base)
                Thread.sleep(1)
            }
        }.apply { isDaemon = true; start() }

        fun stop(): Long {
            peak = maxOf(peak, used() - base)
            running = false
            sampler.join()
            return peak
        }

        private fun used() = runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val MAX = 4096

        /**
         * The repository models Filament cannot load, each for a reason Filament itself asserts:
         * this website model skins more bones than `CONFIG_MAX_BONE_COUNT`.
         */
        val KNOWN_REFUSED = mapOf(
            "website-static/models/platforms/KindredLol.glb" to listOf("skin 0 has 1523 joints, more than the 256 Filament can skin"),
        )

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

        /** A GLB of [json] and [bin], with no BIN chunk at all when [bin] is `null`. */
        fun glb(json: String, bin: ByteArray?): ByteBuffer {
            val jsonBytes = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
            val binBytes = bin?.let { it + ByteArray((4 - it.size % 4) % 4) }
            val total = 12 + 8 + jsonBytes.size + (binBytes?.let { 8 + it.size } ?: 0)
            return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x46546C67)
                putInt(2)
                putInt(total)
                putInt(jsonBytes.size)
                putInt(0x4E4F534A)
                put(jsonBytes)
                if (binBytes != null) {
                    putInt(binBytes.size)
                    putInt(0x004E4942)
                    put(binBytes)
                }
                flip()
            }
        }
    }
}
