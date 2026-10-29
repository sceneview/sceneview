package io.github.sceneview.ar

import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.gesture.InertiaTracker
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The numbers behind the feel of [AutoPlacementScene]: when the object places itself, how far
 * ahead, how a thrown drag or twist coasts, how a lost anchor is retried. Shared with the iOS
 * `ARPlacementFeel` so both platforms place, glide and turn the same way.
 */
object PlacementFeel {
    /**
     * How long the camera tracks without a usable surface before the object places itself on
     * the estimated floor instead ([AutoPlacementState.estimatedFallbackMillis]).
     */
    const val ESTIMATED_FALLBACK_MS = 600L

    /** A held phone is about this far above the floor: the floor guess before ARCore finds it. */
    const val PHONE_HEIGHT_GUESS_M = 1.3f

    /** A tracked plane counts as the floor only this far below the camera: a table is not one. */
    const val FLOOR_BELOW_CAMERA_M = 0.4f

    /** The closest the object places itself, as a share of its placement distance. */
    const val NEAR_SHARE = 0.6f

    /** A plane or an instant-placement hit farther than this is ignored. */
    const val MAX_SURFACE_DISTANCE_M = 5f

    /** The nearest an instant-placement hit is asked for. */
    const val MIN_INSTANT_DISTANCE_M = 0.2f

    /** The contact shadow spreads past the footprint by this factor on each axis. */
    const val SHADOW_SPREAD = 1.6f

    /** The smallest contact shadow side, metres. */
    const val MIN_SHADOW_SIZE_M = 0.1f

    /** Drag coast: stops under this speed, px/s. */
    const val GLIDE_MIN_SPEED = 8f

    /** Drag coast: never faster than this, px/s. */
    const val GLIDE_MAX_SPEED = 4000f

    /** Drag coast: a finger lifted slower than this does not throw the object, px/s. */
    const val GLIDE_MIN_RELEASE_SPEED = 150f

    /** Twist coast: stops under this speed, degrees/s. */
    const val TWIST_MIN_SPEED = 5f

    /** Twist coast: never faster than this, degrees/s. */
    const val TWIST_MAX_SPEED = 900f

    /** Twist coast: a twist released slower than this does not spin on, degrees/s. */
    const val TWIST_MIN_RELEASE_SPEED = 45f

    /**
     * How fast the object follows the finger during a drag, per second: each frame covers
     * `1 - e^(-DRAG_SPEED * dt)` of the way left, so touch jitter is smoothed without lag.
     */
    const val DRAG_SPEED = 18f

    /**
     * How fast the object eases onto its anchor when the anchor moves on its own (an estimated
     * placement refining, a recenter, a drag committed), per second.
     */
    const val SETTLE_SPEED = 6f

    /** The scale a first placement grows in from: it appears where it stands, not popping in. */
    const val APPEAR_SCALE = 0.01f

    /** The glide after a thrown one-finger drag, in screen pixels. */
    fun moveCoast(): InertiaTracker = InertiaTracker(
        dimensions = 2, minSpeed = GLIDE_MIN_SPEED, maxSpeed = GLIDE_MAX_SPEED,
        minReleaseSpeed = GLIDE_MIN_RELEASE_SPEED,
    )

    /** The spin after a flicked two-finger twist, in degrees. */
    fun twistCoast(): InertiaTracker = InertiaTracker(
        dimensions = 1, minSpeed = TWIST_MIN_SPEED, maxSpeed = TWIST_MAX_SPEED,
        minReleaseSpeed = TWIST_MIN_RELEASE_SPEED,
    )
}

/** A ray in ARCore world space, metres, Y up. [direction] is unit length. */
data class PlacementRay(val origin: Float3, val direction: Float3) {
    fun at(t: Float): Float3 = origin + direction * t
}

/**
 * Where an automatic placement stands, as pure geometry: no ARCore, no Filament, so it runs in
 * JVM tests. Matrices are 4x4 column-major, as ARCore's `Camera.getViewMatrix` and
 * `getProjectionMatrix` write them.
 */
object PlacementMath {

    /**
     * How far ahead the object places itself, from its largest side in metres: a mug at arm's
     * length, a chair a couple of steps away, a sofa further, so it shows whole at real size.
     */
    fun placementDistanceFor(largestSideMeters: Float): Float =
        (0.9f + 0.8f * largestSideMeters.coerceAtLeast(0f)).coerceIn(1f, 3f)

    /**
     * The floor height: the lowest of [planeHeights] (horizontal, upward-facing, tracked)
     * at least [PlacementFeel.FLOOR_BELOW_CAMERA_M] below [cameraY], else
     * [PlacementFeel.PHONE_HEIGHT_GUESS_M] under the camera.
     */
    fun estimateFloorHeight(cameraY: Float, planeHeights: Collection<Float>): Float =
        planeHeights.filter { it <= cameraY - PlacementFeel.FLOOR_BELOW_CAMERA_M }.minOrNull()
            ?: (cameraY - PlacementFeel.PHONE_HEIGHT_GUESS_M)

    /** The distance along [ray] to the horizontal plane at height [y], or null if it never gets there. */
    fun intersectHorizontal(ray: PlacementRay, y: Float): Float? {
        val dy = ray.direction.y
        if (dy > -1e-4f && dy < 1e-4f) return null
        val t = (y - ray.origin.y) / dy
        return if (t > 0f) t else null
    }

