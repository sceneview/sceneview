package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.render.RenderTestHarness
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device regression for #3937: `CameraNode.destroy()` must be idempotent and order-safe.
 *
 * A glTF camera becomes a `ModelNode.CameraNode` child on the asset's own entity. On a model
 * switch, `rememberModelInstance` runs `ModelLoader.destroyModel()` — which tears down the
 * asset's camera components — with no ordering against the `ModelNode`'s own disposal. Before the
 * fix, the node's later `destroy()` read the throwing `camera` getter and crashed the app with
 * `IllegalStateException: Entity … does not have a Camera component` (Model Viewer, Toy Car then
 * any other model).
 *
 * The model is generated here — one triangle and one perspective camera — so no fixture is
 * committed.
 */
@RunWith(AndroidJUnit4::class)
class CameraNodeDestroyOrderTest {

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
    fun gltf_camera_node_destroys_after_its_model_was_destroyed() {
        val glb = cameraGlb()
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            harness.runOnMain {
                val model = modelLoader.createModel(
                    ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder())
                        .put(glb).apply { rewind() }
                )
                val modelNode = ModelNode(model.instance)
                assertEquals("the generated GLB carries one camera", 1, modelNode.cameraNodes.size)
                val cameraEntity = modelNode.cameraNodes.single().entity
                assertTrue(harness.engine.getCameraComponent(cameraEntity) != null)

                // The disposal order `rememberModelInstance` can produce: the model first...
                modelLoader.destroyModel(model)
                assertNull(harness.engine.getCameraComponent(cameraEntity))
                // ...then the node. This threw before the fix.
                modelNode.destroy()

                assertTrue(modelNode.isDestroyed)
                assertTrue(modelNode.cameraNodes.single().isDestroyed)
            }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun camera_node_destroys_after_its_component_was_destroyed() {
        harness.runOnMain {
            val node = CameraNode(harness.engine)
            harness.engine.destroyCameraComponent(node.entity)
            node.destroy()
            assertTrue(node.isDestroyed)
        }
    }

    @Test
    fun camera_node_destroy_is_idempotent() {
        harness.runOnMain {
            val node = CameraNode(harness.engine)
            node.destroy()
            node.destroy()
            assertTrue(node.isDestroyed)
        }
    }
}

/** A minimal GLB: node 0 is a one-triangle mesh, node 1 a perspective camera. */
private fun cameraGlb(): ByteArray {
    val bin = ByteArrayOutputStream()
    val bufferViews = JSONArray()
    fun view(bytes: ByteArray, target: Int): Int {
        while (bin.size() % 4 != 0) bin.write(0)
        bufferViews.put(JSONObject().apply {
            put("buffer", 0)
            put("byteOffset", bin.size())
            put("byteLength", bytes.size)
            put("target", target)
        })
        bin.write(bytes)
        return bufferViews.length() - 1
    }

    val positions = view(
        ByteBuffer.allocate(9 * 4).order(ByteOrder.LITTLE_ENDIAN)
            .apply { floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { putFloat(it) } }
            .array(),
        34962,
    )
    val indices = view(
        ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .apply { shortArrayOf(0, 1, 2).forEach { putShort(it) } }.array(),
        34963,
    )
    while (bin.size() % 4 != 0) bin.write(0)

    val json = JSONObject().apply {
        put("asset", JSONObject().put("version", "2.0"))
        put("scene", 0)
        put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0).put(1))))
        put("nodes", JSONArray().apply {
            put(JSONObject().put("mesh", 0))
            put(JSONObject().put("camera", 0).put("translation", JSONArray(listOf(0, 0, 3))))
        })
        put("cameras", JSONArray().put(JSONObject().apply {
            put("type", "perspective")
            put("perspective", JSONObject().put("yfov", 0.8).put("znear", 0.1).put("zfar", 100))
        }))
        put("meshes", JSONArray().put(JSONObject().put("primitives", JSONArray().put(
            JSONObject().put("attributes", JSONObject().put("POSITION", 0)).put("indices", 1)
        ))))
        put("accessors", JSONArray().apply {
            put(JSONObject().put("bufferView", positions).put("componentType", 5126).put("count", 3)
                .put("type", "VEC3")
                .put("min", JSONArray(listOf(0, 0, 0))).put("max", JSONArray(listOf(1, 1, 0))))
            put(JSONObject().put("bufferView", indices).put("componentType", 5123).put("count", 3)
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
