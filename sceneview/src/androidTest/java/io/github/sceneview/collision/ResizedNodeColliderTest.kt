package io.github.sceneview.collision

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed proof for issue #3194: a node resized after construction must pick at the size it
 * renders at.
 *
 * `updateGeometry` pushes the new AABB to Filament, which is what culling and rendering use, but
 * `collisionShape` was separate state that kept the box the node was built with. So a `CubeNode`
 * whose `size` was assigned after construction rendered large and hit-tested small.
 *
 * Every assertion here reads the *real* collider back out of a real node driven by a real Filament
 * `Engine` — the JVM `RenderableNodeCollisionShapeContractTest` can only pin the wiring, because
 * `Node` cannot be constructed off-device at all (`src/test/.../node/UNTESTABLE.md`).
 *
 * Filament JNI is main-thread only, so every body runs inside `runOnMainSync`.
 */
@RunWith(AndroidJUnit4::class)
class ResizedNodeColliderTest {

    private lateinit var engine: com.google.android.filament.Engine

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
            engine.safeDestroy()
        }
    }

    /** `io.github.sceneview.collision.Box.getSize()` is the FULL extent, not the half extent. */
    private fun Box.fullSize() = getSize()

    // ── The reported defect ──────────────────────────────────────────────────

    @Test
    fun resizingACube_movesItsCollider() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))

            val built = node.collisionShape as Box
            assertEquals("built collider follows the constructor size", 1.0f, built.fullSize().x, 1e-3f)

            node.updateGeometry(size = Size(3.0f))

            val resized = node.collisionShape as Box
            // Before the fix this was still 1.0 — the cube rendered at 3 and picked at 1.
            assertEquals("collider must follow the resize", 3.0f, resized.fullSize().x, 1e-3f)
            assertEquals(3.0f, resized.fullSize().y, 1e-3f)
            assertEquals(3.0f, resized.fullSize().z, 1e-3f)

            node.destroy()
        }
    }

    @Test
    fun resizingACube_movesItsColliderCenterToo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))

            node.updateGeometry(center = Position(5.0f, 0.0f, 0.0f), size = Size(1.0f))

            val moved = node.collisionShape as Box
            assertEquals("collider must follow a recentred geometry", 5.0f, moved.getCenter().x, 1e-3f)

            node.destroy()
        }
    }

    @Test
    fun resizingASphere_movesItsCollider() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = SphereNode(engine, radius = 0.5f, center = Position(0.0f))

            node.updateGeometry(radius = 2.0f)

            val resized = node.collisionShape as Box
            assertEquals(
                "the fix must reach every shape node, not just CubeNode",
                4.0f, resized.fullSize().x, 1e-2f
            )

            node.destroy()
        }
    }

    // ── The opt-out that keeps the fix non-breaking ──────────────────────────

    @Test
    fun anAppAssignedCollisionShapeSurvivesAResize() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))

            val custom = Box(Vector3(10.0f, 10.0f, 10.0f), Vector3.zero())
            node.collisionShape = custom

            node.updateGeometry(size = Size(3.0f))

            val kept = node.collisionShape as Box
            assertEquals(
                "assigning collisionShape is a documented capability — the automatic refresh " +
                    "must not overwrite it",
                10.0f, kept.fullSize().x, 1e-3f
            )

            node.destroy()
        }
    }

    @Test
    fun aDeliberatelyClearedCollisionShapeIsNotResurrected() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))
            assertNotNull("a freshly built node has a derived collider", node.collisionShape)

            // `null` means "this node has no collider" — a blind refresh would make an
            // intentionally unpickable node pickable again.
            node.collisionShape = null
            assertNull(node.collisionShape)

            node.updateGeometry(size = Size(3.0f))

            assertNull("clearing collisionShape must survive a geometry change", node.collisionShape)

            node.destroy()
        }
    }

    @Test
    fun updateCollisionShape_optsBackInToTheAutomaticRefresh() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))

            node.collisionShape = Box(Vector3(10.0f, 10.0f, 10.0f), Vector3.zero())
            node.updateGeometry(size = Size(3.0f))
            assertEquals(10.0f, (node.collisionShape as Box).fullSize().x, 1e-3f)

            // Explicitly asking for the derived shape re-arms the automatic refresh.
            node.updateCollisionShape()
            assertEquals(3.0f, (node.collisionShape as Box).fullSize().x, 1e-3f)

            node.updateGeometry(size = Size(5.0f))
            assertEquals(
                "updateCollisionShape() must re-arm the refresh, not just apply once",
                5.0f, (node.collisionShape as Box).fullSize().x, 1e-3f
            )

            node.destroy()
        }
    }

    // ── The user-visible symptom ─────────────────────────────────────────────

    @Test
    fun aRayThroughTheGrownCubeCorner_hitsIt() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = CubeNode(engine, size = Size(1.0f), center = Position(0.0f))
            node.updateGeometry(size = Size(4.0f))

            // A point well outside the ORIGINAL 1x1x1 box but inside the new 4x4x4 one.
            val corner = Vector3(1.5f, 1.5f, 1.5f)
            val ray = Ray(Vector3(1.5f, 1.5f, 20.0f), Vector3(0.0f, 0.0f, -1.0f))

            val hit = RayHit()
            val shape = node.collisionShape as Box
            assertTrue(
                "the ray through $corner must hit the resized cube — this is the reported " +
                    "repro: the ray reported no hit while the cube visibly rendered there",
                shape.rayIntersection(ray, hit)
            )

            node.destroy()
        }
    }
}
