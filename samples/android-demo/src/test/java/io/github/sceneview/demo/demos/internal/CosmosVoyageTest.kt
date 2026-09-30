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
                    if (!first) assertSmoothStep(shot, t, aspect, warpIn, pose, previous)
                    pose.copyInto(previous)
                    first = false
                }
            }
        }
    }

    /**
     * A warp throws the camera fast, but never teleports it between frames; out of a warp it glides.
     * A jump's push is a share of the eye-to-target distance: its fastest frame, at the start of an
     * arrival, moves 1/8 of it at most.
     */
    private fun assertSmoothStep(
        shot: VoyageShot,
        t: Float,
        aspect: Float,
        warpIn: Boolean,
        pose: FloatArray,
        previous: FloatArray,
    ) {
        val jump = dist(pose, 0, previous, 0)
        val warping = (warpIn && t < CosmosVoyageCamera.WARP_IN_SECONDS) ||
            (shot.exit == VoyageExit.Warp && t > shot.seconds - CosmosVoyageCamera.WARP_OUT_SECONDS)
        val limit = if (warping) MAX_WARP_SHARE * dist(previous, 0, previous, 3) else MAX_GLIDE_STEP
        assertTrue("${shot.scene} at $t jumped $jump ($aspect)", jump < limit)
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
    fun `the galaxy opens and pulls back outside the core's glow`() {
        // Inside the widest core sprite (1.45 across the radius) the additive halo covers the
        // screen: the frame burns white and the frame rate halves.
        val camera = CosmosVoyageCamera()
        val shot = CosmosVoyage.shotOf(CosmosScene.Galaxy)
        for (aspect in aspects) {
            for (t in frames(shot).takeWhile { it <= GALAXY_SKIM_SECONDS }) {
                camera.evaluate(shot, t, t, aspect, arrivedByWarp = false)
                val r = dist(camera.pose, 0, floatArrayOf(0f, 0f, 0f), 0)
                assertTrue("eye at $t ($aspect): $r from the core", r > CORE_GLOW_RADIUS)
            }
        }
    }

    @Test
    fun `the galaxy only stops down in the jump into its core`() {
        val camera = CosmosVoyageCamera()
        val shot = CosmosVoyage.shotOf(CosmosScene.Galaxy)
        val jump = shot.seconds - CosmosVoyageCamera.WARP_OUT_SECONDS
        var darkest = 1f
        for (t in frames(shot)) {
            camera.evaluate(shot, t, t, 0.45f, arrivedByWarp = false)
            val exposure = CosmosVoyage.galaxyExposure(dist(camera.pose, 0, floatArrayOf(0f, 0f, 0f), 0))
            if (t < jump) assertTrue("dimmed at $t: $exposure", exposure > FULL_EXPOSURE)
            darkest = minOf(darkest, exposure)
        }
        assertTrue("never dimmed: $darkest", darkest < 0.5f)
    }

    @Test
    fun `the planet stays in frame from the approach to the pull-back`() {
        val camera = CosmosVoyageCamera()
        val rig = CosmosRig()
        val shot = CosmosVoyage.shotOf(CosmosScene.Star)
        for (aspect in aspects) {
            for (t in frames(shot).filter { it in PLANET_FROM..PLANET_TO }) {
                camera.evaluate(shot, t, t, aspect, arrivedByWarp = true)
                val planet = rig.planetPosition(t, aspect)
                val ndc = CosmosSystem.project(camera.pose, aspect, planet)
                assertTrue("planet behind the camera at $t ($aspect)", ndc != null)
                // project() assumes the default lens: a longer one magnifies in proportion.
                val zoom = camera.focal / CosmosVoyageCamera.DEFAULT_FOCAL
                val x = ndc!![0] * zoom
                val y = ndc[1] * zoom
                assertTrue("planet at x=$x, t=$t ($aspect)", abs(x) <= IN_FRAME)
                assertTrue("planet at y=$y, t=$t ($aspect)", abs(y) <= IN_FRAME)
            }
        }
    }

    @Test
    fun `captions lead their key and the next shot's opens on the switch`() {
        val camera = CosmosVoyageCamera()
        val shot = CosmosVoyage.shotOf(CosmosScene.Star)
        val key = shot.keys.first { it.at > 0f && it.caption != null }
        camera.evaluate(shot, key.at - CosmosVoyageCamera.CAPTION_LEAD_SECONDS - 0.05f, 0f, 0.45f, false)
        assertFalse(key.caption == camera.caption)
        camera.evaluate(shot, key.at - CosmosVoyageCamera.CAPTION_LEAD_SECONDS + 0.05f, 0f, 0.45f, false)
        assertEquals(key.caption, camera.caption)
        for (next in CosmosVoyage.SHOTS) {
            camera.evaluate(next, 0f, 0f, 0.45f, CosmosVoyage.arrivesByWarp(next.scene))
            assertEquals(camera.caption, CosmosVoyage.openingCaption(next.scene))
            assertTrue(camera.caption.isNotEmpty())
        }
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

    private companion object {
        /** Largest eye move between two 60 fps frames in a glide (12 units/s: the star pull-back, ~8 away). */
        const val MAX_GLIDE_STEP = 0.2f

        /** Largest eye move between two 60 fps frames in a jump, as a share of the aim distance. */
        const val MAX_WARP_SHARE = 0.125f

        const val CORE_GLOW_RADIUS = 1.45f
        const val FULL_EXPOSURE = 0.9f
        const val GALAXY_SKIM_SECONDS = 12.5f

        /** The Star shot, from its first key at the planet to the system framing. */
        const val PLANET_FROM = 11.5f
        const val PLANET_TO = 25f
        const val IN_FRAME = 0.9f
    }
}
