package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.ScanResultFraming
import kotlin.math.cos
import kotlin.math.sin

/**
 * The finish's first tidy-up in [CleanGeometry]: the surface cut to the scan's own box before
 * anything else — what depth saw through a window or in a mirror is not the room. The floor's
 * holes are closed later, on its plane, by [MeshCleanup.fillSmallHoles]. Pure and off the main
 * thread.
 */
internal object ScanTidy {
    /**
     * [mesh] less every triangle whose middle lies outside the scan's box: square to its walls
     * (the frame [ScanResultFraming] reads), from the [TAIL] quantile of the vertices to the
     * 1 - [TAIL] on each axis, [MARGIN_M] wider all round. A wall holds far more than [TAIL] of a
     * room's vertices, so it stays whole; a piece beyond it does not. Returns the triangles cut.
     */
    @Suppress("ReturnCount")
    fun crop(mesh: WorkMesh): Pair<WorkMesh, Int> {
        val n = mesh.vertexCount
        if (n < MIN_VERTICES || mesh.triangleCount == 0) return mesh to 0
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        val yaw = Math.toRadians(ScanResultFraming.yawDegrees(faces.normals).toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        val axis = FloatArray(n)
        val low = FloatArray(AXES)
        val high = FloatArray(AXES)
        for (k in 0 until AXES) {
            for (v in 0 until n) axis[v] = turned(mesh.positions, v, k, c, s)
            axis.sort()
            low[k] = axis[((n - 1) * TAIL).toInt()] - MARGIN_M
            high[k] = axis[((n - 1) * (1f - TAIL) + HALF).toInt().coerceAtMost(n - 1)] + MARGIN_M
        }
        val keep = BooleanArray(mesh.triangleCount)
        var cut = 0
        for (t in 0 until mesh.triangleCount) {
            val x = faces.centroids[t * 3]
            val y = faces.centroids[t * 3 + 1]
            val z = faces.centroids[t * 3 + 2]
            val a = x * c + z * s
            val b = -x * s + z * c
            keep[t] = a in low[0]..high[0] && y in low[1]..high[1] && b in low[2]..high[2]
            if (!keep[t]) cut++
        }
        return if (cut == 0) mesh to 0 else mesh.keepTriangles(keep) to cut
    }

    private fun turned(positions: FloatArray, v: Int, axis: Int, c: Float, s: Float): Float {
        val x = positions[v * 3]
        val z = positions[v * 3 + 2]
        return when (axis) {
            0 -> x * c + z * s
            1 -> positions[v * 3 + 1]
            else -> -x * s + z * c
        }
    }

    /** The share of a scan's vertices past each end of each axis [crop] leaves out. */
    const val TAIL = 0.01f

    /** How much wider than its quantiles [crop] keeps the box, metres. */
    const val MARGIN_M = 0.15f

    private const val MIN_VERTICES = 100
    private const val AXES = 3
    private const val HALF = 0.5f
}
