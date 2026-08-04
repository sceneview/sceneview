package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.destroyTransformable
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression test for the stale-`transformInstance`-cache bug (#2977).
 *
 * [Node.transformInstance] caches a [com.google.android.filament.TransformManager]
 * `EntityInstance` handle for the lifetime of the Kotlin [Node]. That handle is only stable
 * while no OTHER entity's transform component is destroyed — Filament's `TransformManager` is
 * a packed-array store that compacts on removal by swapping the last live entity into the
 * removed slot, silently reindexing that one other live entity's handle (creation only
 * appends/copies in place and never reindexes an existing entity, so it isn't a trigger here).
 * Before the fix, a node's cached handle was never revisited after such a reindex, so every
 * subsequent transform read silently operated on whatever entity now occupies that stale slot.
 *
 * This test asserts the cached [Node.transformInstance] always matches a fresh, uncached
 * `transformManager.getInstance(entity)` lookup after an unrelated node's [Node.destroy].
 */
@RunWith(AndroidJUnit4::class)
class NodeTransformInstanceStalenessTest {

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

    @Test
    fun transformInstance_survivesUnrelatedEntityDestroy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tm = engine.transformManager

            // Populate the packed array with decoys before the node under test, so a later
            // destroy() has room to reindex something.
            val decoys = List(8) { Node(engine) }
            val underTest = Node(engine)
            underTest.transformInstance // populate the cache

            // Destroy an UNRELATED entity — no parent/child relationship to `underTest` at all.
            decoys[3].destroy()

            val freshAfter = tm.getInstance(underTest.entity)
            assertEquals(
                "underTest's cached transformInstance must track TransformManager's real " +
                    "index after an unrelated entity's transform component is destroyed",
                freshAfter, underTest.transformInstance,
            )

            underTest.destroy()
            decoys.forEach { it.destroy() }
        }
    }

    /**
     * [Node.parentInstance] (#2404) is a second cached `TransformManager` handle from the same
     * packed array as [Node.transformInstance], and it feeds a WRITE path
     * (`transformManager.setParent`) — a stale handle here can reparent the wrong entity, not
     * just misread one. It needs the same generation-based invalidation (#2978 review gap 1).
     */
    @Test
    fun parentInstance_survivesUnrelatedEntityDestroy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tm = engine.transformManager

            val decoys = List(8) { Node(engine) }
            val parentNode = Node(engine)
            val child = Node(engine)
            child.parent = parentNode
            child.parentInstance // populate the cache

            // Destroy an UNRELATED entity — no parent/child relationship to either node.
            decoys[3].destroy()

            val freshAfter = tm.getInstance(parentNode.entity)
            assertEquals(
                "child's cached parentInstance must track TransformManager's real index " +
                    "after an unrelated entity's transform component is destroyed",
                freshAfter, child.parentInstance,
            )

            child.destroy()
            parentNode.destroy()
            decoys.forEach { it.destroy() }
        }
    }

    /**
     * Models the shape of `AssetLoader.destroyAsset` (via `ModelLoader.destroyModel`): raw
     * entities carrying a transform component, owned by no [Node], destroyed directly via
     * [io.github.sceneview.destroyTransformable] rather than [Node.destroy]. Before the fix, this
     * path invalidated nothing, so the freeze this test guards against was still reachable via
     * glTF asset teardown even after the sibling-`Node.destroy()` invalidation landed (#2978
     * review gap 2).
     */
    @Test
    fun transformInstance_survivesNonNodeTransformComponentDestroy() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tm = engine.transformManager

            val rawEntities = List(8) {
                EntityManager.get().create().also { tm.create(it) }
            }
            val underTest = Node(engine)
            underTest.transformInstance // populate the cache

            // Destroy a raw, non-Node transform component directly — the destroyAsset shape.
            engine.destroyTransformable(rawEntities[3])

            val freshAfter = tm.getInstance(underTest.entity)
            assertEquals(
                "underTest's cached transformInstance must track TransformManager's real " +
                    "index after a non-Node transform component is destroyed",
                freshAfter, underTest.transformInstance,
            )

            underTest.destroy()
            rawEntities.forEach { runCatching { tm.destroy(it) } }
        }
    }
}
