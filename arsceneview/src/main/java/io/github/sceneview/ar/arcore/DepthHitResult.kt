package io.github.sceneview.ar.arcore

import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.distance
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import java.nio.ByteOrder

/**
 * Result of a depth-based hit test — a real-world point and the orientation of the surface it
 * sits on, sampled from the ARCore depth image rather than from a detected [com.google.ar.core.Plane].
 *
 * Unlike [Frame.hitTest], a depth hit test resolves a point on *any* real-world geometry the
 * depth camera can see — a sofa, a slope, a cluttered desk — without waiting for ARCore to grow
 * a plane there, and it carries a real surface [normal].
 *
 * @see Frame.hitTestDepth
 */
data class DepthHitResult(
    /** World-space point on the real-world surface under the queried screen pixel. */
    val position: Position,
    /**
     * Unit surface normal at [position], estimated from neighbouring depth samples and oriented
     * to face the camera.
     */
    val normal: Direction,
    /** Distance from the camera to [position], in meters. */
    val distance: Float
)

/**
 * Number of depth-image pixels away from the centre sample used to estimate the surface normal.
 */
private const val NORMAL_SAMPLE_RADIUS = 2

/**
 * Raycasts the ARCore depth image at a screen pixel and returns the real-world point hit, together
 * with the surface normal at that point.
 *
 * This is the SceneView equivalent of Google's [arcore-depth-lab](https://github.com/googlesamples/arcore-depth-lab)
 * "Oriented Reticle": it works on arbitrary geometry, not just detected planes, and gives the
 * surface orientation so a placed object can be aligned to whatever it lands on.
 *
 * Requires the [com.google.ar.core.Session] to be configured with a depth mode — either
 * [com.google.ar.core.Config.DepthMode.AUTOMATIC] or
 * [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY]. Returns `null` when depth is unavailable
 * (not supported, not yet computed, camera not tracking, or no valid depth at that pixel).
 *
 * The depth image is acquired and released within this call. It is inexpensive enough for
 * tap-driven placement; do not call it for every pixel of every frame.
 *
 * Threading: must be called on the GL/main thread, like the rest of the AR frame pipeline.
 *
 * @param xPx screen X in pixels.
 * @param yPx screen Y in pixels.
 *
 * @return a single [DepthHitResult] — the real-world point under the queried pixel — or `null`
 *         when depth is unavailable. **This is intentionally not a list, unlike
 *         [Frame.hitTest]**: the depth at one screen pixel is by definition a unique point in
 *         space (the depth image stores one Z per pixel), whereas a standard
 *         [Frame.hitTest] casts a ray that can pierce multiple parallel planes / surfaces at
 *         increasing distances. A list return would always have at most one element here, so
 *         the API is flattened to `DepthHitResult?` for ergonomics.
 */
fun Frame.hitTestDepth(xPx: Float, yPx: Float): DepthHitResult? {
    val camera = camera
    if (camera.trackingState != TrackingState.TRACKING) return null

    val depthImage = runCatching { acquireDepthImage16Bits() }.getOrNull()
        ?: runCatching { acquireRawDepthImage16Bits() }.getOrNull()
        ?: return null

    try {
        val depthWidth = depthImage.width
        val depthHeight = depthImage.height
        val plane = depthImage.planes[0]
        val rowStrideShorts = plane.rowStride / 2
        // ARCore packs DEPTH16 little-endian on every supported device; nativeOrder matches it.
        val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()

        // Map the screen pixel to normalized depth-image coordinates.
        val normalized = FloatArray(2)
        transformCoordinates2d(
            Coordinates2d.VIEW,
            floatArrayOf(xPx, yPx),
            Coordinates2d.IMAGE_NORMALIZED,
            normalized
        )
        val depthX = (normalized[0] * depthWidth).toInt()
        val depthY = (normalized[1] * depthHeight).toInt()

        val centerDepth = buffer.depthMetersAt(depthX, depthY, rowStrideShorts, depthWidth, depthHeight)
        if (centerDepth <= 0f) return null

        // ARCore reports intrinsics for the full-resolution CPU image; scale them to the
        // (much smaller) depth image resolution.
        val intrinsics = camera.imageIntrinsics
        // Defensive guard (#1812): without this, degenerate intrinsics (zero width or zero focal
        // length) would propagate `Inf` into world-space coordinates; `estimateNormal` then sees
        // Inf inputs and `normalize(normal)` returns NaN. We surface `null` instead so callers
        // can fall back to a regular hit-test path.
        val intrinsicWidth = intrinsics.imageDimensions[0]
        val intrinsicHeight = intrinsics.imageDimensions[1]
        val rawFx = intrinsics.focalLength[0]
        val rawFy = intrinsics.focalLength[1]
        if (!areDepthIntrinsicsUsable(intrinsicWidth, intrinsicHeight, rawFx, rawFy)) return null
        val scaleX = depthWidth / intrinsicWidth.toFloat()
        val scaleY = depthHeight / intrinsicHeight.toFloat()
        val fx = rawFx * scaleX
        val fy = rawFy * scaleY
        val cx = intrinsics.principalPoint[0] * scaleX
        val cy = intrinsics.principalPoint[1] * scaleY

        val pose = camera.pose

        fun worldAt(px: Int, py: Int, depthMeters: Float): Position {
            val cameraSpace = unprojectDepthPixel(px, py, depthMeters, fx, fy, cx, cy)
            val world = pose.transformPoint(cameraSpace.toFloatArray())
            return Position(world[0], world[1], world[2])
        }

        val center = worldAt(depthX, depthY, centerDepth)

        // Sample the four neighbours for the surface normal; fall back to the centre when a
        // neighbour has no valid depth (object edge, hole) so a degenerate sample never skews it.
        fun neighbour(dx: Int, dy: Int): Position {
            val nx = depthX + dx
            val ny = depthY + dy
            val d = buffer.depthMetersAt(nx, ny, rowStrideShorts, depthWidth, depthHeight)
            return if (d > 0f) worldAt(nx, ny, d) else center
        }

        val normal = estimateNormal(
            center = center,
            right = neighbour(NORMAL_SAMPLE_RADIUS, 0),
            left = neighbour(-NORMAL_SAMPLE_RADIUS, 0),
            up = neighbour(0, -NORMAL_SAMPLE_RADIUS),
            down = neighbour(0, NORMAL_SAMPLE_RADIUS),
            cameraPosition = pose.position
        )

        return DepthHitResult(
            position = center,
            normal = normal,
            distance = distance(pose.position, center)
        )
    } finally {
        depthImage.close()
    }
}

