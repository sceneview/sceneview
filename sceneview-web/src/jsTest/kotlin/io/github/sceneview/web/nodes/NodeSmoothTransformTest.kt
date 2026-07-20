package io.github.sceneview.web.nodes

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.web.bindings.Entity
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2024 P5b — `Node.smoothTransform` on the frame loop.
 *
 * The interpolation core (`updateSmoothTransform`, TRS variant) is already
 * unit-tested in `sceneview-core`; these tests cover the WIRING — the
 * decompose-once target cache, the per-frame step through [Node.onFrame],
 * convergence + auto-reset, cancellation, the destroyed guard, and the
 * render-gate invalidation hook (including inheritance on attach).
 */
class NodeSmoothTransformTest {

    private class FakeBackend : NodeBackend {
        override fun setLocalTransform(transform: Transform) = Unit
        override fun setParent(parent: NodeBackend?) = Unit
        override fun adoptChildEntity(child: Entity) = Unit
        override fun destroy() = Unit
    }

    private fun node(name: String? = null) = Node(FakeBackend()).also { it.name = name }

    private val eps = 1e-3f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")
    }

    /** 60 fps tick. */
    private val tick = 1f / 60f

    private fun Node.runFrames(count: Int) = repeat(count) { onFrame(tick) }

    // --- Stepping -----------------------------------------------------------

    @Test
    fun smoothStepMovesTowardTheTargetWithoutSnapping() {
        val node = node()
        node.smoothTransform = Transform(position = Position(10f, 0f, 0f))
        node.onFrame(tick)
        val x = node.position.x
        assertTrue(x > 0f, "one tick must move toward the target (x=$x)")
        assertTrue(x < 10f, "one tick must not snap to the target (x=$x)")
        assertNotNull(node.smoothTransform, "still animating — target must persist")
    }

    @Test
    fun smoothConvergesSnapsAndResetsToNull() {
        val node = node()
        node.smoothTransform = Transform(position = Position(2f, -1f, 3f))
        node.runFrames(600)
        assertClose(2f, node.position.x, "position.x")
        assertClose(-1f, node.position.y, "position.y")
        assertClose(3f, node.position.z, "position.z")
        assertNull(node.smoothTransform, "arrived — smoothTransform must reset to null")
    }

    @Test
    fun higherSpeedConvergesFaster() {
        val slow = node().also { it.smoothTransformSpeed = 2f }
        val fast = node().also { it.smoothTransformSpeed = 20f }
        val target = Transform(position = Position(1f, 0f, 0f))
        slow.smoothTransform = target
        fast.smoothTransform = target
        slow.runFrames(10)
        fast.runFrames(10)
        assertTrue(
            abs(1f - fast.position.x) < abs(1f - slow.position.x),
            "speed=20 (x=${fast.position.x}) must be closer than speed=2 (x=${slow.position.x})"
        )
    }

    @Test
    fun rotationSlerpsTowardTheTarget() {
        val node = node()
        val targetQuaternion = Rotation(0f, 90f, 0f).toQuaternion()
        node.smoothTransform = Transform(
            position = Position(),
            quaternion = targetQuaternion,
            scale = Scale(1f)
        )
        node.runFrames(600)
        // Compare via |dot| ≈ 1 — tolerant of the q/-q double cover.
        val q = node.quaternion
        val dot = q.x * targetQuaternion.x + q.y * targetQuaternion.y +
            q.z * targetQuaternion.z + q.w * targetQuaternion.w
        assertTrue(abs(dot) > 1f - 1e-3f, "quaternion must converge (|dot|=${abs(dot)})")
        assertNull(node.smoothTransform)
    }

    @Test
    fun animationInterpolatesInLocalSpaceUnderAParent() {
        val parent = node("group")
        parent.position = Position(5f, 0f, 0f)
        val child = node("content")
        parent.addChildNode(child)
        child.smoothTransform = Transform(position = Position(1f, 0f, 0f))
        child.runFrames(600)
        assertClose(1f, child.position.x, "child local x")
        assertClose(6f, child.worldPosition.x, "child world x = parent + local target")
    }

    // --- Cancellation and lifecycle ----------------------------------------

    @Test
    fun settingNullCancelsInPlace() {
        val node = node()
        node.smoothTransform = Transform(position = Position(10f, 0f, 0f))
        node.runFrames(3)
        val midway = node.position.x
        assertTrue(midway > 0f && midway < 10f, "must be mid-animation (x=$midway)")
        node.smoothTransform = null
        node.onFrame(tick)
        assertClose(midway, node.position.x, "cancelled — the node must hold its transform")
    }

    @Test
    fun destroyedNodeStopsAnimatingAndClearsTheTarget() {
        val node = node()
        node.smoothTransform = Transform(position = Position(10f, 0f, 0f))
        node.runFrames(2)
        node.destroy()
        node.onFrame(tick)
        assertNull(node.smoothTransform, "destroyed — the target must be released")
    }

    // --- Render-gate invalidation ------------------------------------------

    @Test
    fun onInvalidateFiresEachAnimatedFrameAndStopsAfterArrival() {
        val node = node()
        var invalidations = 0
        node.propagateInvalidate { invalidations++ }
        node.smoothTransform = Transform(position = Position(1f, 0f, 0f))
        val afterSet = invalidations
        assertTrue(afterSet >= 1, "setting a target must wake the render gate")
        node.runFrames(600)
        val duringAnimation = invalidations - afterSet
        assertTrue(duringAnimation >= 2, "every animated frame must invalidate")
        val settled = invalidations
        node.runFrames(5)
        assertEquals(settled, invalidations, "an idle node must not keep invalidating")
    }

    @Test
    fun childAttachedToAWiredParentInheritsTheInvalidateHook() {
        val parent = node("group")
        var invalidations = 0
        parent.propagateInvalidate { invalidations++ }
        val child = node("content")
        parent.addChildNode(child)
        child.smoothTransform = Transform(position = Position(1f, 0f, 0f))
        child.onFrame(tick)
        assertTrue(
            invalidations >= 1,
            "a child attached to an in-scene subtree must reach the render gate"
        )
    }

    @Test
    fun propagateWiresTheWholeExistingSubtree() {
        val root = node("root")
        val child = node("child")
        val grandChild = node("grandchild")
        root.addChildNode(child)
        child.addChildNode(grandChild)
        var invalidations = 0
        root.propagateInvalidate { invalidations++ }
        grandChild.smoothTransform = Transform(position = Position(1f, 0f, 0f))
        assertTrue(invalidations >= 1, "the set itself must wake the gate through the hook")
    }
}
