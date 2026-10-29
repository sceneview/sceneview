package io.github.sceneview

import android.net.Uri
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sceneview.loaders.ModelLoadProgress
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.loaders.UnsupportedGltfExtensionException
import io.github.sceneview.loaders.texturedGlb
import io.github.sceneview.render.RenderTestHarness
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [rememberModelLoadState] in a real composition: `Loading` steps up to `Ready` once the textures
 * are final, `Failed` for a model gltfio cannot load, and the model it owns is destroyed when it
 * leaves the composition.
 */
@RunWith(AndroidJUnit4::class)
class ModelLoadStateComposeTest {

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

    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, glb: ByteArray): String =
        Uri.fromFile(File(context.cacheDir, name).apply { writeBytes(glb) }).toString()

    @Test
    fun loading_steps_end_ready_and_leaving_the_composition_destroys_the_model() {
        val location = file("compose_textured.glb", texturedGlb(textures = 4, size = 1024))
        val modelLoader = ModelLoader(harness.engine, context)
        val states = CopyOnWriteArrayList<ModelLoadState>()
        var shown by mutableStateOf(true)
        try {
            compose.setContent {
                if (shown) {
                    val state = rememberModelLoadState(modelLoader, location)
                    SideEffect { states += state }
                }
            }
            compose.waitUntil(30_000) { states.lastOrNull() is ModelLoadState.Ready }

            assertTrue("first state ${states.first()}", states.first() is ModelLoadState.Loading)
            val loading = states.filterIsInstance<ModelLoadState.Loading>().map { it.progress.fraction }
            assertEquals("progress went back: $states", loading.sorted(), loading)
            // Four 1024 px textures take several frames to decode: the composition sees them going.
            assertTrue(
                "no texture step seen: $states",
                states.any { it is ModelLoadState.Loading && it.progress.stage == ModelLoadProgress.Stage.Textures },
            )
            assertEquals(1, modelLoader.registeredModels())
            harness.runOnMain { assertTrue("textures still loading when Ready", !modelLoader.isLoading) }

            compose.runOnIdle { shown = false }
            compose.waitForIdle()
            assertEquals("the model outlived the composition", 0, modelLoader.registeredModels())
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun a_new_fileLocation_loads_again_and_destroys_the_previous_model() {
        val first = file("compose_first.glb", texturedGlb(textures = 1, size = 64))
        val second = file("compose_second.glb", texturedGlb(textures = 2, size = 64))
        val modelLoader = ModelLoader(harness.engine, context)
        val states = CopyOnWriteArrayList<ModelLoadState>()
        var location by mutableStateOf(first)
        var shown by mutableStateOf(true)
        try {
            compose.setContent {
                if (shown) {
                    val state = rememberModelLoadState(modelLoader, location)
                    SideEffect { states += state }
                }
            }
            compose.waitUntil(30_000) { states.lastOrNull() is ModelLoadState.Ready }
            val firstInstance = (states.last() as ModelLoadState.Ready).instance
            val switchedAt = states.size

            compose.runOnIdle { location = second }
            compose.waitUntil(30_000) {
                (states.lastOrNull() as? ModelLoadState.Ready)?.instance?.let { it !== firstInstance } == true
            }

            val afterSwitch = states.drop(switchedAt)
            assertTrue("no Loading after the switch: $afterSwitch", afterSwitch.first() is ModelLoadState.Loading)
            assertEquals("the first model outlived its fileLocation", 1, modelLoader.registeredModels())

            compose.runOnIdle { shown = false }
            compose.waitForIdle()
            assertEquals("the second model outlived the composition", 0, modelLoader.registeredModels())
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    @Test
    fun a_model_gltfio_cannot_load_is_failed_with_the_typed_error() {
        val location = file(
            "compose_avif.glb",
            texturedGlb(textures = 1, size = 64, extensionsRequired = listOf("EXT_texture_avif")),
        )
        val modelLoader = ModelLoader(harness.engine, context)
        val states = CopyOnWriteArrayList<ModelLoadState>()
        try {
            compose.setContent {
                val current = rememberModelLoadState(modelLoader, location)
                SideEffect { states += current }
            }
            compose.waitUntil(30_000) { states.lastOrNull() is ModelLoadState.Failed }

            val error = (states.last() as ModelLoadState.Failed).error
            assertTrue("failed with $error", error is UnsupportedGltfExtensionException)
            assertEquals(listOf("EXT_texture_avif"), (error as UnsupportedGltfExtensionException).extensions)
            assertEquals(0, modelLoader.registeredModels())
        } finally {
            harness.runOnMain { modelLoader.destroy() }
        }
    }

    private fun ModelLoader.registeredModels(): Int =
        (ModelLoader::class.java.getDeclaredField("models").apply { isAccessible = true }.get(this) as List<*>).size
}
