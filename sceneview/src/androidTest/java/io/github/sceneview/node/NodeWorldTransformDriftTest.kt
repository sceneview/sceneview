package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Engine-backed regression test for the world-space TRS cache added in PR #2280 (#2264),
 * the world-space half of the #2187 transform-drift fix family.
 *
 * The pure-math [NodeTransformDriftTest] pins the LOCAL TRS backing-field fix without a
 * Filament Engine. The WORLD-space cache (`_worldTransform / _worldPosition /
 * _worldQuaternion / _worldScale / _worldRotation`) cannot be tested in pure math: it is
 * populated from `TransformManager.getWorldTransform()`, a Filament JNI call. So this test
 * is an instrumented `androidTest` that spins up a real Filament [com.google.android.filament.Engine]
 * on the emulator — the same harness pattern as [NodeLifecycleTest] — and exercises:
 *
 * 1. **Drift** — spin a CHILD node by 1° every frame for 10 000 frames (its world transform
 *    is the product parent × local), asserting the recovered `worldScale` stays within 1e-5.
 * 2. **Invalidation completeness** — the cache returns the correct world value after a local
 *    `transform / position / quaternion / scale` write, after a parent move (propagation down
 *    the tree), after **reparenting** (the latent bug the #2280 `parentInstance` setter side-fix
 *    closed), and after **adding a child to an already-moved parent** (first read must refresh).
 * 3. **Staleness guard** — reading `worldPosition` never returns a stale value after any of
 *    the above.
 *
 * The world-space cache is the highest-risk part of the #2187 fix family: a missed
 * invalidation path returns a silently-wrong world position with no crash. This test makes
 * the invalidation contract permanent — it would have caught the reparent bug that the
 * #2263 review wave only found by manual inspection.
 *
 * Refs: #2264 #2280 #2263 #2284
 */
@RunWith(AndroidJUnit4::class)
class NodeWorldTransformDriftTest {

