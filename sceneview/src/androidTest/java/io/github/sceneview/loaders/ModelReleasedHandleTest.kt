package io.github.sceneview.loaders

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.isReleased
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.model.isDestroyed
import io.github.sceneview.model.model
import io.github.sceneview.node.ModelNode
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device check of the invariant every "model destroyed" guard rests on.
 *
 * SceneView never keeps a destroyed flag of its own: it reads the Java handle of the
 * `FilamentAsset`, which gltfio's `AssetLoader.destroyAsset` resets to 0 (`clearNativeObject`).
 * The unit tests can only stage that reset by hand. This test lets the real gltfio do it, so a
 * Filament upgrade that stops resetting the handle fails here instead of turning every guard into
 * a use-after-free.
 */
@RunWith(AndroidJUnit4::class)
class ModelReleasedHandleTest {

    private lateinit var engine: Engine
    private var modelLoader: ModelLoader? = null

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            modelLoader?.destroy()
            engine.safeDestroy()
        }
    }

    @Test
    fun destroyModel_resets_the_asset_handle_the_guards_read() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val loader = ModelLoader(engine, InstrumentationRegistry.getInstrumentation().context)
                .also { modelLoader = it }
            val instance = loader.createModelInstance("khronos_fox.glb")
            val node = ModelNode(modelInstance = instance, autoAnimate = false)
            assertFalse("a live model must not read as released", instance.model.isReleased)
            assertFalse(node.isModelDestroyed)

            loader.destroyModel(instance.model)

            assertTrue(
                "gltfio no longer resets FilamentAsset's handle on destroyAsset: every " +
                    "destroyed-model guard in SceneView now reads freed memory",
                instance.model.isReleased,
            )
            assertTrue(instance.isDestroyed)
            assertTrue(node.isModelDestroyed)
            node.destroy()
        }
    }
}
