package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.rotation
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The orientation and the render-on-demand behaviour behind `cameraPositionProvider` on
 * [BillboardNode], [TextNode] and [ViewNode] (#4387).
 *
 * The three nodes share one [CameraFacing], so what is pinned here is pinned for all of them:
 * - **what facing means** — a full look-at, front (`+Z`) at the camera position, top toward world
 *   `+Y`: yaw and pitch, no roll;
 * - **when it writes** — only when the camera, the node or the parent moved, because every write
 *   is a request for a frame;
 * - **that it settles** — one application ends the activity, or the scene never parks.
 *
 * A real `Node` creates Filament entities and cannot exist on the JVM, so the behaviour runs
 * against [FakeTarget].
 */
class CameraFacingTest {

    // ── What "facing" means ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the front of the quad points at the camera`() {
        val node = Position(0f, 1f, -2f)
        for (camera in cameras) {
            val basis = basisOf(node, camera)
            assertVecEquals(
                "front (+Z) for a camera at $camera",
                normalized(camera - node),
                basis.front
            )
        }
    }

    @Test
    fun `the quad never rolls - its horizontal edge stays level`() {
        // Roll is the one rotation a full look-at with a world +Y up vector must not produce: the
        // quad's local +X stays in the horizontal plane wherever the camera goes, so text on it
        // stays level with the horizon.
        val node = Position(0f, 1f, -2f)
        for (camera in cameras) {
            val basis = basisOf(node, camera)
            assertEquals("right.y for a camera at $camera", 0.0, basis.right.y.toDouble(), EPS)
            assertTrue("top must lean toward world +Y for a camera at $camera", basis.top.y > 0f)
        }
    }

    @Test
    fun `the picture is not mirrored - right stays on the viewer's right`() {
        // Seen from the camera, the quad's local +X has to be on the right. With the camera on
        // +Z looking toward -Z, the viewer's right is world +X.
        val basis = basisOf(node = Position(0f, 0f, 0f), camera = Position(0f, 0f, 5f))
        assertVecEquals("right", Float3(1f, 0f, 0f), basis.right)
        assertVecEquals("top", Float3(0f, 1f, 0f), basis.top)
        assertVecEquals("front", Float3(0f, 0f, 1f), basis.front)
    }

    @Test
    fun `a camera above the node pitches the quad up toward it`() {
        // The difference with a yaw-only billboard: the front tilts by the elevation of the camera.
        // 45° up and straight ahead → front = (0, sin 45°, cos 45°).
        val basis = basisOf(node = Position(0f, 0f, 0f), camera = Position(0f, 3f, 3f))
        val h = sqrt(0.5f)
        assertVecEquals("front", Float3(0f, h, h), basis.front)
        assertVecEquals("top", Float3(0f, h, -h), basis.top)
    }

    @Test
    fun `a camera level with the node gives a pure yaw`() {
        // The documented way to get an upright sign: report the camera at the node's height.
        val node = Position(1f, 2f, 3f)
        val camera = Position(4f, 9f, -1f)
        val basis = basisOf(node, camera.copy(y = node.y))
        assertVecEquals("top stays exactly vertical", Float3(0f, 1f, 0f), basis.top)
        assertEquals("front stays horizontal", 0.0, basis.front.y.toDouble(), EPS)
    }

    @Test
    fun `a position offset like the camera from its target keeps every quad parallel to the screen`() {
        // The documented way to keep an off-axis card a rectangle: report
        // `node + (camera - target)` instead of the camera. Every quad then gets the orientation
        // the one sitting on the target has, wherever it is.
        val target = Position(0f, 0.5f, 0f)
        for (camera in cameras) {
            val onAxis = basisOf(target, camera)
            for (node in listOf(Position(0.8f, 0.9f, 0.1f), Position(-1.5f, 0.2f, 2f))) {
                val basis = basisOf(node, node + (camera - target))
                assertVecEquals("front, node $node, camera $camera", onAxis.front, basis.front)
                assertVecEquals("top, node $node, camera $camera", onAxis.top, basis.top)
                assertVecEquals("right, node $node, camera $camera", onAxis.right, basis.right)
            }
        }
    }

    @Test
    fun `the orientation is a unit quaternion`() {
        val node = Position(0f, 1f, -2f)
        for (camera in cameras) {
            val q = cameraFacingQuaternion(node, camera)!!
            val length = sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w)
            assertEquals("for a camera at $camera", 1.0, length.toDouble(), EPS)
        }
    }

    // ── Where there is nothing to face ───────────────────────────────────────────────────────────

    @Test
    fun `a camera sitting on the node has no direction to face`() {
        assertNull(cameraFacingQuaternion(Position(1f, 2f, 3f), Position(1f, 2f, 3f)))
    }

    @Test
    fun `a NaN position is rejected instead of producing a NaN transform`() {
        val node = Position(0f, 0f, 0f)
        assertNull(cameraFacingQuaternion(node, Position(Float.NaN, 0f, 5f)))
        assertNull(cameraFacingQuaternion(node, Position(0f, Float.NaN, 5f)))
        assertNull(cameraFacingQuaternion(node, Position(0f, 0f, Float.NaN)))
        assertNull(cameraFacingQuaternion(Position(Float.NaN, 0f, 0f), Position(0f, 0f, 5f)))
    }

    @Test
    fun `a camera straight above or below still gets a finite orientation`() {
        // The look direction is then parallel to the world +Y up vector, and the plain look-at
        // builds its basis from a cross product of two parallel vectors: every component NaN, and
        // the node disappears. This is the top-down view of a map label.
        val node = Position(0f, 0f, 0f)

        val above = basisOf(node, camera = Position(0f, 4f, 0f))
        assertVecEquals("front, camera above", Float3(0f, 1f, 0f), above.front)
        assertVecEquals("top, camera above", Float3(0f, 0f, -1f), above.top)

        val below = basisOf(node, camera = Position(0f, -4f, 0f))
        assertVecEquals("front, camera below", Float3(0f, -1f, 0f), below.front)
        assertVecEquals("top, camera below", Float3(0f, 0f, 1f), below.top)
    }

    @Test
    fun `passing over the top from the front does not flip the picture`() {
        // Just short of the pole, on the +Z side, the regular look-at already has the top of the
        // quad toward -Z. The pole itself must agree, or the label would snap as the camera
        // crosses it.
        val node = Position(0f, 0f, 0f)
        val nearPole = basisOf(node, camera = Position(0f, 4f, 0.05f))
        val atPole = basisOf(node, camera = Position(0f, 4f, 0f))
        assertTrue(dot(nearPole.top, atPole.top) > 0.99f)
        assertTrue(dot(nearPole.right, atPole.right) > 0.99f)
    }

    // ── When the orientation has to be rebuilt ───────────────────────────────────────────────────

    private val applied = AppliedCameraFacing(
        camera = Position(0f, 1f, 5f),
        node = Position(0f, 1f, 0f),
        parentQuaternion = Quaternion()
    )

    @Test
    fun `nothing moved - no write and no frame`() {
        assertFalse(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), Quaternion(), applied)
        )
    }

    @Test
    fun `a camera move rebuilds it`() {
        assertTrue(
            cameraFacingIsStale(Position(2f, 1f, 5f), Position(0f, 1f, 0f), Quaternion(), applied)
        )
    }

    @Test
    fun `a node move rebuilds it even with a still camera`() {
        // A label riding a moving object. Before #4387 only the camera was watched, and the label
        // kept the angle it had the last time the camera moved.
        assertTrue(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(3f, 1f, 0f), Quaternion(), applied)
        )
    }

    @Test
    fun `a parent turning underneath rebuilds it even though no position changed`() {
        // The orientation is stored relative to the parent. A card at the centre of a turntable
        // does not move and neither does the camera — and it is carried round all the same.
        val turned = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 5f)
        assertTrue(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), turned, applied)
        )
    }

    @Test
    fun `gaining or losing a parent rebuilds it`() {
        assertTrue(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), null, applied)
        )
        val root = AppliedCameraFacing(applied.camera, applied.node, parentQuaternion = null)
        assertTrue(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), Quaternion(), root)
        )
        assertFalse(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), null, root)
        )
    }

    @Test
    fun `the same parent orientation written as -q is not a change`() {
        // q and -q are one rotation, and a matrix decomposition is free to return either. Reading
        // that as movement would have a still scene re-orient on a coin flip.
        val q = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 40f)
        val settled = AppliedCameraFacing(applied.camera, applied.node, parentQuaternion = q)
        val negated = Quaternion(-q.x, -q.y, -q.z, -q.w)
        assertFalse(
            cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), negated, settled)
        )
    }

    @Test
    fun `float noise is not movement`() {
        // Positions and orientations are re-derived from the world matrix; the last bits wobble.
        val noisyParent = Quaternion(1e-6f, -1e-6f, 1e-6f, 1f)
        assertFalse(
            cameraFacingIsStale(
                Position(1e-5f, 1f - 1e-5f, 5f + 1e-5f),
                Position(-1e-5f, 1f + 1e-5f, 1e-5f),
                noisyParent,
                applied
            )
        )
    }

    @Test
    fun `no provider never needs a frame, a first orientation always does`() {
        assertFalse(cameraFacingIsStale(null, Position(0f, 1f, 0f), null, applied = null))
        assertFalse(cameraFacingIsStale(null, Position(0f, 1f, 0f), null, applied))
        assertTrue(cameraFacingIsStale(Position(0f, 1f, 5f), Position(0f, 1f, 0f), null, null))
    }

    // ── The behaviour the nodes share ────────────────────────────────────────────────────────────

    @Test
    fun `a node built with a provider faces the camera on its first frame, then goes quiet`() {
        val target = FakeTarget(worldPosition = Position(0f, 1f, 0f))
        val camera = Position(0f, 1f, 5f)
        val facing = CameraFacing(target, cameraPositionProvider = { camera })

        assertTrue("it has never been oriented", facing.isPending)
        facing.onFrame()

        assertEquals(1, target.writes)
        assertFalse("one application settles it — the term cannot latch on", facing.isPending)

        repeat(60) { facing.onFrame() }
        assertEquals("a still scene must not be written to again", 1, target.writes)
    }

    @Test
    fun `it follows the camera, one write per move`() {
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        var camera = Position(0f, 0f, 5f)
        val facing = CameraFacing(target, cameraPositionProvider = { camera })
        facing.onFrame()

        camera = Position(5f, 0f, 0f)
        assertTrue("the loop must stay awake until the node has turned", facing.isPending)
        facing.onFrame()

        assertEquals(2, target.writes)
        assertVecEquals("front", Float3(1f, 0f, 0f), basisOf(target.faced!!).front)
        assertFalse(facing.isPending)
    }

    @Test
    fun `a provider that hands back one mutated instance is still seen to move`() {
        // `{ cameraPosition }` over a Float3 the caller updates in place is a natural way to write
        // a provider. If the applied position were kept by reference it would always equal itself.
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        val camera = Position(0f, 0f, 5f)
        val facing = CameraFacing(target, cameraPositionProvider = { camera })
        facing.onFrame()

        camera.x = 5f
        camera.z = 0f

        assertTrue(facing.isPending)
        facing.onFrame()
        assertEquals(2, target.writes)
    }

    @Test
    fun `it follows a parent that turns under a still camera`() {
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f), parent = Quaternion())
        val facing = CameraFacing(target, cameraPositionProvider = { Position(0f, 0f, 5f) })
        facing.onFrame()

        target.parentWorldQuaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f)

        assertTrue(facing.isPending)
        facing.onFrame()
        assertEquals(2, target.writes)
        // World orientation, so the card faces the camera whatever the turntable is doing.
        assertVecEquals("front", Float3(0f, 0f, 1f), basisOf(target.faced!!).front)
    }

    @Test
    fun `without a provider the node is left alone`() {
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        val facing = CameraFacing(target)

        repeat(10) { facing.onFrame() }

        assertFalse(facing.isPending)
        assertEquals(0, target.writes)
        assertEquals(0, target.renderRequests)
    }

    @Test
    fun `setting a provider later wakes the loop and turns the node`() {
        // The provider used to be fixed at construction. A parked render loop polls nothing, so
        // switching the behaviour on has to ask for the frame that will apply it.
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        val facing = CameraFacing(target)

        facing.cameraPositionProvider = { Position(0f, 0f, 5f) }

        assertEquals(1, target.renderRequests)
        assertTrue(facing.isPending)
        facing.onFrame()
        assertEquals(1, target.writes)
    }

    @Test
    fun `clearing the provider stops the writes and leaves the node as it was turned`() {
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        var camera = Position(0f, 0f, 5f)
        val facing = CameraFacing(target, cameraPositionProvider = { camera })
        facing.onFrame()
        val turned = target.faced

        facing.cameraPositionProvider = null
        camera = Position(5f, 0f, 0f)
        repeat(10) { facing.onFrame() }

        assertFalse(facing.isPending)
        assertEquals(1, target.writes)
        assertEquals(turned, target.faced)
    }

    @Test
    fun `switching it back on re-orients even if the camera never moved`() {
        // In between, the orientation belonged to someone else — `ViewNode(rotation = …)` puts its
        // own back. The node must not conclude it is already facing a camera that did not move.
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        val provider = { Position(0f, 0f, 5f) }
        val facing = CameraFacing(target, cameraPositionProvider = provider)
        facing.onFrame()

        facing.cameraPositionProvider = null
        facing.cameraPositionProvider = provider

        assertTrue(facing.isPending)
        facing.onFrame()
        assertEquals(2, target.writes)
    }

    @Test
    fun `swapping one provider for another does not ask for a frame by itself`() {
        // The composables hand the node a new lambda on every recomposition. That must cost
        // nothing while the camera it reports has not moved.
        val target = FakeTarget(worldPosition = Position(0f, 0f, 0f))
        val facing = CameraFacing(target, cameraPositionProvider = { Position(0f, 0f, 5f) })
        facing.onFrame()

        repeat(10) { facing.cameraPositionProvider = { Position(0f, 0f, 5f) } }

        assertEquals(0, target.renderRequests)
        assertFalse(facing.isPending)
        facing.onFrame()
        assertEquals(1, target.writes)
    }

    @Test
    fun `a camera on the node settles without a write`() {
        // Nothing to face — and nothing to wait for either: the loop must not spin on it.
        val target = FakeTarget(worldPosition = Position(1f, 2f, 3f))
        val facing = CameraFacing(target, cameraPositionProvider = { Position(1f, 2f, 3f) })

        facing.onFrame()

        assertEquals(0, target.writes)
        assertFalse(facing.isPending)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────────

    private class FakeTarget(
        override var worldPosition: Position,
        parent: Quaternion? = null
    ) : CameraFacing.Target {
        override var parentWorldQuaternion: Quaternion? = parent
        var faced: Quaternion? = null
        var writes = 0
        var renderRequests = 0

        override fun faceWith(worldQuaternion: Quaternion) {
            faced = worldQuaternion
            writes++
        }

        override fun requestRender() {
            renderRequests++
        }
    }

    /** Camera positions all round a node at (0, 1, -2): each side, above, below, oblique. */
    private val cameras = listOf(
        Position(0f, 1f, 3f),
        Position(0f, 1f, -7f),
        Position(5f, 1f, -2f),
        Position(-5f, 1f, -2f),
        Position(2f, 4f, 1f),
        Position(-3f, -2f, -6f),
        Position(0.5f, 6f, -2.5f),
        Position(40f, 1.2f, 37f)
    )

    private class Basis(val right: Float3, val top: Float3, val front: Float3)

    private fun basisOf(node: Position, camera: Position): Basis {
        val q = cameraFacingQuaternion(node, camera)
        assertNotNull("no orientation for node $node, camera $camera", q)
        return basisOf(q!!)
    }

    /** The world-space directions of the quad's local +X, +Y and +Z under [q]. */
    private fun basisOf(q: Quaternion): Basis {
        val m = rotation(q)
        return Basis(
            right = Float3(m.x.x, m.x.y, m.x.z),
            top = Float3(m.y.x, m.y.y, m.y.z),
            front = Float3(m.z.x, m.z.y, m.z.z)
        )
    }

    private fun normalized(v: Float3): Float3 {
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Float3(v.x / length, v.y / length, v.z / length)
    }

    private fun dot(a: Float3, b: Float3) = a.x * b.x + a.y * b.y + a.z * b.z

    private fun assertVecEquals(what: String, expected: Float3, actual: Float3) {
        assertTrue(
            "$what: expected $expected but was $actual",
            abs(expected.x - actual.x) < EPS &&
                abs(expected.y - actual.y) < EPS &&
                abs(expected.z - actual.z) < EPS
        )
    }

    private companion object {
        const val EPS = 1e-4
    }
}
