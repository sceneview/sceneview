package io.github.sceneview.gesture

import com.google.android.filament.Box
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.components.RenderableComponent
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.node.FilamentWithoutJni
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

    // Reflection also runs against the original private function for the red regression run.
    @Suppress("UNCHECKED_CAST")
    private fun bounds(node: Node): Pair<Float3, Float3> =
        Class.forName("io.github.sceneview.gesture.NodeEditingOverlayKt")
            .getDeclaredMethod("localBounds", Node::class.java)
            .apply { isAccessible = true }
            .invoke(null, node) as Pair<Float3, Float3>

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
}
