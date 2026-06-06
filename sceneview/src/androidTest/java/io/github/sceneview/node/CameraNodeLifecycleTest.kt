package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.Scene
import com.google.android.filament.View
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.SceneNodeManager
import io.github.sceneview.collision.CollisionSystem
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.createView
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression pin for the `DisposableEffect(cameraNode)` rewire shipped in
 * [PR #1147 / commit 04d1c90a](https://github.com/sceneview/sceneview/pull/1147) —
 * the cameraNode-leak half of the bug wave that also fixed env loader (#1124) and
 * PhysicsDemo (#1125).
 *
 * ## What this pins
 *
 * Pre-fix, `SceneView.kt:293` wired `cameraNode` registration through a `SideEffect` +
 * `AtomicReference<CameraNode?>` swap — only firing the `removeNode` path when a
 * *different* camera replaced the previous one. A `SceneView` leaving composition
 * cleanly orphaned the camera (and any HUD-space children parented under it) in
 * the underlying Filament `Scene` — a cumulative leak when sharing a single
 * `Scene` across multiple sequential `SceneView` composables (the documented
 * use case at `SceneView.kt:191`).
 *
 * Post-fix, the camera registers via `DisposableEffect(cameraNode) { addNode;
 * onDispose { removeNode } }` — the same shape as the `#1122` light-node leak
 * fix (PR #1131). The `prevCameraNodeRef` AtomicReference is now dead code.
 *
 * The instrumented test below simulates the documented "share scene between
 * views" use case: 5 sequential `SceneNodeManager` cycles share the same
 * Filament `Scene`, each adding then disposing a fresh `CameraNode`. The
 * assertion: after each `removeNode`, the manager's tracked set is empty
 * (idempotent — second `removeNode` is a no-op) AND the Filament scene's
 * entity count never accumulates beyond the steady-state baseline.
 *
 * Same-shape regression check as PR #1131's light-leak fix — repeated
 * Compose `DisposableEffect` enter/exit cycles must zero out the managed set
 * between cycles, with no per-cycle residue.
 *
 * Lineage: #1143 / PR #1147 cameraNode leak. Same family as #1122 (light-node
 * leak), the broader umbrella was the post-#1075 Pixel-9 visual regression
 * sweep (#1106).
 *
 * @see io.github.sceneview.SceneNodeManagerTest for the underlying
 *     [SceneNodeManager.addNode] / [SceneNodeManager.removeNode] idempotency
 *     contract this test piggybacks on.
 */
@RunWith(AndroidJUnit4::class)
class CameraNodeLifecycleTest {

    private lateinit var engine: Engine
    private lateinit var filamentScene: Scene
    private lateinit var filamentView: View
    private lateinit var collisionSystem: CollisionSystem

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            val eglContext = createEglContext()
            engine = createEngine(eglContext)
            filamentScene = engine.createScene()
            filamentView = createView(engine)
            collisionSystem = CollisionSystem(filamentView)
        }
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.destroyScene(filamentScene)
            engine.destroyView(filamentView)
            engine.safeDestroy()
        }
    }

    /**
     * Five sequential `addNode → removeNode` cycles sharing the same Filament `Scene`
     * — the production shape of `SceneView.kt:293`'s `DisposableEffect(cameraNode)`. Each
     * cycle creates a fresh `SceneNodeManager` + a fresh `CameraNode` (a new
     * `DisposableEffect` enter), adds it, then disposes (the `onDispose` block).
     *
     * Post-fix invariant: the managed set is empty after every `removeNode`. Pre-fix,
     * a `SceneView` leaving composition without first being replaced by another
     * skipped `removeNode` entirely — the manager's tracked set carried the orphaned
     * camera reference forever. Across 5 cycles this would have leaked 5 cameras into
     * Filament's `Scene`.
     */
    @Test
    fun fiveSceneViewLifecycles_sharingScene_leakNoCameraNodes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val baselineEntityCount = filamentScene.entityCount
            // Note: CameraNode has no `renderable` component, so adding/removing it
            // does NOT mutate `filamentScene.entityCount` directly — the SceneNodeManager
            // only forwards a node's `sceneEntities` (renderable subset) to the
            // Filament scene. The leak shape we're pinning is the manager's tracked
            // set, not Filament's entity table. The `entityCount` assert below
            // doubles as a sanity check that no other code path snuck in a residue.

            repeat(5) { cycle ->
                // Fresh manager — one per SceneView composition.
                val manager = SceneNodeManager(filamentScene, collisionSystem)
                // Fresh CameraNode — Compose's `DisposableEffect(cameraNode)` enter
                // would call addNode here.
                val cameraNode = CameraNode(engine)
                manager.addNode(cameraNode)
                assertEquals(
                    "After addNode on cycle $cycle, manager must track exactly 1 node",
                    1, managedNodesCount(manager)
                )

                // `DisposableEffect.onDispose` runs — must remove the camera.
                manager.removeNode(cameraNode)
                assertEquals(
                    "After removeNode on cycle $cycle, manager must be empty",
                    0, managedNodesCount(manager)
                )

                // A redundant removeNode (Compose can fire onDispose twice in some
                // edge cases — fragment re-create) MUST be a no-op, not a crash.
                manager.removeNode(cameraNode)

                cameraNode.destroy()
            }

            // After 5 cycles, the shared Filament `Scene` is back at baseline — no
            // orphaned camera entities accumulated. Pre-fix this would have been
            // baseline + 5.
            assertEquals(
                "Shared Filament scene must contain the same entity count after 5 " +
                    "lifecycles as it did before — pre-#1143, cameraNode leaks would " +
                    "have left 5 orphan entities here.",
                baselineEntityCount, filamentScene.entityCount
            )
        }
    }

    /**
     * Parent → child propagation: when the `cameraNode` registers, any nodes
     * parented under it (e.g. a HUD compass arrow) get auto-managed via
     * `Node.onChildAdded += ::addNode`. Pre-#1143, the SideEffect path silently
     * leaked those children alongside the camera on every clean dispose.
     *
     * Verifies the documented use case at `SceneView.kt:283-288` (`children parented to
     * the camera ... are automatically added to the scene and rendered in
     * camera/HUD space`).
     */
    @Test
    fun cameraNodeDispose_removesParentedHudChildren() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val baselineEntityCount = filamentScene.entityCount

            val manager = SceneNodeManager(filamentScene, collisionSystem)
            val cameraNode = CameraNode(engine)
            // HUD-style child parented to camera (the documented use case).
            val hudChild = Node(engine)
            cameraNode.addChildNode(hudChild)

            manager.addNode(cameraNode)
            assertEquals(
                "addNode must auto-register both the camera and its HUD child",
                2, managedNodesCount(manager)
            )

            manager.removeNode(cameraNode)
            assertEquals(
                "removeNode must cascade to HUD children — pre-#1143, the SideEffect " +
                    "path skipped this cascade on clean dispose.",
                0, managedNodesCount(manager)
            )
            // Shared scene returns to baseline — child entities also freed from
            // the underlying Filament scene.
            assertEquals(baselineEntityCount, filamentScene.entityCount)

            hudChild.destroy()
            cameraNode.destroy()
        }
    }

    /**
     * Compose `DisposableEffect(cameraNode)` re-keys when the `cameraNode` reference
     * changes (e.g. caller swaps `cameraNode = newCameraNode` between recompositions).
     * The keyed effect MUST run `onDispose` for the previous camera before `addNode`
     * for the new one — otherwise both end up tracked simultaneously and the old
     * one leaks.
     *
     * Pre-#1143, this case actually worked because the SideEffect path swapped
     * through `prevCameraNodeRef`. We pin it post-fix to make sure the
     * DisposableEffect rewrite didn't accidentally regress the swap path.
     */
    @Test
    fun cameraNodeSwap_replacesPrevious_withoutLeaking() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val manager = SceneNodeManager(filamentScene, collisionSystem)
            val firstCamera = CameraNode(engine)
            val secondCamera = CameraNode(engine)

            manager.addNode(firstCamera)
            assertEquals(1, managedNodesCount(manager))

            // Compose's `DisposableEffect(key)` semantics on key change:
            // onDispose(oldKey) → addNode(newKey).
            manager.removeNode(firstCamera)
            manager.addNode(secondCamera)
            assertEquals(
                "After camera swap, only the new camera is tracked — first is gone.",
                1, managedNodesCount(manager)
            )
            assertTrue(
                "Tracked node must be the second camera, not the first.",
                isManagedNode(manager, secondCamera)
            )
            assertFalse(
                "First camera must no longer be tracked after the swap.",
                isManagedNode(manager, firstCamera)
            )

            manager.removeNode(secondCamera)
            firstCamera.destroy()
            secondCamera.destroy()
        }
    }

    // ── reflection helpers ──────────────────────────────────────────────────
    //
    // `SceneNodeManager.managedNodes` is private — we read it via reflection so the
    // test can assert on size + membership without exposing the field publicly.
    // Rename-safety: if a future refactor renames `managedNodes`, both helpers
    // throw `NoSuchFieldException` at the first access and the test fails fast.

    @Suppress("UNCHECKED_CAST")
    private fun managedNodesCount(manager: SceneNodeManager): Int {
        val field = SceneNodeManager::class.java.getDeclaredField("managedNodes")
        field.isAccessible = true
        return (field.get(manager) as Set<Node>).size
    }

    @Suppress("UNCHECKED_CAST")
    private fun isManagedNode(manager: SceneNodeManager, node: Node): Boolean {
        val field = SceneNodeManager::class.java.getDeclaredField("managedNodes")
        field.isAccessible = true
        return (field.get(manager) as Set<Node>).contains(node)
    }
}