/**
 * Returns `true` only when ARCore camera intrinsics are safe to scale into depth-image space.
 *
 * Degenerate intrinsics — a zero (or negative) image dimension, or a zero focal length —
 * would make [Frame.hitTestDepth] compute a division by zero / `Inf` scale factor, propagate
 * `Inf` into world-space coordinates, and ultimately surface `NaN` from `normalize(normal)`.
 * The caller treats a `false` here as "depth unavailable" and returns `null` so no poisoned
 * intrinsics ever reach [unprojectDepthPixel] (#1812, #1957).
 *
 * `internal` so the guard can be unit-tested without an ARCore [Frame].
 */
internal fun areDepthIntrinsicsUsable(
    intrinsicWidth: Int,
    intrinsicHeight: Int,
    rawFx: Float,
    rawFy: Float
): Boolean = intrinsicWidth > 0 && intrinsicHeight > 0 && rawFx != 0f && rawFy != 0f

/**
 * Reads the depth sample at depth-image pixel ([x], [y]) and returns it in meters, or `0f` when
 * the pixel is out of bounds or has no depth.
 */
private fun java.nio.ShortBuffer.depthMetersAt(
    x: Int,
    y: Int,
    rowStrideShorts: Int,
    width: Int,
    height: Int
): Float {
    if (x !in 0 until width || y !in 0 until height) return 0f
    val millimeters = get(y * rowStrideShorts + x).toInt() and 0xFFFF
    return millimeters / 1000f
}

/**
 * Unprojects a depth-image pixel into a 3D point in ARCore camera space.
 *
 * ARCore camera space is +X right, +Y up, -Z forward, while the depth image has its origin at the
 * top-left with Y growing downward — hence the negated Y, and the depth (a positive forward
 * distance) mapping to a negative Z.
 *
 * `internal` so the projection math can be unit-tested without an ARCore [Frame].
 *
 * @throws IllegalArgumentException when [fx] or [fy] is `0f` — degenerate ARCore intrinsics
 *         would otherwise propagate `Inf` into world-space coordinates and poison every
 *         downstream consumer (#1812).
 */
internal fun unprojectDepthPixel(
    pixelX: Int,
    pixelY: Int,
    depthMeters: Float,
    fx: Float,
    fy: Float,
    cx: Float,
    cy: Float
): Position {
    require(fx != 0f && fy != 0f) {
        "Invalid focal length: fx=$fx, fy=$fy. ARCore intrinsics must have non-zero focal length."
    }
    return Position(
        x = (pixelX - cx) * depthMeters / fx,
        y = -(pixelY - cy) * depthMeters / fy,
        z = -depthMeters
    )
}

/**
 * Estimates a unit surface normal from a [center] point and its four neighbours, oriented to face
 * the camera at [cameraPosition].
 *
 * When the neighbourhood is degenerate (all samples collapsed onto the centre), it falls back to a
 * normal pointing straight at the camera so a placed object still faces the user.
 *
 * `internal` so the vector math can be unit-tested without an ARCore [Frame].
 */
internal fun estimateNormal(
    center: Position,
    right: Position,
    left: Position,
    up: Position,
    down: Position,
    cameraPosition: Position
): Direction {
    val tangentX = right - left
    val tangentY = up - down
    var normal = cross(tangentX, tangentY)
    if (length(normal) <= 0f) {
        // Degenerate neighbourhood — fall back to facing straight at the camera.
        normal = cameraPosition - center
    }
    normal = normalize(normal)
    // Flip so the normal points back toward the camera.
    if (dot(normal, cameraPosition - center) < 0f) {
        normal = -normal
    }
    return normal
}
