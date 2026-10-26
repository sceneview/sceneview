package io.github.sceneview.loaders

import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.ModelLoadProgress.Stage
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.render.RenderTestHarness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Loading the large, texture-heavy GLBs generation services hand back: a required extension gltfio
 * lacks is refused before any Filament call, and [ModelLoader.awaitModelInstance]
 * reports its progress on the main thread and returns once the textures are final.
 */
@RunWith(AndroidJUnit4::class)
class LargeModelLoadTest {

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

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, glb: ByteArray): String =
        Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(glb) }).toString()

    @Test
    fun a_required_extension_gltfio_lacks_is_refused_on_every_load_path_without_a_new_exception() {
        val glb = texturedGlb(textures = 1, size = 64, extensionsRequired = listOf("EXT_texture_avif"))
        val location = file("requires_avif.glb", glb)
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            // The new API throws the typed error…
            val awaitError = runCatching { runBlocking { modelLoader.awaitModelInstance(location) } }.exceptionOrNull()
            assertTrue("awaitModelInstance threw $awaitError", awaitError is UnsupportedGltfExtensionException)
            assertEquals(listOf("EXT_texture_avif"), (awaitError as UnsupportedGltfExtensionException).extensions)

            // …the existing suspend loads keep their null-and-log contract…
            runBlocking {
                assertNull(modelLoader.loadModel(location))
                assertNull(modelLoader.loadModelInstance(location))
                assertEquals(emptyList<ModelInstance>(), modelLoader.loadInstancedModel(location, count = 2))
            }

            // …and the synchronous ones reject it with the IllegalArgumentException they declare.
            harness.runOnMain {
                fun buffer() = ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb)
                    .apply { rewind() }
                val errors = listOf(
                    runCatching { modelLoader.createModel(buffer()) },
                    runCatching { modelLoader.createModelInstance(buffer()) },
                    runCatching { modelLoader.createInstancedModel(buffer(), count = 2) },
                ).map { it.exceptionOrNull() }
                errors.forEach { assertTrue("threw $it", it is UnsupportedGltfExtensionException && it is IllegalArgumentException) }
            }

            val done = CountDownLatch(1)
            var asyncResult: ModelInstance? = null
            modelLoader.loadModelInstanceAsync(location) {
                asyncResult = it
                done.countDown()
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertNull(asyncResult)

            // Refused before any Filament call: nothing began loading, nothing was created.
            harness.runOnMain { assertFalse(modelLoader.isLoading) }
            assertEquals(0, modelLoader.registeredModels())
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun awaitModelInstance_reports_progress_on_main_and_returns_with_its_textures_final() {
        val location = file("textured.glb", texturedGlb(textures = 4, size = 1024))
        val modelLoader = ModelLoader(harness.engine, context)
        val reports = CopyOnWriteArrayList<ModelLoadProgress>()
        var offMain = 0
        try {
            val instance = runBlocking {
                modelLoader.awaitModelInstance(location) {
                    if (Looper.myLooper() != Looper.getMainLooper()) offMain++
                    reports += it
                }
            }
            assertNotNull(instance)
            harness.runOnMain {
                assertFalse("textures still loading", modelLoader.isLoading)
                assertEquals(1f, modelLoader.progress, 0.0001f)
            }
            assertEquals("progress reported off the main thread", 0, offMain)
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), reports.lastOrNull())
            val fractions = reports.map { it.fraction }
            assertEquals("progress went back: $reports", fractions.sorted(), fractions)
            val stages = reports.map { it.stage.ordinal }
            assertEquals("stages out of order: $reports", stages.sorted(), stages)
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun cancelling_awaitModelInstance_at_any_step_leaves_no_model_in_the_loader() {
        val location = file("cancelled.glb", texturedGlb(textures = 4, size = 1024))
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            val steps = CopyOnWriteArrayList<ModelLoadProgress>()
            val startedAt = SystemClock.elapsedRealtime()
            val first = runBlocking { modelLoader.awaitModelInstance(location) { steps += it } }
            val loadMs = SystemClock.elapsedRealtime() - startedAt
            harness.runOnMain { modelLoader.destroyModel(first.model) }
            assertEquals(0, modelLoader.registeredModels())
            assertTrue("no texture step to cancel at: $steps", steps.any { it.stage == Stage.Textures })

            // Cancelled from the progress callback, at each step (the Textures ones included)…
            steps.indices.forEach { step ->
                var seen = 0
                assertNothingLeaks(modelLoader, "cancelled at step $step of $steps") { scope ->
                    modelLoader.awaitModelInstance(location) { if (seen++ == step) scope.cancel() }
                }
            }
            // …and from another thread, anywhere in the load, between the steps too.
            (0..10).forEach { tenth ->
                assertNothingLeaks(modelLoader, "cancelled after ${tenth * loadMs / 10} ms", cancelAfterMs = tenth * loadMs / 10) {
                    modelLoader.awaitModelInstance(location)
                }
            }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    /**
     * Two calls on one loader both return their own model with its textures final, and leave the
     * loader idle. Whether the second begins while the first still decodes is not forced: on the
     * emulator gltfio has every texture final at the first poll, so no texture step below 1 exists
     * to begin the second load from (see the PR notes). The per-model rule this relies on is
     * gltfio's: `asyncBeginLoad` drains the previous load's decoders.
     */
    @Test
    fun concurrent_awaitModelInstance_calls_both_return_with_their_own_textures_final() {
        val first = file("concurrent_first.glb", texturedGlb(textures = 8, size = 2048))
        val second = file("concurrent_second.glb", texturedGlb(textures = 8, size = 2048))
        val modelLoader = ModelLoader(harness.engine, context)
        val firstReports = CopyOnWriteArrayList<ModelLoadProgress>()
        val secondReports = CopyOnWriteArrayList<ModelLoadProgress>()
        try {
            val (a, b) = runBlocking(Dispatchers.Default) {
                withTimeout(60_000) {
                    val a = async { modelLoader.awaitModelInstance(first) { firstReports += it } }
                    val b = async { modelLoader.awaitModelInstance(second) { secondReports += it } }
                    a.await() to b.await()
                }
            }
            Log.i("LargeModelLoadTest", "first: $firstReports\nsecond: $secondReports")
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), firstReports.last())
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), secondReports.last())
            assertTrue("one model for both calls", a.model !== b.model)
            harness.runOnMain { assertFalse("textures still loading", modelLoader.isLoading) }
            assertEquals(2, modelLoader.registeredModels())
            harness.runOnMain {
                modelLoader.destroyModel(a.model)
                modelLoader.destroyModel(b.model)
            }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun clear_during_awaitModelInstance_fails_it_and_leaves_no_model() {
        val location = file("cleared.glb", texturedGlb(textures = 4, size = 1024))
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            val steps = CopyOnWriteArrayList<ModelLoadProgress>()
            val first = runBlocking { modelLoader.awaitModelInstance(location) { steps += it } }
            harness.runOnMain { modelLoader.destroyModel(first.model) }
            // Every Textures step, the last one (`Textures 1`, which ends the wait) included: on the
            // emulator it is often the only one, the textures being final at the first poll.
            val textureSteps = steps.indices.filter { steps[it].stage == Stage.Textures }
            assertTrue("no texture step to clear at: $steps", textureSteps.isNotEmpty())
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), steps.last())

            textureSteps.forEachIndexed { k, step ->
                var seen = 0
                var cleared = false
                // `clear()` from the progress callback, on the main thread, at the k-th texture
                // step (a faster run may not reach the later ones below 1: it then just loads).
                val outcome = runBlocking(Dispatchers.Default) {
                    withTimeout(30_000) {
                        runCatching {
                            modelLoader.awaitModelInstance(location) {
                                if (it.stage == Stage.Textures && seen++ == k) {
                                    modelLoader.clear()
                                    cleared = true
                                }
                            }
                        }
                    }
                }
                if (!cleared) {
                    assertTrue("never cleared, even at the first texture step: $outcome", k > 0)
                    harness.runOnMain { modelLoader.destroyModel(outcome.getOrThrow().model) }
                    return@forEachIndexed
                }
                val error = outcome.exceptionOrNull()
                assertTrue("cleared at step $step of $steps: $outcome", error is IllegalStateException)
                assertEquals("cleared at step $step: the model leaked", 0, modelLoader.registeredModels())
                harness.runOnMain { assertFalse("cleared at step $step: still loading", modelLoader.isLoading) }
            }

            // The loader stays usable after a clear.
            val again = runBlocking { withTimeout(30_000) { modelLoader.awaitModelInstance(location) } }
            harness.runOnMain { modelLoader.destroyModel(again.model) }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun a_gltf_whose_external_image_is_missing_still_completes() {
        val location = Uri.fromFile(File(context.cacheDir, "missing_image.gltf").apply { writeText(gltfWithMissingImage()) })
            .toString()
        File(context.cacheDir, "missing.png").delete()
        val modelLoader = ModelLoader(harness.engine, context)
        val reports = CopyOnWriteArrayList<ModelLoadProgress>()
        try {
            val instance = runBlocking { withTimeout(30_000) { modelLoader.awaitModelInstance(location) { reports += it } } }
            // `Textures 1`, not the stall guard's degraded result (which stays below 1).
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), reports.last())
            harness.runOnMain {
                assertFalse("textures still loading", modelLoader.isLoading)
                modelLoader.destroyModel(instance.model)
            }

            // The loads that return before the textures are final settle too.
            val loaded = runBlocking { modelLoader.loadModelInstance(location) }
            assertNotNull(loaded)
            val deadline = SystemClock.uptimeMillis() + 5_000
            var loading = true
            while (loading && SystemClock.uptimeMillis() < deadline) {
                harness.runOnMain {
                    modelLoader.updateLoad()
                    loading = modelLoader.isLoading
                }
                SystemClock.sleep(16)
            }
            assertFalse("isLoading never dropped", loading)
            harness.runOnMain { modelLoader.destroyModel(loaded!!.model) }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    /** Writes [glb] as `[name].gltf` plus `[name].bin`, its buffer moved to that external file. */
    @Test
    fun awaitModelInstance_returns_for_a_model_with_no_texture() {
        val location = file("untextured.glb", untexturedGlb())
        val modelLoader = ModelLoader(harness.engine, context)
        val reports = CopyOnWriteArrayList<ModelLoadProgress>()
        try {
            val instance = runBlocking { withTimeout(30_000) { modelLoader.awaitModelInstance(location) { reports += it } } }
            assertNotNull(instance)
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), reports.last())
            assertEquals("a step was reported twice: $reports", reports.distinct(), reports)
            harness.runOnMain { assertFalse(modelLoader.isLoading) }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    /**
     * Runs [load] in a scope that is cancelled by [load] itself or after [cancelAfterMs], then
     * checks the loader holds nothing it did not hand out.
     */
    private fun assertNothingLeaks(
        modelLoader: ModelLoader,
        what: String,
        cancelAfterMs: Long? = null,
        load: suspend (scope: CoroutineScope) -> ModelInstance,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val result = scope.async { load(scope) }
        if (cancelAfterMs != null) {
            SystemClock.sleep(cancelAfterMs)
            scope.cancel()
        }
        val outcome = runBlocking { runCatching { result.await() } }
        val instance = outcome.getOrNull()
        if (instance != null) {
            // Returned before the cancellation landed: the caller owns it.
            assertEquals(what, 1, modelLoader.registeredModels())
            harness.runOnMain { modelLoader.destroyModel(instance.model) }
        } else {
            assertTrue("$what: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is CancellationException)
            assertEquals("$what: the model leaked", 0, modelLoader.registeredModels())
        }
    }

    private fun ModelLoader.registeredModels(): Int =
        (ModelLoader::class.java.getDeclaredField("models").apply { isAccessible = true }.get(this) as List<*>).size
}

