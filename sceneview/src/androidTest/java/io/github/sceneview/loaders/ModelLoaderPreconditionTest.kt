package io.github.sceneview.loaders

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Texture
import io.github.sceneview.render.RenderTestHarness
import io.github.sceneview.safeDestroy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device reproductions of the Filament `PreconditionPanic`s seen in the field: each one aborted
 * the whole app from inside the JNI, where no Java `try` reaches. `ModelLoader` now refuses them
 * with an exception before the call.
 *
 * - The render loop running a frame after the loader was destroyed: `asyncUpdateLoad` on a freed
 *   `ResourceLoader` (gltfio's Java `destroy()` keeps the native handle).
 * - A model created on a destroyed loader, or on a destroyed engine.
 * - A skin of more than 256 joints, which `RenderableManager::Builder::build` panics on inside
 *   `AssetLoader.createAsset`.
 * - A texture larger than the GPU's largest, which `Texture::Builder::build` panics on inside
 *   `ResourceLoader.asyncBeginLoad`. Only its PNG header is real: that is all stb reads before the
 *   texture is built.
 */
@RunWith(AndroidJUnit4::class)
class ModelLoaderPreconditionTest {

    companion object {
        private lateinit var harness: RenderTestHarness

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 64, height = 64)
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.destroy()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().context

    @Test
    fun frames_after_the_loader_is_destroyed_touch_nothing() {
        lateinit var modelLoader: ModelLoader
        harness.runOnMain {
            modelLoader = ModelLoader(harness.engine, context)
            modelLoader.createModel(direct(glb(texturedQuad(png(64, 64)))))
            modelLoader.destroy()
        }
        repeat(30) {
            harness.runOnMain {
                modelLoader.updateLoad()
                assertFalse(modelLoader.isLoading)
                assertEquals(0f, modelLoader.progress, 0f)
            }
            SystemClock.sleep(16)
        }
        // A second destroy, as a DisposableEffect and an explicit call can both do.
        harness.runOnMain { modelLoader.destroy() }
    }

    @Test
    fun a_destroyed_loader_refuses_new_models() {
        var refused: Throwable? = null
        harness.runOnMain {
            val modelLoader = ModelLoader(harness.engine, context)
            modelLoader.destroy()
            refused = runCatching { modelLoader.createModel(direct(glb(texturedQuad(png(4, 4))))) }.exceptionOrNull()
        }
        assertTrue("got $refused", refused is IllegalStateException)
    }

    @Test
    fun a_loader_whose_engine_is_gone_refuses_new_models_and_destroys_quietly() {
        var refused: Throwable? = null
        harness.runOnMain {
            val engine = Engine.create()
            val modelLoader = ModelLoader(engine, context)
            engine.safeDestroy()
            refused = runCatching { modelLoader.createModel(direct(glb(texturedQuad(png(4, 4))))) }.exceptionOrNull()
            modelLoader.updateLoad()
            modelLoader.destroy()
        }
        assertTrue("got $refused", refused is IllegalStateException)
    }

    @Test
    fun a_skin_beyond_256_joints_is_refused() {
        var refused: Throwable? = null
        harness.runOnMain {
            val modelLoader = ModelLoader(harness.engine, context)
            refused = runCatching { modelLoader.createModel(direct(glb(skinnedTriangle(joints = 300)))) }.exceptionOrNull()
            modelLoader.destroy()
        }
        assertTrue("got $refused", refused is IllegalArgumentException)
    }

    @Test
    fun a_skin_of_256_joints_still_loads() {
        harness.runOnMain {
            val modelLoader = ModelLoader(harness.engine, context)
            modelLoader.createModel(direct(glb(skinnedTriangle(joints = 256))))
            modelLoader.destroy()
        }
    }

    @Test
    fun a_texture_larger_than_the_gpu_supports_is_refused() {
        var refused: Throwable? = null
        harness.runOnMain {
            val max = Texture.getMaxTextureSize(harness.engine, Texture.Sampler.SAMPLER_2D)
            val modelLoader = ModelLoader(harness.engine, context)
            refused = runCatching { modelLoader.createModel(direct(glb(texturedQuad(png(max + 1, 4))))) }.exceptionOrNull()
            modelLoader.destroy()
        }
        assertTrue("got $refused", refused is IllegalArgumentException)
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
}

/** A glTF document and its binary chunk. */
private class Gltf(val json: JSONObject, val bin: ByteArray)

/** A PNG with a real header and no pixels: stb sizes the texture from the header alone. */
private fun png(width: Int, height: Int): ByteArray = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN).apply {
    put(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    putInt(13)
    put("IHDR".toByteArray())
    putInt(width)
    putInt(height)
    put(byteArrayOf(8, 6, 0, 0, 0))
    putInt(0)
}.array()

private class BinWriter {
    val bin = ByteArrayOutputStream()
    val bufferViews = JSONArray()
    val accessors = JSONArray()

    fun view(bytes: ByteArray, target: Int? = null): Int {
        while (bin.size() % 4 != 0) bin.write(0)
        bufferViews.put(JSONObject().apply {
            put("buffer", 0)
            put("byteOffset", bin.size())
            put("byteLength", bytes.size)
            target?.let { put("target", it) }
        })
        bin.write(bytes)
        return bufferViews.length() - 1
    }

    fun accessor(bytes: ByteArray, componentType: Int, count: Int, type: String, target: Int?, bounds: Boolean = false): Int {
        accessors.put(JSONObject().apply {
            put("bufferView", view(bytes, target))
            put("componentType", componentType)
            put("count", count)
            put("type", type)
            if (bounds) {
                put("min", JSONArray(listOf(0, 0, 0)))
                put("max", JSONArray(listOf(1, 1, 0)))
            }
        })
        return accessors.length() - 1
    }

    fun gltf(extra: JSONObject.() -> Unit): Gltf {
        while (bin.size() % 4 != 0) bin.write(0)
        val json = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0"))
            put("scene", 0)
            extra()
            put("accessors", accessors)
            put("bufferViews", bufferViews)
            put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
        }
        return Gltf(json, bin.toByteArray())
    }
}

private fun floats(vararg values: Float) =
    ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()

private fun shorts(vararg values: Int) =
    ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()

/** One quad sampling [image]. */
private fun texturedQuad(image: ByteArray): Gltf = BinWriter().run {
    val positions = accessor(floats(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), 5126, 4, "VEC3", 34962, bounds = true)
    val uvs = accessor(floats(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 5126, 4, "VEC2", 34962)
    val indices = accessor(shorts(0, 1, 2, 0, 2, 3), 5123, 6, "SCALAR", 34963)
    val imageView = view(image)
    gltf {
        put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
        put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
        put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(JSONObject().apply {
            put("attributes", JSONObject().put("POSITION", positions).put("TEXCOORD_0", uvs))
            put("indices", indices)
            put("material", 0)
        }))))
        put("materials", JSONArray().put(JSONObject().put("pbrMetallicRoughness",
            JSONObject().put("baseColorTexture", JSONObject().put("index", 0)))))
        put("textures", JSONArray().put(JSONObject().put("source", 0)))
        put("images", JSONArray().put(JSONObject().put("bufferView", imageView).put("mimeType", "image/png")))
    }
}

