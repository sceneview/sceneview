package io.github.sceneview.node

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Camera
import com.google.android.filament.MaterialInstance
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.render.RenderTestHarness
import io.github.sceneview.texture.ImageTexture
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for sceneview/sceneview#4285: an image-backed node removed while the scene keeps
 * rendering.
 *
 * `ImageNode.destroy()` handed `MaterialLoader` a read-back wrapper of its material instance. The
 * loader matched on identity only, so the instance was never destroyed. When the texture left the
 * destroy queue, the next `Renderer.beginFrame` committed that live instance and aborted with
 * `Invalid texture still bound to MaterialInstance: 'Transparent Textured'`. This is the ML Object
 * Label crash on label eviction.
 *
 * `ImageNodeTest` could not catch it: it never renders a frame, and its teardown destroys the
 * loader, so every instance, before the engine drains the textures.
 */
@RunWith(AndroidJUnit4::class)
class ImageNodeLiveDestroyTest {

    private lateinit var harness: RenderTestHarness
    private lateinit var materialLoader: MaterialLoader
    private var cameraEntity = 0

    @Before
    fun setup() {
        harness = RenderTestHarness(width = 32, height = 32)
        harness.runOnMain {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            materialLoader = MaterialLoader(harness.engine, context)
            cameraEntity = harness.engine.entityManager.create()
            harness.view.camera = harness.engine.createCamera(cameraEntity).apply {
                setProjection(45.0, 1.0, 0.1, 100.0, Camera.Fov.VERTICAL)
                lookAt(0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
            }
        }
    }

    @After
    fun teardown() {
        harness.runOnMain {
            materialLoader.destroy()
            harness.engine.destroyCameraComponent(cameraEntity)
            harness.engine.entityManager.destroy(cameraEntity)
        }
        harness.destroy()
    }

    /** One frame as `SceneRenderer.renderFrame` runs it: drain the destroy queue, then render. */
    private fun frame() {
        EngineDestroyQueue.of(harness.engine).drain()
        harness.renderFrames(1)
    }

    @Test
    fun destroyWhileRendering_destroysTheMaterialInstance_andKeepsRendering() {
        harness.runOnMain {
            val engine = harness.engine
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            val keep = BillboardNode(materialLoader = materialLoader, bitmap = bitmap)
            harness.scene.addEntity(keep.entity)
            repeat(10) { round ->
                val node = BillboardNode(materialLoader = materialLoader, bitmap = bitmap)
                harness.scene.addEntity(node.entity)
                frame()

                // A read-back wrapper, the same kind `destroy()` used to pass on.
                val readBack = node.materialInstance
                val material = readBack.material
                assertTrue(engine.isValidMaterialInstance(material, readBack))

                harness.scene.removeEntity(node.entity)
                node.destroy()

                assertFalse(
                    "round $round: destroy() must destroy the node's material instance",
                    engine.isValidMaterialInstance(material, readBack)
                )
                // Past the grace period the texture is freed. Before the fix, this aborted the
                // process at the next beginFrame.
                repeat(EngineDestroyQueue.GRACE_FRAMES + 2) { frame() }
            }
            // Still a live, rendered sibling sharing the same bitmap.
            assertTrue(engine.isValidTexture(keep.texture))
            harness.scene.removeEntity(keep.entity)
            keep.destroy()
            repeat(EngineDestroyQueue.GRACE_FRAMES + 2) { frame() }
            bitmap.recycle()
        }
    }

    @Test
    fun aReadBackWrapperIsEnoughToDestroyATrackedInstance() {
        harness.runOnMain {
            val engine = harness.engine
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val texture = ImageTexture.Builder().bitmap(bitmap).build(engine)
            val tracked = materialLoader.createImageInstance(texture)
            val material = tracked.material
            // Same native instance, new Java object: what any read-back returns.
            val alias = MaterialInstance(engine, tracked.nativeObject)

            materialLoader.destroyMaterialInstance(alias)

            assertFalse(engine.isValidMaterialInstance(material, alias))
            materialLoader.destroyMaterialInstance(alias) // already gone: a no-op
            EngineDestroyQueue.of(engine).enqueueTexture(texture)
            repeat(EngineDestroyQueue.GRACE_FRAMES + 2) { frame() }
            bitmap.recycle()
        }
    }
}
