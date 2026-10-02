package io.github.sceneview.ar.depth

import kotlin.math.floor

/**
 * Metric anchors for one ML frame, in **world** space: ARCore feature points and samples of
 * tracked planes, copied on the render thread and projected on the worker.
 *
 * Fixed capacity, reused across frames: no allocation per frame.
 */
internal class DepthAnchorSet(val capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val z = FloatArray(capacity)
    val confidence = FloatArray(capacity)
    var count = 0
        private set

    fun clear() {
        count = 0
    }

    /** Adds one world-space point; ignored once [capacity] is reached. */
    fun add(wx: Float, wy: Float, wz: Float, conf: Float) {
        if (count >= capacity) return
        x[count] = wx
        y[count] = wy
        z[count] = wz
        confidence[count] = conf
        count++
    }
}

/**
 * The pure geometry behind ML depth scaling: plane sampling, projection of world anchors into
 * the network's output grid, and the bilinear read of the network value under each anchor.
 *
 * No Android or ARCore type here: matrices are column-major `FloatArray(16)` as written by
 * `Pose.toMatrix`, so the whole path is unit-tested on the JVM.
 */
internal object DepthAnchorMath {

    /** Anchors nearer than this to the camera are ignored (the network is unreliable there). */
    const val MIN_ANCHOR_DEPTH_M = 0.1f

    /**
     * Samples a grid of [columns] × [rows] points inside a plane polygon and adds them, in world
     * space, to [out].
     *
     * @param polygon the plane polygon in its local frame, as ARCore gives it: `x, z` pairs.
     * @param centerPose `Plane.getCenterPose().toMatrix(...)`, local → world, column-major.
     */
    fun samplePlane(
        polygon: FloatArray,
        centerPose: FloatArray,
        out: DepthAnchorSet,
        columns: Int = 8,
        rows: Int = 6,
    ) {
        val vertices = polygon.size / 2
        if (vertices < 3) return
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until vertices) {
            minX = minOf(minX, polygon[2 * i])
            maxX = maxOf(maxX, polygon[2 * i])
            minZ = minOf(minZ, polygon[2 * i + 1])
            maxZ = maxOf(maxZ, polygon[2 * i + 1])
        }
        for (r in 0 until rows) {
            val lz = minZ + (maxZ - minZ) * (r + 0.5f) / rows
            for (c in 0 until columns) {
                val lx = minX + (maxX - minX) * (c + 0.5f) / columns
                if (!insidePolygon(polygon, lx, lz)) continue
                // Local point (lx, 0, lz) → world.
                val m = centerPose
                out.add(
                    m[0] * lx + m[8] * lz + m[12],
                    m[1] * lx + m[9] * lz + m[13],
                    m[2] * lx + m[10] * lz + m[14],
                    1f,
                )
            }
        }
    }

    /** Even-odd point-in-polygon test on `x, z` pairs. */
    fun insidePolygon(polygon: FloatArray, px: Float, pz: Float): Boolean {
        val n = polygon.size / 2
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = polygon[2 * i]
            val zi = polygon[2 * i + 1]
            val xj = polygon[2 * j]
            val zj = polygon[2 * j + 1]
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Projects every anchor of [anchors] into a depth map of [width] × [height] and reads the
     * network value under it.
     *
     * @param viewMatrix world → camera, column-major (`camera.pose.inverse().toMatrix(...)`):
     *   the physical camera, `+X` right, `+Y` up in the image, looking down `-Z`.
     * @param intrinsics pinhole intrinsics in the **map's** pixel grid.
     * @param map the network output, `width × height`, row-major.
     * @param outD network value under each kept anchor.
     * @param outZ depth of each kept anchor along the optical axis, metres.
     * @param outConfidence confidence of each kept anchor.
     * @return how many anchors were kept (in front of the camera and inside the map).
     */
    @Suppress("LongParameterList")
    fun project(
        anchors: DepthAnchorSet,
        viewMatrix: FloatArray,
        intrinsics: DepthIntrinsics,
        map: FloatArray,
        width: Int,
        height: Int,
        outD: FloatArray,
        outZ: FloatArray,
        outConfidence: FloatArray,
    ): Int {
        val m = viewMatrix
        var kept = 0
        var i = 0
        while (i < anchors.count && kept < outD.size) {
            val wx = anchors.x[i]
            val wy = anchors.y[i]
            val wz = anchors.z[i]
            val cx = m[0] * wx + m[4] * wy + m[8] * wz + m[12]
            val cy = m[1] * wx + m[5] * wy + m[9] * wz + m[13]
            val cz = m[2] * wx + m[6] * wy + m[10] * wz + m[14]
            val depth = -cz
            // Behind or too close to the camera: no stable projection.
            val d = if (depth < MIN_ANCHOR_DEPTH_M) {
                Float.NaN
            } else {
                val u = intrinsics.cx + intrinsics.fx * cx / depth
                val v = intrinsics.cy - intrinsics.fy * cy / depth
                sampleBilinear(map, width, height, u, v)
            }
            if (!d.isNaN()) {
                outD[kept] = d
                outZ[kept] = depth
                outConfidence[kept] = anchors.confidence[i]
                kept++
            }
            i++
        }
        return kept
    }

    /**
     * Bilinear read at pixel-centre coordinates (`u = 0.5` is the middle of column 0), or `NaN`
     * outside the map.
     */
    fun sampleBilinear(map: FloatArray, width: Int, height: Int, u: Float, v: Float): Float {
        val fx = u - 0.5f
        val fy = v - 0.5f
        if (fx !in 0f..(width - 1f) || fy !in 0f..(height - 1f)) return Float.NaN
        val x0 = floor(fx).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(fy).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        val ax = fx - x0
        val ay = fy - y0
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val top = map[y0 * width + x0] * (1 - ax) + map[y0 * width + x1] * ax
        val bottom = map[y1 * width + x0] * (1 - ax) + map[y1 * width + x1] * ax
        return top * (1 - ay) + bottom * ay
    }

    /**
     * Scales CPU-image intrinsics to a map of [mapWidth] × [mapHeight] resampled from an image of
     * [imageWidth] × [imageHeight] (a plain stretch, as the YUV resampler does).
     */
    @Suppress("LongParameterList")
    fun scaleIntrinsics(
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        imageWidth: Int,
        imageHeight: Int,
        mapWidth: Int,
        mapHeight: Int,
    ): DepthIntrinsics {
        val sx = mapWidth.toFloat() / imageWidth
        val sy = mapHeight.toFloat() / imageHeight
        return DepthIntrinsics(fx * sx, fy * sy, cx * sx, cy * sy)
    }
}
