package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.DebugPlane
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Completes room surfaces inside observed plane polygons. Fused planes without an ARCore
 * polygon only bridge gaps within 30 cm of their own samples and inside their convex hull.
 * No room rectangle, ceiling, or floor-to-wall extrusion is guessed. Furniture stays in place.
 * Cells are clipped to polygon edges, so the outer boundary is straight rather than stair-stepped.
 */
internal object BoundedPlaneCompletion {
    private const val CELL = 0.15f
    private const val REACH = 0.30f
    private const val SNAP = 0.025f
    private const val MAX_CELLS = 20_000
    private data class P(val x: Float, val y: Float)
    private data class Patch(val plane: Plane, val rim: List<P>, val support: List<P>?, val colors: List<Int>) {
        // u cross v = normal, with u horizontal on vertical walls.
        private val horizontal = sqrt(plane.nx * plane.nx + plane.nz * plane.nz)
        val ux = if (horizontal < 1e-5f) 1f else plane.nz / horizontal
        val uz = if (horizontal < 1e-5f) 0f else -plane.nx / horizontal
        val vx = plane.ny * uz
        val vy = plane.nz * ux - plane.nx * uz
        val vz = -plane.ny * ux
        fun project(x: Float, y: Float, z: Float) = P(x * ux + z * uz, x * vx + y * vy + z * vz)
        fun world(p: P) = floatArrayOf(
            plane.nx * plane.offset + ux * p.x + vx * p.y,
            plane.ny * plane.offset + vy * p.y,
            plane.nz * plane.offset + uz * p.x + vz * p.y,
        )
    }

