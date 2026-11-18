package io.github.sceneview.gesture

import com.google.android.filament.Box
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.model.RestPoseBounds
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the bounds actually used by the overlay, without a native renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingOverlayBoundsTest {
    private lateinit var engine: Engine

    @Before
    fun setUp() {
        engine = FilamentWithoutJni.engine()
    }

    private fun bounds(node: Node): Pair<Float3, Float3> = node.localBounds(ContentBoundsScratch())

    private fun content(center: Float3, halfExtent: Float3) =
        object : Node(engine), RenderableComponent {
            override var axisAlignedBoundingBox = Box(
                center.x, center.y, center.z, halfExtent.x, halfExtent.y, halfExtent.z,
            )
        }.apply {
            // There is no native TransformManager to supply the initial identity matrix.
            transform = Transform()
        }

    private fun assertVector(expected: Float3, actual: Float3) {
        assertEquals("x", expected.x, actual.x, 1e-4f)
        assertEquals("y", expected.y, actual.y, 1e-4f)
        assertEquals("z", expected.z, actual.z, 1e-4f)
    }

    @Test
    fun `bare pivot uses content bounds through a translated and scaled intermediate node`() {
        val pivot = Node(engine)
        val group = Node(engine).apply {
            position = Position(1f, 2f, -1f)
            scale = Scale(2f)
        }
        group.addChildNode(content(Float3(0f, 0.3f, 0f), Float3(0.3f, 0.1f, 0.2f)))
        pivot.addChildNode(group)

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(1f, 2.6f, -1f), center)
        assertVector(Float3(0.6f, 0.2f, 0.4f), halfExtent)
        assertEquals("ring is at the content base", 2.4f, center.y - halfExtent.y, 1e-4f)
    }

    @Test
    fun `empty siblings do not contribute invented quarter metre boxes`() {
        val pivot = Node(engine)
        pivot.addChildNode(Node(engine).apply { position = Position(0f, -10f, 0f) })
        pivot.addChildNode(content(Float3(0f, 0.5f, 0f), Float3(0.2f, 0.5f, 0.1f)))

        val (center, halfExtent) = bounds(pivot)

        assertEquals(0f, center.y - halfExtent.y, 1e-4f)
    }

    @Test
    fun `descendant corners account for rotation and reflected non-uniform scale`() {
        val pivot = Node(engine)
        pivot.addChildNode(content(Float3(0f), Float3(0.3f, 0.1f, 0.2f)).apply {
            position = Position(1f, 2f, -1f)
            rotation = Rotation(y = 90f)
            scale = Scale(2f, 3f, -4f)
        })

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(1f, 2f, -1f), center)
        assertVector(Float3(0.8f, 0.3f, 0.6f), halfExtent)
    }

    @Test
    fun `cancelling nested rotations keep the content base at zero`() {
        val pivot = Node(engine)
        val group = Node(engine).apply { rotation = Rotation(z = 45f) }
        group.addChildNode(content(Float3(0f, 1f, 0f), Float3(1f)).apply {
            rotation = Rotation(z = -45f)
        })
        pivot.addChildNode(group)

        val (center, halfExtent) = bounds(pivot)

        assertEquals("ring is at the content base", 0f, center.y - halfExtent.y, 1e-4f)
        assertVector(Float3(0f, 1f, 0f), center)
        assertVector(Float3(1f), halfExtent)
    }

    @Test
    fun `truly empty nodes keep the fallback`() {
        val (center, halfExtent) = bounds(Node(engine))

        assertVector(Float3(0f), center)
        assertVector(Float3(0.25f), halfExtent)
    }

    @Test
    fun `a renderable keeps its own non-empty bounds`() {
        val node = content(Float3(1f, 2f, 3f), Float3(0.1f, 0.2f, 0.3f))
        node.addChildNode(content(Float3(0f), Float3(10f)))

        val (center, halfExtent) = bounds(node)

        assertVector(Float3(1f, 2f, 3f), center)
        assertVector(Float3(0.1f, 0.2f, 0.3f), halfExtent)
    }

    @Test
    fun `a single model node gives exactly its own bounds`() {
        val model = modelNode(minMax = floatArrayOf(-0.4f, 0f, -0.3f, 0.2f, 1.6f, 0.5f))
        model.addChildNode(content(Float3(0f), Float3(10f)))

        val (center, halfExtent) = bounds(model)

        // Exact, not within a tolerance: the box is returned as read, never re-derived.
        assertEquals(model.center, center)
        assertEquals(model.halfExtent, halfExtent)
        assertVector(Float3(-0.1f, 0.8f, 0.1f), center)
        assertVector(Float3(0.3f, 0.8f, 0.4f), halfExtent)
    }

    @Test
    fun `a model node under a bare pivot contributes its bounds`() {
        val pivot = Node(engine)
        pivot.addChildNode(
            modelNode(minMax = floatArrayOf(-0.5f, 0f, -0.5f, 0.5f, 2f, 0.5f)).apply {
                position = Position(0f, 1f, 0f)
            },
        )

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(0f, 2f, 0f), center)
        assertVector(Float3(0.5f, 1f, 0.5f), halfExtent)
    }

    @Test
    fun `hidden descendants do not widen the bounds`() {
        val pivot = Node(engine)
        // A variant switcher: one shown variant, a hidden one, and a hidden nested group.
        pivot.addChildNode(content(Float3(0f, 0.5f, 0f), Float3(0.2f, 0.5f, 0.2f)))
        pivot.addChildNode(content(Float3(0f, 5f, 0f), Float3(5f)).apply { isVisible = false })
        pivot.addChildNode(
            Node(engine).apply {
                transform = Transform()
                isVisible = false
                addChildNode(content(Float3(0f, -3f, 0f), Float3(3f)))
            },
        )

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(0f, 0.5f, 0f), center)
        assertVector(Float3(0.2f, 0.5f, 0.2f), halfExtent)
    }

    @Test
    fun `a pivot whose only content is hidden keeps the fallback`() {
        val pivot = Node(engine)
        pivot.addChildNode(content(Float3(0f, 5f, 0f), Float3(5f)).apply { isVisible = false })

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(0f), center)
        assertVector(Float3(0.25f), halfExtent)
    }

    @Test
    fun `a model node with an empty rest-pose box keeps the placeholder without walking its sub-nodes`() {
        val model = modelNode(minMax = FloatArray(6))
        val subNode = countingContent(Float3(0f, 3f, 0f), Float3(2f))
        model.addChildNode(subNode)

        val (center, halfExtent) = bounds(model)

        assertVector(Float3(0f), center)
        assertVector(Float3(0.25f), halfExtent)
        assertEquals("glTF sub-node boxes read", 0, subNode.boxReads)
    }

    @Test
    fun `an empty model node under a pivot is not walked and does not widen the bounds`() {
        val pivot = Node(engine)
        val model = modelNode(minMax = FloatArray(6))
        val subNode = countingContent(Float3(0f, -5f, 0f), Float3(5f))
        model.addChildNode(subNode)
        pivot.addChildNode(model)
        pivot.addChildNode(content(Float3(0f, 0.5f, 0f), Float3(0.2f, 0.5f, 0.2f)))

        val (center, halfExtent) = bounds(pivot)

        assertVector(Float3(0f, 0.5f, 0f), center)
        assertVector(Float3(0.2f, 0.5f, 0.2f), halfExtent)
        assertEquals("glTF sub-node boxes read", 0, subNode.boxReads)
    }

    /** A renderable that counts how often the overlay reads its box. */
    private inner class CountingContent(center: Float3, halfExtent: Float3) :
        Node(engine), RenderableComponent {
        var boxReads = 0
        private val box = Box(center.x, center.y, center.z, halfExtent.x, halfExtent.y, halfExtent.z)
        override var axisAlignedBoundingBox: Box
            get() = box.also { boxReads++ }
            set(_) = Unit
    }

    private fun countingContent(center: Float3, halfExtent: Float3) =
        CountingContent(center, halfExtent).apply { transform = Transform() }

    /** A real [ModelNode] whose rest-pose box is [minMax] (`[minX, minY, minZ, maxX, maxY, maxZ]`). */
    private fun modelNode(minMax: FloatArray): ModelNode =
        ModelNode(FilamentWithoutJni.modelInstance(engine)).apply {
            RestPoseBounds.record(model, minMax)
            transform = Transform()
        }
}
