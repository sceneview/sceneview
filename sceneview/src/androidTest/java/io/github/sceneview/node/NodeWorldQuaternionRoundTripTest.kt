package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Engine-backed regression test for the world-space quaternion round-trip on parented nodes (#2392).
 *
 * 4.15.2 → 4.17.0 regressed: setting a parented node's world-space rotation via
 * `node.worldQuaternion = X` no longer round-tripped — reading it straight back returned
 * `parentWorldRotation ⊗ X`, not `X`, silently mis-orienting child nodes (e.g. a per-frame
 * billboard that orients markers toward the camera). The combination of the world-TRS cache
 * (#2280 / #2264) and the cached-quaternion fast path in `getLocalQuaternion` (#2294 / #2267)
 * meant the world→local conversion that backs the setter trusted the **parent's cached**
 * `worldQuaternion`. When that cache was stale relative to the live Filament world matrix, the
 * conversion produced a wrong local rotation. Pre-4.16 every world→local conversion re-read the
 * live matrix, so the bug did not exist.
 *
 * The fix re-validates the world cache against Filament's live matrix inside the conversion
 * helpers ([Node.getLocalQuaternion] / [Node.getWorldQuaternion] and the position/scale/transform
 * siblings) — exactly the live-matrix read 4.15.2 did. These helpers are on the (write-time)
 * setter path, not the per-frame world-quaternion *getter*, which stays fully cache-served.
 *
 * Like [NodeWorldTransformDriftTest], this is an instrumented `androidTest` because [Node]
 * requires a real Filament `Engine` (the world transform is read from `TransformManager`, a JNI
 * call), so it cannot be exercised in a pure-JVM unit test.
 *
 * Refs: #2392 #2284 #2280 #2264 #2294 #2267
 */
@RunWith(AndroidJUnit4::class)
class NodeWorldQuaternionRoundTripTest {

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

    /**
     * `set worldQuaternion = X` then `get worldQuaternion` must return `X` for a child whose
     * parent has a non-identity world rotation. This is the exact symptom reported in #2392.
     */
    @Test
    fun worldQuaternionSetter_roundTrips_onParentedNode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            // Non-identity parent world rotation: 57° about a tilted axis.
            parent.quaternion = Quaternion.fromAxisAngle(normalize(Float3(0.3f, 1f, 0.2f)), 57f)
            parent.addChildNode(child)

            // The reported failing value.
            val x = normalize(Quaternion(0.2715f, 0.6100f, 0.3027f, 0.6801f))
            child.worldQuaternion = x

            assertQuaternionsEqual(
                "worldQuaternion setter must round-trip on a parented node (#2392)",
                x,
                child.worldQuaternion,
            )

            child.destroy()
            parent.destroy()
        }
    }

    /**
     * The per-frame billboard pattern: prime the parent's world cache by reading it, THEN set
     * the child's worldQuaternion to a series of values, asserting every set round-trips. The
     * prior read populates the parent's `_worldQuaternion` cache, so this exercises the
     * cache-served path the fix re-validates.
     */
    @Test
    fun worldQuaternionSetter_roundTrips_afterParentCachePrimed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            parent.quaternion = Quaternion.fromAxisAngle(normalize(Float3(0f, 1f, 0f)), 90f)
            parent.addChildNode(child)

            // Prime the parent's world cache (and the child's) — the billboard reads world state.
            parent.worldQuaternion
            child.worldQuaternion

            repeat(8) { i ->
                val target = Quaternion.fromAxisAngle(normalize(Float3(0.1f, 1f, 0.3f)), (i * 31f))
                child.worldQuaternion = target
                assertQuaternionsEqual(
                    "billboard worldQuaternion set #$i must round-trip",
                    target,
                    child.worldQuaternion,
                )
            }

            child.destroy()
            parent.destroy()
        }
    }

    /**
     * Round-trip must hold even when the parent's world transform changes via its **full
     * `transform` setter** between primings — the cache-invalidation + live-refresh contract.
     */
    @Test
    fun worldQuaternionSetter_roundTrips_afterParentTransformWrite() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            parent.addChildNode(child)

            // First parent orientation, prime, set, verify.
            parent.transform = Transform(
                position = Position(0f, 0f, 0f),
                quaternion = Quaternion.fromAxisAngle(normalize(Float3(0f, 1f, 0f)), 45f),
            )
            parent.worldQuaternion
            val a = Quaternion.fromAxisAngle(normalize(Float3(1f, 0f, 0f)), 20f)
            child.worldQuaternion = a
            assertQuaternionsEqual("round-trip under parent orientation A", a, child.worldQuaternion)

            // Re-orient the parent, set again, verify — the conversion must see the NEW parent rotation.
            parent.transform = Transform(
                position = Position(0f, 0f, 0f),
                quaternion = Quaternion.fromAxisAngle(normalize(Float3(0f, 0f, 1f)), 110f),
            )
            val b = Quaternion.fromAxisAngle(normalize(Float3(0f, 1f, 0f)), 75f)
            child.worldQuaternion = b
            assertQuaternionsEqual("round-trip under parent orientation B", b, child.worldQuaternion)

            child.destroy()
            parent.destroy()
        }
    }

    /**
     * `worldRotation` (Euler) setter is the same conversion under the hood — guard it too.
     */
    @Test
    fun worldRotationSetter_roundTrips_onParentedNode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val parent = Node(engine)
            val child = Node(engine)
            parent.quaternion = Quaternion.fromAxisAngle(normalize(Float3(0.2f, 1f, 0.1f)), 40f)
            parent.addChildNode(child)

            val target = Quaternion.fromAxisAngle(normalize(Float3(0f, 1f, 0f)), 33f)
            child.worldRotation = target.toEulerAngles()

            assertQuaternionsEqual(
                "worldRotation setter must round-trip on a parented node (#2392)",
                target,
                child.worldQuaternion,
            )

            child.destroy()
            parent.destroy()
        }
    }

    /**
     * Compares two unit quaternions for equal rotation, sign-agnostic (`q` and `-q` are the same
     * rotation): `|dot(a, b)|` must be ≈ 1.
     */
    private fun assertQuaternionsEqual(message: String, expected: Quaternion, actual: Quaternion) {
        val a = normalize(expected)
        val b = normalize(actual)
        val dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        val deviation = abs(1f - abs(dot))
        assertTrue(
            "$message — expected≈$expected actual=$actual (|1-|dot||=$deviation)",
            deviation < 1e-4f,
        )
    }
}
