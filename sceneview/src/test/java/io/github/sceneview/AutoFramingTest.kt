package io.github.sceneview

import com.google.android.filament.Box
import io.github.sceneview.math.Position
import io.github.sceneview.model.AnimationEnvelope
import io.github.sceneview.node.AnimationCompositionTracker
import io.github.sceneview.node.ModelNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

class AutoFramingTest {
    @Test
    fun `automatic mode truth table`() {
        assertEquals(FitMode.Corners, resolveAutoFitMode(0, hasStaticContent = false))
        assertEquals(FitMode.Corners, resolveAutoFitMode(0, hasStaticContent = true))
        assertEquals(FitMode.Corners, resolveAutoFitMode(1, hasStaticContent = true))
        assertEquals(FitMode.Silhouette, resolveAutoFitMode(1, hasStaticContent = false))
        assertEquals(FitMode.Silhouette, resolveAutoFitMode(3, hasStaticContent = false))
        assertEquals(
            FitMode.Corners,
            resolveAutoFitMode(
                1, hasStaticContent = false, requiresMeasuredPose = true
            )
        )
    }

    @Test
    fun `concurrent clips retain their application order for composed sampling`() {
        val tracker = AnimationCompositionTracker()

        tracker.record(linkedMapOf(
            0 to ModelNode.PlayingAnimation(),
            1 to ModelNode.PlayingAnimation(),
        ))

        assertTrue(tracker.hasRetainedComposition)
        assertEquals(listOf(0, 1), tracker.retainedClips)
    }

    @Test
    fun `sequential clips retain a composed pose`() {
        val tracker = AnimationCompositionTracker()
        tracker.record(mapOf(0 to ModelNode.PlayingAnimation()))

        tracker.record(mapOf(1 to ModelNode.PlayingAnimation()))

        assertTrue(tracker.hasRetainedComposition)
        assertEquals(1, tracker.lastAppliedClip)
    }

    @Test
    fun `a clip replacing every earlier channel drops the stale composition`() {
        val tracker = AnimationCompositionTracker()
        tracker.record(mapOf(0 to ModelNode.PlayingAnimation()))

        tracker.record(mapOf(1 to ModelNode.PlayingAnimation())) { covering, covered ->
            covering == 1 && covered == 0
        }

        assertTrue(!tracker.hasRetainedComposition)
        assertEquals(listOf(1), tracker.retainedClips)
        assertEquals(1, tracker.lastAppliedClip)
    }

    @Test
    fun `animation envelopes require every owned renderable to be visible`() {
        assertTrue(qualifiesForAnimationEnvelope(listOf(true, true)))
        assertTrue(!qualifiesForAnimationEnvelope(listOf(true, false)))
        assertTrue(!qualifiesForAnimationEnvelope(listOf(false, false)))
        assertTrue(!qualifiesForAnimationEnvelope(emptyList()))
    }

    @Test
    fun `playing clips win are validated and capped by playback precedence`() {
        assertEquals(listOf(4, 2, 1), autoFitClips(listOf(4, 2, 9, -1, 1), 5))
        assertEquals((4 until 12).toList(), autoFitClips(emptyList(), 12))
        assertEquals(listOf(2, 1), autoFitClips(listOf(2, 1), 5, max = 2))
        assertTrue(autoFitClips(emptyList(), 0).isEmpty())
        assertTrue(autoFitClips(listOf(0), 0).isEmpty())
    }

    @Test
    fun `clip cap keeps the last applied pose controller`() {
        val clips = autoFitClips(emptyList(), animationCount = 9, retainedClip = 0)

        assertEquals(MAX_AUTO_FIT_CLIPS, clips.size)
        assertEquals((2..8).toList() + 0, clips)
        assertTrue(clips.contains(0))
    }

    @Test
    fun `paused selection keeps the clip that last wrote the displayed pose`() {
        val clips = autoFitClips(
            playing = emptyList(),
            animationCount = 2,
            retainedClip = 0,
            playbackPresent = true,
        )

        assertEquals(listOf(0), clips)
        assertTrue(
            AutoFramingPlayback(1, clips = emptyList(), pausedClips = listOf(1), retainedClip = 0) !=
                AutoFramingPlayback(1, clips = listOf(1), retainedClip = 1)
        )
    }

    @Test
    fun `playback keys keep only non-owning model identities`() {
        val model = Any()

        val playback = AutoFramingPlayback(System.identityHashCode(model), listOf(0))

        assertEquals(System.identityHashCode(model), playback.modelIdentity)
        assertEquals(Int::class, playback.modelIdentity::class)
    }

    @Test
    fun `merged envelopes keep poses separate and silhouette probes fit`() {
        val rest = Box(0f, 0f, 0f, 0.1f, 0.2f, 0.1f)
        val left = Box(-1f, 0.2f, 0.1f, 0.35f, 1.2f, 0.4f)
        val right = Box(1f, -0.1f, -0.2f, 0.45f, 0.9f, 0.5f)
        val mergedOrNull = mergeEnvelopes(
            listOf(AnimationEnvelope(listOf(rest, left)), AnimationEnvelope(listOf(rest, right)))
        )
        assertNotNull(mergedOrNull)
        val merged = mergedOrNull!!
        assertEquals(3, merged.poses.size) // One rest pose, plus both sampled poses.

        val bounds = merged.bounds.toAabb()
        val direction = Position(-0.35f, -0.45f, -0.82f)
        val silhouette = fitCameraToBounds(
            bounds, direction, 46.4, 0.81, 0.92f, 0.84f,
            FitMode.Silhouette, merged,
        )!!
        val corners = fitCameraToBounds(bounds, direction, 46.4, 0.81, fitMode = FitMode.Corners)!!
        assertTrue(silhouette.distance <= corners.distance)
        merged.poses.forEach { pose ->
            pose.toAabb().framingProbes(FitMode.Silhouette).forEach { probe ->
                val (x, y) = project(probe, silhouette, 46.4, 0.81)
                assertTrue("x=$x", abs(x) <= 0.92 + 2e-3)
                assertTrue("y=$y", abs(y) <= 0.84 + 2e-3)
            }
        }
    }

    private fun project(
        point: Position,
        fit: CameraFit,
        fovDegrees: Double,
        aspect: Double,
    ): Pair<Double, Double> {
        fun normalized(x: Double, y: Double, z: Double): DoubleArray {
            val length = sqrt(x * x + y * y + z * z)
            return doubleArrayOf(x / length, y / length, z / length)
        }
        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )
        val forward = normalized(
            (fit.target.x - fit.eye.x).toDouble(),
            (fit.target.y - fit.eye.y).toDouble(),
            (fit.target.z - fit.eye.z).toDouble(),
        )
        var right = cross(forward, doubleArrayOf(0.0, 1.0, 0.0))
        right = normalized(right[0], right[1], right[2])
        val up = cross(right, forward)
        val v = doubleArrayOf(
            (point.x - fit.eye.x).toDouble(),
            (point.y - fit.eye.y).toDouble(),
            (point.z - fit.eye.z).toDouble(),
        )
        val depth = v.indices.sumOf { v[it] * forward[it] }
        val tanY = tan(Math.toRadians(fovDegrees) / 2.0)
        val x = v.indices.sumOf { v[it] * right[it] } / (depth * tanY * aspect)
        val y = v.indices.sumOf { v[it] * up[it] } / (depth * tanY)
        return x to y
    }
}
