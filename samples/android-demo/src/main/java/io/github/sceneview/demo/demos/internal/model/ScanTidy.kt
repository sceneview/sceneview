package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.ScanResultFraming
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The finish's two tidy-ups round [CleanGeometry]: the surface cut to the scan's own box before
 * anything else — what depth saw through a window or in a mirror is not the room — and, last,
 * the holes the floor kept closed on its plane, in its own colour. Pure and off the main thread.
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
     * [mesh] with the holes in its floor closed: the floor is the height the most up-facing
     * surface stands at in the lower part of the scan; its cells ([CELL_M]) are closed over gaps
     * up to [CLOSE_CELLS] wide and any hole they enclose, and each cell the floor left empty is
     * filled by a square [FILL_DROP_M] under its plane, in the floor's average colour — drawn
     * where the scan saw no floor, hidden where it did. A scan with less floor than
     * [MIN_FLOOR_M2] — an object — is left as it is. Returns the cells filled.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    fun closeFloor(mesh: WorkMesh): Pair<WorkMesh, Int> {
        if (mesh.triangleCount == 0) return mesh to 0
        val faces = FaceGeometry(mesh.positions, mesh.indices)
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (v in 0 until mesh.vertexCount) {
            minY = min(minY, mesh.positions[v * 3 + 1])
            maxY = max(maxY, mesh.positions[v * 3 + 1])
        }
        val lowTop = minY + (maxY - minY) * LOW_SHARE
        // The floor's height: the bin of up-facing area the lower part holds most of.
        val bins = ((lowTop - minY) / BIN_M).toInt() + 1
        val binArea = FloatArray(bins)
        for (t in 0 until faces.count) {
            val y = faces.centroids[t * 3 + 1]
            if (faces.normals[t * 3 + 1] < UP || y > lowTop) continue
            binArea[((y - minY) / BIN_M).toInt().coerceIn(0, bins - 1)] += faces.areas[t]
        }
        val best = binArea.indices.maxByOrNull { binArea[it] } ?: return mesh to 0
        if (binArea[best] < MIN_FLOOR_M2) return mesh to 0
        val floorLow = minY + (best - 1) * BIN_M
        val floorHigh = minY + (best + 2) * BIN_M
        val floorFaces = (0 until faces.count).filter { t ->
            faces.normals[t * 3 + 1] >= UP && faces.centroids[t * 3 + 1] in floorLow..floorHigh
        }
        var weight = 0.0
        var sumY = 0.0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var x0 = Float.MAX_VALUE
        var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var z1 = -Float.MAX_VALUE
        for (t in floorFaces) {
            val area = faces.areas[t].toDouble()
            weight += area
            sumY += faces.centroids[t * 3 + 1] * area
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k]
                val colour = mesh.colors[v]
                r += (colour shr RED and BYTE) * area / 3.0
                g += (colour shr GREEN and BYTE) * area / 3.0
                b += (colour and BYTE) * area / 3.0
                x0 = min(x0, mesh.positions[v * 3])
                z0 = min(z0, mesh.positions[v * 3 + 2])
                x1 = max(x1, mesh.positions[v * 3])
                z1 = max(z1, mesh.positions[v * 3 + 2])
            }
        }
        if (weight <= 0.0) return mesh to 0
        // The grid over the floor, a closing's width of room round it so a closing is whole.
        val pad = CLOSE_CELLS + 1
        val cols = ((x1 - x0) / CELL_M).toInt() + 1 + 2 * pad
        val rows = ((z1 - z0) / CELL_M).toInt() + 1 + 2 * pad
        if (cols.toLong() * rows > MAX_CELLS) return mesh to 0
        val originX = x0 - pad * CELL_M
        val originZ = z0 - pad * CELL_M
        val seen = BooleanArray(cols * rows)
        for (t in floorFaces) {
            val col = floor((faces.centroids[t * 3] - originX) / CELL_M).toInt().coerceIn(0, cols - 1)
            val row = floor((faces.centroids[t * 3 + 2] - originZ) / CELL_M).toInt().coerceIn(0, rows - 1)
            seen[row * cols + col] = true
            // A face larger than a cell marks its corners' cells too.
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k]
                val vc = floor((mesh.positions[v * 3] - originX) / CELL_M).toInt().coerceIn(0, cols - 1)
                val vr = floor((mesh.positions[v * 3 + 2] - originZ) / CELL_M).toInt().coerceIn(0, rows - 1)
                seen[vr * cols + vc] = true
            }
        }
        val closed = erode(fillEnclosed(dilate(seen, cols, rows, CLOSE_CELLS), cols, rows), cols, rows, CLOSE_CELLS)
        val holes = (0 until cols * rows).filter { closed[it] && !seen[it] }
        if (holes.isEmpty()) return mesh to 0
        val y = (sumY / weight).toFloat() - FILL_DROP_M
        val colour = (BYTE shl ALPHA) or
            ((r / weight).toInt().coerceIn(0, BYTE) shl RED) or
            ((g / weight).toInt().coerceIn(0, BYTE) shl GREEN) or
            (b / weight).toInt().coerceIn(0, BYTE)
        val base = mesh.vertexCount
        val positions = mesh.positions.copyOf((base + holes.size * QUAD) * 3)
        val colors = mesh.colors.copyOf(base + holes.size * QUAD)
        val indices = mesh.indices.copyOf(mesh.indices.size + holes.size * QUAD_INDICES)
        var i = mesh.indices.size
        for ((h, cell) in holes.withIndex()) {
            val cx = originX + (cell % cols) * CELL_M
            val cz = originZ + (cell / cols) * CELL_M
            val v = base + h * QUAD
            val corners = floatArrayOf(cx, cz, cx + CELL_M, cz, cx + CELL_M, cz + CELL_M, cx, cz + CELL_M)
            for (k in 0 until QUAD) {
                positions[(v + k) * 3] = corners[k * 2]
                positions[(v + k) * 3 + 1] = y
                positions[(v + k) * 3 + 2] = corners[k * 2 + 1]
                colors[v + k] = colour
            }
            // Wound to face up: (0, 2, 1) and (0, 3, 2) seen from +Y.
            for (k in intArrayOf(0, 2, 1, 0, 3, 2)) indices[i++] = v + k
        }
        return WorkMesh(positions, colors, indices) to holes.size
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

    /** [cells] grown by [by] cells, a square neighbourhood. */
    internal fun dilate(cells: BooleanArray, cols: Int, rows: Int, by: Int): BooleanArray {
        val out = BooleanArray(cells.size)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                if (!cells[row * cols + col]) continue
                for (r in max(0, row - by)..min(rows - 1, row + by)) {
                    for (c in max(0, col - by)..min(cols - 1, col + by)) out[r * cols + c] = true
                }
            }
        }
        return out
    }

    /** [cells] shrunk by [by] cells: a cell stays when its whole square neighbourhood is set. */
    internal fun erode(cells: BooleanArray, cols: Int, rows: Int, by: Int): BooleanArray {
        val unset = BooleanArray(cells.size) { !cells[it] }
        val grown = dilate(unset, cols, rows, by)
        return BooleanArray(cells.size) { !grown[it] }
    }

    /** [cells] with every unset region no path of unset cells joins to the grid's edge set too. */
    internal fun fillEnclosed(cells: BooleanArray, cols: Int, rows: Int): BooleanArray {
        val outside = BooleanArray(cells.size)
        val stack = ArrayDeque<Int>()
        fun push(i: Int) {
            if (!cells[i] && !outside[i]) {
                outside[i] = true
                stack.addLast(i)
            }
        }
        for (col in 0 until cols) {
            push(col)
            push((rows - 1) * cols + col)
        }
        for (row in 0 until rows) {
            push(row * cols)
            push(row * cols + cols - 1)
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val col = i % cols
            val row = i / cols
            if (col > 0) push(i - 1)
            if (col < cols - 1) push(i + 1)
            if (row > 0) push(i - cols)
            if (row < rows - 1) push(i + cols)
        }
        return BooleanArray(cells.size) { !outside[it] }
    }

    /** The share of a scan's vertices past each end of each axis [crop] leaves out. */
    const val TAIL = 0.01f

    /** How much wider than its quantiles [crop] keeps the box, metres. */
    const val MARGIN_M = 0.15f

    /** The floor's cells, metres. */
    const val CELL_M = 0.05f

    /** The widest gap, in cells, the floor is closed over: 15 cm. */
    const val CLOSE_CELLS = 3

    /** How far under the floor's plane its fill lies: hidden under the floor the scan saw. */
    const val FILL_DROP_M = 0.01f

    /** Up-facing area under which a scan has no floor to close: an object's base, a table top. */
    const val MIN_FLOOR_M2 = 1f

    private const val MIN_VERTICES = 100
    private const val AXES = 3
    private const val HALF = 0.5f
    private const val LOW_SHARE = 0.4f
    private const val BIN_M = 0.02f
    private const val UP = 0.9f
    private const val MAX_CELLS = 1_000_000L
    private const val QUAD = 4
    private const val QUAD_INDICES = 6
    private const val BYTE = 0xFF
    private const val ALPHA = 24
    private const val RED = 16
    private const val GREEN = 8
}
