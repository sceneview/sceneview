package io.github.sceneview.node

import com.google.android.filament.Engine
import com.google.android.filament.gltfio.FilamentInstance
import io.github.sceneview.SceneFrameDispatch
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.overridesOnFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * When a scene hands a frame to a [ModelNode] (#4451).
 *
 * A model used to be called on every frame, in full, because its class overrode `onFrame` — and
 * with it every node of its glTF. It now declares its work: a step while an animation plays (or
 * while the model has a skin, or a bounding box still watched), none at rest.
 *
 * Real `ModelNode`s over a Filament whose native methods return zero (Robolectric): a model with
 * no renderable, no skin and animations of zero length. That is enough here — a zero-length,
 * non-looping animation ends on the first frame it is given, which is how these tests see that a
 * frame reached the model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class ModelNodeFrameWorkTest {

    private lateinit var engine: Engine
    private val dispatch = SceneFrameDispatch()

    /** Later than any `PlayingAnimation.startTime` taken during the test. */
    private var frameTimeNanos = System.nanoTime() + 1_000_000_000L

    @Before
    fun createEngineWithoutJni() {
        engine = FilamentWithoutJni.engine()
    }

    @After
    fun noSkin() {
        RiggedInstance.skinCount = 0
    }

    private fun modelInstance() = FilamentWithoutJni.modelInstance(engine)

    private fun model() = ModelNode(modelInstance())

    private fun frame(roots: List<Node>) {
        frameTimeNanos += 16_000_000L
        dispatch.dispatch(roots, frameTimeNanos)
    }

    /** An app's model type that overrides `onFrame`: still called on every frame, in full. */
    private class OverridingModel(modelInstance: ModelInstance) : ModelNode(modelInstance) {
        var frames = 0
        override fun onFrame(frameTimeNanos: Long) {
            super.onFrame(frameTimeNanos)
            frames++
        }
    }

    /** An app's model type that adds things, and leaves `onFrame` alone. */
    private class InheritingModel(modelInstance: ModelInstance) : ModelNode(modelInstance)

    @Test
    fun `a scene of static models hands a frame to no node`() {
        val root = Node(engine, 100)
        repeat(5) { root.addChildNode(model()) }
        val roots = listOf(root)

        frame(roots)
        frame(roots)

        assertEquals(0, dispatch.stepCount)
        // And nothing made the scene walk its tree again for the second frame.
        assertEquals(1, dispatch.buildCount)
    }

    @Test
    fun `an animation started on a model at rest gets the very next frame`() {
        val model = model()
        val roots = listOf<Node>(model)
        frame(roots)
        assertEquals(0, dispatch.stepCount)

        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)
        frame(roots)

        // The frame reached the animator: the zero-length animation wrote its pose and left.
        assertTrue(model.playingAnimations.isEmpty())
    }

    @Test
    fun `a model is a step while an animation plays, and stops being one when it ends`() {
        val model = model()
        val roots = listOf<Node>(model)

        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = true)
        repeat(3) {
            frame(roots)
            assertEquals(1, dispatch.stepCount)
        }

        model.stopAnimation(0)
        // The frame after the stop is the one on which the model finds nothing left to play.
        frame(roots)
        frame(roots)
        assertEquals(0, dispatch.stepCount)
    }

    @Test
    fun `a paused animation keeps the model's step`() {
        val model = model()
        val roots = listOf<Node>(model)
        model.playingAnimations[0] = ModelNode.PlayingAnimation(speed = 0f)

        repeat(3) { frame(roots) }

        assertEquals(1, dispatch.stepCount)
    }

    @Test
    fun `every way of adding to playingAnimations starts the model`() {
        val additions = listOf<(ModelNode) -> Unit>(
            { it.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false) },
            { it.playingAnimations.put(0, ModelNode.PlayingAnimation(loop = false)) },
            { it.playingAnimations.putAll(mapOf(0 to ModelNode.PlayingAnimation(loop = false))) },
            { it.playingAnimations.putIfAbsent(0, ModelNode.PlayingAnimation(loop = false)) },
            { it.playingAnimations.computeIfAbsent(0) { ModelNode.PlayingAnimation(loop = false) } },
            { it.playingAnimations.compute(0) { _, _ -> ModelNode.PlayingAnimation(loop = false) } },
            { model ->
                model.playingAnimations.merge(0, ModelNode.PlayingAnimation(loop = false)) { _, new -> new }
            },
            { it.playingAnimations = mutableMapOf(0 to ModelNode.PlayingAnimation(loop = false)) },
            { model ->
                // A map assigned empty, then filled through the property.
                model.playingAnimations = HashMap()
                model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)
            },
        )
        additions.forEachIndexed { index, add ->
            val model = model()
            val roots = listOf<Node>(model)
            frame(roots)
            add(model)
            assertEquals("addition $index", 1, model.playingAnimations.size)
            frame(roots)
            assertTrue("addition $index was not played", model.playingAnimations.isEmpty())
        }
    }

    @Test
    fun `every way of removing from playingAnimations lets the model rest`() {
        // Removals are not reported to the model, on purpose: a model with an entry is being
        // given frames, and finds the map empty at the end of the next one.
        val removals = listOf<(ModelNode) -> Unit>(
            { it.playingAnimations.remove(0) },
            { it.playingAnimations.clear() },
            { it.playingAnimations.keys.remove(0) },
            { it.playingAnimations.values.clear() },
            { it.playingAnimations.entries.removeIf { entry -> entry.key == 0 } },
            { model ->
                val entries = model.playingAnimations.entries.iterator()
                entries.next()
                entries.remove()
            },
            { model ->
                val keys = model.playingAnimations.keys.iterator()
                keys.next()
                keys.remove()
            },
        )
        removals.forEachIndexed { index, remove ->
            val model = model()
            val roots = listOf<Node>(model)
            model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = true)
            frame(roots)
            assertEquals("removal $index", 1, dispatch.stepCount)

            remove(model)
            assertTrue("removal $index", model.playingAnimations.isEmpty())
            frame(roots)
            frame(roots)
            assertEquals("removal $index left the model a step", 0, dispatch.stepCount)
        }
    }

    @Test
    fun `a map assigned to playingAnimations stays the model's storage`() {
        val model = model()
        val assigned = mutableMapOf<Int, ModelNode.PlayingAnimation>()
        model.playingAnimations = assigned

        model.playingAnimations[2] = ModelNode.PlayingAnimation()
        assertTrue(2 in assigned)
        assigned.remove(2)
        assertTrue(model.playingAnimations.isEmpty())

        // The view the node hands back is accepted back as it is.
        val view = model.playingAnimations
        model.playingAnimations = view
        assertSame(view, model.playingAnimations)
    }

    @Test
    fun `a callback set on a model fires on every frame, animation or not`() {
        val model = model()
        val roots = listOf<Node>(model)
        var calls = 0
        model.onFrame = { calls++ }

        frame(roots)
        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)
        frame(roots)
        frame(roots)
        assertEquals(3, calls)

        model.onFrame = null
        frame(roots)
        assertEquals(3, calls)
        assertEquals(0, dispatch.stepCount)
    }

    @Test
    fun `the model's work comes after its onFrame callback`() {
        val model = model()
        val roots = listOf<Node>(model)
        val seenByCallback = ArrayList<Int>()
        model.onFrame = { seenByCallback += model.playingAnimations.size }
        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)

        frame(roots)

        // Still playing when the callback ran, played and ended once it had returned: a callback
        // that poses the skeleton is followed by the bone-matrix update of the same frame.
        assertEquals(listOf(1), seenByCallback)
        assertTrue(model.playingAnimations.isEmpty())
    }

    @Test
    fun `a model under a parent is planned on its own, without its parent`() {
        val root = Node(engine, 100)
        val group = Node(engine, 101).also { root.addChildNode(it) }
        val moving = model().also { group.addChildNode(it) }
        group.addChildNode(model())
        val roots = listOf(root)

        moving.playingAnimations[0] = ModelNode.PlayingAnimation(loop = true)
        frame(roots)

        assertEquals(listOf<Node>(moving), dispatch.steps().map { it.second })
    }

    @Test
    fun `an app's model type that overrides onFrame is still called on every frame, in full`() {
        assertTrue(overridesOnFrame(OverridingModel::class.java))
        assertFalse(overridesOnFrame(InheritingModel::class.java))

        val model = OverridingModel(modelInstance())
        val roots = listOf<Node>(model)
        frame(roots)
        frame(roots)
        assertEquals(2, model.frames)

        // Its `super.onFrame` still reaches the model's own work.
        model.playingAnimations[0] = ModelNode.PlayingAnimation(loop = false)
        frame(roots)
        assertTrue(model.playingAnimations.isEmpty())
    }

    @Test
    @Config(shadows = [RiggedInstance::class])
    fun `a rigged model keeps its step at rest`() {
        RiggedInstance.skinCount = 1
        val model = model()
        val roots = listOf<Node>(model)

        repeat(3) { frame(roots) }

        // Its bone matrices are recomputed on every frame: a skeleton can be posed by writes
        // that nothing reports.
        assertEquals(1, dispatch.stepCount)
    }

    /** A [FilamentInstance] that reports [skinCount] skins. */
    @Implements(FilamentInstance::class)
    class RiggedInstance {
        @Implementation
        fun getSkinCount(): Int = skinCount

        companion object {
            var skinCount = 0
        }
    }
}
