package io.github.sceneview.web

import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.web.nodes.Node
import io.github.sceneview.web.nodes.NodeBackend
import io.github.sceneview.web.xr.XRAnchor
import io.github.sceneview.web.xr.XRAnchorNode
import io.github.sceneview.web.xr.XRFrame
import io.github.sceneview.web.xr.XRReferenceSpace
import io.github.sceneview.web.xr.applyXRPoseMatrix
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2024 P5a — the XR-anchor → scene-graph bridge, proven with SYNTHETIC poses.
 *
 * WebXR is not headlessly drivable (no XR device in Karma/CI), so these tests
 * feed [XRAnchorNode.update] a fake [XRFrame] whose `getPose` returns a
 * hand-built pose matrix — the exact per-frame input a live ARCore/Quest
 * session produces — and assert the driven [Node]'s world transform tracks
 * it. Nodes use a recording fake backend (the [NodeTest] pattern): the graph
 * math under test is pure Kotlin; the engine write path
 * (`TransformManager.setTransform`) is the #2024-P1 in-browser probe's job.
 */
class XRAnchorNodeDriveTest {

    private class FakeBackend : NodeBackend {
        override fun setLocalTransform(transform: Transform) {}
        override fun setParent(parent: NodeBackend?) {}
        override fun adoptChildEntity(child: io.github.sceneview.web.bindings.Entity) {}
        override fun destroy() {}
    }

    private fun node(name: String? = null) = Node(FakeBackend()).also { it.name = name }

    private val eps = 1e-4f

    private fun assertClose(expected: Float, actual: Float, message: String) {
        assertTrue(abs(expected - actual) < eps, "$message: expected $expected, was $actual")
    }

    private fun assertPosition(node: Node, x: Float, y: Float, z: Float) {
        val p = node.worldPosition
        assertClose(x, p.x, "worldPosition.x")
        assertClose(y, p.y, "worldPosition.y")
        assertClose(z, p.z, "worldPosition.z")
    }

    // --- Synthetic WebXR objects -------------------------------------------

    private fun fakeAnchor(): XRAnchor {
        val a: dynamic = js("{}")
        a.anchorSpace = js("{}")
        a.delete = {}
        return a.unsafeCast<XRAnchor>()
    }

    private val referenceSpace = js("{}").unsafeCast<XRReferenceSpace>()

    /**
     * A frame whose `getPose` always resolves to [matrix] — the synthetic
     * stand-in for a live tracked anchor. `trackedAnchors` is absent, which
     * [XRAnchorNode.update] treats as "presence unknown, assume tracked".
     */
    private fun fakeFrame(matrix: dynamic): XRFrame {
        val transform: dynamic = js("{}")
        transform.matrix = matrix
        val pose: dynamic = js("{}")
        pose.transform = transform
        val frame: dynamic = js("{}")
        frame.getPose = { _: dynamic, _: dynamic -> pose }
        return frame.unsafeCast<XRFrame>()
    }

    /** Column-major identity + translation — what `XRRigidTransform.matrix` yields. */
    private fun translationMatrix(x: Float, y: Float, z: Float): dynamic {
        val m: dynamic = js("new Float32Array(16)")
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        m[12] = x; m[13] = y; m[14] = z
        return m
    }

    /** Column-major 30° yaw (rotation about +y) then translation. */
    private fun yaw30Matrix(x: Float, y: Float, z: Float): dynamic {
        val cos30 = 0.8660254f
        val sin30 = 0.5f
        val m: dynamic = js("new Float32Array(16)")
        // x column = (cos, 0, -sin), z column = (sin, 0, cos)
        m[0] = cos30; m[2] = -sin30
        m[5] = 1f
        m[8] = sin30; m[10] = cos30
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
        return m
    }

    // --- applyXRPoseMatrix (the conversion itself) --------------------------

    @Test
    fun poseMatrixTranslationLandsInWorldPosition() {
        val root = node("anchored")
        applyXRPoseMatrix(root, translationMatrix(2f, 3f, 4f))
        assertPosition(root, 2f, 3f, 4f)
    }

    @Test
    fun poseMatrixRotationLandsInWorldRotation() {
        val root = node("anchored")
        applyXRPoseMatrix(root, yaw30Matrix(2f, 3f, 4f))
        assertPosition(root, 2f, 3f, 4f)
        assertClose(30f, root.worldRotation.y, "worldRotation.y")
        // Scale must stay identity — pose matrices are rigid transforms.
        assertClose(1f, root.worldScale.x, "worldScale.x")
    }