    /**
     * Where the object stands on the floor at height [floorY]: where [ray] meets it, kept between
     * [minDistance] and [maxDistance] from the camera horizontally, along the way it looks. Aimed
     * at the horizon or too far: [maxDistance] ahead; at the feet: [minDistance] ahead. Null only
     * when the camera looks straight up or down and no way ahead is known.
     */
    fun floorPointAlong(ray: PlacementRay, floorY: Float, maxDistance: Float, minDistance: Float = 0f): Float3? {
        val t = intersectHorizontal(ray, floorY)
        if (t != null) {
            val p = ray.at(t)
            val d = hypot(p.x - ray.origin.x, p.z - ray.origin.z)
            if (d in minDistance..maxDistance) return p
            if (d > maxDistance) return ahead(ray, floorY, maxDistance)
            return ahead(ray, floorY, minDistance) ?: p
        }
        return ahead(ray, floorY, maxDistance)
    }

    private fun ahead(ray: PlacementRay, floorY: Float, distance: Float): Float3? {
        val flat = hypot(ray.direction.x, ray.direction.z)
        if (flat < 1e-3f) return null
        return Float3(
            ray.origin.x + ray.direction.x / flat * distance,
            floorY,
            ray.origin.z + ray.direction.z / flat * distance,
        )
    }

    /** The turn about Y, degrees, that has an object's front (+Z) at [at] face [viewer]. */
    fun yawToward(at: Float3, viewer: Float3): Float =
        Math.toDegrees(atan2((viewer.x - at.x).toDouble(), (viewer.z - at.z).toDouble())).toFloat()

    /** The ray from the camera through a pixel, or null when the matrices cannot be inverted. */
    fun screenRay(
        xPx: Float, yPx: Float, widthPx: Int, heightPx: Int,
        view: FloatArray, projection: FloatArray,
    ): PlacementRay? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val inverse = invert(ViewportProjection.multiply(projection, view)) ?: return null
        val ndcX = 2f * xPx / widthPx - 1f
        val ndcY = 1f - 2f * yPx / heightPx
        val near = transformPoint(inverse, ndcX, ndcY, -1f) ?: return null
        val far = transformPoint(inverse, ndcX, ndcY, 1f) ?: return null
        val d = far - near
        val length = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        if (length < 1e-6f) return null
        return PlacementRay(near, d / length)
    }

    /** The pixel a world point lands on, or null when it is behind the camera. */
    fun projectToScreen(
        point: Float3, widthPx: Int, heightPx: Int, view: FloatArray, projection: FloatArray,
    ): Pair<Float, Float>? {
        val ndc = ViewportProjection.project(ViewportProjection.multiply(projection, view), point.x, point.y, point.z)
            ?: return null
        return (ndc.x + 1f) / 2f * widthPx to (1f - ndc.y) / 2f * heightPx
    }

    /** The ring around a footprint: the circle through its corners plus 12 %. */
    fun ringRadiusFor(widthMeters: Float, depthMeters: Float): Float =
        hypot(widthMeters, depthMeters) / 2f * 1.12f

    /** The inverse of a 4x4 column-major matrix, or null when it is singular. */
    internal fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] + m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] - m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] + m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] - m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] - m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] + m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] - m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] + m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] + m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] - m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] + m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] - m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] - m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] + m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] - m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] + m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]
        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det > -1e-12f && det < 1e-12f) return null
        val d = 1f / det
        for (i in 0 until 16) inv[i] *= d
        return inv
    }

    private fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): Float3? {
        val w = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (w > -1e-9f && w < 1e-9f) return null
        return Float3(
            (m[0] * x + m[4] * y + m[8] * z + m[12]) / w,
            (m[1] * x + m[5] * y + m[9] * z + m[13]) / w,
            (m[2] * x + m[6] * y + m[10] * z + m[14]) / w,
        )
    }
}

/**
 * When to try a new anchor again after ARCore refused one: right away, then backing off from
 * [BASE_MS] doubling up to [CAP_MS], and giving up after [LIMIT] attempts so a spot ARCore
 * cannot hold never costs an anchor attempt per frame.
 */
class AnchorRetryPolicy {
    /** Failed attempts so far. */
    var attempts: Int = 0
        private set

    /** When the next attempt may run, in the caller's millisecond clock. */
    var nextAttemptAtMillis: Long = 0L
        private set

    /** Whether the retries are used up. */
    val isExhausted: Boolean get() = attempts >= LIMIT

    /** An attempt may run at [nowMillis]. */
    fun isDue(nowMillis: Long): Boolean = !isExhausted && nowMillis >= nextAttemptAtMillis

    /** The attempt at [nowMillis] failed: schedules the next one, or exhausts the policy. */
    fun failed(nowMillis: Long) {
        attempts++
        if (!isExhausted) nextAttemptAtMillis = nowMillis + delayAfter(attempts)
    }

    /** Back to a first, immediate attempt. */
    fun reset() {
        attempts = 0
        nextAttemptAtMillis = 0L
    }

    companion object {
        const val LIMIT = 6
        const val BASE_MS = 100L
        const val CAP_MS = 800L

        /** The wait after the [failures]-th failure: 100, 200, 400, 800, 800 ms. */
        fun delayAfter(failures: Int): Long =
            if (failures <= 0) 0L else (BASE_MS shl (failures - 1).coerceAtMost(16)).coerceAtMost(CAP_MS)
    }
}
