package io.github.sceneview.demo.demos.internal

import java.util.Arrays
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Garland–Heckbert plane quadrics, with the minimum restricted to the collapsed edge segment.
 * Segment placement keeps positions and interpolated sRGB colours in their input convex hull.
 * Open boundary vertices, strong colour edges, non-manifold edge vertices and bounds extrema are locked.
 * The link condition, duplicate-face check and positive face-normal dot product protect topology.
 * Strong colour jumps (>64 in any byte channel) cannot collapse; smaller jumps add geometric cost
 * along the edge that carries them only, so a stripe still thins out along its own length. Its
 * border does fray: the voxel staircase between two colours becomes larger teeth. Charging every
 * vertex near a gradient instead was tried and reverted — it thinned stripes across, and lost them.
 *
 * Blocking CPU work: call it on a worker. Input arrays are never mutated.
 * Marching cubes shares vertices by grid edge. At an exact zero-valued grid corner, different
 * edges can still meet at identical positions: first weld only coincident endpoints connected
 * by an input edge. Separate coincident sheets are never joined. Colours carry the lower index.
 * Output is compact indexed geometry with rebuilt area-weighted unit normals.
 *
 * Costs are sorted in deterministic sweeps, then recomputed before each collapse; no stale heap
 * entries or per-edge objects. At most 64 sweeps, stopping when no legal collapse remains.
 * Target is best effort: boundaries, topology and colour take priority over the triangle budget.
 * Storage: [workspaceBytes] of primitive working arrays, plus input/output and array headers.
 * Typical 400k-T / 200k-V scan: ~53 MiB workspace; the extraction's cap (1.5 M triangles) would
 * take ~200 MiB, more than a phone's heap gives — ask [workspaceBytes] before calling.
 * Time: O(S * (T log T + T d²)), S <= 64, d local vertex valence (normally ~6).
 */
internal object RerunMeshSimplifier {
    const val DEFAULT_TARGET_TRIANGLES = 100_000

    /**
     * [mesh] in at most [targetTriangles] triangles, where its borders and colours allow.
     *
     * [progress] is told the share done, 0–1, a few times a second; a caller stops the work by
     * throwing from it (a coroutine's `ensureActive()`), which leaves nothing behind.
     */
    fun simplify(
        mesh: RerunMesh,
        targetTriangles: Int = DEFAULT_TARGET_TRIANGLES,
        progress: (Float) -> Unit = {},
    ): RerunMesh {
        require(targetTriangles >= 0)
        if (mesh.triangleCount == 0) return mesh
        return Worker(mesh, progress).run(targetTriangles)
    }

    /** The working arrays [simplify] allocates for a mesh of this size, in bytes. */
    fun workspaceBytes(vertices: Int, triangles: Int): Long =
        BYTES_PER_VERTEX * vertices + BYTES_PER_TRIANGLE * triangles

    // positions 12, colours 4, quadric 80, corner list head 4, marks 4, two flags.
    private const val BYTES_PER_VERTEX = 106L

    // indices 12, corner links 24, edge keys 24, sweep order 24.
    private const val BYTES_PER_TRIANGLE = 84L

    /** Collapses between two progress reports: a few milliseconds of work. */
    private const val REPORT_EVERY = 2048

    private class Worker(val input: RerunMesh, val progress: (Float) -> Unit) {
        val vertices = input.vertexCount
        val p = input.positions.copyOf()
        val colors = input.colors.copyOf()
        val triangles = input.indices.copyOf()
        val q = DoubleArray(vertices * 10)
        val head = IntArray(vertices) { -1 }
        val next = IntArray(triangles.size) { -1 }
        val previous = IntArray(triangles.size) { -1 }
        val locked = BooleanArray(vertices)
        val alive = BooleanArray(vertices) { true }
        val edges = LongArray(triangles.size)
        val order = LongArray(triangles.size)
        val marks = IntArray(vertices)
        var stamp = 0
        var count = input.triangleCount
        var edgeCount = 0
        var fraction = 0.0
        val normal = DoubleArray(3)
        val oldNormal = DoubleArray(3)
        val plane = DoubleArray(4)

