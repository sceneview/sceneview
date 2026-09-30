package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CosmosVoyageTest {

    private val aspects = floatArrayOf(1080f / 2400f, 1f, 2400f / 1080f)
    private val step = 1f / 60f

    private fun dist(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Float {
        val dx = a[ao] - b[bo]
        val dy = a[ao + 1] - b[bo + 1]
        val dz = a[ao + 2] - b[bo + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun frames(shot: VoyageShot): Sequence<Float> =
        generateSequence(0f) { it + step }.takeWhile { it <= shot.seconds }

    @Test
    fun `every scene has exactly one shot and the loop visits them all`() {
        for (scene in CosmosScene.entries) {
            assertEquals(1, CosmosVoyage.SHOTS.count { it.scene == scene })
        }
        var scene = CosmosVoyage.SHOTS.first().scene
        val seen = mutableSetOf<CosmosScene>()
        repeat(CosmosScene.entries.size) {
            seen += scene
            scene = CosmosVoyage.next(scene)
        }
        assertEquals(CosmosScene.entries.toSet(), seen)
        assertEquals(CosmosVoyage.SHOTS.first().scene, scene)
    }

    @Test
    fun `a jump out of one shot is the arrival of the next`() {
        for (shot in CosmosVoyage.SHOTS) {
            assertEquals(shot.exit == VoyageExit.Warp, CosmosVoyage.arrivesByWarp(CosmosVoyage.next(shot.scene)))
        }
    }

    @Test
    fun `poses are finite and continuous at 60 fps on every screen shape`() {
        val camera = CosmosVoyageCamera()
        val previous = FloatArray(CosmosSystem.POSE_FLOATS)
        for (aspect in aspects) {
            for (shot in CosmosVoyage.SHOTS) {
                val warpIn = CosmosVoyage.arrivesByWarp(shot.scene)
                var first = true
                for (t in frames(shot)) {
                    camera.evaluate(shot, t, t, aspect, warpIn)
                    val pose = camera.pose
                    for (v in pose) assertTrue("${shot.scene} t=$t", v.isFinite())
                    assertTrue(camera.focal.isFinite() && camera.focal > 5f)
                    // The up vector is a unit vector square to the view axis.
                    val up = sqrt(pose[6] * pose[6] + pose[7] * pose[7] + pose[8] * pose[8])
                    assertEquals(1f, up, 1e-3f)
                    if (!first) {
                        // A warp throws the camera fast, but never teleports it between frames.
                        val jump = dist(pose, 0, previous, 0)
                        assertTrue("${shot.scene} at $t jumped $jump", jump < 1f)
                    }
                    pose.copyInto(previous)
                    first = false
                }
            }
        }
    }

    @Test
    fun `the star shot never flies through the star or the planet`() {
        val camera = CosmosVoyageCamera()
        val rig = CosmosRig()
        val shot = CosmosVoyage.shotOf(CosmosScene.Star)
        for (aspect in aspects) {
            for (t in frames(shot)) {
                camera.evaluate(shot, t, t, aspect, arrivedByWarp = true)
                val eye = camera.pose
                val star = sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2])
                assertTrue("star at $t ($aspect): $star", star > 1.3f)
                val planet = rig.planetPosition(t, aspect)
                assertTrue("planet at $t ($aspect)", dist(eye, 0, planet, 0) > 0.4f)
            }
        }
    }

    @Test
    fun `transitions reach black at the end of a fade or a jump`() {
        val camera = CosmosVoyageCamera()
        for (shot in CosmosVoyage.SHOTS) {
            camera.evaluate(shot, shot.seconds, shot.seconds, 0.45f, arrivedByWarp = false)
            when (shot.exit) {
                VoyageExit.Warp -> {
                    assertEquals(0f, camera.fade, 1e-4f)
                    assertTrue(camera.streaks > 0.9f)
                }
                VoyageExit.Fade -> assertEquals(0f, camera.fade, 1e-4f)
                VoyageExit.Cut -> assertEquals(1f, camera.fade, 1e-4f)
            }
            // Mid-shot, the scene is fully lit and no streak shows.
            camera.evaluate(shot, shot.seconds / 2f, shot.seconds / 2f, 0.45f, arrivedByWarp = false)
            assertEquals(1f, camera.fade, 1e-4f)
            assertEquals(0f, camera.streaks, 1e-4f)
        }
    }

    @Test
    fun `a jump arrives out of the streaks and settles`() {
        val camera = CosmosVoyageCamera()
        val shot = CosmosVoyage.shotOf(CosmosScene.Burst)
        camera.evaluate(shot, 0f, 0f, 0.45f, arrivedByWarp = true)
        assertTrue(camera.streaks > 0.9f)
        camera.evaluate(shot, CosmosVoyageCamera.WARP_IN_SECONDS, 0f, 0.45f, arrivedByWarp = true)
        assertEquals(0f, camera.streaks, 1e-4f)
    }

    @Test
    fun `the flow shot only pushes in, so the field's edge never shows`() {
        val shot = CosmosVoyage.shotOf(CosmosScene.Flow)
        for (key in shot.keys) {
            assertEquals(VoyageAnchor.Framing, key.anchor)
            assertTrue(key.focal >= CosmosVoyageCamera.DEFAULT_FOCAL)
            assertTrue(key.eye.all { it == 0f })
            assertTrue(key.target.all { abs(it) <= 0.2f })
        }
    }

    @Test
    fun `captions follow the keys`() {
        val camera = CosmosVoyageCamera()
        val shot = CosmosVoyage.shotOf(CosmosScene.Star)
        camera.evaluate(shot, 0f, 0f, 0.45f, arrivedByWarp = false)
        assertEquals(shot.keys.first().caption, camera.caption)
        camera.evaluate(shot, shot.seconds, shot.seconds, 0.45f, arrivedByWarp = false)
        assertEquals(shot.keys.last { it.caption != null }.caption, camera.caption)
    }

    @Test
    fun `dragging the free camera orbits its target at the same distance`() {
        val pose = floatArrayOf(0f, 1f, 5f, 0.2f, 0f, 0f, 0f, 1f, 0f)
        val target = pose.copyOfRange(3, 6)
        val before = dist(pose, 0, pose, 3)
        val scratch = FloatArray(15)
        orbitPose(pose, 40f, -25f, scratch)
        assertEquals(before, dist(pose, 0, pose, 3), 1e-4f)
        for (i in 0..2) assertEquals(target[i], pose[3 + i], 0f)
        assertFalse(pose[0] == 0f && pose[2] == 5f)
        val up = sqrt(pose[6] * pose[6] + pose[7] * pose[7] + pose[8] * pose[8])
        assertEquals(1f, up, 1e-4f)
    }

    @Test
    fun `a shot rejects keys out of order`() {
        val o = floatArrayOf(0f, 0f, 0f)
        val result = runCatching {
            VoyageShot(
                CosmosScene.Galaxy,
                listOf(VoyageKey(2f, VoyageAnchor.World, o, o), VoyageKey(1f, VoyageAnchor.World, o, o)),
            )
        }
        assertTrue(result.isFailure)
    }
}
