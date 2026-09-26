package io.github.sceneview.loaders

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.render.RenderTestHarness
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * On-device reproduction of #3868: a model destroyed while gltfio is still decoding its textures.
 *
 * `createModel` returns right after `asyncBeginLoad`, with the textures decoding on Filament's job
 * threads. Destroying the model right then is what `rememberModelInstance` does when its key
 * changes just after the first load (AR Model Viewer did it on every AR open). Before the fix,
 * `destroyModel` freed the asset and its textures without `asyncCancelLoad()`, so the next
 * `updateLoad()` (run every frame) or the next `asyncBeginLoad` uploaded into freed textures: a
 * native crash of the whole process, which is how this test fails without the fix.
 *
 * The model is generated here — four 2048² noise JPEG textures, slow enough to decode that the
 * destroy always lands mid-decode — so no heavy fixture is committed.
 */
@RunWith(AndroidJUnit4::class)
class ModelDestroyDuringAsyncLoadTest {

    companion object {
        private const val TEXTURES = 4
        private const val TEXTURE_SIZE = 2048
        private const val REMOUNTS = 5

        private lateinit var harness: RenderTestHarness
        private lateinit var glb: ByteArray

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 64, height = 64)
            glb = texturedGlb(TEXTURES, TEXTURE_SIZE)
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.destroy()
        }
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().context

    private fun buffer(): ByteBuffer =
        ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb).apply { rewind() }

    @Test
    fun frames_keep_pumping_after_a_model_is_destroyed_mid_decode() {
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            repeat(REMOUNTS) {
                harness.runOnMain {
                    val model = modelLoader.createModel(buffer())
                    modelLoader.destroyModel(model)
                }
                // The render loop keeps calling updateLoad(): before the fix, this is the
                // ResourceLoader::asyncUpdateLoad() SIGSEGV / Filament use-after-free panic.
                pump(modelLoader, frames = 30)
            }
            assertTheNextLoadCompletes(modelLoader)
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun a_remount_loads_the_next_model_right_after_destroying_one_mid_decode() {
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            harness.runOnMain {
                var model = modelLoader.createModel(buffer())
                repeat(REMOUNTS) {
                    // Key change: old model disposed, new one loaded in the same frame. Before the
                    // fix, asyncBeginLoad drained the freed model's decoder jobs into freed textures.
                    modelLoader.destroyModel(model)
                    model = modelLoader.createModel(buffer())
                }
            }
            assertTheNextLoadCompletes(modelLoader)
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    /**
     * A model loaded after the cancelled ones finalises all its textures and `isLoading` drops.
     * An interrupted cancel leaves gltfio's progress counters skewed for good: without the fresh
     * `ResourceLoader` that `ModelLoader` swaps in afterwards, `isLoading` would stay `true` and
     * keep an on-demand scene rendering forever.
     */
    private fun assertTheNextLoadCompletes(modelLoader: ModelLoader) {
        harness.runOnMain { modelLoader.createModel(buffer()) }
        val deadline = SystemClock.uptimeMillis() + 30_000
        var loading = true
        while (loading && SystemClock.uptimeMillis() < deadline) {
            pump(modelLoader, frames = 1)
            harness.runOnMain { loading = modelLoader.isLoading }
        }
        assertFalse("the load after the cancelled ones must finish", loading)
        harness.runOnMain { assertEquals(1f, modelLoader.progress, 0.0001f) }
    }

    private fun pump(modelLoader: ModelLoader, frames: Int) = repeat(frames) {
        harness.runOnMain { modelLoader.updateLoad() }
        SystemClock.sleep(16)
    }
}

/** A GLB: one quad per texture, each with its own material sampling a distinct noise JPEG. */
private fun texturedGlb(textures: Int, size: Int): ByteArray {
    val bin = ByteArrayOutputStream()
    val bufferViews = JSONArray()
    fun view(bytes: ByteArray, target: Int?): Int {
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
    fun floats(vararg values: Float) = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putFloat(it) } }.array()

    val positions = view(floats(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f), 34962)
    val uvs = view(floats(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f), 34962)
    val indices = view(
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .apply { shortArrayOf(0, 1, 2, 0, 2, 3).forEach { putShort(it) } }.array(),
        34963,
    )
    val random = Random(3868)
    val images = JSONArray()
    repeat(textures) {
        val pixels = IntArray(size * size) { random.nextInt() or (0xFF shl 24) }
        val jpeg = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                .compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()
        images.put(JSONObject().put("bufferView", view(jpeg, null)).put("mimeType", "image/jpeg"))
    }
    while (bin.size() % 4 != 0) bin.write(0)

    val json = JSONObject().apply {
        put("asset", JSONObject().put("version", "2.0"))
        put("scene", 0)
        put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
        put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
        put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().apply {
            repeat(textures) { i ->
                put(JSONObject().apply {
                    put("attributes", JSONObject().put("POSITION", 0).put("TEXCOORD_0", 1))
                    put("indices", 2)
                    put("material", i)
                })
            }
        })))
        put("materials", JSONArray().apply {
            repeat(textures) { i ->
                put(JSONObject().put("pbrMetallicRoughness", JSONObject()
                    .put("baseColorTexture", JSONObject().put("index", i))))
            }
        })
        put("textures", JSONArray().apply { repeat(textures) { put(JSONObject().put("source", it)) } })
        put("images", images)
        put("accessors", JSONArray().apply {
            put(JSONObject().put("bufferView", positions).put("componentType", 5126).put("count", 4)
                .put("type", "VEC3")
                .put("min", JSONArray(listOf(0, 0, 0))).put("max", JSONArray(listOf(1, 1, 0))))
            put(JSONObject().put("bufferView", uvs).put("componentType", 5126).put("count", 4)
                .put("type", "VEC2"))
            put(JSONObject().put("bufferView", indices).put("componentType", 5123).put("count", 6)
                .put("type", "SCALAR"))
        })
        put("bufferViews", bufferViews)
        put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.size())))
    }
    val jsonBytes = json.toString().toByteArray(Charsets.UTF_8).let { raw ->
        raw + ByteArray((4 - raw.size % 4) % 4) { ' '.code.toByte() }
    }
    val binBytes = bin.toByteArray()
    return ByteBuffer.allocate(12 + 8 + jsonBytes.size + 8 + binBytes.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x46546C67).putInt(2).putInt(12 + 8 + jsonBytes.size + 8 + binBytes.size)
        .putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
        .putInt(binBytes.size).putInt(0x004E4942).put(binBytes)
        .array()
}