        init {
            require(p.all { it.isFinite() })
            require(triangles.all { it in 0 until vertices })
            val weld = IntArray(vertices) { it }
            fun root(v: Int): Int {
                var at = v
                while (weld[at] != at) {
                    weld[at] = weld[weld[at]]
                    at = weld[at]
                }
                return at
            }
            for (t in triangles.indices step 3) for (k in 0..2) {
                val a = triangles[t + k]
                val b = triangles[t + (k + 1) % 3]
                if (p[a * 3] == p[b * 3] && p[a * 3 + 1] == p[b * 3 + 1] && p[a * 3 + 2] == p[b * 3 + 2]) {
                    val ra = root(a)
                    val rb = root(b)
                    weld[maxOf(ra, rb)] = minOf(ra, rb)
                }
            }
            for (i in triangles.indices) triangles[i] = root(triangles[i])
            progress(0f)
            for (t in 0 until count) {
                val offset = t * 3
                val a = triangles[offset]
                val b = triangles[offset + 1]
                val c = triangles[offset + 2]
                faceNormal(a, b, c, normal)
                val length = sqrt(dot(normal, normal))
                val repeated = a == b || a == c || b == c
                if (repeated || length <= 1e-15) {
                    triangles[offset] = -1
                    continue
                }
                for (k in 0..2) attach(offset + k, triangles[offset + k])
                val nx = normal[0] / length
                val ny = normal[1] / length
                val nz = normal[2] / length
                plane[0] = nx
                plane[1] = ny
                plane[2] = nz
                plane[3] = -(nx * p[a * 3] + ny * p[a * 3 + 1] + nz * p[a * 3 + 2])
                for (k in 0..2) {
                    var at = triangles[offset + k] * 10
                    for (i in 0..3) for (j in i..3) q[at++] += plane[i] * plane[j]
                }
            }
            count = (triangles.indices step 3).count { triangles[it] >= 0 }
            // Keep the exact original bounding box, without locking whole planar walls.
            for (axis in 0..2) {
                var low = 0
                var high = 0
                for (v in 1 until vertices) {
                    if (p[v * 3 + axis] < p[low * 3 + axis]) low = v
                    if (p[v * 3 + axis] > p[high * 3 + axis]) high = v
                }
                locked[root(low)] = true
                locked[root(high)] = true
            }
            collectEdges()
            var at = 0
            while (at < triangles.size && edges[at] != Long.MAX_VALUE) {
                var end = at + 1
                while (end < triangles.size && edges[end] == edges[at]) end++
                val a = (edges[at] ushr 32).toInt()
                val b = edges[at].toInt()
                if (end - at != 2 || colorStep(a, b).isInfinite()) {
                    locked[a] = true
                    locked[b] = true
                }
                at = end
            }
            // Remove any duplicate input faces before doing topology checks.
            for (t in triangles.indices step 3) {
                if (triangles[t] < 0) continue
                val a = triangles[t]
                val b = triangles[t + 1]
                val c = triangles[t + 2]
                var corner = head[a]
                var duplicate = false
                while (corner >= 0) {
                    val other = corner / 3 * 3
                    if (other < t && contains(other, b) && contains(other, c)) duplicate = true
                    corner = next[corner]
                }
                if (duplicate) remove(t)
            }
        }

        fun run(target: Int): RerunMesh {
            val toRemove = (count - target).coerceAtLeast(1).toFloat()
            var sinceReport = 0
            repeat(64) {
                if (count <= target) return compact()
                progress(1f - (count - target) / toRemove)
                collectEdges()
                var unique = 0
                var last = -1L
                for (i in edges.indices) {
                    val edge = edges[i]
                    if (edge == Long.MAX_VALUE) break
                    if (edge != last) edges[unique++] = edge
                    last = edge
                }
                edgeCount = unique
                for (i in 0 until edgeCount) {
                    val a = (edges[i] ushr 32).toInt()
                    val b = edges[i].toInt()
                    val cost = cost(a, b).toFloat()
                    order[i] = (cost.toRawBits().toLong() shl 32) or i.toLong()
                }
                Arrays.sort(order, 0, edgeCount)
                val before = count
                var i = 0
                while (i < edgeCount && count > target) {
                    val edge = edges[order[i++].toInt()]
                    val a = (edge ushr 32).toInt()
                    val b = edge.toInt()
                    // The cost is recomputed: it also sets where on the edge the collapse lands.
                    val gone = !alive[a] || !alive[b]
                    if (gone || !cost(a, b).isFinite() || !legal(a, b)) continue
                    collapse(a, b)
                    if (++sinceReport == REPORT_EVERY) {
                        sinceReport = 0
                        progress(1f - (count - target) / toRemove)
                    }
                }
                if (before == count) return compact()
            }
            return compact()
        }

        fun collectEdges() {
            var at = 0
            for (t in triangles.indices step 3) {
                if (triangles[t] < 0) continue
                for (k in 0..2) {
                    val a = triangles[t + k]
                    val b = triangles[t + (k + 1) % 3]
                    edges[at++] = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                }
            }
            Arrays.fill(edges, at, edges.size, Long.MAX_VALUE)
            Arrays.sort(edges)
        }

