package io.github.sceneview.leak

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.LightManager
import com.google.android.filament.Texture
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Position
import io.github.sceneview.node.CubeNode
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.Node
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leak-churn guard (#2762) — asserts **return-to-baseline of engine-level state** across
 * repeated create/destroy cycles, so the repo's single most recurrent bug class (leaks:
 * #1122/#1123, #2036, #2037, #2039, #2458/#2459, #933) cannot quietly come back.
 *
 * The existing lifecycle suites (`NodeLifecycleTest`, `CameraNodeLifecycleTest`,
 * `MaterialInstanceLeakTest`) each verify **one** teardown path once. A leak that only
 * appears on the 30th cycle — a map entry never evicted, an entity never released, a
 * deferred destroy never drained — passes all of them. This suite churns instead.
 *
 * ## Which probes are real (measured, not assumed)
 *
 * #2762 proposed asserting `RenderableManager` / `LightManager` / `TransformManager`
 * *component counts* plus `EntityManager` *alive entities*. Checked against the Filament
 * build this SDK actually consumes (`filament-android` 1.72.1, `javap` on the shipped
 * `classes.jar`), **only one of those global counters exists**:
 *
 * | Proposed probe | Java API in 1.72.1 | Used here |
 * |---|---|---|
 * | `LightManager` component count | `getComponentCount()` ✅ | global baseline assert |
 * | `RenderableManager` component count | ❌ none (only `hasComponent(entity)`) | per-entity assert |
 * | `TransformManager` component count | ❌ none (only `hasComponent(entity)`) | per-entity assert |
 * | `EntityManager` alive count | ❌ none (only `isAlive(entity)`) | pinned, not asserted (see below) |
 * | `EngineDestroyQueue.size` | ✅ (`EngineDestroyQueue.kt:58`) | global assert |
 *
 * Per-entity probing is not a downgrade: a global counter tells you *that* something
 * leaked, whereas capturing the entity ids a cycle created and asserting each one is dead
 * afterwards tells you **which** object leaked — the first question a bisect asks.
 *
 * ## Methodology
 *
 * No `SwapChain`, no rendering, no `readPixels`: this suite drives the same headless
 * `createEglContext()` + `createEngine()` path as `NodeLifecycleTest`, so — unlike the
 * `render` package, which is gated behind `gpuReadback` (#803) — it **genuinely executes
 * on the SwiftShader CI emulator** rather than reporting a skip. Every Filament call runs
 * on the main thread via `runOnMainSync`, per the SDK's JNI threading rule.
 *
 * [canary_injectedLeakIsDetected] is a **permanent mutation test**: it deliberately skips
 * one `destroy()` and asserts the probes tell it apart from a properly destroyed control,
 * for **both** live probes (`LightManager` and `TransformManager`).
 * If a future Filament release turns `hasComponent`/`getComponentCount` into no-ops, the
 * canary fails loudly instead of letting every other test pass vacuously on a dead
 * instrument.
 *
 * One gap is **pinned rather than asserted**: `Node.destroy()` never returns the entity id
 * to `EntityManager` (see [entityIdRecycling_isTheKnownPreExistingGap]). That is a real,
 * pre-existing leak this suite measured on its first run; it is tracked in #2859 rather
 * than smuggled into a test PR as a runtime change.
 *
 * ## Scope, stated honestly
 *
 * This churns the **engine + scene-graph** layer (`Engine`, `Node`, `LightNode`,
 * entities, the deferred-destroy queue) — where every leak listed above actually lived.
 * It does *not* mount the `SceneView` **composable**: that needs a Compose host and a
 * SwapChain, whose teardown on the emulator is the flaky path #1643 tracks. Composable-
 * level churn is a follow-up, deliberately not smuggled in behind a green tick here.
 */
@RunWith(AndroidJUnit4::class)
class LeakChurnTest {

    private companion object {
        /**
         * Cycles per test. #2762 asks for 30–50: 40 is comfortably inside the band and
         * keeps the whole class well under a minute on the CI emulator.
         */
        const val CYCLES = 40

        /** Nodes built per cycle — a small tree, so parent/child teardown is exercised. */
        const val NODES_PER_CYCLE = 5
    }

    private lateinit var engine: Engine

    @Before
    fun setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Gltfio.init(); Filament.init(); Utils.init()
            engine = createEngine(createEglContext())
        }
    }

    @After
    fun teardown() {
        // `createEglContext()` hard-errors on a host without a usable EGL config, which
        // would leave `engine` uninitialized; dereferencing it here would bury the real
        // setup failure under a second, meaningless one.
        if (!::engine.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            engine.safeDestroy()
        }
    }

    /** Runs [block] on the main thread — every Filament JNI call must. */
    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /**
     * Asserts every **component** attached to [entity] was released. A leaked component
     * keeps native memory alive even when the Kotlin `Node` is long gone — that is exactly
     * the #2036/#2039 shape.
     *
     * Deliberately does NOT assert `EntityManager.isAlive(entity) == false`: the SDK's
     * `Node.destroy()` calls `Engine.destroyEntity()`, which in Filament destroys the
     * components but does **not** return the id to `EntityManager` (only
     * `EntityManager.destroy()` does). That id-recycling gap is real, measured, and
     * pre-existing — see [entityIdRecycling_isTheKnownPreExistingGap] and #2859. Asserting
     * it here would just paint this suite red for a bug it did not introduce and cannot fix.
     */
    private fun assertComponentsReleased(label: String, entity: Int) {
        assertFalse(
            "$label: entity $entity still has a TransformManager component after destroy()",
            engine.transformManager.hasComponent(entity),
        )
        assertFalse(
            "$label: entity $entity still has a RenderableManager component after destroy()",
            engine.renderableManager.hasComponent(entity),
        )
        assertFalse(
            "$label: entity $entity still has a LightManager component after destroy()",
            engine.lightManager.hasComponent(entity),
        )
    }

    /** Plain-node churn: a small parent/child tree built and torn down [CYCLES] times. */
    @Test
    fun nodeTreeChurn_releasesEveryComponent() {
        val created = mutableListOf<Int>()

        onMain {
            repeat(CYCLES) {
                val root = Node(engine)
                val children = List(NODES_PER_CYCLE - 1) { index ->
                    Node(engine).also { child ->
                        child.position = Position(index.toFloat(), 0f, 0f)
                        root.addChildNode(child)
                    }
                }
                created += root.entity
                created += children.map { it.entity }

                // Destroying the root cascades to the children (Node.destroy snapshots
                // childNodes first) — the path a real scene teardown takes.
                root.destroy()
            }
        }

        assertEquals(
            "sanity: the churn should have created $CYCLES × $NODES_PER_CYCLE entities",
            CYCLES * NODES_PER_CYCLE,
            created.size,
        )
        onMain {
            created.forEachIndexed { index, entity ->
                assertComponentsReleased("cycle node #$index", entity)
            }
        }
    }

    /**
     * Light churn — the one path with a real global counter. `LightManager` components are
     * created by the builder and released by `LightNode.destroy()`; after the churn the
     * count must be exactly what it was before it.
     */
    @Test
    fun lightChurn_returnsLightManagerCountToBaseline() {
        var baseline = 0
        val created = mutableListOf<Int>()

        onMain { baseline = engine.lightManager.componentCount }

        onMain {
            repeat(CYCLES) {
                val light = LightNode(engine, type = LightManager.Type.DIRECTIONAL) {
                    intensity(100_000f)
                    castShadows(false)
                }
                created += light.entity
                light.destroy()
            }
        }

        onMain {
            assertEquals(
                "LightManager component count did not return to baseline after $CYCLES " +
                    "create/destroy cycles — a light component is leaking per cycle",
                baseline,
                engine.lightManager.componentCount,
            )
            created.forEachIndexed { index, entity ->
                assertComponentsReleased("light #$index", entity)
            }
        }
    }

    /**
     * Re-parenting churn — the #2458/#2459 shape: a node swapped between parents many
     * times before teardown. A parent map that keeps a stale reference leaks the whole
     * subtree, which no single-teardown test would notice.
     */
    @Test
    fun reparentingChurn_leavesNoDetachedSubtree() {
        val created = mutableListOf<Int>()

        onMain {
            val parentA = Node(engine)
            val parentB = Node(engine)
            val child = Node(engine)
            created += listOf(parentA.entity, parentB.entity, child.entity)

            repeat(CYCLES) { cycle ->
                val target = if (cycle % 2 == 0) parentA else parentB
                val other = if (cycle % 2 == 0) parentB else parentA
                target.addChildNode(child)
                // The real guard is EVICTION from the previous parent (Node.kt:525) — the
                // stale-reference leak of #2458/#2459. Reading back `child.parent` alone
                // would only echo the field `addChildNode` just wrote.
                assertTrue(
                    "cycle $cycle: the new parent should hold the child",
                    child in target.childNodes,
                )
                assertFalse(
                    "cycle $cycle: the previous parent still references the child — a " +
                        "stale childNodes entry leaks the whole subtree (#2458/#2459)",
                    child in other.childNodes,
                )
            }

            parentA.destroy()
            parentB.destroy()
            // `child` rides on whichever parent owned it last; destroy() is idempotent
            // (`isDestroyed` guard), so this is safe either way.
            child.destroy()
        }

        onMain {
            created.forEachIndexed { index, entity ->
                assertComponentsReleased("reparent node #$index", entity)
            }
        }
    }

    /**
     * Geometry churn — the only path that creates a **renderable** component.
     *
     * Without this, `assertComponentsReleased`'s `RenderableManager` probe would never be
     * fed anything and would read `false` vacuously in every test. `CubeNode` also
     * exercises `GeometryNode.destroy()` → `safeDestroyGeometry`, i.e. the vertex/index
     * buffer release path.
     */
    @Test
    fun geometryChurn_releasesRenderableComponent() {
        val created = mutableListOf<Int>()

        onMain {
            repeat(CYCLES) {
                val cube = CubeNode(engine = engine, materialInstances = listOf(null))
                // Guard against a vacuous pass: the renderable must actually exist while
                // the node is alive, otherwise the post-destroy assert proves nothing.
                assertTrue(
                    "CubeNode did not create a RenderableManager component — the " +
                        "renderable probe below would pass vacuously",
                    engine.renderableManager.hasComponent(cube.entity),
                )
                created += cube.entity
                cube.destroy()
            }
        }

        onMain {
            created.forEachIndexed { index, entity ->
                assertComponentsReleased("cube #$index", entity)
            }
        }
    }

    /**
     * The deferred-destroy queue's Filament binding: enqueued resources must **wait**
     * [EngineDestroyQueue.GRACE_FRAMES] frames and then be released — a resource destroyed
     * eagerly is the `SIGABRT` of #874, one never released is the leak of #1630.
     *
     * Textures are built and enqueued directly rather than via a node, on purpose: the
     * only three enqueue sites in the SDK (`SplatNode`, `ViewNode`, `ImageNode`) all need
     * an asset or a bitmap, which would add I/O flakiness to a probe that must be
     * deterministic. Note that a churn of plain `Node`s can never populate this queue —
     * asserting `size == 0` after one would be a tautology, not a test.
     */
    @Test
    fun deferredDestroyQueue_holdsThenReleasesEnqueuedResources() {
        onMain {
            val queue = EngineDestroyQueue.of(engine)
            assertEquals("baseline: a fresh engine's queue is empty", 0, queue.size)

            repeat(CYCLES) {
                val texture = Texture.Builder()
                    .width(1)
                    .height(1)
                    .levels(1)
                    .format(Texture.InternalFormat.RGBA8)
                    .build(engine)
                queue.enqueueTexture(texture)
            }
            assertEquals(
                "enqueued textures must be held for the grace period, not destroyed " +
                    "eagerly — destroying one before Filament reclaims its MaterialInstance " +
                    "is the #874 SIGABRT",
                CYCLES,
                queue.size,
            )

            repeat(EngineDestroyQueue.GRACE_FRAMES) { queue.drain() }
            assertEquals(
                "the queue still holds resources after ${EngineDestroyQueue.GRACE_FRAMES} " +
                    "drained frames — they would never be released (#1630 shape)",
                0,
                queue.size,
            )
        }
    }

    /**
     * Permanent mutation test: skip one `destroy()` and prove every probe notices. This is
     * what stops the suite above from passing vacuously — if Filament ever turns these
     * calls into no-ops, this test goes red first and names the reason.
     *
     * The assertions are **differential** (leaked light vs destroyed light) on purpose. An
     * absolute `hasComponent(leaked) == true` would also pass on a build where
     * `hasComponent` always returned `true`; requiring the destroyed one to read `false` in
     * the same breath is what actually proves the instrument discriminates.
     */
    @Test
    fun canary_injectedLeakIsDetected() {
        var baseline = 0
        var leakedEntity = 0
        var destroyedEntity = 0
        var liveNodeEntity = 0
        var destroyedNodeEntity = 0

        onMain {
            baseline = engine.lightManager.componentCount

            // Deliberately NOT destroyed — this is the injected leak.
            leakedEntity = LightNode(engine, type = LightManager.Type.POINT) {
                intensity(1_000f)
            }.entity

            // The control: same construction, properly destroyed.
            destroyedEntity = LightNode(engine, type = LightManager.Type.POINT) {
                intensity(1_000f)
            }.also { it.destroy() }.entity

            // Same differential pair for TransformManager — the probe that carries
            // nodeTreeChurn, reparentingChurn and the id-recycling pin. Without this the
            // canary would only ever vouch for LightManager.
            liveNodeEntity = Node(engine).entity
            destroyedNodeEntity = Node(engine).also { it.destroy() }.entity
        }

        onMain {
            assertTrue(
                "Leak detection is broken: a deliberately leaked light has no LightManager " +
                    "component — hasComponent is not measuring anything on this build, so " +
                    "every per-entity assert in this class would pass vacuously",
                engine.lightManager.hasComponent(leakedEntity),
            )
            assertFalse(
                "Leak detection is broken: hasComponent still reports a component for a " +
                    "properly destroyed light, so it cannot discriminate leaked from freed",
                engine.lightManager.hasComponent(destroyedEntity),
            )
            assertEquals(
                "Leak detection is broken: LightManager.getComponentCount() did not move " +
                    "by exactly +1 for one leaked light (and one freed one) — the baseline " +
                    "assert in lightChurn_returnsLightManagerCountToBaseline cannot catch " +
                    "anything",
                baseline + 1,
                engine.lightManager.componentCount,
            )
            assertTrue(
                "Leak detection is broken: a live Node has no TransformManager component — " +
                    "the transform probe is dead, so nodeTreeChurn/reparentingChurn would " +
                    "pass vacuously",
                engine.transformManager.hasComponent(liveNodeEntity),
            )
            assertFalse(
                "Leak detection is broken: TransformManager still reports a component for " +
                    "a destroyed Node, so it cannot discriminate leaked from freed",
                engine.transformManager.hasComponent(destroyedNodeEntity),
            )

            // Clean up so the canary itself does not leak into engine teardown.
            engine.lightManager.destroy(leakedEntity)
            engine.destroyEntity(leakedEntity)
            engine.destroyEntity(liveNodeEntity)
        }
    }

    /**
     * Characterization test for a **pre-existing, measured** gap — not a new regression.
     *
     * `Node.destroy()` → `Engine.destroyEntity(entity)` destroys the entity's *components*
     * but does not return the id to `EntityManager`; only `EntityManager.destroy(entity)`
     * does, and the SDK calls it in exactly two places (`PlaneVisualizer`,
     * `PlaneVisualizerV2`) out of 15 creation sites. So every `Node` ever created burns an
     * entity id for the lifetime of the process.
     *
     * This test pins today's behaviour so the churn suite above is not red for a bug it did
     * not introduce, and so the gap is impossible to forget: **when #2859 is fixed, this
     * test goes red — invert it then, and tighten
     * [assertComponentsReleased] to also require `isAlive == false`.**
     */
    @Test
    fun entityIdRecycling_isTheKnownPreExistingGap() {
        var entity = 0
        onMain { entity = Node(engine).also { it.destroy() }.entity }

        onMain {
            assertTrue(
                "Entity ids are now recycled by Node.destroy() — the known gap this test " +
                    "pins is FIXED. Invert this assertion and tighten assertComponentsReleased " +
                    "to require EntityManager.isAlive(entity) == false.",
                EntityManager.get().isAlive(entity),
            )
            assertComponentsReleased("id-recycling gap probe", entity)
        }
    }
}
