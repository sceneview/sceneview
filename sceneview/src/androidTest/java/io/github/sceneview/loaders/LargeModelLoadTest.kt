package io.github.sceneview.loaders

import android.net.Uri
import android.os.Looper
import android.os.SystemClock
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
 * lacks fails with a typed error before any Filament call, and [ModelLoader.awaitModelInstance]
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
    fun a_required_extension_gltfio_lacks_is_a_typed_error_on_every_load_path() {
        val glb = texturedGlb(textures = 1, size = 64, extensionsRequired = listOf("EXT_texture_avif"))
        val location = file("requires_avif.glb", glb)
        val modelLoader = ModelLoader(harness.engine, context)
        try {
            val suspendError = runCatching { runBlocking { modelLoader.loadModelInstance(location) } }
                .exceptionOrNull()
            assertTrue("loadModelInstance threw $suspendError", suspendError is UnsupportedGltfExtensionException)
            assertEquals(listOf("EXT_texture_avif"), (suspendError as UnsupportedGltfExtensionException).extensions)

            val awaitError = runCatching { runBlocking { modelLoader.awaitModelInstance(location) } }.exceptionOrNull()
            assertTrue("awaitModelInstance threw $awaitError", awaitError is UnsupportedGltfExtensionException)

            harness.runOnMain {
                val buffer = ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb)
                    .apply { rewind() }
                assertThrows(UnsupportedGltfExtensionException::class.java) { modelLoader.createModel(buffer) }
            }

            val done = CountDownLatch(1)
            var asyncResult: ModelInstance? = null
            modelLoader.loadModelInstanceAsync(location) {
                asyncResult = it
                done.countDown()
            }
            assertTrue(done.await(30, TimeUnit.SECONDS))
            assertNull(asyncResult)

            // Refused before any Filament call: nothing began loading.
            harness.runOnMain { assertFalse(modelLoader.isLoading) }
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

    @Test
    fun concurrent_awaitModelInstance_calls_each_wait_for_their_own_textures_only() {
        val first = file("concurrent_first.glb", texturedGlb(textures = 8, size = 2048))
        val second = file("concurrent_second.glb", texturedGlb(textures = 8, size = 2048))
        val modelLoader = ModelLoader(harness.engine, context)
        val firstReports = CopyOnWriteArrayList<ModelLoadProgress>()
        val secondReports = CopyOnWriteArrayList<ModelLoadProgress>()
        // Read on the main thread, at the step that ends the first load.
        var secondBegunWhenFirstEnded = false
        var loadingWhenFirstEnded = false
        val secondCanStart = CompletableDeferred<Unit>()
        try {
            val (a, b) = runBlocking(Dispatchers.Default) {
                withTimeout(60_000) {
                    val a = async {
                        modelLoader.awaitModelInstance(first) {
                            firstReports += it
                            if (it.stage == Stage.Creating) secondCanStart.complete(Unit)
                            if (it == ModelLoadProgress(Stage.Textures, 1f)) {
                                secondBegunWhenFirstEnded = secondReports.any { r -> r.stage == Stage.Textures }
                                loadingWhenFirstEnded = modelLoader.isLoading
                            }
                        }
                    }
                    secondCanStart.await()
                    val b = async { modelLoader.awaitModelInstance(second) { secondReports += it } }
                    a.await() to b.await()
                }
            }
            Log.i("LargeModelLoadTest", "first: $firstReports\nsecond: $secondReports")
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), firstReports.last())
            assertEquals(ModelLoadProgress(Stage.Textures, 1f), secondReports.last())
            assertEquals(2, modelLoader.registeredModels())
            harness.runOnMain { assertFalse("textures still loading", modelLoader.isLoading) }
            // The second load drained the first one's decoders when it began: once it has, the
            // first is final and returns, while the second still decodes its eight 2K textures.
            if (secondBegunWhenFirstEnded) {
                assertTrue("the first load waited for the second one's textures", loadingWhenFirstEnded)
            } else {
                Log.w("LargeModelLoadTest", "the first load ended before the second began: the overlap was not exercised")
            }
            harness.runOnMain {
                modelLoader.destroyModel(a.model)
                modelLoader.destroyModel(b.model)
            }
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

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