        fun cost(a: Int, b: Int): Double {
            if (locked[a] || locked[b]) return Double.POSITIVE_INFINITY
            val colorError = colorStep(a, b)
            if (colorError.isInfinite()) return colorError
            val ax = p[a * 3].toDouble()
            val ay = p[a * 3 + 1].toDouble()
            val az = p[a * 3 + 2].toDouble()
            val dx = p[b * 3] - ax
            val dy = p[b * 3 + 1] - ay
            val dz = p[b * 3 + 2] - az
            fun coefficient(k: Int) = q[a * 10 + k] + q[b * 10 + k]
            val curvature = coefficient(0) * dx * dx + 2 * coefficient(1) * dx * dy +
                2 * coefficient(2) * dx * dz + coefficient(4) * dy * dy +
                2 * coefficient(5) * dy * dz + coefficient(7) * dz * dz
            val slope = dx * (coefficient(0) * ax + coefficient(1) * ay + coefficient(2) * az + coefficient(3)) +
                dy * (coefficient(1) * ax + coefficient(4) * ay + coefficient(5) * az + coefficient(6)) +
                dz * (coefficient(2) * ax + coefficient(5) * ay + coefficient(7) * az + coefficient(8))
            fraction = if (curvature > 1e-15) (-slope / curvature).coerceIn(0.0, 1.0) else 0.5
            val x = ax + fraction * dx
            val y = ay + fraction * dy
            val z = az + fraction * dz
            val error = coefficient(0) * x * x + 2 * coefficient(1) * x * y + 2 * coefficient(2) * x * z +
                2 * coefficient(3) * x + coefficient(4) * y * y + 2 * coefficient(5) * y * z +
                2 * coefficient(6) * y + coefficient(7) * z * z + 2 * coefficient(8) * z + coefficient(9)
            // Length tie-breaker avoids concentrating planar collapses around the first vertex.
            return max(0.0, error) + (colorError + 1e-6) * (dx * dx + dy * dy + dz * dz)
        }

        /**
         * How far apart the colours of [a] and [b] are: the squared distance of their sRGB
         * channels, each 0–1 — infinite past the strong-edge step, which never collapses.
         */
        fun colorStep(a: Int, b: Int): Double {
            var error = 0.0
            for (shift in 0..16 step 8) {
                val delta = ((colors[a] ushr shift) and 255) - ((colors[b] ushr shift) and 255)
                if (abs(delta) > 64) return Double.POSITIVE_INFINITY
                error += delta * delta / (255.0 * 255.0)
            }
            return error
        }

        fun legal(a: Int, b: Int): Boolean = linked(a, b) && keepsFaces(a, b, a) && keepsFaces(a, b, b)

        /**
         * The link condition: exactly two faces on the edge, and exactly their two opposite
         * vertices shared by both one-rings. Boundaries were locked before any collapse.
         */
        fun linked(a: Int, b: Int): Boolean {
            stamp++
            var corner = head[a]
            while (corner >= 0) {
                val t = corner / 3 * 3
                for (k in 0..2) marks[triangles[t + k]] = stamp
                corner = next[corner]
            }
            var shared = 0
            var incident = 0
            corner = head[b]
            while (corner >= 0) {
                val t = corner / 3 * 3
                if (contains(t, a)) incident++
                for (k in 0..2) {
                    val v = triangles[t + k]
                    if (v != a && v != b && marks[v] == stamp) {
                        shared++
                        marks[v] = -stamp
                    }
                }
                corner = next[corner]
            }
            return incident == 2 && shared == 2
        }

        /** No face around [v], an end of the edge [a]–[b], would double another, vanish or flip. */
        fun keepsFaces(a: Int, b: Int, v: Int): Boolean {
            var corner = head[v]
            while (corner >= 0) {
                val t = corner / 3 * 3
                // The two faces on the edge go with it; every other one moves.
                val moved = !(contains(t, a) && contains(t, b))
                if (moved && v == b && doubles(corner, a)) return false
                if (moved && flips(t, v, a, b)) return false
                corner = next[corner]
            }
            return true
        }

        /** Tetrahedron / shared link edge: moving this [corner] to [a] would duplicate a face of [a]. */
        fun doubles(corner: Int, a: Int): Boolean {
            val t = corner / 3 * 3
            val u = triangles[t + (corner % 3 + 1) % 3]
            val w = triangles[t + (corner % 3 + 2) % 3]
            var other = head[a]
            while (other >= 0) {
                val ot = other / 3 * 3
                if (contains(ot, u) && contains(ot, w)) return true
                other = next[other]
            }
            return false
        }