    fun complete(mesh: WorkMesh, observed: List<DebugPlane>): WorkMesh {
        if (mesh.triangleCount == 0) return mesh
        val flattened = PlaneFlattening.flatten(mesh)
        val patches = ArrayList<Patch>()
        // Only the lowest horizontal plane is a floor: ARCore also labels tabletops upward.
        val floorY = observed.filter { it.kind == DebugPlaneKind.Floor && it.vertexCount >= 3 }
            .minOfOrNull { it.polygon[1] }
        for (seen in observed) {
            if (seen.vertexCount < 3 || seen.polygon.any { !it.isFinite() }) continue
            if (seen.kind != DebugPlaneKind.Wall &&
                !(seen.kind == DebugPlaneKind.Floor && abs(seen.polygon[1] - (floorY ?: 0f)) < 0.1f)) continue
            val a = seen.polygon
            var nx = 0f; var ny = 0f; var nz = 0f
            // Newell normal works even when the polygon starts with collinear vertices.
            for (i in 0 until seen.vertexCount) {
                val j = (i + 1) % seen.vertexCount
                nx += (a[i * 3 + 1] - a[j * 3 + 1]) * (a[i * 3 + 2] + a[j * 3 + 2])
                ny += (a[i * 3 + 2] - a[j * 3 + 2]) * (a[i * 3] + a[j * 3])
                nz += (a[i * 3] - a[j * 3]) * (a[i * 3 + 1] + a[j * 3 + 1])
            }
            if (seen.kind == DebugPlaneKind.Floor) { nx = 0f; ny = 1f; nz = 0f } else ny = 0f
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length < 1e-5f) continue
            nx /= length; ny /= length; nz /= length
            val plane = Plane(nx, ny, nz, nx * a[0] + ny * a[1] + nz * a[2])
            val basis = Patch(plane, emptyList(), null, emptyList())
            val rim = hull(List(seen.vertexCount) { basis.project(a[it * 3], a[it * 3 + 1], a[it * 3 + 2]) })
            val colors = (0 until mesh.vertexCount).filter {
                abs(plane.distance(mesh.positions[it * 3], mesh.positions[it * 3 + 1], mesh.positions[it * 3 + 2])) < 0.08f
            }.map { mesh.colors[it] }
            if (rim.size >= 3 && colors.isNotEmpty()) patches += basis.copy(rim = rim, colors = colors)
        }
        for ((id, plane) in flattened.planes.withIndex()) {
            if (abs(plane.ny) in 0.1f..0.99f || plane.ny < -0.1f) continue
            val vertices = HashSet<Int>()
            for (t in 0 until mesh.triangleCount) if (flattened.facePlane[t] == id) {
                for (c in 0..2) vertices += mesh.indices[t * 3 + c]
            }
            val basis = Patch(plane, emptyList(), null, emptyList())
            val points = vertices.map { basis.project(mesh.positions[it * 3], mesh.positions[it * 3 + 1], mesh.positions[it * 3 + 2]) }
            val rim = hull(points)
            if (rim.size >= 3) patches += basis.copy(rim = rim, support = points, colors = vertices.map { mesh.colors[it] })
        }
        val positions = mesh.positions.toMutableList()
        val colors = mesh.colors.toMutableList()
        val indices = ArrayList<Int>()
        val keep = BooleanArray(mesh.triangleCount) { true }
        val completed = ArrayList<Pair<Patch, Set<Pair<Int, Int>>>>()
        var budget = MAX_CELLS
        for (patch in patches) {
            val rim = patch.rim
            val minX = floor(rim.minOf { it.x } / CELL).toInt()
            val maxX = ceil(rim.maxOf { it.x } / CELL).toInt()
            val minY = floor(rim.minOf { it.y } / CELL).toInt()
            val maxY = ceil(rim.maxOf { it.y } / CELL).toInt()
            if ((maxX.toLong() - minX) * (maxY.toLong() - minY) > budget) continue
            val cells = HashSet<Pair<Int, Int>>()
            val ids = HashMap<Pair<Int, Int>, Int>()
            val color = average(patch.colors)
            val supportGrid = patch.support?.groupBy { floor(it.x / REACH).toInt() to floor(it.y / REACH).toInt() }
            fun supported(p: P): Boolean {
                val grid = supportGrid ?: return true
                val cx = floor(p.x / REACH).toInt(); val cy = floor(p.y / REACH).toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    if (grid[cx + dx to cy + dy]?.any { distance2(p, it) <= REACH * REACH } == true) return true
                }
                return false
            }
            fun vertex(p: P): Int = ids.getOrPut((p.x * 100_000).roundToInt() to (p.y * 100_000).roundToInt()) {
                val id = colors.size
                positions.addAll(patch.world(p).toList()); colors += color
                id
            }
            for (x in minX until maxX) for (y in minY until maxY) {
                val polygon = clip(rim, x * CELL, y * CELL, (x + 1) * CELL, (y + 1) * CELL)
                if (polygon.size < 3) continue
                val center = P(polygon.sumOf { it.x.toDouble() }.toFloat() / polygon.size, polygon.sumOf { it.y.toDouble() }.toFloat() / polygon.size)
                val support = patch.support
                // Every corner must have nearby evidence, not just the cell's middle.
                if (support != null && (polygon + center).any { p -> !supported(p) }) continue
                val world = patch.world(center)
                if (completed.any { (prior, covered) ->
                    abs(prior.plane.distance(world[0], world[1], world[2])) < SNAP &&
                        contains(prior.rim, prior.project(world[0], world[1], world[2])) &&
                        cell(prior.project(world[0], world[1], world[2])) in covered
                }) continue
                cells += x to y
                val first = vertex(polygon[0])
                for (i in 1 until polygon.lastIndex) {
                    indices += first; indices += vertex(polygon[i]); indices += vertex(polygon[i + 1])
                }
                budget--
            }
            if (cells.isEmpty()) continue
            completed += patch to cells
            // Replace only faces wholly covered by the patch, lying on the same plane.
            // Relief, furniture and the uncompleted boundary retain their original triangles.
            for (t in 0 until mesh.triangleCount) {
                if (!keep[t]) continue
                val vs = (0..2).map { mesh.indices[t * 3 + it] }
                if (vs.all { v ->
                    val px = mesh.positions[v * 3]; val py = mesh.positions[v * 3 + 1]; val pz = mesh.positions[v * 3 + 2]
                    val p = patch.project(px, py, pz)
                    abs(patch.plane.distance(px, py, pz)) < SNAP && contains(rim, p) && cell(p) in cells
                }) keep[t] = false
            }
        }
        if (indices.isEmpty()) return mesh
        for (t in 0 until mesh.triangleCount) if (keep[t]) for (k in 0..2) indices += mesh.indices[t * 3 + k]
        return WorkMesh(positions.toFloatArray(), colors.toIntArray(), indices.toIntArray())
            .keepTriangles(BooleanArray(indices.size / 3) { true })
    }

    private fun cell(p: P) = floor(p.x / CELL).toInt() to floor(p.y / CELL).toInt()
    private fun distance2(a: P, b: P) = (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)
    private fun cross(a: P, b: P, c: P) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    private fun contains(rim: List<P>, p: P) = rim.indices.all { cross(rim[it], rim[(it + 1) % rim.size], p) >= -1e-5f }
    private fun hull(points: List<P>): List<P> {
        val sorted = points.distinct().sortedWith(compareBy<P> { it.x }.thenBy { it.y })
        if (sorted.size < 3) return emptyList()
        fun half(ps: List<P>): List<P> {
            val out = ArrayList<P>()
            for (p in ps) {
                while (out.size >= 2 && cross(out[out.size - 2], out.last(), p) <= 0f) out.removeAt(out.lastIndex)
                out += p
            }
            return out.dropLast(1)
        }
        return half(sorted) + half(sorted.reversed())
    }
    private fun clip(rim: List<P>, x0: Float, y0: Float, x1: Float, y1: Float): List<P> {
        var out = rim
        for (edge in 0..3) {
            val input = out
            out = ArrayList()
            if (input.isEmpty()) break
            fun d(p: P) = when (edge) { 0 -> p.x - x0; 1 -> x1 - p.x; 2 -> p.y - y0; else -> y1 - p.y }
            var a = input.last()
            for (b in input) {
                val da = d(a); val db = d(b)
                if ((da >= 0f) != (db >= 0f)) {
                    val t = da / (da - db)
                    out += P(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
                }
                if (db >= 0f) out += b
                a = b
            }
        }
        return out.distinct()
    }
    private fun average(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xffb4b4b4.toInt()
        fun channel(shift: Int) = (colors.sumOf { ((it ushr shift) and 255).toLong() } / colors.size).toInt()
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
