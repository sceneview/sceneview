package io.github.sceneview.ar

import com.google.ar.core.Config
import dev.romainguy.kotlin.math.Float3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** The numbers and the geometry behind the default AR placement, pinned on the JVM. */
class PlacementFeelTest {

    // ── The feel: same numbers as the iOS viewer ─────────────────────────────────────────

    @Test
    fun `the feel matches the iOS viewer`() {
        assertEquals(600L, PlacementFeel.ESTIMATED_FALLBACK_MS)
        assertEquals(5f, PlacementFeel.MAX_SURFACE_DISTANCE_M)
        assertEquals(1.3f, PlacementFeel.PHONE_HEIGHT_GUESS_M)
        assertEquals(0.4f, PlacementFeel.FLOOR_BELOW_CAMERA_M)
        assertEquals(18f, PlacementFeel.DRAG_SPEED)
        assertEquals(6f, PlacementFeel.SETTLE_SPEED)
        assertEquals(0.01f, PlacementFeel.APPEAR_SCALE)
        assertEquals(1.6f, PlacementFeel.SHADOW_SPREAD)
        assertEquals(PlacementFeel.APPEAR_SCALE, PlacementEntrance.START_FRACTION)
    }

    @Test
    fun `a thrown drag glides on and slows down to a stop`() {
        val glide = PlacementFeel.moveCoast()
        // 1 000 px/s to the right over 50 ms.
        for (i in 0..5) glide.addSample(i * 0.01, i * 10f, 0f)
        assertTrue(glide.release(0.05))
        val step = FloatArray(2)
        var travelled = 0f
        var frames = 0
        while (glide.advance(1f / 60f, step) && frames < 600) {
            assertTrue("never turns back", step[0] >= 0f)
            travelled += step[0]
            frames++
        }
        assertTrue("it glides on: $travelled px", travelled > 50f)
        assertTrue("and stops", frames < 600)
    }

    @Test
    fun `a slow release does not glide`() {
        val glide = PlacementFeel.moveCoast()
        for (i in 0..5) glide.addSample(i * 0.01, i * 0.5f, 0f) // 50 px/s
        assertFalse(glide.release(0.05))
    }

    @Test
    fun `a flicked twist spins on, a slow one does not`() {
        val fast = PlacementFeel.twistCoast()
        for (i in 0..5) fast.addSample(i * 0.01, i * 3f) // 300 °/s
        assertTrue(fast.release(0.05))
        val step = FloatArray(1)
        assertTrue(fast.advance(1f / 60f, step))
        assertTrue(step[0] > 0f)

        val slow = PlacementFeel.twistCoast()
        for (i in 0..5) slow.addSample(i * 0.01, i * 0.2f) // 20 °/s
        assertFalse(slow.release(0.05))
    }

    // ── Distances and the floor ───────────────────────────────────────────────────────────

    @Test
    fun `the placement distance grows with the object, from arm's length to three metres`() {
        assertEquals(1f, PlacementMath.placementDistanceFor(0.05f), 1e-6f)
        assertEquals(1.3f, PlacementMath.placementDistanceFor(0.5f), 1e-6f)
        assertEquals(2.5f, PlacementMath.placementDistanceFor(2f), 1e-6f)
        assertEquals(3f, PlacementMath.placementDistanceFor(10f), 1e-6f)
        assertEquals(1f, PlacementMath.placementDistanceFor(-1f), 1e-6f)
    }

    @Test
    fun `the floor is the lowest plane well below the camera, else a phone's height under it`() {
        assertEquals(0.1f, PlacementMath.estimateFloorHeight(1.5f, listOf(0.8f, 0.1f, 1.3f)), 1e-6f)
        // A table at 1.2 m is not 0.4 m under a camera at 1.5 m: not the floor.
        assertEquals(0.2f, PlacementMath.estimateFloorHeight(1.5f, listOf(1.2f)), 1e-6f)
        assertEquals(0.2f, PlacementMath.estimateFloorHeight(1.5f, emptyList()), 1e-6f)
    }

    @Test
    fun `a ray meets a horizontal plane ahead of it, never behind`() {
        val down = PlacementRay(Float3(0f, 1.5f, 0f), Float3(0f, -1f, 0f))
        assertEquals(1.5f, PlacementMath.intersectHorizontal(down, 0f)!!, 1e-6f)
        val up = PlacementRay(Float3(0f, 1.5f, 0f), Float3(0f, 1f, 0f))
        assertNull(PlacementMath.intersectHorizontal(up, 0f))
        val level = PlacementRay(Float3(0f, 1.5f, 0f), Float3(0f, 0f, -1f))
        assertNull(PlacementMath.intersectHorizontal(level, 0f))
    }

