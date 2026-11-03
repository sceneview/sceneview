package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.ScanResultFraming
import kotlin.math.cos
import kotlin.math.sin

/**
 * The finish's tidy-ups in [CleanGeometry]: the surface cut to the scan's own box before
 * anything else — what depth saw through a window or in a mirror is not the room — and the
 * pieces left standing apart from it dropped ([dropIsolated]). The floor's
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

    /**
     * [mesh] less the pieces left standing apart from the scan: each piece under [ISOLATED_SHARE]
     * of the largest one's area whose own box lies wholly more than [ISOLATED_GAP_M] outside the
     * largest piece's box on the floor plan — a tuft of a hedge, a patch of a neighbour's wall, a
     * reflection hanging past the room. What stands over the room — a lamp, a table the floor did
     * not join — is over its footprint and stays, whatever its size or height.
     * Returns the pieces dropped.
     */
    @Suppress("ReturnCount")
    fun dropIsolated(mesh: WorkMesh): Pair<WorkMesh, Int> {
        if (mesh.triangleCount == 0) return mesh to 0
        val (piece, count) = MeshTopology(mesh.vertexCount, mesh.indices).components()
        if (count < 2) return mesh to 0
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        val area = DoubleArray(count)
        val box = Array(count) { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }
        for (t in 0 until mesh.triangleCount) area[piece[mesh.indices[t * 3]]] += faces.areas[t].toDouble()
        for (v in 0 until mesh.vertexCount) {
            val b = box[piece[v]]
            for (k in 0 until AXES) {
                val value = mesh.positions[v * 3 + k]
                if (value < b[k]) b[k] = value
                if (value > b[k + AXES]) b[k + AXES] = value
            }
        }
        val largest = area.indices.maxBy { area[it] }
        val main = box[largest]
        val keepPiece = BooleanArray(count) { p ->
            p == largest || area[p] >= area[largest] * ISOLATED_SHARE ||
                PLAN.all { k ->
                    box[p][k] <= main[k + AXES] + ISOLATED_GAP_M && box[p][k + AXES] >= main[k] - ISOLATED_GAP_M
                }
        }
        val dropped = keepPiece.count { !it }
        if (dropped == 0) return mesh to 0
        return mesh.keepTriangles(BooleanArray(mesh.triangleCount) { keepPiece[piece[mesh.indices[it * 3]]] }) to dropped
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

    /** A piece apart from the scan under this share of its largest piece's area is a stray. */
    const val ISOLATED_SHARE = 0.05f

    /** How far outside the largest piece's box a piece must lie wholly to be apart from it, metres. */
    const val ISOLATED_GAP_M = 0.1f

    /** The floor plan's axes, x and z: what hangs over the scan's footprint, at any height, stays. */
    private val PLAN = intArrayOf(0, 2)

    private const val MIN_VERTICES = 100
    private const val AXES = 3
    private const val HALF = 0.5f
}