/** One triangle skinned to the first joint of a skin of [joints] joints, all children of node 0. */
private fun skinnedTriangle(joints: Int): Gltf = BinWriter().run {
    val positions = accessor(floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), 5126, 3, "VEC3", 34962, bounds = true)
    val jointIndices = accessor(ByteArray(12), 5121, 3, "VEC4", 34962)
    val weights = accessor(floats(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f), 5126, 3, "VEC4", 34962)
    val indices = accessor(shorts(0, 1, 2), 5123, 3, "SCALAR", 34963)
    gltf {
        put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0).put(1))))
        put("nodes", JSONArray().apply {
            put(JSONObject().put("children", JSONArray((2 until joints + 2).toList())))
            put(JSONObject().put("mesh", 0).put("skin", 0))
            repeat(joints) { put(JSONObject().put("name", "joint$it")) }
        })
        put("skins", JSONArray().put(JSONObject().put("joints", JSONArray((2 until joints + 2).toList()))))
        put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(JSONObject().apply {
            put("attributes", JSONObject().put("POSITION", positions).put("JOINTS_0", jointIndices).put("WEIGHTS_0", weights))
            put("indices", indices)
        }))))
    }
}

private fun glb(gltf: Gltf): ByteArray {
    val json = gltf.json.toString().toByteArray(Charsets.UTF_8).let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
    val total = 12 + 8 + json.size + 8 + gltf.bin.size
    return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546C67)
        putInt(2)
        putInt(total)
        putInt(json.size)
        putInt(0x4E4F534A)
        put(json)
        putInt(gltf.bin.size)
        putInt(0x004E4942)
        put(gltf.bin)
    }.array()
}
