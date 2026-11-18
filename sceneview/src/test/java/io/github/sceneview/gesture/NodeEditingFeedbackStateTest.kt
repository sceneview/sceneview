package io.github.sceneview.gesture

import io.github.sceneview.math.Rotation
import io.github.sceneview.node.FilamentWithoutJni
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** State coverage for the refresh hook called by the overlay's projection loop. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingFeedbackStateTest {
    private lateinit var node: Node
    private lateinit var feedback: NodeEditingFeedbackState

    @Before
    fun setUp() {
        node = Node(FilamentWithoutJni.engine())
        feedback = NodeEditingFeedbackState(node, scaleBaseline = 1f)
    }

    @Test
    fun `refresh follows an application rotation at rest`() {
        assertEquals(0f, feedback.yawDegrees, 0.01f)

        node.rotation = Rotation(y = 90f)
        // Invoke the same refresh hook as the overlay's frame loop.
        feedback.refreshRotationFromNode()

        assertEquals(90f, feedback.yawDegrees, 0.01f)
        assertEquals(0f, feedback.rotationDeltaDegrees, 0f)
    }

    @Test
    fun `refreshing the absolute heading preserves the active gesture delta`() {
        feedback.onEditingBegin(node, NodeEditingKind.Rotate)
        node.rotation = Rotation(y = 3f)
        feedback.onRotationEdited(node, 3f)
        node.rotation = Rotation(y = -120f)

        feedback.refreshRotationFromNode()

        assertEquals(-120f, feedback.yawDegrees, 0.01f)
        assertEquals(3f, feedback.rotationDeltaDegrees, 0f)
        assertEquals("+3°", rotationBadgeText(feedback.rotationDeltaDegrees))
    }

    @Test
    fun `a new twist restarts the delta at zero`() {
        feedback.onEditingBegin(node, NodeEditingKind.Rotate)
        feedback.onRotationEdited(node, -22f)
        feedback.onEditingEnd(node, NodeEditingKind.Rotate)

        feedback.onEditingBegin(node, NodeEditingKind.Rotate)

        assertEquals(0f, feedback.rotationDeltaDegrees, 0f)
    }
}
