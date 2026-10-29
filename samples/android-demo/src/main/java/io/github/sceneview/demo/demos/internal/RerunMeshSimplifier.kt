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
 * entries or per-edge objects. At most 64 sweeps, stopping at the first one that removes under
 * 1 % of the triangles: past it the budget is out of reach, and each further sweep would sort
 * every edge again for a handful of triangles.
 * Target is best effort: boundaries, topology and colour take priority over the triangle budget.
 * Storage: [workspaceBytes] of primitive working arrays, plus input/output and array headers.
 * Typical 400k-T / 200k-V scan: ~43 MiB workspace; the extraction's cap
 * ([RerunMarchingCubes.MAX_TRIANGLES], ~0.5 M vertices) would take ~108 MiB, more than a phone's
 * heap gives — ask [workspaceBytes] before calling. A vertex's corners are singly linked (a
 * detach walks its few corners), and the sweep order holds the edges counted on the way in,
 * about half the corners: a collapse only ever merges edges.
 * Time: O(S * (T log T + T d²)), S <= 64, d local vertex valence (normally ~6).
 */
internal object RerunMeshSimplifier {
    const val DEFAULT_TARGET_TRIANGLES = 100_000

    /**
     * [mesh] in at most [targetTriangles] triangles, where its borders and colours allow.
     *
     * [progress] is told the share done, 0–1, a few times a second; a caller stops the work by
     * throwing from it (a coroutine's `ensureActive()`), which leaves nothing behind.
     * No collapse leaves an edge longer than [maxEdgeM]: a textured model's faces must each fit
     * in one photo. A collapse is refused when its cost passes [maxErrorM]²: the cost is the sum
     * of the squared distances from the new vertex to every plane its quadric gathered (plus the
     * colour charge above), so each single distance stays under [maxErrorM], and a curved patch,
     * whose planes disagree, stops sooner than one plane would make it. A flat wall goes down to
     * a few large faces, a cushion keeps its curve, and the result may stay well above
     * [targetTriangles]: the caller logs what it reached.
     *
     * With [maxErrorCeilingM] above [maxErrorM], the budget wins over the cap up to that ceiling:
     * a sweep that stalls over [targetTriangles] doubles the cap and goes on, the cheapest
     * collapses — the flattest — still first. A noisy surface the cap held back comes down to the
     * budget instead of staying several times over it.
     *
     * With [belowTarget], a surface that starts under [targetTriangles] has every collapse under
     * [maxErrorM] made, past the budget too: a small surface still loses the faces its flat runs
     * do not need. One that starts over the budget stops there, as without it. With [consume], [mesh]'s colour and index arrays
     * are worked in rather than copied, and left scrambled: for a caller done with them.
     *
     * With [collapseBorders], an open border is simplified too instead of held still: a border
     * edge collapses along the border, an inner edge onto its border end, never across a strip
     * between two borders, and every border edge adds to its ends' quadric the plane standing on
     * it across its face, so the outline moves no further than the cap. A scan full of small holes
     * — the lace a phone's depth makes of a far or dark wall — otherwise locks most of its vertices.
     */
    @Suppress("LongParameterList")
    fun simplify(
        mesh: RerunMesh,
        targetTriangles: Int = DEFAULT_TARGET_TRIANGLES,
        maxEdgeM: Float = Float.POSITIVE_INFINITY,
        maxErrorM: Float = Float.POSITIVE_INFINITY,
        maxErrorCeilingM: Float = maxErrorM,
        belowTarget: Boolean = false,
        consume: Boolean = false,
        collapseBorders: Boolean = false,
        progress: (Float) -> Unit = {},
    ): RerunMesh {
        require(targetTriangles >= 0)
        if (mesh.triangleCount == 0) return mesh
        val ceiling = maxOf(maxErrorM, maxErrorCeilingM).toDouble()
        val worker = Worker(
            mesh,
            consume,
            collapseBorders,
            progress,
            maxEdgeM.toDouble() * maxEdgeM,
            maxErrorM.toDouble() * maxErrorM,
            ceiling * ceiling,
        )
        return worker.run(targetTriangles, belowTarget)
    }