/** A GLB holding one triangle and no material, so no texture at all. */
private fun untexturedGlb(): ByteArray {
    val bin = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        .apply { floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f).forEach { putFloat(it) } }.array()
    val json = """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
        """"meshes":[{"primitives":[{"attributes":{"POSITION":0}}]}],""" +
        """"accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]}],""" +
        """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36,"target":34962}],"buffers":[{"byteLength":36}]}"""
    val jsonBytes = json.toByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
    val total = 12 + 8 + jsonBytes.size + 8 + bin.size
    return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x46546C67).putInt(2).putInt(total)
        .putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
        .putInt(bin.size).putInt(0x004E4942).put(bin)
        .array()
}

/**
 * A text glTF: one textured quad whose buffer is an embedded `data:` URI and whose only image is
 * an external `missing.png` that does not exist.
 */
private fun gltfWithMissingImage(): String {
    val bin = ByteBuffer.allocate(12 * 4 + 8 * 4 + 6 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f).forEach { putFloat(it) }
        floatArrayOf(0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f).forEach { putFloat(it) }
        shortArrayOf(0, 1, 2, 0, 2, 3).forEach { putShort(it) }
    }.array()
    val data = "data:application/octet-stream;base64," + Base64.encodeToString(bin, Base64.NO_WRAP)
    return """{"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],""" +
        """"meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],""" +
        """"materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],""" +
        """"textures":[{"source":0}],"images":[{"uri":"missing.png"}],""" +
        """"accessors":[""" +
        """{"bufferView":0,"componentType":5126,"count":4,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},""" +
        """{"bufferView":1,"componentType":5126,"count":4,"type":"VEC2"},""" +
        """{"bufferView":2,"componentType":5123,"count":6,"type":"SCALAR"}],""" +
        """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":48,"target":34962},""" +
        """{"buffer":0,"byteOffset":48,"byteLength":32,"target":34962},""" +
        """{"buffer":0,"byteOffset":80,"byteLength":12,"target":34963}],""" +
        """"buffers":[{"byteLength":${bin.size},"uri":"$data"}]}"""
}
