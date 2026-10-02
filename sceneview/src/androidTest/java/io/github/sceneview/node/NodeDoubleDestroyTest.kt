package io.github.sceneview.node

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.LightManager
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.lightGeneration
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.render.RenderTestHarness
import io.github.sceneview.renderableGeneration
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device regression for #4259: a second `destroy()` on a real node is a no-op.
 *
 * `Node.destroy()` was guarded by `isDestroyed`, but the subclass overrides ran their own
 * teardown before calling `super`. The ML Object Label demo destroyed its billboards twice (a
 * parent `AnchorNode` tears its children down, then their own composition slot does it again),
 * so each second call destroyed the renderable component again, re-enqueued the image texture
 * and bumped the engine's component generations, invalidating every other live node's cached
 * handles. Each case below measures one of those side effects across the second call.
 */
@RunWith(AndroidJUnit4::class)
class NodeDoubleDestroyTest {

    companion object {
        private lateinit var harness: RenderTestHarness
        private lateinit var materialLoader: MaterialLoader

        @JvmStatic
        @BeforeClass
        fun setupClass() {
            harness = RenderTestHarness(width = 64, height = 64)
            harness.runOnMain {
                materialLoader = MaterialLoader(
                    harness.engine,
                    InstrumentationRegistry.getInstrumentation().context
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun teardownClass() {
            harness.runOnMain {
                EngineDestroyQueue.of(harness.engine).drainAll()
                materialLoader.destroy()
            }
            harness.destroy()
        }
    }

    private val engine get() = harness.engine

    @Test
    fun renderable_node_second_destroy_leaves_the_renderable_manager_alone() {
        harness.runOnMain {
            val node = CubeNode(engine)
            node.destroy()
            val generation = engine.renderableGeneration()

            node.destroy()

            assertTrue(node.isDestroyed)
            assertEquals(
                "a second destroy must not destroy a renderable again",
                generation,
                engine.renderableGeneration()
            )
        }
    }

    @Test
    fun light_node_second_destroy_leaves_the_light_manager_alone() {
        harness.runOnMain {
            val node = LightNode(engine, LightManager.Type.POINT) { intensity(1_000f) }
            node.destroy()
            val generation = engine.lightGeneration()

            node.destroy()

            assertEquals(
                "a second destroy must not destroy a light again",
                generation,
                engine.lightGeneration()
            )
        }
    }

    @Test
    fun image_node_second_destroy_does_not_enqueue_its_texture_again() {
        harness.runOnMain {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val queue = EngineDestroyQueue.of(engine)
            val node = ImageNode(materialLoader = materialLoader, bitmap = bitmap)
            node.destroy()
            val pending = queue.size

            node.destroy()

            assertEquals("the texture must be enqueued once", pending, queue.size)
            bitmap.recycle()
        }
    }

    @Test
    fun child_destroyed_by_its_parent_then_by_its_own_slot_is_destroyed_once() {
        // The demo's order: the re-keyed parent destroys its children, then the child's own
        // composition slot disposes it again.
        harness.runOnMain {
            val parent = CubeNode(engine)
            val child = CubeNode(engine)
            parent.addChildNode(child)
            parent.destroy()
            assertTrue("the parent destroys its children", child.isDestroyed)
            val generation = engine.renderableGeneration()

            child.destroy()

            assertEquals(generation, engine.renderableGeneration())
        }
    }
}
