package io.github.sceneview.node

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.filament.Engine
import com.google.android.filament.Filament
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.Utils
import dev.romainguy.kotlin.math.distance
import io.github.sceneview.createEglContext
import io.github.sceneview.createEngine
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.managers.getTransform
import io.github.sceneview.managers.setTransform
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.safeDestroy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine-backed regression test for the redundant-transform-write guard (#3718).
 *
 * [Node] skips a transform write whose composed matrix equals `_transform`, the mirror of what the
 * node last pushed to Filament — that guard is what stops a settled per-frame producer from holding
 * a render-on-demand scene at full cadence. The mirror is only Filament's own state as long as
 * nothing writes the entity **behind the node's back**, which the library itself does in two
 * places: glTF animation (`Animator.applyAnimation`, every sub-node of a [ModelNode]) and the
 * camera helpers (`Camera.lookAt` / `Camera.setModelMatrix`, [CameraNode]).
 *
 * Each test below plays the same three-beat scenario with REAL nodes on a real [Engine]:
 * 1. the application writes a pose through the node — the mirror now holds it;
 * 2. something moves the entity out of band — Filament is elsewhere, the mirror is stale;
 * 3. the application re-asserts **the same pose it already owns**.
 *
 * Filament must end up carrying the node's value. Without [Node.invalidateTransformCache] wired
 * into the two out-of-band writers, step 3 matches the stale mirror, the write is dropped as
 * redundant, and the entity stays on the out-of-band pose for good (a glTF sub-node freezes on its
 * last keyframe; a camera stays where `lookAt` put it).
 *
 * Step 2 is asserted, not assumed: if the out-of-band write did not actually move the entity the
 * test fails there rather than passing vacuously.
 */
@RunWith(AndroidJUnit4::class)
class NodeOutOfBandTransformWriteTest {

    private lateinit var engine: Engine
    private var modelLoader: ModelLoader? = null

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
            modelLoader?.destroy()
            engine.safeDestroy()
        }
    }

    /** Reads the entity's local matrix straight from Filament, bypassing every node cache. */
    private fun filamentTransform(node: Node): Transform =
        engine.transformManager.let { it.getTransform(it.getInstance(node.entity)) }

    private fun assertPosition(message: String, expected: Position, node: Node) {
        val actual = filamentTransform(node).position
        assertEquals("$message (x)", expected.x, actual.x, 1e-5f)
        assertEquals("$message (y)", expected.y, actual.y, 1e-5f)
        assertEquals("$message (z)", expected.z, actual.z, 1e-5f)
    }

    private fun movedAwayFrom(node: Node, pose: Position): Boolean =
        distance(filamentTransform(node).position, pose) > 1e-3f

    /**
     * The blocker's first scenario: a glTF animation moves the sub-nodes of a [ModelNode] through
     * the `TransformManager` directly, so a later `subNode.position = p` re-asserting the pose the
     * application had set before the animation ran must still reach Filament.
     *
     * Uses the Khronos Fox (3 skeletal animations); the animated sub-node is discovered by playing
     * a frame and diffing Filament's matrices, so the test does not hard-code an asset's node names.
     */
    @Test
    fun gltfAnimationThenRewritingTheSamePose_reachesFilament() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val loader = ModelLoader(engine, InstrumentationRegistry.getInstrumentation().context)
                .also { modelLoader = it }
            val modelNode = ModelNode(
                modelInstance = loader.createModelInstance("khronos_fox.glb"),
                autoAnimate = false
            )
            assertTrue("The test asset must carry at least one animation", modelNode.animationCount > 0)

            // Find a sub-node the animation actually drives, from Filament's own matrices.
            val before = modelNode.nodes.associateWith { filamentTransform(it) }
            modelNode.playAnimation(0, loop = true)
            modelNode.onFrame(System.nanoTime() + 300_000_000L)
            val animated = requireNotNull(
                modelNode.nodes.firstOrNull { filamentTransform(it) != before[it] }
            ) { "The animation must move at least one sub-node" }

            // 1. The application owns a pose for that sub-node.
            val pose = Position(x = 0.25f, y = 0.5f, z = 0.75f)
            animated.position = pose
            assertPosition("in-band write must reach Filament", pose, animated)

            // 2. The animation writes the entity again, out of band.
            modelNode.onFrame(System.nanoTime() + 600_000_000L)
            assertTrue(
                "instrument check: the animation must have moved the sub-node off the " +
                    "application pose, otherwise this test proves nothing",
                movedAwayFrom(animated, pose)
            )
            modelNode.stopAnimation(0)

            // 3. The application re-asserts the very same pose.
            animated.position = pose
            assertPosition(
                "re-writing the pose the node already believed it had pushed must reach " +
                    "Filament: the animator moved the entity since",
                pose, animated
            )

            modelNode.destroy()
        }
    }

    /**
     * The blocker's second scenario: [io.github.sceneview.components.CameraComponent.lookAt] writes
     * the camera entity's transform through the Filament `Camera`, never through the node.
     */
    @Test
    fun cameraLookAtThenRewritingTheSamePose_reachesFilament() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cameraNode = CameraNode(engine)

            val pose = Position(x = 1.0f, y = 2.0f, z = 3.0f)
            cameraNode.position = pose
            assertPosition("in-band write must reach Filament", pose, cameraNode)

            cameraNode.lookAt(
                eye = Position(x = 5.0f, y = 6.0f, z = 7.0f),
                center = Position(),
                up = Direction(y = 1.0f)
            )
            assertTrue(
                "instrument check: lookAt must have moved the camera entity off the pose",
                movedAwayFrom(cameraNode, pose)
            )

            cameraNode.position = pose
            assertPosition(
                "re-writing the pose the node already believed it had pushed must reach " +
                    "Filament: lookAt moved the camera entity since",
                pose, cameraNode
            )

            cameraNode.destroy()
        }
    }

    /**
     * The escape hatch for the third writer the library cannot see: application code writing
     * [Node.entity]'s transform itself. [Node.invalidateTransformCache] is what makes that legal.
     */
    @Test
    fun thirdPartyWriteThenInvalidate_letsTheSamePoseThrough() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)
            val tm = engine.transformManager

            val pose = Position(x = -1.5f, y = 4.0f, z = 0.5f)
            node.position = pose

            tm.setTransform(
                tm.getInstance(node.entity),
                Transform(position = Position(x = 9.0f, y = 9.0f, z = 9.0f))
            )
            node.invalidateTransformCache()

            node.position = pose
            assertPosition(
                "after invalidateTransformCache(), re-writing the same pose must reach Filament",
                pose, node
            )

            node.destroy()
        }
    }

    /**
     * `Transform` is a `Mat4`, mutable in place: a caller reusing one scratch matrix per frame must
     * not be able to mutate the node's cache — and through it, what the redundant-write guard
     * compares against — after the write.
     */
    @Test
    fun transformSetterCachesACopyOfTheCallerMatrix() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine)

            val scratch = Transform(position = Position(x = 1.0f, y = 2.0f, z = 3.0f))
            node.transform = scratch

            // The caller reuses its scratch matrix for the next frame's computation.
            scratch.w.x = 42.0f

            assertEquals(
                "the cached transform must still describe what Filament holds",
                filamentTransform(node), node.transform
            )

            node.destroy()
        }
    }
}
