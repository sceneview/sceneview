package io.github.sceneview

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.times
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class AnimationEnvelopeTest {
    @Test fun probesKeepRealCornersOnlyInCornersMode() {
        val box = Aabb(Position(2f, 3f, 4f), Position(1f, 2f, 3f))
        val corners = box.framingProbes(FitMode.Corners)
        assertEquals(8, corners.toSet().size)
        assertEquals(box.min, corners.first())
        assertEquals(box.max, corners.last())
        val silhouette = box.framingProbes(FitMode.Silhouette)
        assertEquals(6, silhouette.toSet().size)
        assertEquals(4, silhouette.count { it.y == box.center.y })
        assertTrue(Position(2f, 1f, 4f) in silhouette)
        assertTrue(Position(2f, 5f, 4f) in silhouette)
        assertTrue(corners.none { it in silhouette })
        assertTrue(Aabb().framingProbes(FitMode.Silhouette).isEmpty())
    }

    @Test fun fleshMarginIsAsymmetricAndRestIsIncludedSeparately() {
        val rest = Aabb.fromMinMax(Position(-2f, -1f, -3f), Position(3f, 4f, 5f))
        val joints = Aabb.fromMinMax(Position(-1f, 0f, -1f), Position(1f, 3f, 2f))
        val posed = joints.copy(center = joints.center + Position(5f, 2f, -3f))
        assertEquals(rest, fleshBounds(rest, joints, joints))
        val pose = fleshBounds(rest, joints, posed)
        assertEquals(rest.center + Position(5f, 2f, -3f), pose.center)
        val envelope = AnimationEnvelope(listOf(rest, pose))
        assertEquals(rest.union(pose), envelope.bounds)
        assertEquals(12, envelope.silhouette.size)
        // No top centre of the union was invented between different moments in the clip.
        assertFalse(Position(envelope.bounds.center.x, envelope.bounds.max.y,
            envelope.bounds.center.z) in envelope.silhouette)
    }

    @Test fun singleJointAndJointsOutsideRestDoNotLoseTheRestBox() {
        val rest = Aabb(halfExtent = Position(1f))
        val single = boundsOfPoints(listOf(Position(0f)))
        assertEquals(rest, fleshBounds(rest, single, single))
        val outside = Aabb(halfExtent = Position(3f))
        assertEquals(outside, fleshBounds(rest, outside, outside))
    }

    @Test fun transformationPreservesEachProbeRatherThanInventingRotatedBoxCorners() {
        val envelope = creature()
        val transform = Transform(Position(3f, 4f, 5f), Rotation(10f, 35f, 0f), Scale(2f, 1f, 3f))
        val moved = envelope.transformed(transform)
        assertEquals(envelope.silhouette.map { transform * it }, moved.silhouette)
        moved.corners.forEach { assertContains(moved.bounds, it) }
    }

    private fun creature(): AnimationEnvelope {
        val rest = Aabb(Position(0f, 1f, 0f), Position(0.6f, 1f, 1.2f))
        val restJoints = Aabb(Position(0f, 1f, 0f), Position(0.4f, 0.8f, 1f))
        val jointPoses = listOf(
            Aabb(Position(-0.3f, 1.1f, 0.1f), Position(0.5f, 0.9f, 0.9f)),
            Aabb(Position(0.25f, 1f, -0.2f), Position(0.45f, 0.8f, 1.1f))
        )
        return AnimationEnvelope(listOf(rest) + jointPoses.map { fleshBounds(rest, restJoints, it) })
    }

    @Test fun silhouetteIncreasesProjectedSubjectHeightWithoutCroppingAnySampledProbe() {
        val envelope = creature()
        val direction = Position(-0.65f, -0.35f, -0.68f)
        val corners = fitCameraToBounds(envelope.bounds, direction, 46.4, 0.8,
            fitMode = FitMode.Corners)!!
        val silhouette = fitCameraToBounds(envelope.bounds, direction, 46.4, 0.8,
            fitMode = FitMode.Silhouette, animationEnvelope = envelope)!!
        val oldPicture = project(envelope.silhouette, corners, 0.8)
        val picture = project(envelope.silhouette, silhouette, 0.8)
        fun occupancy(p: List<Pair<Double, Double>>) = (p.maxOf { it.second } - p.minOf { it.second }) / 2
        println("Synthetic clip occupancy: Corners=${occupancy(oldPicture)}, Silhouette=${occupancy(picture)}")
        assertTrue(occupancy(picture) > occupancy(oldPicture))
        picture.forEach { (x, y) -> assertTrue(abs(x) <= 0.80001 && abs(y) <= 0.80001) }
    }

    @Test fun everyPoseFitsAtPortraitLandscapeAndOverheadAngles() {
        val envelope = creature()
        for (aspect in listOf(0.45, 1.0, 2.1)) for (azimuth in listOf(0.0, 45.0, 130.0)) {
            for (elevation in listOf(-25.0, 0.0, 30.0, 90.0)) for (mode in FitMode.entries) {
                val az = Math.toRadians(azimuth); val el = Math.toRadians(elevation)
                val direction = Position((-sin(az) * cos(el)).toFloat(), -sin(el).toFloat(),
                    (-cos(az) * cos(el)).toFloat())
                val fit = fitCameraToBounds(envelope.bounds, direction, 46.4, aspect,
                    fitMode = mode, animationEnvelope = envelope)!!
                val probes = if (mode == FitMode.Corners) envelope.corners else envelope.silhouette
                project(probes, fit, aspect).forEach { (x, y) ->
                    assertTrue("$aspect/$azimuth/$elevation/$mode: $x,$y", abs(x) <= 0.80001 && abs(y) <= 0.80001)
                }
            }
        }
    }

    @Test fun explicitCornersMatchesDefaultExactly() {
        val box = creature().bounds
        val direction = Position(-0.6f, -0.4f, -0.7f)
        assertEquals(fitCameraToBounds(box, direction, 45.0, 0.7),
            fitCameraToBounds(box, direction, 45.0, 0.7, fitMode = FitMode.Corners))
    }

    @Test fun reportPureEnvelopeAndFitCost() {
        // Native Animator cost is measured by AnimationEnvelopeRenderTest on a device.
        val rest = creature().bounds
        val poses = List(24) { i -> rest.copy(center = rest.center + Position(sin(i.toFloat()) * 0.3f, 0f, 0f)) }
        repeat(100) { fitCameraToBounds(rest, verticalFovDegrees = 46.4, aspect = 0.8) }
        val start = System.nanoTime()
        repeat(1000) {
            val envelope = AnimationEnvelope(poses)
            assertNotNull(fitCameraToBounds(rest, verticalFovDegrees = 46.4, aspect = 0.8,
                fitMode = FitMode.Silhouette, animationEnvelope = envelope))
        }
        println("24-pose envelope + fit JVM mean: ${(System.nanoTime() - start) / 1_000_000.0 / 1000} ms")
    }

    private fun project(probes: List<Position>, fit: CameraFit, aspect: Double): List<Pair<Double, Double>> {
        fun normalize(v: Position): Position = v / sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        fun cross(a: Position, b: Position) = Position(a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)
        fun dot(a: Position, b: Position) = (a.x * b.x + a.y * b.y + a.z * b.z).toDouble()
        val forward = normalize(fit.target - fit.eye)
        val rawRight = cross(forward, Position(0f, 1f, 0f))
        val right = if (abs(rawRight.x) + abs(rawRight.z) < 1e-6f) Position(1f, 0f, 0f) else normalize(rawRight)
        val up = cross(right, forward)
        val tanY = tan(Math.toRadians(46.4) / 2)
        return probes.map {
            val delta = it - fit.eye
            val depth = dot(delta, forward)
            assertTrue("probe behind camera", depth > 0)
            dot(delta, right) / (depth * tanY * aspect) to dot(delta, up) / (depth * tanY)
        }
    }

    private fun assertContains(box: Aabb, p: Position) {
        assertTrue(p.x >= box.min.x - 1e-5 && p.x <= box.max.x + 1e-5)
        assertTrue(p.y >= box.min.y - 1e-5 && p.y <= box.max.y + 1e-5)
        assertTrue(p.z >= box.min.z - 1e-5 && p.z <= box.max.z + 1e-5)
    }
}
