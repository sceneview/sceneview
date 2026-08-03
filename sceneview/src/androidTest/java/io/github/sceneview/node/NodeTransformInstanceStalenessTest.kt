package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression test for the stale-`transformInstance`-cache bug (see
 * `STALE_TRANSFORM_INSTANCE_ROOT_CAUSE.md` at the repo root).
 *
 * [Node.transformInstance] caches a [com.google.android.filament.TransformManager]
 * `EntityInstance` handle for the lifetime of the Kotlin [Node]. That handle is only
 * stable while no OTHER entity's transform component is created or destroyed — Filament's
 * component managers are packed-array stores, so any other entity's transform component
 * being created or destroyed can silently reindex a live entity's handle. Before the fix,
 * a node's cached handle was never revisited after such a reindex, so every subsequent
 * transform read silently operated on whatever entity now occupies that stale slot.
 *
 * These tests assert the cached [Node.transformInstance] always matches a fresh, uncached
 * `transformManager.getInstance(entity)` lookup, across both reindex triggers: an unrelated
 * node's [Node.destroy] and an unrelated node's construction.
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

    @Test
    fun transformInstance_survivesUnrelatedEntityCreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val tm = engine.transformManager

            val underTest = Node(engine)
            underTest.transformInstance // populate the cache

            // Construct a generous number of unrelated decoys to reliably cross whatever
            // capacity-growth threshold TransformManager's packed array uses internally.
            val decoys = List(512) { Node(engine) }

            val freshAfter = tm.getInstance(underTest.entity)
            assertEquals(
                "underTest's cached transformInstance must track TransformManager's real " +
                    "index after unrelated entities' transform components are created",
                freshAfter, underTest.transformInstance,
            )

            underTest.destroy()
            decoys.forEach { it.destroy() }
        }
    }
}
