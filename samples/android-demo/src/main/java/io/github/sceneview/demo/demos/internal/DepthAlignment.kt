package io.github.sceneview.demo.demos.internal

import kotlin.math.abs

/**
 * How ARCore's raw depth image lines up with the lenses ARCore describes.
 *
 * The depth image is aligned with the GPU camera texture, not with the CPU camera image: both
 * come from one sensor, but a phone commonly gives a 4:3 CPU image (640×480) and a 16:9 texture
 * (1920×1080), and the depth has the texture's aspect (160×90). Scaling the CPU image's lens to
 * such a depth squeezed its vertical focal length to 0.75 of the true one, so every height came
 * out a third too tall, and each depth pixel took its colour from the wrong row.
 *
 * So the depth's lens is the lens of whichever image it shares its aspect with — the texture
 * whenever they agree — scaled to its size; and a depth pixel's colour is read in the CPU image
 * along the same ray, since both images look through the same optics.
 */
object DepthAlignment {
    /** Two aspects closer than this are the same framing, give or take a pixel of rounding. */
    const val ASPECT_TOLERANCE = 0.02f

    /**
     * The lens of a [width]×[height] depth image: [texture]'s, the image ARCore aligns the depth
     * with, scaled to it; or [image]'s when only the CPU image has the depth's aspect. `null`
     * when neither framing matches — a depth that no lens describes is not fused.
     */
    fun depthLens(texture: ScanIntrinsics?, image: ScanIntrinsics, width: Int, height: Int): ScanIntrinsics? {
        if (width <= 0 || height <= 0) return null
        val source = listOfNotNull(texture, image).firstOrNull { sameAspect(it, width, height) } ?: return null
        val kx = width.toFloat() / source.width
        val ky = height.toFloat() / source.height
        return ScanIntrinsics(source.fx * kx, source.fy * ky, source.cx * kx, source.cy * ky, width, height)
    }

    /**
     * The linear map from a pixel of the [depth] lens to the pixel of the [image] lens that sees
     * along the same ray: `u = a.x · x + b.x`, `v = a.y · y + b.y`, as `[ax, bx, ay, by]`.
     */
    fun toImage(depth: ScanIntrinsics, image: ScanIntrinsics): FloatArray {
        val ax = image.fx / depth.fx
        val ay = image.fy / depth.fy
        return floatArrayOf(ax, image.cx - ax * depth.cx, ay, image.cy - ay * depth.cy)
    }

    private fun sameAspect(lens: ScanIntrinsics, width: Int, height: Int): Boolean {
        val a = lens.width.toFloat() / lens.height
        val b = width.toFloat() / height
        return abs(a - b) <= ASPECT_TOLERANCE * b
    }
}
