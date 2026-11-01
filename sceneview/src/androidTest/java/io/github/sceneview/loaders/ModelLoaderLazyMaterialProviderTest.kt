package io.github.sceneview.loaders

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.render.RenderTestHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `ModelLoader` builds gltfio's `UbershaderProvider` — tens of milliseconds of archive
 * decompression on the main thread — for its first model, not in its constructor, so that
 * `rememberModelLoader` stays out of the frame a 3D screen opens with.
 */
@RunWith(AndroidJUnit4::class)
class ModelLoaderLazyMaterialProviderTest {

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

    @Test
    fun a_loader_that_creates_no_model_never_builds_the_material_provider() {
        harness.runOnMain {
            val modelLoader = ModelLoader(harness.engine, context)
            // What the render loop asks of every loader, every frame.
            modelLoader.updateLoad()
            modelLoader.isLoading
            modelLoader.progress
            modelLoader.clear()
            assertFalse(modelLoader.hasGltfLoaders)

            modelLoader.destroy()
            assertFalse(modelLoader.hasGltfLoaders)
        }
    }

    @Test
    fun the_first_model_builds_it_and_destroy_frees_it() {
        harness.runOnMain {
            val modelLoader = ModelLoader(harness.engine, context)
            val glb = texturedGlb(textures = 1, size = 4)
            val model = modelLoader.createModel(
                ByteBuffer.allocateDirect(glb.size).order(ByteOrder.nativeOrder()).put(glb).rewind()
            )
            assertNotNull(model)
            assertTrue(modelLoader.hasGltfLoaders)
            modelLoader.destroy()
        }
    }

    @Test
    fun a_suspend_load_called_off_the_main_thread_still_builds_it_on_the_main_thread() {
        val glb = texturedGlb(textures = 1, size = 4)
        val location = Uri.fromFile(File(context.cacheDir, "lazy_provider.glb").apply { writeBytes(glb) })
            .toString()
        lateinit var modelLoader: ModelLoader
        harness.runOnMain { modelLoader = ModelLoader(harness.engine, context) }

        // Called from a background thread, as `rememberModelInstance`'s callers and apps do. The
        // provider is created inside the main-thread hop that creates the asset; built here it
        // would race the main thread's Filament calls.
        val instance = runBlocking {
            withContext(Dispatchers.IO) {
                assertFalse(Looper.myLooper() == Looper.getMainLooper())
                modelLoader.loadModelInstance(location)
            }
        }
        assertNotNull(instance)
        harness.runOnMain {
            assertTrue(modelLoader.hasGltfLoaders)
            modelLoader.destroy()
        }
    }
}