        /** Face [t] would lose its area or turn over with [v] moved to where [a]–[b] collapses. */
        fun flips(t: Int, v: Int, a: Int, b: Int): Boolean {
            faceNormal(triangles[t], triangles[t + 1], triangles[t + 2], oldNormal)
            val px = p[v * 3]
            val py = p[v * 3 + 1]
            val pz = p[v * 3 + 2]
            for (axis in 0..2) p[v * 3 + axis] =
                (p[a * 3 + axis] + fraction * (p[b * 3 + axis] - p[a * 3 + axis])).toFloat()
            faceNormal(triangles[t], triangles[t + 1], triangles[t + 2], normal)
            p[v * 3] = px
            p[v * 3 + 1] = py
            p[v * 3 + 2] = pz
            val area = dot(normal, normal)
            return area <= 1e-24 || dot(normal, oldNormal) <= 0.1 * sqrt(area * dot(oldNormal, oldNormal))
        }

        fun collapse(a: Int, b: Int) {
            for (axis in 0..2) p[a * 3 + axis] =
                (p[a * 3 + axis] + fraction * (p[b * 3 + axis] - p[a * 3 + axis])).toFloat()
            var color = 0xFF000000.toInt()
            for (shift in 0..16 step 8) {
                val ca = (colors[a] ushr shift) and 255
                val cb = (colors[b] ushr shift) and 255
                color = color or ((ca + fraction * (cb - ca) + 0.5).toInt() shl shift)
            }
            colors[a] = color
            for (k in 0..9) q[a * 10 + k] += q[b * 10 + k]
            while (head[b] >= 0) {
                val corner = head[b]
                val t = corner / 3 * 3
                if (contains(t, a)) remove(t) else {
                    detach(corner)
                    triangles[corner] = a
                    attach(corner, a)
                }
            }
            alive[b] = false
        }

        fun contains(t: Int, v: Int) = triangles[t] == v || triangles[t + 1] == v || triangles[t + 2] == v

        fun attach(corner: Int, v: Int) {
            next[corner] = head[v]
            previous[corner] = -1
            if (head[v] >= 0) previous[head[v]] = corner
            head[v] = corner
        }

        fun detach(corner: Int) {
            val before = previous[corner]
            val after = next[corner]
            if (before < 0) head[triangles[corner]] = after else next[before] = after
            if (after >= 0) previous[after] = before
        }

        fun remove(t: Int) {
            for (k in 0..2) detach(t + k)
            triangles[t] = -1
            count--
        }

        fun faceNormal(a: Int, b: Int, c: Int, out: DoubleArray) {
            val ux = p[b * 3].toDouble() - p[a * 3]
            val uy = p[b * 3 + 1].toDouble() - p[a * 3 + 1]
            val uz = p[b * 3 + 2].toDouble() - p[a * 3 + 2]
            val vx = p[c * 3].toDouble() - p[a * 3]
            val vy = p[c * 3 + 1].toDouble() - p[a * 3 + 1]
            val vz = p[c * 3 + 2].toDouble() - p[a * 3 + 2]
            out[0] = uy * vz - uz * vy
            out[1] = uz * vx - ux * vz
            out[2] = ux * vy - uy * vx
        }

        fun dot(u: DoubleArray, v: DoubleArray) = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]

        fun compact(): RerunMesh {
            progress(1f)
            val remap = IntArray(vertices) { -1 }
            var n = 0
            for (t in triangles.indices step 3) if (triangles[t] >= 0) {
                for (k in 0..2) if (remap[triangles[t + k]] < 0) remap[triangles[t + k]] = n++
            }
            val positions = FloatArray(n * 3)
            val normals = FloatArray(n * 3)
            val outColors = IntArray(n)
            val indices = IntArray(count * 3)
            for (v in 0 until vertices) if (remap[v] >= 0) {
                p.copyInto(positions, remap[v] * 3, v * 3, v * 3 + 3)
                outColors[remap[v]] = colors[v]
            }
            var at = 0
            for (t in triangles.indices step 3) if (triangles[t] >= 0) {
                faceNormal(triangles[t], triangles[t + 1], triangles[t + 2], normal)
                for (k in 0..2) {
                    val v = remap[triangles[t + k]]
                    indices[at++] = v
                    for (axis in 0..2) normals[v * 3 + axis] += normal[axis].toFloat()
                }
            }
            for (v in 0 until n) {
                val offset = v * 3
                val length = sqrt(normals[offset] * normals[offset] + normals[offset + 1] * normals[offset + 1] +
                    normals[offset + 2] * normals[offset + 2])
                if (length > 0f) for (axis in 0..2) normals[offset + axis] /= length
                else normals[offset + 1] = 1f
            }
            return RerunMesh(positions, normals, outColors, indices)
        }
    }
}