    // --- drive() through update() with synthetic frames ---------------------

    @Test
    fun driveWritesPoseIntoRootNodeEachUpdate() {
        val root = node("anchored")
        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(root)

        assertTrue(anchorNode.update(fakeFrame(translationMatrix(1f, 0f, -2f)), referenceSpace))
        assertPosition(root, 1f, 0f, -2f)

        // The next frame's pose moves — the node tracks it.
        assertTrue(anchorNode.update(fakeFrame(translationMatrix(5f, 1f, -3f)), referenceSpace))
        assertPosition(root, 5f, 1f, -3f)
    }

    @Test
    fun childrenComposeUnderTheDrivenRoot() {
        val root = node("anchored")
        val child = node("content")
        root.addChildNode(child)
        child.position = Position(1f, 0f, 0f)

        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(root)
        anchorNode.update(fakeFrame(translationMatrix(2f, 3f, 4f)), referenceSpace)

        // world = anchorPose * local — the whole point of the bridge.
        val p = child.worldPosition
        assertClose(3f, p.x, "child.worldPosition.x")
        assertClose(3f, p.y, "child.worldPosition.y")
        assertClose(4f, p.z, "child.worldPosition.z")
    }

    @Test
    fun callbacksObserveTheAlreadyDrivenNode() {
        val root = node("anchored")
        val anchorNode = XRAnchorNode(fakeAnchor())
        var observed: Position? = null
        anchorNode.onAttached = { observed = root.worldPosition }
        anchorNode.drive(root)
        anchorNode.update(fakeFrame(translationMatrix(7f, 8f, 9f)), referenceSpace)
        assertClose(7f, observed!!.x, "onAttached must see the driven transform")
    }

    // --- Guards -------------------------------------------------------------

    @Test
    fun driveRejectsAParentedNode() {
        val parent = node("parent")
        val child = node("child")
        parent.addChildNode(child)
        val anchorNode = XRAnchorNode(fakeAnchor())
        assertFails("world-space poses must not double-compose") { anchorNode.drive(child) }
        assertNull(anchorNode.drivenNode)
    }

    @Test
    fun driveRejectsADestroyedNode() {
        val root = node("gone")
        root.destroy()
        val anchorNode = XRAnchorNode(fakeAnchor())
        assertFails { anchorNode.drive(root) }
    }

    @Test
    fun updateSkipsANodeReparentedAfterDrive() {
        val root = node("anchored")
        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(root)
        anchorNode.update(fakeFrame(translationMatrix(1f, 1f, 1f)), referenceSpace)

        val newParent = node("group")
        newParent.addChildNode(root)
        // No write, no throw — the stale pose stays, the graph is untouched.
        assertTrue(anchorNode.update(fakeFrame(translationMatrix(9f, 9f, 9f)), referenceSpace))
        assertPosition(root, 1f, 1f, 1f)

        // Back to root — driving resumes.
        newParent.removeChildNode(root)
        anchorNode.update(fakeFrame(translationMatrix(2f, 2f, 2f)), referenceSpace)
        assertPosition(root, 2f, 2f, 2f)
    }

    @Test
    fun updateAutoReleasesADestroyedNode() {
        val root = node("anchored")
        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(root)
        root.destroy()
        assertTrue(anchorNode.update(fakeFrame(translationMatrix(1f, 2f, 3f)), referenceSpace))
        assertNull(anchorNode.drivenNode, "a destroyed node must be auto-released")
    }

    @Test
    fun stopDrivingReleasesTheNodeAndKeepsItsLastPose() {
        val root = node("anchored")
        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(root)
        anchorNode.update(fakeFrame(translationMatrix(4f, 5f, 6f)), referenceSpace)
        anchorNode.stopDriving()
        assertNull(anchorNode.drivenNode)
        anchorNode.update(fakeFrame(translationMatrix(0f, 0f, 0f)), referenceSpace)
        assertPosition(root, 4f, 5f, 6f)
    }

    @Test
    fun driveReplacesThePreviouslyDrivenNode() {
        val first = node("first")
        val second = node("second")
        val anchorNode = XRAnchorNode(fakeAnchor())
        anchorNode.drive(first)
        anchorNode.drive(second)
        assertEquals(second, anchorNode.drivenNode)
        anchorNode.update(fakeFrame(translationMatrix(1f, 2f, 3f)), referenceSpace)
        assertPosition(second, 1f, 2f, 3f)
        assertPosition(first, 0f, 0f, 0f)
    }
}