    @Test
    fun `the floor point stays between the near share and the placement distance`() {
        val eye = Float3(0f, 1.5f, 0f)
        // Looking 45° down: the floor is 1.5 m ahead, past 1.3 m: held at 1.3 m.
        val diagonal = PlacementRay(eye, normalized(Float3(0f, -1f, -1f)))
        val far = PlacementMath.floorPointAlong(diagonal, 0f, 1.3f, 1.3f * PlacementFeel.NEAR_SHARE)!!
        assertEquals(1.3f, hypot(far.x, far.z), 1e-4f)
        assertEquals(0f, far.y, 1e-6f)
        // At the horizon: the ray never lands, the point stands the full distance ahead.
        val level = PlacementRay(eye, Float3(0f, 0f, -1f))
        val ahead = PlacementMath.floorPointAlong(level, 0f, 1.3f, 0.78f)!!
        assertEquals(-1.3f, ahead.z, 1e-4f)
        // At the feet: pushed out to the near share.
        val feet = PlacementRay(eye, normalized(Float3(0f, -1f, -0.1f)))
        val near = PlacementMath.floorPointAlong(feet, 0f, 1.3f, 0.78f)!!
        assertEquals(0.78f, hypot(near.x, near.z), 1e-4f)
        // Within range: exactly where the ray lands.
        val mid = PlacementRay(eye, normalized(Float3(0f, -1.5f, -1f)))
        assertEquals(-1f, PlacementMath.floorPointAlong(mid, 0f, 1.3f, 0.78f)!!.z, 1e-4f)
        // Straight down: no way ahead is known, it stands where the ray lands.
        val under = PlacementMath.floorPointAlong(PlacementRay(eye, Float3(0f, -1f, 0f)), 0f, 1.3f, 0.78f)!!
        assertEquals(0f, hypot(under.x, under.z), 1e-6f)
        // Straight up: nowhere to stand.
        assertNull(PlacementMath.floorPointAlong(PlacementRay(eye, Float3(0f, 1f, 0f)), 0f, 1.3f, 0.78f))
    }

    @Test
    fun `the object turns its front to the viewer`() {
        assertEquals(0f, PlacementMath.yawToward(Float3(0f, 0f, -2f), Float3(0f, 1.5f, 0f)), 1e-4f)
        assertEquals(90f, PlacementMath.yawToward(Float3(-2f, 0f, 0f), Float3(0f, 1.5f, 0f)), 1e-4f)
        assertEquals(180f, kotlin.math.abs(PlacementMath.yawToward(Float3(0f, 0f, 2f), Float3(0f, 0f, 0f))), 1e-4f)
    }

    @Test
    fun `screen rays and projections round-trip`() {
        val view = identity()
        val projection = perspective()
        val centre = PlacementMath.screenRay(500f, 1000f, 1000, 2000, view, projection)!!
        assertEquals(0f, centre.direction.x, 1e-5f)
        assertEquals(0f, centre.direction.y, 1e-5f)
        assertEquals(-1f, centre.direction.z, 1e-5f)

        val point = Float3(0.4f, -0.3f, -2f)
        val (x, y) = PlacementMath.projectToScreen(point, 1000, 2000, view, projection)!!
        val ray = PlacementMath.screenRay(x, y, 1000, 2000, view, projection)!!
        val t = (point.z - ray.origin.z) / ray.direction.z
        val hit = ray.at(t)
        assertEquals(point.x, hit.x, 1e-4f)
        assertEquals(point.y, hit.y, 1e-4f)

        assertNull("behind the camera", PlacementMath.projectToScreen(Float3(0f, 0f, 2f), 1000, 2000, view, projection))
        assertNull(PlacementMath.screenRay(0f, 0f, 0, 0, view, projection))
        assertNull(PlacementMath.screenRay(0f, 0f, 10, 10, view, FloatArray(16)))
    }

    @Test
    fun `the ring circles the footprint with a twelve percent margin`() {
        assertEquals(0.5f * 1.12f, PlacementMath.ringRadiusFor(0.6f, 0.8f), 1e-5f)
    }

    @Test
    fun `a matrix times its inverse is the identity`() {
        val m = perspective()
        val product = ViewportProjection.multiply(m, PlacementMath.invert(m)!!)
        val id = identity()
        for (i in 0 until 16) assertEquals(id[i], product[i], 1e-4f)
    }

    // ── ARCore's instant placement ────────────────────────────────────────────────────────

    @Test
    fun `a surface flow turns instant placement on, a wall flow keeps it off`() {
        assertEquals(Config.InstantPlacementMode.LOCAL_Y_UP, autoPlacementInstantPlacementMode(PlacementSurface.SURFACE))
        assertEquals(Config.InstantPlacementMode.DISABLED, autoPlacementInstantPlacementMode(PlacementSurface.WALL))
    }

    // ── Anchor retries ────────────────────────────────────────────────────────────────────

    @Test
    fun `anchor retries back off from 100 ms to 800 ms and give up after six`() {
        assertEquals(listOf(100L, 200L, 400L, 800L, 800L), (1..5).map { AnchorRetryPolicy.delayAfter(it) })
        val retry = AnchorRetryPolicy()
        assertTrue("the first attempt runs at once", retry.isDue(0L))
        retry.failed(1_000L)
        assertFalse(retry.isDue(1_099L))
        assertTrue(retry.isDue(1_100L))
        repeat(5) { retry.failed(2_000L) }
        assertTrue(retry.isExhausted)
        assertFalse(retry.isDue(100_000L))
        retry.reset()
        assertEquals(0, retry.attempts)
        assertTrue(retry.isDue(0L))
    }

    private fun normalized(v: Float3): Float3 {
        val l = kotlin.math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Float3(v.x / l, v.y / l, v.z / l)
    }

    private fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** 90° vertical field of view, 1:2, near 0.1, far 100, column-major. */
    private fun perspective(): FloatArray {
        val near = 0.1f
        val far = 100f
        val f = 1f
        return FloatArray(16).also {
            it[0] = f / 0.5f
            it[5] = f
            it[10] = (far + near) / (near - far)
            it[11] = -1f
            it[14] = 2f * far * near / (near - far)
        }
    }
}
