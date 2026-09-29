package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.safeDestroy
import io.github.sceneview.sortTransformsIfUnsorted
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression test for the second TransformManager reindexing path (#4171): a
 * transaction commit, which gltfio's `Animator.applyAnimation` runs on every call.
 *
 * `commitLocalTransformTransaction()` re-sorts the whole packed array so that every child sits
 * after its parent, `swapNode()`-ing whatever is out of order. A destroy leaves it out of order:
 * the swap-remove moves the LAST entities — here the animated model's — into the freed slots, some
 * of them ahead of their parent. In the demo, leaving an HD scan for the Soldier did exactly that
 * (the scan is held until the Soldier is ready, so it is destroyed after the Soldier exists): the
 * Soldier's first animated frame re-sorted, its ModelNode's cached root handle pointed at one of
 * its own meshes, and the model rendered as nothing.
 *
 * Every case stages that order — animated model B created while static model A is alive, then A
 * destroyed — and asserts two things after the commit: the node's cached handle equals a fresh
 * `getInstance(entity)`, and a position written through the node lands on B's root entity.
 * Each case also asserts that the commit really moved B's root, so none can pass vacuously.
 */
@RunWith(AndroidJUnit4::class)
class AnimatorCommitReindexTest {

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

    /**
     * A (the static stand-in: never animated) then B (the Khronos Fox, skinned and animated),
     * B's root handle cached, then A destroyed. Returns B and its root's fresh handle right after
     * the destroy, before anything re-sorted.
     */
    private fun stageDestroyAfterAnimatedModel(): Pair<ModelNode, Int> {
        val loader = ModelLoader(engine, InstrumentationRegistry.getInstrumentation().context)
            .also { modelLoader = it }
        val staticModel = loader.createModel("khronos_fox.glb")
        val animated = ModelNode(
            modelInstance = loader.createModelInstance("khronos_fox.glb"),
            autoAnimate = false
        )
        animated.transformInstance // populate the cache, as a composed node does on attach
        loader.destroyModel(staticModel)
        val tm = engine.transformManager
        return animated to tm.getInstance(animated.entity)
    }

    private fun assertNodeDrivesItsRoot(node: ModelNode, rootAfterDestroy: Int) {
        val tm = engine.transformManager
        val fresh = tm.getInstance(node.entity)
        assertNotEquals(
            "instrument check: the commit must have re-sorted B's root to another slot, " +
                "otherwise this test proves nothing",
            rootAfterDestroy, fresh,
        )
        assertEquals(
            "the node's transformInstance must follow its root through the re-sort",
            fresh, node.transformInstance,
        )

        val pose = Position(x = 1.5f, y = -2f, z = 3.25f)
        node.position = pose
        val world = FloatArray(16).also { tm.getWorldTransform(tm.getInstance(node.entity), it) }
        val message = "a position written through the node must land on its root"
        assertEquals("$message (x)", pose.x, world[12], 1e-5f)
        assertEquals("$message (y)", pose.y, world[13], 1e-5f)
        assertEquals("$message (z)", pose.z, world[14], 1e-5f)
    }

    /** The demo's case: the next frame plays B's clip through ModelNode.onFrame. */
    @Test
    fun animatedFrameAfterDestroy_keepsRootHandle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (node, rootAfterDestroy) = stageDestroyAfterAnimatedModel()
            node.playAnimation(0)

            // One SceneView frame: the loop's top-of-frame sort, then the nodes' onFrame.
            engine.sortTransformsIfUnsorted()
            node.onFrame(System.nanoTime() + 300_000_000L)

            assertNodeDrivesItsRoot(node, rootAfterDestroy)
            node.destroy()
        }
    }

    /**
     * A clip playing at speed 0 never calls applyAnimation during the frame. The commit comes
     * later, from an app scrubbing the paused clip by hand — it must not reindex under the node.
     */
    @Test
    fun speedZeroFrameThenScrub_keepsRootHandle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (node, rootAfterDestroy) = stageDestroyAfterAnimatedModel()
            node.playAnimation(0, speed = 0f)

            engine.sortTransformsIfUnsorted()
            node.onFrame(System.nanoTime() + 300_000_000L)
            node.transformInstance // re-cache after the frame, as any read would
            node.modelInstance.animator.applyAnimation(0, 0.5f)

            assertNodeDrivesItsRoot(node, rootAfterDestroy)
            node.destroy()
        }
    }

    /**
     * An app calling the animator directly (scrub, cross-fade) right after the destroy, before the
     * frame loop had a chance to sort: that commit is the one that re-sorts.
     */
    @Test
    fun directApplyAnimationBeforeAnyFrame_keepsRootHandle() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val (node, rootAfterDestroy) = stageDestroyAfterAnimatedModel()

            node.transformInstance // a read between the destroy and the commit
            node.modelInstance.animator.applyAnimation(0, 0.5f)

            assertNodeDrivesItsRoot(node, rootAfterDestroy)
            node.destroy()
        }
    }
}
