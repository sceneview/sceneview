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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Engine-backed regression test for the #2335 component-setter drift — the residual half of the
 * #2187/#2217 fix.
 *
 * #2187/#2217 cached pristine local TRS values so the **getters** never re-decompose the Filament
 * 4×4 matrix. But the per-component **setters** (`node.quaternion = …`, `position`, `scale`,
 * `rotation`) still routed through the public `transform =` setter, which re-decomposes the
 * composed matrix back into TRS on every write. Driving a single component at 60–120 Hz therefore
 * fed the matrix column-length (scale) and polar-decomposition (quaternion) imprecision straight
 * back into the caches, and local scale crept off 1.0 (~1e-4 over 10 000 frames) — exactly the
 * #2187 mesh-warp, reintroduced through the setter path.
 *
 * The pure-math [NodeTransformDriftTest] can NOT catch this: it models the *intended* compose-only
 * path in isolation and never exercises the real `Node` setter that round-tripped through Filament.
 * This test drives the real [Node] on a live Filament [com.google.android.filament.Engine], so it
 * exercises the actual `setTransform` / `getTransform` JNI round-trip.
 *
 * Fix (#2335): the component setters now push the composed matrix to Filament via
 * `applyCachedTransform()` WITHOUT reading it back, so they never re-decompose.
 *
 * These tests FAIL on the pre-fix `Node` (drift ~1e-4) and PASS after the fix (drift < 1e-6).
 */
@RunWith(AndroidJUnit4::class)
class NodeLocalTransformDriftTest {

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
     * Spinning a node via the `quaternion` setter must NOT drift its local scale.
     *
     * Pre-fix: each `quaternion =` re-decomposed the matrix; the column-length scale extraction
     * accumulated error and `scale.x` crept off 1.0 by ~1e-4 over 10 000 frames.
     * Post-fix: the setter composes from the pristine caches and never reads the matrix back.
     */
    @Test
    fun quaternionSetter_10000Frames_doesNotDriftScale() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            node.scale = Scale(1.0f)

            repeat(10_000) { i ->
                val angleRad = (i * 3f) * (Math.PI.toFloat() / 180f)
                node.quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), angleRad)
            }

            val driftX = abs(node.scale.x - 1.0f)
            val driftY = abs(node.scale.y - 1.0f)
            val driftZ = abs(node.scale.z - 1.0f)
            assertTrue(
                "Local scale must stay within 1e-6 of 1.0 after 10 000 quaternion-setter frames; " +
                    "scale=${node.scale} drift=($driftX, $driftY, $driftZ)",
                driftX < 1e-6f && driftY < 1e-6f && driftZ < 1e-6f,
            )

            node.destroy()
        }
    }

    /**
     * Spinning a node via the `rotation` (Euler) setter — which routes through `quaternion` — must
     * also keep the local scale pristine. Guards the `rotation` path explicitly.
     */
    @Test
    fun rotationSetter_10000Frames_doesNotDriftScale() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            node.scale = Scale(1.0f)

            repeat(10_000) { i ->
                node.rotation = Float3(0f, (i * 3f) % 360f, 0f)
            }

            val driftX = abs(node.scale.x - 1.0f)
            val driftY = abs(node.scale.y - 1.0f)
            val driftZ = abs(node.scale.z - 1.0f)
            assertTrue(
                "Local scale must stay within 1e-6 of 1.0 after 10 000 rotation-setter frames; " +
                    "scale=${node.scale} drift=($driftX, $driftY, $driftZ)",
                driftX < 1e-6f && driftY < 1e-6f && driftZ < 1e-6f,
            )

            node.destroy()
        }
    }

    /**
     * Driving the `position` setter every frame while the node carries a non-trivial rotation +
     * scale must not corrupt the rotation or scale via matrix decomposition. The recovered position
     * must also track the last value set, and scale must stay pristine.
     */
    @Test
    fun positionSetter_10000Frames_doesNotDriftScaleOrPosition() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            node.scale = Scale(1.0f)
            node.quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0.7f)

            var lastX = 0f
            repeat(10_000) { i ->
                lastX = i.toFloat() * 0.001f
                node.position = Position(lastX, 0f, 0f)
            }

            val scaleDrift = abs(node.scale.x - 1.0f) +
                abs(node.scale.y - 1.0f) +
                abs(node.scale.z - 1.0f)
            assertTrue(
                "Local scale must stay within 1e-6 of 1.0 after 10 000 position-setter frames; " +
                    "scale=${node.scale}",
                scaleDrift < 1e-6f,
            )

            val posDrift = abs(node.position.x - lastX)
            assertTrue(
                "Recovered position.x must equal the last value set; expected=$lastX " +
                    "actual=${node.position.x}",
                posDrift < 1e-5f,
            )

            node.destroy()
        }
    }

    /**
     * Re-setting `scale` to the same uniform value every frame must keep it exactly stable — the
     * setter must not feed the value through a lossy matrix round-trip.
     */
    @Test
    fun scaleSetter_10000Frames_isStable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            node.quaternion = Quaternion.fromAxisAngle(Float3(1f, 1f, 0f), 0.9f)

            repeat(10_000) {
                node.scale = Scale(1.0f)
            }

            val drift = abs(node.scale.x - 1.0f) +
                abs(node.scale.y - 1.0f) +
                abs(node.scale.z - 1.0f)
            assertTrue(
                "Local scale set to 1.0 every frame must stay exactly 1.0; scale=${node.scale}",
                drift < 1e-6f,
            )

            node.destroy()
        }
    }
}
