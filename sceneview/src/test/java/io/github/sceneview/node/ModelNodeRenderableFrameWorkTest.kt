package io.github.sceneview.node

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.RenderableManager
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.SceneFrameDispatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * A write on one of a model's renderables brings the model back for a frame (#4451).
 *
 * `ModelNode` watches the bounding boxes of its renderables — Filament aborts on an empty one
 * unless culling and shadows are off (#2311, #4344) — and stops watching a static renderable once
 * it has seen it valid. A geometry or bounding-box write puts the renderable back under watch.
 * While a model was ticked on every frame the next frame did the re-scan; a model at rest has no
 * next frame of its own, so the write has to ask for it — on the model, not on the glTF sub-node
 * the write went through.
 *
 * What is real here: the `ModelNode`, its `ModelNode.RenderableNode` (the model's one entity),
 * the latch and the sanitize pass that reads and writes it, and the frame plan. What is not: the
 * native `RenderableManager`, replaced by [Renderables], which answers the four questions the
 * sanitize pass asks and records what it is told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    instrumentedPackages = ["com.google.android.filament"],
    shadows = [
        ModelNodeRenderableFrameWorkTest.OneEntityInstance::class,
        ModelNodeRenderableFrameWorkTest.Renderables::class
    ]
)
class ModelNodeRenderableFrameWorkTest {

    private lateinit var engine: Engine
    private val dispatch = SceneFrameDispatch()
    private var frameTimeNanos = System.nanoTime() + 1_000_000_000L

    @Before
    fun setUp() {
        engine = FilamentWithoutJni.engine()
        Renderables.reset()
    }

    private fun model() = ModelNode(FilamentWithoutJni.modelInstance(engine))

    private fun frame(roots: List<Node>) {
        frameTimeNanos += 16_000_000L
        dispatch.dispatch(roots, frameTimeNanos)
    }

    private fun steppedNodes() = dispatch.steps().map { it.second }

    /** A static model, its one renderable seen valid when it was built, left alone: no step. */
    private fun modelAtRest(roots: (ModelNode) -> List<Node> = { listOf(it) }): ModelNode {
        val model = model()
        assertEquals(1, model.renderableNodes.size)
        assertFalse(model.hasOwnFrameWork)
        frame(roots(model))
        frame(roots(model))
        // Scanned once, at construction, and never on a frame.
        assertEquals(1, Renderables.boundingBoxReads)
        assertEquals(0, dispatch.stepCount)
        return model
    }

    @Test
    fun `a model whose renderable is not seen valid yet keeps its step, then rests`() {
        Renderables.halfExtent = EMPTY
        val model = model()
        val roots = listOf<Node>(model)

        repeat(3) { frame(roots) }
        assertEquals(listOf<Node>(model), steppedNodes())
        // Once when it was built, then once a frame.
        assertEquals(4, Renderables.boundingBoxReads)
        assertEquals(listOf(false), Renderables.cullingWrites)

        Renderables.halfExtent = VALID
        frame(roots)
        assertEquals(listOf(false, true), Renderables.cullingWrites)
        frame(roots)
        assertEquals(0, dispatch.stepCount)
        assertEquals(5, Renderables.boundingBoxReads)
    }

    @Test
    fun `a bounding box written on a renderable of a model at rest is scanned on the next frame`() {
        val model = modelAtRest()
        val roots = listOf<Node>(model)

        // Degenerate: what Filament aborts on while culling is on.
        model.renderableNodes[0].axisAlignedBoundingBox = Box(0f, 0f, 0f, 0f, 0f, 0f)
        assertTrue(model.hasOwnFrameWork)
        frame(roots)

        // The model was given the frame — the model, not the sub-node — and its guard ran.
        assertEquals(listOf<Node>(model), steppedNodes())
        assertEquals(2, Renderables.boundingBoxReads)
        assertEquals(listOf(false), Renderables.cullingWrites)

        // Still empty: still watched, frame after frame.
        frame(roots)
        assertEquals(3, Renderables.boundingBoxReads)
        assertEquals(1, dispatch.stepCount)

        // Valid again: culling comes back, and the model goes back to rest.
        model.renderableNodes[0].axisAlignedBoundingBox = Box(0f, 0f, 0f, 1f, 1f, 1f)
        frame(roots)
        assertEquals(listOf(false, true), Renderables.cullingWrites)
        frame(roots)
        frame(roots)
        assertEquals(0, dispatch.stepCount)
        assertEquals(4, Renderables.boundingBoxReads)
    }

    @Test
    fun `a geometry applied to a renderable of a model at rest is scanned on the next frame`() {
        val model = modelAtRest()
        val roots = listOf<Node>(model)

        // What `Geometry.update` calls on every renderable its buffers are bound to (#4344).
        Renderables.halfExtent = EMPTY
        model.renderableNodes[0].onGeometryApplied()
        frame(roots)

        assertEquals(listOf<Node>(model), steppedNodes())
        assertEquals(listOf(false), Renderables.cullingWrites)
    }

    @Test
    fun `the write wakes the model through a parent too, and gives the sub-node no step`() {
        val root = Node(engine, 100)
        val model = modelAtRest { model -> root.addChildNode(model); listOf(root) }
        val roots = listOf(root)

        model.renderableNodes[0].axisAlignedBoundingBox = Box(0f, 0f, 0f, 2f, 2f, 2f)
        assertFalse(model.renderableNodes[0].hasOwnFrameWork)
        frame(roots)
        assertEquals(listOf<Node>(model), steppedNodes())

        frame(roots)
        assertEquals(0, dispatch.stepCount)
    }

    @Test
    fun `the model's work is all in onOwnFrame, and its onFrame override adds none`() {
        // A renderable that is never seen valid: the model has work on every frame.
        Renderables.halfExtent = EMPTY
        val model = model()
        assertTrue(model.hasOwnFrameWork)
        assertEquals(1, Renderables.boundingBoxReads)

        // Called in full, `onFrame` reaches the work once, through `Node.onFrame` — not a second
        // time from the override's own body.
        model.onFrame(frameTimeNanos)
        assertEquals(2, Renderables.boundingBoxReads)

        // The flag is all that stands between a frame and the work: with it down, `onFrame` —
        // what the frame plan no longer calls on a `ModelNode` — scans nothing and plays nothing.
        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)
        model.hasOwnFrameWork = false
        repeat(3) { model.onFrame(frameTimeNanos) }
        assertEquals(2, Renderables.boundingBoxReads)
        assertEquals(1, model.playingAnimations.size)

        model.onOwnFrame(frameTimeNanos)
        assertEquals(3, Renderables.boundingBoxReads)
        assertTrue(model.playingAnimations.isEmpty())
    }

    /** A glTF instance with one entity. */
    @Implements(FilamentInstance::class)
    class OneEntityInstance {
        @Implementation
        fun getEntities(): IntArray = intArrayOf(ENTITY)
    }

    /**
     * The renderable manager, as far as the sanitize pass goes: every entity is a renderable with
     * no morph target and one bounding box, [halfExtent].
     *
     * The parameters are the native methods' own: a shadow has to repeat the signature it stands
     * in for, used or not.
     */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    @Implements(RenderableManager::class)
    class Renderables {
        @Implementation
        fun hasComponent(entity: Int): Boolean = true

        @Implementation
        fun getInstance(entity: Int): Int = INSTANCE

        @Implementation
        fun getMorphTargetCount(instance: Int): Int = 0

        @Implementation
        fun getAxisAlignedBoundingBox(instance: Int, out: Box?): Box {
            boundingBoxReads++
            val box = out ?: Box()
            box.setHalfExtent(halfExtent[0], halfExtent[1], halfExtent[2])
            return box
        }

        @Implementation
        fun setAxisAlignedBoundingBox(instance: Int, aabb: Box) {
            halfExtent = aabb.halfExtent.copyOf()
        }

        @Implementation
        fun setCulling(instance: Int, enabled: Boolean) {
            cullingWrites += enabled
        }

        companion object {
            var halfExtent = VALID
            var boundingBoxReads = 0
            val cullingWrites = ArrayList<Boolean>()

            fun reset() {
                halfExtent = VALID
                boundingBoxReads = 0
                cullingWrites.clear()
            }
        }
    }

    private companion object {
        const val ENTITY = 7
        const val INSTANCE = 1
        val VALID = floatArrayOf(1f, 1f, 1f)
        val EMPTY = floatArrayOf(0f, 0f, 0f)
    }
}
