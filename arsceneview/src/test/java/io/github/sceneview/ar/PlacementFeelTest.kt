package io.github.sceneview.ar

import com.google.ar.core.Config
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toTransform
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementFeelTest {
    @Test
    fun `a surface flow enables local Y up and a wall flow disables it`() {
        assertEquals(Config.InstantPlacementMode.LOCAL_Y_UP, autoPlacementInstantPlacementMode(PlacementSurface.SURFACE))
        assertEquals(Config.InstantPlacementMode.DISABLED, autoPlacementInstantPlacementMode(PlacementSurface.WALL))
    }

    @Test
    fun `the floor is a low plane or an estimate below the camera`() {
        assertEquals(0.1f, PlacementMath.estimateFloorHeight(1.5f, 0.1f), 1e-6f)
        assertEquals(0.2f, PlacementMath.estimateFloorHeight(1.5f, 0.8f), 1e-6f)
        assertEquals(0.2f, PlacementMath.estimateFloorHeight(1.5f, null), 1e-6f)
    }

    @Test
    fun `the floor point is kept between the near share and the placement distance`() {
        val eye = Float3(0f, 1.5f, 0f)
        fun floorPoint(direction: Float3) =
            PlacementMath.floorPointAlong(Ray(eye, direction), 0f, maxDistance = 1.3f, minDistance = 0.78f)
        val shallow = checkNotNull(floorPoint(Float3(0f, -0.2f, -1f)))
        assertEquals(1.3f, hypot(shallow.x, shallow.z), 1e-4f)
        assertEquals(0f, shallow.y, 1e-6f)
        val steep = checkNotNull(floorPoint(Float3(0f, -1f, -0.1f)))
        assertEquals(0.78f, hypot(steep.x, steep.z), 1e-4f)
        assertEquals(-1f, checkNotNull(floorPoint(Float3(0f, -1.5f, -1f))).z, 1e-4f)
        assertEquals(-1.3f, checkNotNull(floorPoint(Float3(0f, 0f, -1f))).z, 1e-4f)
        assertNull(floorPoint(Float3(0f, 1f, 0f)))
    }

    @Test
    fun `yaw turns the object's front toward the viewer`() {
        val eye = Float3(0f, 1.5f, 0f)
        assertEquals(0f, PlacementMath.yawToward(Float3(0f, 0f, -2f), eye), 1e-4f)
        assertEquals(90f, PlacementMath.yawToward(Float3(-2f, 0f, 0f), eye), 1e-4f)
        assertEquals(180f, abs(PlacementMath.yawToward(Float3(0f, 0f, 2f), eye)), 1e-4f)
    }

    @Test
    fun `screen rays and projection use the same ARCore pixel coordinates`() {
        val view = Transform()
        val projection = floatArrayOf(
            2f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -1.002f, -1f,
            0f, 0f, -0.2f, 0f,
        ).toTransform()
        val centre = PlacementMath.screenRay(500f, 1_000f, 1_000, 2_000, view, projection)
        assertEquals(-1f, centre.direction.z, 1e-5f)
        val point = Float3(0.2f, -0.5f, -2f)
        val pixel = checkNotNull(PlacementMath.projectToScreen(point, 1_000, 2_000, view, projection))
        val ray = PlacementMath.screenRay(pixel.x, pixel.y, 1_000, 2_000, view, projection)
        val hit = ray.origin + ray.direction * ((point.z - ray.origin.z) / ray.direction.z)
        assertEquals(point.x, hit.x, 1e-4f)
        assertEquals(point.y, hit.y, 1e-4f)
        assertNull(PlacementMath.projectToScreen(Float3(0f, 0f, 2f), 1_000, 2_000, view, projection))
        assertNull(PlacementMath.projectToScreen(Float3(0f, -3f, -2f), 1_000, 2_000, view, projection))
    }

    @Test
    fun `anchor retries double up to 800 milliseconds and a drag gives up after six attempts`() {
        val retry = AnchorRetryPolicy(AnchorRetryPolicy.RELOCATION_ATTEMPTS)
        var now = 0L
        for (delay in listOf(100L, 200L, 400L, 800L, 800L)) {
            assertTrue(retry.isDue(now))
            retry.failed(now)
            assertFalse(retry.isDue(now + delay - 1))
            now += delay
        }
        assertTrue(retry.isDue(now))
        retry.failed(now)
        assertTrue(retry.isExhausted)
        assertFalse(retry.isDue(now + 10_000))
        retry.reset()
        assertTrue(retry.isDue(0L))
    }
}