    private lateinit var engine: com.google.android.filament.Engine

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.safeDestroy()
        }
    }

    // ── 1. Drift over many frames ──────────────────────────────────────────────

    /**
     * Spin a child node by re-writing its full local [Node.transform] every frame for 10 000
     * frames and assert its recovered `worldScale` never drifts from `1.0`. The child's world
     * transform is the product `parent.world × child.local`, so every read exercises the full
     * Filament `TransformManager.getWorldTransform()` → decompose-into-cache path (#2264) — the
     * code that pure-math [NodeTransformDriftTest] structurally cannot reach (no Engine).
     *
     * The per-frame write rebuilds the transform from a fresh unit quaternion and a **pristine**
     * `Scale(1f)`, mirroring how a spin animation sets `worldTransform(...)`/`transform(...)`.
     * The world matrix Filament composes is therefore orthonormal-rotation × unit-scale every
     * frame, and decomposing its column lengths must return `1.0` within float round-off with no
     * frame-over-frame accumulation. A regression that reopened the #2187 getter→setter→getter
     * feedback loop on the world cache would make `worldScale` creep away from `1.0`.
     *
     * NOTE: driving the spin through the *individual-component* setter (`child.quaternion = …`)
     * instead would surface a SEPARATE, pre-existing local-cache drift unrelated to the world
     * cache this test targets — tracked independently. This test deliberately drives the full
     * `transform` setter so it isolates the #2264 world-cache contract.
     */
    @Test
    fun worldScale_doesNotDrift_over10000FrameRotation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            // Non-trivial parent transform (rotated + translated) so the child's world transform
            // is a real product, not identity — this is what surfaces matrix-decomposition error.
            parent.transform = io.github.sceneview.math.Transform(
                position = Position(1f, 2f, 3f),
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 30f),
                scale = Scale(1f),
            )
            parent.addChildNode(child)

            repeat(10_000) { i ->
                child.transform = io.github.sceneview.math.Transform(
                    position = Position(),
                    quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), (i % 360).toFloat()),
                    scale = Scale(1f),
                )
                // Read the world cache each frame (re-fetched from Filament because the local
                // write invalidated it). This is the #2264 decompose-into-cache hot path.
                child.worldScale
            }

            val worldScale = child.worldScale
            assertEquals("world scale x must stay 1.0 (no drift)", 1f, worldScale.x, 1e-5f)
            assertEquals("world scale y must stay 1.0 (no drift)", 1f, worldScale.y, 1e-5f)
            assertEquals("world scale z must stay 1.0 (no drift)", 1f, worldScale.z, 1e-5f)

            child.destroy()
            parent.destroy()
        }
    }

    // ── 2. Invalidation on a local transform write ─────────────────────────────

    @Test
    fun localPositionWrite_invalidatesWorldCache() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            // Prime the cache.
            assertEquals(0f, node.worldPosition.x, 1e-5f)

            node.position = Position(5f, 0f, 0f)
            assertEquals("world position must reflect the local position write",
                5f, node.worldPosition.x, 1e-5f)

            node.transform = io.github.sceneview.math.Transform(position = Position(0f, 7f, 0f))
            assertEquals("world position must reflect the transform write",
                7f, node.worldPosition.y, 1e-5f)
            assertEquals(0f, node.worldPosition.x, 1e-5f)

            node.quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 90f)
            assertEquals("world position unchanged after a pure-rotation write",
                7f, node.worldPosition.y, 1e-5f)

            node.scale = Scale(2f)
            assertEquals("world scale must reflect the scale write",
                2f, node.worldScale.x, 1e-5f)

            node.destroy()
        }
    }

    // ── 2b. Invalidation propagation on a PARENT move ──────────────────────────

    @Test
    fun parentMove_propagatesToChildWorldCache() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            child.position = Position(1f, 0f, 0f)
            parent.addChildNode(child)

            // Prime the child world cache: parent at origin → child world x == 1.
            assertEquals(1f, child.worldPosition.x, 1e-5f)

            // Move the parent. onTransformChanged → onWorldTransformChanged recurses to the child.
            parent.position = Position(10f, 0f, 0f)
            assertEquals("child world position must follow the parent move (10 + 1)",
                11f, child.worldPosition.x, 1e-5f)

            child.destroy()
            parent.destroy()
        }
    }

    // ── 2c. Invalidation on REPARENTING (the #2280 side-fix) ───────────────────

    /**
     * The latent bug the #2280 `parentInstance` setter side-fix closed: reparenting changes
     * a node's world transform even though its LOCAL transform is untouched. Without the
     * `onWorldTransformChanged()` call in the `parentInstance` setter, the world cache would
     * stay primed at its old value and `worldPosition` would silently return stale.
     */
    @Test
    fun reparenting_invalidatesWorldCache() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parentA = Node(engine)
            val parentB = Node(engine)
            val child = Node(engine)
            parentA.position = Position(10f, 0f, 0f)
            parentB.position = Position(-20f, 0f, 0f)
            child.position = Position(1f, 0f, 0f)

            parentA.addChildNode(child)
            // Prime the cache under parent A: world x == 11.
            assertEquals(11f, child.worldPosition.x, 1e-5f)

            // Reparent to B. Local transform unchanged; world transform must change.
            child.parent = parentB
            assertEquals("world position must refresh after reparenting (-20 + 1)",
                -19f, child.worldPosition.x, 1e-5f)

            // Detach entirely — world position collapses to local.
            child.parent = null
            assertEquals("world position must refresh after detaching",
                1f, child.worldPosition.x, 1e-5f)

            child.destroy()
            parentB.destroy()
            parentA.destroy()
        }
    }

    // ── 2d. Adding a child to an ALREADY-MOVED parent (first read must refresh) ─

    @Test
    fun addChildToMovedParent_firstReadIsFresh() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            child.position = Position(2f, 0f, 0f)

            // Move the parent BEFORE the child is attached.
            parent.position = Position(100f, 0f, 0f)

            // First-ever world read of the child after attaching to the moved parent must be
            // fresh (parentInstance setter fires onWorldTransformChanged on attach).
            parent.addChildNode(child)
            assertEquals("first world read after attach to a moved parent must be fresh (100 + 2)",
                102f, child.worldPosition.x, 1e-5f)

            child.destroy()
            parent.destroy()
        }
    }

    // ── 3. Staleness guard — combined sequence, no stale read at any step ──────

    /**
     * Drive a node through every invalidation path in sequence and assert `worldPosition`
     * is never stale at any step. This is the contract guard: a missed invalidation returns
     * a silently-wrong world position with no crash, so each step re-asserts after a mutation.
     */
    @Test
    fun worldPosition_isNeverStale_acrossEveryMutation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)

            // local write
            child.position = Position(1f, 0f, 0f)
            assertNoStale("after local write", 1f, child.worldPosition.x)

            // attach to parent at origin
            parent.addChildNode(child)
            assertNoStale("after attach to origin parent", 1f, child.worldPosition.x)

            // parent move
            parent.position = Position(5f, 0f, 0f)
            assertNoStale("after parent move", 6f, child.worldPosition.x)

            // child local move under moved parent
            child.position = Position(2f, 0f, 0f)
            assertNoStale("after child local move", 7f, child.worldPosition.x)

            // detach
            child.parent = null
            assertNoStale("after detach", 2f, child.worldPosition.x)

            child.destroy()
            parent.destroy()
        }
    }

    private fun assertNoStale(step: String, expectedX: Float, actualX: Float) {
        if (abs(actualX - expectedX) >= 1e-5f) {
            throw AssertionError(
                "Stale world position $step: expected x=$expectedX but got x=$actualX " +
                    "— a world-cache invalidation path is missing.",
            )
        }
    }
}
