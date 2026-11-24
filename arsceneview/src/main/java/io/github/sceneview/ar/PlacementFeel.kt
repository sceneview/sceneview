package io.github.sceneview.ar

import com.google.ar.core.Config
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Trackable
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Ray
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Transform
import io.github.sceneview.math.viewToRay
import io.github.sceneview.math.worldToView
import kotlin.math.atan2
import kotlin.math.hypot

internal object PlacementFeel {
    const val ESTIMATED_FALLBACK_MS = 600L
    const val PHONE_HEIGHT_GUESS_M = 1.3f
    const val DEFAULT_DISTANCE_M = 1.3f
    const val NEAR_SHARE = 0.6f
    const val MIN_INSTANT_DISTANCE_M = 0.2f
}

internal fun autoPlacementInstantPlacementMode(surface: PlacementSurface): Config.InstantPlacementMode =
    when (surface) {
        PlacementSurface.SURFACE -> Config.InstantPlacementMode.LOCAL_Y_UP
        PlacementSurface.WALL -> Config.InstantPlacementMode.DISABLED
    }

internal fun isSettledOn(trackable: Trackable): Boolean =
    trackable !is InstantPlacementPoint ||
        trackable.trackingMethod == InstantPlacementPoint.TrackingMethod.FULL_TRACKING

internal object PlacementMath {
    fun estimateFloorHeight(cameraY: Float, lowestPlaneY: Float?): Float =
        lowestPlaneY?.takeIf { cameraY - it >= FLOOR_BELOW_CAMERA_M }
            ?: (cameraY - PlacementFeel.PHONE_HEIGHT_GUESS_M)

    /** Intersect the view ray with the floor, allowing a comfortable range in front of the camera. */
    fun floorPointAlong(ray: Ray, floorY: Float, maxDistance: Float, minDistance: Float): Float3? {
        val direction = ray.direction
        val flat = hypot(direction.x, direction.z)
        if (!flat.isFinite() || flat < 1e-3f) return null
        val toFloor = (floorY - ray.origin.y) / direction.y
        val distance = if (toFloor > 0f) (toFloor * flat).coerceIn(minDistance, maxDistance) else maxDistance
        return Float3(
            ray.origin.x + direction.x / flat * distance,
            floorY,
            ray.origin.z + direction.z / flat * distance,
        )
    }

    fun yawToward(at: Float3, viewer: Float3): Float =
        Math.toDegrees(atan2((viewer.x - at.x).toDouble(), (viewer.z - at.z).toDouble())).toFloat()

    fun screenRay(x: Float, y: Float, width: Int, height: Int, view: Transform, projection: Transform): Ray {
        val ray = viewToRay(Float2(x / width, 1f - y / height), projection, view)
        return Ray(ray.origin, normalize(ray.direction))
    }

    /** Pixel coordinates accepted by ARCore's hit test. */
    fun projectToScreen(
        point: Float3, width: Int, height: Int, view: Transform, projection: Transform,
    ): Float2? =
        worldToView(point, projection, view)
            ?.takeIf { it.x in 0f..1f && it.y in 0f..1f }
            ?.let { Float2(it.x * width, (1f - it.y) * height) }
}

/** Retry after 100, 200, 400 and then 800 ms. A drag may cap the number of attempts. */
internal class AnchorRetryPolicy(private val limit: Int = Int.MAX_VALUE) {
    private var failures = 0
    private var nextAttemptAtMillis = 0L
    val isExhausted: Boolean get() = failures >= limit

    fun isDue(nowMillis: Long): Boolean = !isExhausted && nowMillis >= nextAttemptAtMillis

    fun failed(nowMillis: Long) {
        failures++
        nextAttemptAtMillis = nowMillis + (100L shl (failures - 1).coerceAtMost(3))
    }

    fun reset() {
        failures = 0
        nextAttemptAtMillis = 0L
    }

    companion object {
        const val RELOCATION_ATTEMPTS = 6
    }
}
