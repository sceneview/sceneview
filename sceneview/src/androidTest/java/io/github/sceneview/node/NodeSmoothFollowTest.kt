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
 * Engine-backed regression test for the **smooth-follow glide** that PR #2296 (#2266) preserves
 * for AR reticle nodes — the last open verification gap from the #2263 hot-path audit.
 *
 * ## Why this test exists / what it proves (and does NOT)
 *
 * #2296 made [io.github.sceneview.ar.node.PoseNode.pose] branch on `isSmoothTransformEnabled`:
 * `HitResultNode` / `DepthHitResultNode` reticles keep the smooth slerp by routing through
 * `worldTransform(position = …, quaternion = …)` (the smooth path), while high-frequency
 * anchor / plane / face nodes take the alloc-free direct write. The reviewer confirmed the
 * **wiring** of that branch, but the **visual** check ("the reticle glides across a live AR
 * plane") is impossible locally: ARCore `session_create` fails on the arm64 Mac emulator.
 *
 * The crucial observation is that the smooth-follow itself is a **[Node] / slerp behavior, not
 * ARCore-specific** — `PoseNode.pose` (smooth branch) just calls the same
 * `Node.worldTransform(position =, quaternion =, smooth = true)` overload any 3D node can call,
 * which feeds [NodeAnimationDelegate.smoothTransform] and is interpolated by
 * [NodeAnimationDelegate.onFrame]. So the glide is **deterministically testable with a real
 * Filament [com.google.android.filament.Engine] and no ARCore**, which is what this test does.
 *
 * It cannot synthesize an ARCore [com.google.ar.core.Pose] to drive [PoseNode] directly, so it
 * exercises **the exact smooth path #2296 routes reticles through** on a plain [Node]:
 * `isSmoothTransformEnabled = true` + `worldTransform(position =, quaternion =, smooth = true)`.
 * The `PoseNode.pose → worldTransform(position =, quaternion =)` wiring is the merged-and-reviewed
 * part; **this test pins the glide behavior that wiring depends on** so a future change to the
 * [Node] smooth path can never silently turn a gliding reticle into a snapping one.
 *
 * Pure-math [io.github.sceneview.animation.SmoothTransformTest] already pins the slerp factor in
 * isolation, but it cannot tick a real [Node]: per `node/UNTESTABLE.md`, `Node.onFrame()`
 * reads/writes `node.transform` through the Filament `TransformManager`. This instrumented test
 * spins up a real Engine on the emulator (same harness as [NodeWorldTransformDriftTest]) and
 * drives the full `worldTransform(smooth) → smoothTransform → onFrame → slerp → node.transform`
 * loop end to end.
 *
 * ## Asserted behavior
 *
 * 1. **Glide, not snap** — a smooth node started at the origin and given a far target *eases*
 *    toward it: strictly between start and target on the early frames, converging to the target
 *    after enough frames. (speed = 5 s⁻¹, 60 fps ⇒ ~8 % of the gap closed on frame 1.)
 * 2. **The #2296 smooth path specifically** — `worldTransform(position =, quaternion =,
 *    smooth = true)` populates `smoothTransform` (a pending glide) rather than jumping the node
 *    on the spot.
 * 3. **No-regression negative control** — the same node with `isSmoothTransformEnabled = false`
 *    fed the same target via the same overload **snaps** on the very first tick (direct write).
 *
 * Refs: #2296 #2266 #2263 #2334 (Engine harness pattern)
 */
@RunWith(AndroidJUnit4::class)
class NodeSmoothFollowTest {

    private lateinit var engine: com.google.android.filament.Engine

    /** Default smooth speed (s⁻¹) — matches [NodeAnimationDelegate.smoothTransformSpeed]. */
    private val speed = 5.0f

    /** 60 fps frame period in nanoseconds — matches the render harness' tick. */
    private val frameNanos = 16_666_667L

    /** Far target on +X so the glide is a long, unambiguous translation. */
    private val targetX = 10f

    @Before
    fun setup() {
        frameIndex = 0
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
     * Monotonic frame counter for the [tick] timeline. Reset to 0 at the start of each test
     * (a fresh node's [NodeAnimationDelegate] starts with `lastFrameTimeNanos == null`).
     */
    private var frameIndex = 0

    /**
     * Advance the node's smooth interpolation by [frames] frames on a **continuous** timeline.
     *
     * [NodeAnimationDelegate.onFrame] derives `deltaSeconds` from the gap to the previous
     * timestamp (the first tick's gap is measured from 0), so timestamps must increase
     * monotonically by one frame period across *every* call — passing raw `System.nanoTime()`
     * would make the first delta the whole machine uptime, and restarting the timeline on each
     * call would feed a zero delta (factor 0) that the delegate reads as "converged" and snaps
     * to the target. A shared, ever-increasing [frameIndex] keeps each tick exactly one 60 fps
     * period after the last.
     */
    private fun tick(node: Node, frames: Int) {
        repeat(frames) { node.onFrame((++frameIndex) * frameNanos) }
    }

    // ── 1. Glide, not snap — a smooth node eases toward a far target ───────────────

    @Test
    fun smoothNode_glidesTowardTarget_doesNotSnap() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply { isSmoothTransformEnabled = true }
            assertEquals("starts at origin", 0f, node.worldPosition.x, 1e-5f)

            // Drive it the way a HitResultNode reticle is driven (the #2296 smooth branch):
            // a world-space target via the component overload, smooth = true.
            node.worldTransform(
                position = Position(targetX, 0f, 0f),
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0f),
                smooth = true
            )

            // One frame must move PART of the way — strictly between start and target, not a jump.
            tick(node, 1)
            val afterOne = node.worldPosition.x
            assertTrue(
                "after 1 frame the smooth node must have moved off the origin (glide started), " +
                    "but x=$afterOne",
                afterOne > 1e-4f
            )
            assertTrue(
                "after 1 frame the smooth node must NOT have reached the target (no snap), " +
                    "but x=$afterOne ≈ target $targetX",
                afterOne < targetX - 0.5f
            )

            // A few more frames: monotonically closer, still short of the target (mid-glide).
            tick(node, 4)
            val afterFive = node.worldPosition.x
            assertTrue(
                "after 5 frames the glide must have progressed past the 1-frame position " +
                    "($afterFive should be > $afterOne)",
                afterFive > afterOne
            )
            assertTrue(
                "after 5 frames the glide must still be short of the target (x=$afterFive)",
                afterFive < targetX - 0.1f
            )

            // Enough frames to converge: lands on the target (delegate snaps to target within
            // its 0.001 tolerance and clears the animation).
            tick(node, 400)
            val converged = node.worldPosition.x
            assertEquals(
                "after 405 total frames the glide must have converged to the target",
                targetX, converged, 1e-3f
            )
            assertNull(
                "the smooth animation must clear itself once converged",
                node.smoothTransform
            )

            node.destroy()
        }
    }

    // ── 2. The #2296 smooth path specifically — sets a pending glide, not an instant write ──

    /**
     * The exact branch #2296 keeps for reticles: with `isSmoothTransformEnabled = true`, a
     * `worldTransform(position =, quaternion =, smooth = true)` write must register a pending
     * [Node.smoothTransform] (to be eased over subsequent frames) **without** teleporting the
     * node — that pending target is precisely the glide a `HitResultNode.pose` reassignment
     * produces every frame.
     */
    @Test
    fun smoothWorldTransform_registersPendingGlide_withoutInstantMove() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply { isSmoothTransformEnabled = true }

            node.worldTransform(
                position = Position(targetX, 0f, 0f),
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0f),
                smooth = true
            )

            // A pending smooth target was registered (the glide), and the node has NOT yet moved
            // — the move only happens on the onFrame ticks.
            assertNotNull(
                "smooth = true must register a pending smoothTransform glide target",
                node.smoothTransform
            )
            assertEquals(
                "node must not move on the spot before any frame tick",
                0f, node.worldPosition.x, 1e-5f
            )

            node.destroy()
        }
    }

    // ── 3. No-regression negative control — non-smooth snaps on the first tick ─────

    /**
     * The other arm of the #2296 branch (high-frequency direct write). With
     * `isSmoothTransformEnabled = false`, the **same** overload writes the transform
     * immediately — no `smoothTransform`, no glide. This is the control proving the test
     * actually discriminates glide from snap: it would fail if the smooth path were wired to
     * the non-smooth direct write.
     */
    @Test
    fun nonSmoothNode_snapsImmediately_negativeControl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val node = Node(engine).apply { isSmoothTransformEnabled = false }

            node.worldTransform(
                position = Position(targetX, 0f, 0f),
                quaternion = Quaternion.fromAxisAngle(Float3(0f, 1f, 0f), 0f),
                smooth = false
            )

            // Direct write: already at the target before any frame, and no pending glide.
            assertNull("non-smooth write must NOT register a smoothTransform", node.smoothTransform)
            assertEquals(
                "non-smooth write must land on the target immediately (snap)",
                targetX, node.worldPosition.x, 1e-5f
            )

            // Ticking frames changes nothing — there is no animation to advance.
            tick(node, 5)
            assertEquals(
                "non-smooth node stays put across frames (no glide to advance)",
                targetX, node.worldPosition.x, 1e-5f
            )

            node.destroy()
        }
    }
}
