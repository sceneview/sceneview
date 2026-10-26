package io.github.sceneview.loaders

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.ModelLoadProgress.Stage
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.render.RenderTestHarness
import kotlinx.coroutines.runBlocking
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
}
