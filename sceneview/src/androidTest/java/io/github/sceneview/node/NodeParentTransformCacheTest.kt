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
import io.github.sceneview.managers.getParentOrNull
import io.github.sceneview.managers.getTransform
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toColumnsFloatArray
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression test for the local-transform (#2405) and parent-entity/parent-instance
 * (#2403 / #2404) caches — the local half of the JNI-hot-path caching family (audit umbrella #2402,
 * same class as the #2264 world-cache test [NodeWorldTransformDriftTest]).
 *
 * These caches replace a `TransformManager.getTransform()` / `getParentOrNull()` / `getInstance()`
 * JNI round-trip on every getter read with a cached value invalidated/populated on the write paths.
 * The caches CANNOT be exercised in a pure-JVM runner: every value is produced by a Filament JNI
 * call. So this is an instrumented `androidTest` that spins up a real Filament
 * [com.google.android.filament.Engine] on the emulator — same harness as [NodeWorldTransformDriftTest]
 * — and asserts the two correctness invariants the brief mandates:
 *
 *  (a) **Equivalence after mutation** — the cached getter returns the SAME value an uncached fresh
 *      JNI read returns, after every mutation path (local transform write; reparent; detach).
 *  (b) **Read stability** — repeated reads never change the value (the cache is not self-mutating).
 *
 * A missed invalidation here is silent: a stale parent or local matrix returns a wrong value with no
 * crash. This test makes the invalidation contract permanent.
 *
 * Refs: #2403 #2404 #2405 #2402
 */
@RunWith(AndroidJUnit4::class)
class NodeParentTransformCacheTest {

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

    /** Asserts two transforms carry byte-identical column-major floats (the cache populates with the
     * exact matrix pushed to Filament, which round-trips it unchanged). */
    private fun assertTransformEquals(message: String, expected: Transform, actual: Transform) {
        assertArrayEquals(message, expected.toColumnsFloatArray(), actual.toColumnsFloatArray(), 0f)
    }

    // ── #2405: local-transform cache equivalence + stability ───────────────────

    @Test
    fun transformCache_matchesFreshJniRead_afterEveryLocalWrite() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            val tm = node.transformManager
            val inst = node.transformInstance

            // Full `transform` setter.
            node.transform = Transform(
                position = Position(1f, 2f, 3f),
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 35f),
                scale = Scale(2f),
            )
            assertTransformEquals(
                "cached transform must equal a fresh getTransform() after the transform setter",
                tm.getTransform(inst), node.transform,
            )

            // Individual-component setters route through applyCachedTransform().
            node.position = Position(7f, 0f, -1f)
            assertTransformEquals(
                "cached transform must equal a fresh read after a position write",
                tm.getTransform(inst), node.transform,
            )

            node.quaternion = Quaternion.fromAxisAngle(Float3(1f, 0f, 0f), 90f)
            assertTransformEquals(
                "cached transform must equal a fresh read after a quaternion write",
                tm.getTransform(inst), node.transform,
            )

            node.scale = Scale(0.5f)
            assertTransformEquals(
                "cached transform must equal a fresh read after a scale write",
                tm.getTransform(inst), node.transform,
            )

            node.destroy()
        }
    }

    @Test
    fun transformCache_repeatedReadsAreStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            node.transform = Transform(position = Position(4f, 5f, 6f))

            val first = node.transform.toColumnsFloatArray()
            repeat(100) {
                assertArrayEquals(
                    "repeated transform reads must not change the value",
                    first, node.transform.toColumnsFloatArray(), 0f,
                )
            }
            node.destroy()
        }
    }

    // ── #2403 / #2404: parent caches equivalence + stability ───────────────────

    @Test
    fun parentCaches_matchFreshJniRead_acrossReparentAndDetach() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parentA = Node(engine)
            val parentB = Node(engine)
            val child = Node(engine)
            val tm = child.transformManager
            val childInst = child.transformInstance

            // Detached: no parent. Cached getter must match a fresh read (null).
            assertEquals("fresh child has no parent entity", null, child.parentEntity)
            assertEquals(
                "cached parentEntity must equal fresh getParentOrNull",
                tm.getParentOrNull(childInst), child.parentEntity,
            )
            assertEquals("fresh child has no parent instance", null, child.parentInstance)

            // Attach to A.
            parentA.addChildNode(child)
            assertEquals(
                "parentEntity must be parentA.entity after attach",
                parentA.entity, child.parentEntity,
            )
            assertEquals(
                "cached parentEntity must equal fresh getParentOrNull after attach",
                tm.getParentOrNull(childInst), child.parentEntity,
            )
            assertEquals(
                "cached parentInstance must equal fresh getInstance(parentEntity) after attach",
                tm.getInstance(parentA.entity), child.parentInstance,
            )

            // Reparent to B — cache must invalidate and refresh.
            child.parent = parentB
            assertEquals(
                "parentEntity must follow the reparent to B",
                parentB.entity, child.parentEntity,
            )
            assertEquals(
                "cached parentEntity must equal fresh read after reparent",
                tm.getParentOrNull(childInst), child.parentEntity,
            )
            assertEquals(
                "cached parentInstance must equal fresh read after reparent",
                tm.getInstance(parentB.entity), child.parentInstance,
            )

            // Detach entirely — back to null.
            child.parent = null
            assertEquals("parentEntity must be null after detach", null, child.parentEntity)
            assertEquals(
                "cached parentEntity must equal fresh read after detach",
                tm.getParentOrNull(childInst), child.parentEntity,
            )
            assertEquals("parentInstance must be null after detach", null, child.parentInstance)

            child.destroy(); parentA.destroy(); parentB.destroy()
        }
    }

    @Test
    fun parentCaches_repeatedReadsAreStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            parent.addChildNode(child)

            val entity = child.parentEntity
            val instance = child.parentInstance
            repeat(100) {
                assertEquals("repeated parentEntity reads must be stable", entity, child.parentEntity)
                assertEquals("repeated parentInstance reads must be stable", instance, child.parentInstance)
            }
            child.destroy(); parent.destroy()
        }
    }
}