    /** The working arrays [simplify] allocates for a mesh of this size, in bytes. */
    fun workspaceBytes(vertices: Int, triangles: Int): Long =
        BYTES_PER_VERTEX * vertices + BYTES_PER_TRIANGLE * triangles

    // positions 12, colours 4, quadric 80, corner list head 4, marks 4, three flags.
    private const val BYTES_PER_VERTEX = 107L

    // indices 12, corner links 12, edge keys 24, sweep order 12 (each edge but a border's has two faces).
    private const val BYTES_PER_TRIANGLE = 60L

    /** Collapses between two progress reports: a few milliseconds of work. */
    private const val REPORT_EVERY = 2048

    /** A sweep earns the next one by removing at least one triangle in this many. */
    private const val SWEEP_GAIN_DIVISOR = 100

    /** A stalled cap doubles: four times its square. */
    private const val CAP_STEP_SQ = 4.0

    private class Worker(
        input: RerunMesh,
        consume: Boolean,
        val collapseBorders: Boolean,
        val progress: (Float) -> Unit,
        val maxEdgeSq: Double,
        var maxErrorSq: Double,
        val maxErrorCeilingSq: Double,
    ) {
        val vertices = input.vertexCount
        val p = input.positions.copyOf()
        val colors = if (consume) input.colors else input.colors.copyOf()
        val triangles = if (consume) input.indices else input.indices.copyOf()
        val q = DoubleArray(vertices * 10)
        val head = IntArray(vertices) { -1 }
        val next = IntArray(triangles.size) { -1 }
        val locked = BooleanArray(vertices)
        val border = BooleanArray(vertices)
        val alive = BooleanArray(vertices) { true }
        val edges = LongArray(triangles.size)

        /** The sweep's edges by cost: as many as the first sweep finds, no collapse adds one. */
        var order = LongArray(0)
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
            var unique = 0
            while (at < triangles.size && edges[at] != Long.MAX_VALUE) {
                var end = at + 1
                while (end < triangles.size && edges[end] == edges[at]) end++
                unique++
                val a = (edges[at] ushr 32).toInt()
                val b = edges[at].toInt()
                val faces = end - at
                if (faces > 2 || faces == 1 && !collapseBorders || colorStep(a, b).isInfinite()) {
                    locked[a] = true
                    locked[b] = true
                } else if (faces == 1) {
                    border[a] = true
                    border[b] = true
                    constrain(a, b)
                }
                at = end
            }
            order = LongArray(unique)
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

        /** Adds to [a]'s and [b]'s quadrics the plane through their border edge, upright on its one face. */
        fun constrain(a: Int, b: Int) {
            var corner = head[a]
            while (corner >= 0 && !contains(corner / 3 * 3, b)) corner = next[corner]
            if (corner < 0) return
            val t = corner / 3 * 3
            faceNormal(triangles[t], triangles[t + 1], triangles[t + 2], normal)
            val ex = (p[b * 3] - p[a * 3]).toDouble()
            val ey = (p[b * 3 + 1] - p[a * 3 + 1]).toDouble()
            val ez = (p[b * 3 + 2] - p[a * 3 + 2]).toDouble()
            val mx = ey * normal[2] - ez * normal[1]
            val my = ez * normal[0] - ex * normal[2]
            val mz = ex * normal[1] - ey * normal[0]
            val length = sqrt(mx * mx + my * my + mz * mz)
            if (length <= 1e-15) return
            plane[0] = mx / length
            plane[1] = my / length
            plane[2] = mz / length
            plane[3] = -(plane[0] * p[a * 3] + plane[1] * p[a * 3 + 1] + plane[2] * p[a * 3 + 2])
            for (v in intArrayOf(a, b)) {
                var at = v * 10
                for (i in 0..3) for (j in i..3) q[at++] += plane[i] * plane[j]
            }
        }

        fun run(target: Int, belowTarget: Boolean): RerunMesh {
            val toRemove = (count - target).coerceAtLeast(1).toFloat()
            val baseErrorSq = maxErrorSq
            // Under the base cap, every collapse goes; once it has grown, the budget stops it.
            // A surface that starts under budget loses what its flat runs do not need; one over it
            // stops at the budget, its faces kept small enough for the photos to cover.
            val pastTarget = belowTarget && count <= target
            fun goal() = if (pastTarget && maxErrorSq == baseErrorSq) 0 else target
            fun report() = progress((1f - (count - target) / toRemove).coerceIn(0f, 1f))
            var sinceReport = 0
            repeat(64) {
                if (count <= goal()) return compact()
                report()
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
                if (order.size < edgeCount) order = LongArray(edgeCount)
                for (i in 0 until edgeCount) {
                    val a = (edges[i] ushr 32).toInt()
                    val b = edges[i].toInt()
                    val cost = cost(a, b).toFloat()
                    order[i] = (cost.toRawBits().toLong() shl 32) or i.toLong()
                }
                Arrays.sort(order, 0, edgeCount)
                val before = count
                var i = 0
                while (i < edgeCount && count > goal()) {
                    val edge = edges[order[i++].toInt()]
                    val a = (edge ushr 32).toInt()
                    val b = edge.toInt()
                    // The cost is recomputed: it also sets where on the edge the collapse lands.
                    val gone = !alive[a] || !alive[b]
                    if (gone) continue
                    val cost = cost(a, b)
                    if (!cost.isFinite() || cost > maxErrorSq || !legal(a, b)) continue
                    collapse(a, b)
                    if (++sinceReport == REPORT_EVERY) {
                        sinceReport = 0
                        report()
                    }
                }
                // Short of the budget and barely moving: what is left is locked, would fold, or
                // costs more than the cap — which doubles, up to its ceiling, before giving up.
                if ((before - count) * SWEEP_GAIN_DIVISOR < before) {
                    if (count <= target || maxErrorSq >= maxErrorCeilingSq) return compact()
                    maxErrorSq = minOf(maxErrorSq * CAP_STEP_SQ, maxErrorCeilingSq)
                }
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
            fraction = when {
                // An inner edge with one end on the border lands there: the outline stays put.
                border[a] && !border[b] -> 0.0
                border[b] && !border[a] -> 1.0
                curvature > 1e-15 -> (-slope / curvature).coerceIn(0.0, 1.0)
                else -> 0.5
            }
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
         * vertices shared by both one-rings — or, on a border edge, its one face and its one
         * opposite vertex. An inner edge between two border vertices would pinch the surface.
         * Without [collapseBorders], boundaries were locked before any collapse.
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
            return when (incident) {
                2 -> shared == 2 && !(border[a] && border[b])
                1 -> border[a] && border[b] && shared == 1
                else -> false
            }
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
            val tooLong = maxEdgeSq.isFinite() && (0..2).any { k ->
                val u = triangles[t + k]
                val w = triangles[t + (k + 1) % 3]
                val dx = (p[u * 3] - p[w * 3]).toDouble()
                val dy = (p[u * 3 + 1] - p[w * 3 + 1]).toDouble()
                val dz = (p[u * 3 + 2] - p[w * 3 + 2]).toDouble()
                dx * dx + dy * dy + dz * dz > maxEdgeSq
            }
            p[v * 3] = px
            p[v * 3 + 1] = py
            p[v * 3 + 2] = pz
            val area = dot(normal, normal)
            return tooLong || area <= 1e-24 || dot(normal, oldNormal) <= 0.1 * sqrt(area * dot(oldNormal, oldNormal))
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
            if (border[b]) border[a] = true
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
            head[v] = corner
        }

        /** Unlinks [corner] from its vertex's list: the head when a collapse moves it, else a few steps in. */
        fun detach(corner: Int) {
            val v = triangles[corner]
            if (head[v] == corner) {
                head[v] = next[corner]
                return
            }
            var before = head[v]
            while (next[before] != corner) before = next[before]
            next[before] = next[corner]
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
