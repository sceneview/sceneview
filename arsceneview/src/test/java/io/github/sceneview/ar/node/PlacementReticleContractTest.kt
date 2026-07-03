package io.github.sceneview.ar.node

import dev.romainguy.kotlin.math.Quaternion
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless contract tests for `PlacementReticleNode` (#2241 Sprint-1, PR 4).
 *
 * Two halves:
 * - behavioral tests on [ReticleOrientationSmoother], the pure Depth Lab
 *   `OrientedReticle` damping (no ARCore / Filament needed);
 * - regex-pin tests on the node + `ARSceneScope.PlacementReticle` sources for the
 *   contracts that need an `Engine` to exercise at runtime (defaults, plane-only
 *   acceptance mapping, smoothing reset on `null`, default-visual lifecycle).
 */
class PlacementReticleContractTest {

    // ── ReticleOrientationSmoother — behaviour ───────────────────────────────────────────

    private val identity = Quaternion(0f, 0f, 0f, 1f)

    /** 90° around Y. */
    private val target = Quaternion(0f, sqrt(0.5f).toFloat(), 0f, sqrt(0.5f).toFloat())

    private fun angleDegreesTo(q: Quaternion, other: Quaternion): Float {
        val dot = abs(q.x * other.x + q.y * other.y + q.z * other.z + q.w * other.w)
            .coerceIn(0.0f, 1.0f)
        return Math.toDegrees(2.0 * Math.acos(dot.toDouble())).toFloat()
    }

    @Test
    fun `first sample is applied verbatim - no roll-in from identity`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `subsequent samples converge exponentially toward the target`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        smoother.smooth(identity)
        val step1 = smoother.smooth(target)
        // One 0.75 slerp step across 90° leaves ~22.5° of error.
        assertEquals(22.5f, angleDegreesTo(step1, target), 0.5f)
        val step2 = smoother.smooth(target)
        // Each further step damps the remaining error by 4x.
        assertEquals(5.6f, angleDegreesTo(step2, target), 0.5f)
        assertTrue(
            "smoothing must be monotonic",
            angleDegreesTo(step2, target) < angleDegreesTo(step1, target)
        )
    }

    @Test
    fun `smoothing = 1 applies the raw orientation`() {
        val smoother = ReticleOrientationSmoother(smoothing = 1.0f)
        smoother.smooth(identity)
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `reset drops the damping state - next sample verbatim`() {
        val smoother = ReticleOrientationSmoother(smoothing = 0.75f)
        smoother.smooth(identity)
        smoother.reset()
        val out = smoother.smooth(target)
        assertEquals(0.0f, angleDegreesTo(out, target), 0.1f)
    }

    @Test
    fun `smoothing values outside 0-1 are coerced`() {
        assertEquals(1.0f, ReticleOrientationSmoother(5.0f).smoothing, 0.0f)
        assertEquals(0.0f, ReticleOrientationSmoother(-1.0f).smoothing, 0.0f)
        val smoother = ReticleOrientationSmoother(0.5f)
        smoother.smoothing = 2.0f
        assertEquals(1.0f, smoother.smoothing, 0.0f)
    }

    // ── Source regex-pins — Engine-dependent contracts ───────────────────────────────────

    private val nodeFile = File("src/main/java/io/github/sceneview/ar/node/PlacementReticleNode.kt")
    private val scopeFile = File("src/main/java/io/github/sceneview/ar/ARSceneScope.kt")

    private val nodeSource: String by lazy {
        assertTrue("missing ${nodeFile.absolutePath}", nodeFile.exists())
        nodeFile.readText()
    }
    private val scopeSource: String by lazy {
        assertTrue("missing ${scopeFile.absolutePath}", scopeFile.exists())
        scopeFile.readText()
    }

    @Test
    fun `Depth Lab default smoothing is 0_75`() {
        assertTrue(
            "DEFAULT_ORIENTATION_SMOOTHING must stay 0.75 (Depth Lab OrientedReticle)",
            Regex("""const val DEFAULT_ORIENTATION_SMOOTHING = 0\.75f""")
                .containsMatchIn(nodeSource)
        )
    }

    @Test
    fun `snapToPlane maps to the planeTypes acceptance set`() {
        assertTrue(
            "snapToPlane must gate planeTypes (all types vs emptySet)",
            Regex("""planeTypes = if \(snapToPlane\) Plane\.Type\.values\(\)\.toSet\(\) else emptySet\(\)""")
                .containsMatchIn(nodeSource)
        )
    }

    @Test
    fun `null hit resets the smoother so the next surface is acquired verbatim`() {
        val setterWindow = nodeSource.substringAfter("override var hitResult")
        assertTrue(
            "hitResult setter must reset the smoother on null",
            setterWindow.contains("smoother.reset()")
        )
        assertTrue(
            "the resolveHitPose hook must apply the smoothed rotation (single pose write " +
                "per frame — no raw-then-smoothed double write)",
            nodeSource.contains("override fun resolveHitPose(hitPose: Pose): Pose") &&
                nodeSource.contains("smoother.smooth(hitPose.quaternion)")
        )
    }

    @Test
    fun `composable has exactly ONE NodeLifecycle call site (no content-branch destroy)`() {
        val composable = scopeSource.substringAfter("fun PlacementReticle(")
            .substringBefore("// ── DepthHitResultNode")
        assertEquals(
            "branching between two NodeLifecycle calls on `content != null` destroys the " +
                "remembered node when a host flips custom/default visuals (use-after-destroy)",
            1,
            Regex("""NodeLifecycle\(node""").findAll(composable).count()
        )
    }

    @Test
    fun `composable keeps the live knobs in sync on recomposition (#2506 class)`() {
        val composable = scopeSource.substringAfter("fun PlacementReticle(")
            .substringBefore("// ── DepthHitResultNode")
        assertTrue(
            "SideEffect must sync onHitResultChanged",
            composable.contains("node.onHitResultChanged = onHitResultChanged")
        )
        assertTrue(
            "SideEffect must sync orientationSmoothing",
            composable.contains("node.orientationSmoothing = orientationSmoothing")
        )
    }

    @Test
    fun `default visual material is destroyed with the composable (#2458 class)`() {
        val composable = scopeSource.substringAfter("fun PlacementReticle(")
            .substringBefore("// ── DepthHitResultNode")
        assertTrue(
            "the built-in disc material must be destroyed on dispose",
            composable.contains("materialLoader.destroyMaterialInstance(reticleMaterial)")
        )
    }
}
