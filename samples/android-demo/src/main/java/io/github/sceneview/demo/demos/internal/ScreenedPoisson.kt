package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The 3D Scan model's second reconstruction (#4630): screened Poisson surface reconstruction
 * (Kazhdan & Hoppe, "Screened Poisson Surface Reconstruction", ACM ToG 32(3), 2013) of the scan's
 * dense cloud — the surfels [DenseFusion] already averaged, position, normal and colour. Pure
 * Kotlin, written from the paper: no native code, no dependency. Its field goes into a fresh
 * [RerunTsdf], so [RerunMarchingCubes], the cleaning and the `.glb` stay those of the fused model.
 */

/** How the end of a scan turns what it saw into a surface. */
enum class ReconstructionMethod {
    /** The depth fused into a signed-distance volume ([RerunTsdf]): the scan's model so far. */
    Fused,

    /** [ScreenedPoisson] over the scan's dense cloud: a smoother surface that closes small holes. */
    Poisson,
}

/**
 * Screened Poisson reconstruction on a regular grid of nodes `(i, j, k) · h` — [RerunTsdf]'s own
 * voxel indexing — kept only in a band of tiles of [TILE]³ nodes within [REACH] nodes of a
 * sample: a room's surface, not its volume.
 *
 * The field `f` minimises `Σ_edges (f_j − f_i − g_ij)² + Σ_s α_s f(p_s)² + ε‖f‖²`: its steps along
 * the grid's edges follow the samples' normals splatted into a vector field (`g`, the gradient a
 * solid's indicator would have: a unit step across the surface, up toward the free space the
 * normals face), and it is screened to its iso-level at the samples themselves (`α`, the
 * paper's screening). Solved by conjugate gradients with a Jacobi preconditioner, from zero.
 * The surface is the level of `f` the samples sit at on average: positive in free space, like
 * the TSDF, so marching cubes meshes it unchanged.
 *
 * Poisson closes what the samples leave open. Nodes further than [TRIM] nodes from every sample
 * are left out of the field, and so are those the samples do not surround ([trimOpenEdges]):
 * holes up to about 7 cm across the radius close, larger ones stay open rather than bulge into
 * surfaces nothing saw, and an open edge stops at its last samples.
 *
 * Memory: [NODE_BYTES] a node and [SAMPLE_BYTES] a surfel while solving, within the budget
 * given; a band over it is solved again with nodes twice as far apart, up to [MAX_COARSENING]
 * times. Single-threaded, off the main thread.
 */
object ScreenedPoisson {
    /** Nodes a tile side: tiles are the band's unit, 64 nodes each. */
    const val TILE = 4

    /** Nodes the band reaches past a sample's cell, every way: about 10 cm at 2.5 cm. */
    const val REACH = 4

    /** Nodes past the nearest sample that keep their field, at most: 7.5 cm at 2.5 cm. */
    const val TRIM = 3

    /** The paper's α, in node units: how hard the field holds its level at the samples. */
    const val SCREENING = 4f

    /** Conjugate-gradient steps at most; the band is thin and screened, it converges well before. */
    const val MAX_ITERATIONS = 200

    /** Relative residual the solve stops at. */
    const val TOLERANCE = 1e-3

    /** The solve's own budget: a phone's 192 MB heap holds it beside the scan and the app. */
    const val MAX_BYTES = 40L * 1024 * 1024

    /** Times the grid may double its spacing to fit [MAX_BYTES]: 2.5 cm up to 10 cm. */
    const val MAX_COARSENING = 2

    /** f, r, p, A·p and 1/diag as floats, and the trim distance. */
    const val NODE_BYTES = 5 * 4 + 1

    /** A surfel's eight grid corners. */
    const val SAMPLE_BYTES = 8 * 4

    /** A tile's six neighbours, its coordinates, its key and its slots in the hash. */
    private const val TILE_BYTES = 6 * 4 + 3 * 4 + 8 + 2 * 12
    private const val TILE_NODES = TILE * TILE * TILE
    private const val LAST = TILE - 1
    private const val REGULARIZATION = 1e-5f
    private const val TANGENT_SWITCH = 0.9f
    private val BOOLEANS = booleanArrayOf(false, true)

    /** Nodes round a node its [Coverage] looks: holes up to about 0.7 of it across the radius close. */
    private const val COVERAGE_REACH = TRIM + 1

    /** The share of that disk the samples must cover: ½ is the edge of a seen surface. */
    private const val COVERED = 0.5f

    /** A node [trimOpenEdges] leaves out, until its pass is over. */
    private const val DROPPED: Byte = -1

    /** Half a square's diagonal over its side. */
    private const val HALF_DIAGONAL = 0.7072f
    private const val PI_F = kotlin.math.PI.toFloat()

    /** The field crosses one unit over about two nodes: back to metres, roughly a distance. */
    private const val METRES_PER_UNIT_NODES = 2f

    /** Shares of [reconstruct]'s progress: the band, the divergence, the solve, the output. */
    private const val BAND_SHARE = 0.1f
    private const val RHS_SHARE = 0.15f
    private const val SOLVE_SHARE = 0.85f

    /** What [reconstruct] made, and what it took. */
    class Result(
        /** The field, as a TSDF of weight-1 voxels of [voxelM]: mesh it with [RerunMarchingCubes]. */
        val tsdf: RerunTsdf,
        /** The grid spacing solved at: the voxel asked for, or coarser to fit the budget. */
        val voxelM: Float,
        val nodes: Int,
        val samples: Int,
        val iterations: Int,
        /** The solve's residual over the divergence's, at the end. */
        val residual: Double,
        /** The solve's arrays, in bytes, at their peak. */
        val solveBytes: Long,
    )

    /**
     * The surface of [cloud]'s oriented surfels, at nodes [voxelM] apart, within [maxBytes]
     * (coarser when it has to). `null` when the cloud has no normals, no surfels, or no grid
     * within [MAX_COARSENING] doublings fits. [progress] gets the share done now and then.
     */
    fun reconstruct(
        cloud: DenseCloud,
        voxelM: Float = RerunTsdf.VOXEL_M,
        maxBytes: Long = MAX_BYTES,
        screening: Float = SCREENING,
        progress: (Float) -> Unit = {},
    ): Result? {
        val normals = cloud.normals ?: return null
        val fit = fit(cloud, normals, voxelM, maxBytes) ?: return null
        progress(BAND_SHARE)
        // The solve's arrays go before the output's TSDF comes.
        val field = solve(cloud, normals, fit.band, screening, progress)
        val tsdf = output(cloud, normals, fit.band, field.f, field.iso)
        progress(1f)
        return Result(tsdf, fit.h, fit.band.nodes, cloud.count, field.iterations, field.residual, fit.bytes)
    }

    /**
     * The grid spacing [reconstruct] would solve [cloud] at within [maxBytes], known before
     * solving: [voxelM], or coarser to fit. `null` when it would give nothing — no normals, no
     * surfels, or no grid within [MAX_COARSENING] doublings fits.
     */
    fun spacing(cloud: DenseCloud, voxelM: Float = RerunTsdf.VOXEL_M, maxBytes: Long = MAX_BYTES): Float? {
        val normals = cloud.normals ?: return null
        return fit(cloud, normals, voxelM, maxBytes)?.h
    }

    /** A band that fits the budget, at spacing [h], and its solve's [bytes]. */
    private class Fit(val band: Band, val h: Float, val bytes: Long)

    /** The finest band of [cloud] from [voxelM] up, within [MAX_COARSENING] doublings, that fits [maxBytes]. */
    private fun fit(cloud: DenseCloud, normals: FloatArray, voxelM: Float, maxBytes: Long): Fit? {
        if (cloud.count == 0) return null
        var h = voxelM
        repeat(MAX_COARSENING + 1) {
            val band = Band.around(cloud, normals, h)
            val bytes = band.nodes.toLong() * NODE_BYTES + cloud.count.toLong() * SAMPLE_BYTES + band.tiles.toLong() * TILE_BYTES
            if (band.tiles > 0 && bytes <= maxBytes) return Fit(band, h, bytes)
            h *= 2f
        }
        return null
    }

    /** The solved field on [Band]'s nodes, and the level its samples sit at. */
    private class Field(val f: FloatArray, val iso: Float, val iterations: Int, val residual: Double)

    @Suppress("LongMethod")
    private fun solve(
        cloud: DenseCloud,
        normals: FloatArray,
        band: Band,
        screening: Float,
        progress: (Float) -> Unit,
    ): Field {
        val h = band.h
        val n = band.nodes
        val corners = band.corners(cloud)
        val weights = FloatArray(8)
        val r = FloatArray(n)
        // The divergence of the splatted normals, straight into the residual: each sample's
        // normal, spread trilinearly over its cell's corners, steps the edges on either side.
        forEachSample(cloud, normals, h) { x, y, z, nx, ny, nz, area, _ ->
            val gx = floor(x / h).toInt()
            val gy = floor(y / h).toInt()
            val gz = floor(z / h).toInt()
            trilinear(x / h - gx, y / h - gy, z / h - gz, weights)
            val scale = 0.5f * area / (h * h)
            for (c in 0 until 8) {
                val m = band.node(gx + (c and 1), gy + ((c shr 1) and 1), gz + (c shr 2))
                if (m < 0) continue
                val w = weights[c] * scale
                splatEdge(band, r, m, 0, nx * w)
                splatEdge(band, r, m, 1, ny * w)
                splatEdge(band, r, m, 2, nz * w)
            }
        }
        progress(RHS_SHARE)
        val alphas = FloatArray(cloud.count) { i -> screening * surfelArea(cloud, i) / (h * h) }
        val invDiag = FloatArray(n)
        for (m in 0 until n) invDiag[m] = REGULARIZATION + band.degree(m)
        for (s in 0 until cloud.count) {
            if (corners[s * 8] < 0) continue
            sampleWeights(cloud, s, h, weights)
            for (c in 0 until 8) invDiag[corners[s * 8 + c]] += alphas[s] * weights[c] * weights[c]
        }
        for (m in 0 until n) invDiag[m] = 1f / invDiag[m]

        val f = FloatArray(n)
        val p = FloatArray(n)
        val ap = FloatArray(n)
        var rz = 0.0
        var rr = 0.0
        for (m in 0 until n) {
            p[m] = r[m] * invDiag[m]
            rz += r[m].toDouble() * p[m]
            rr += r[m].toDouble() * r[m]
        }
        val start = rr
        val target = TOLERANCE * TOLERANCE * start
        var iterations = 0
        var residual = if (start > 0.0) 1.0 else 0.0
        while (iterations < MAX_ITERATIONS && rr > target && rr > 0.0) {
            apply(band, cloud, corners, alphas, p, ap, weights)
            var pap = 0.0
            for (m in 0 until n) pap += p[m].toDouble() * ap[m]
            if (pap <= 0.0) break
            val alpha = (rz / pap).toFloat()
            var rzNext = 0.0
            rr = 0.0
            for (m in 0 until n) {
                f[m] += alpha * p[m]
                r[m] -= alpha * ap[m]
                rzNext += r[m].toDouble() * r[m] * invDiag[m]
                rr += r[m].toDouble() * r[m]
            }
            val beta = (rzNext / rz).toFloat()
            rz = rzNext
            for (m in 0 until n) p[m] = r[m] * invDiag[m] + beta * p[m]
            iterations++
            residual = sqrt(rr / start)
            progress(RHS_SHARE + (SOLVE_SHARE - RHS_SHARE) * iterations / MAX_ITERATIONS)
        }

        // The level the samples sit at, weighted by the surface each stands for.
        var level = 0.0
        var areas = 0.0
        for (s in 0 until cloud.count) {
            if (corners[s * 8] < 0) continue
            sampleWeights(cloud, s, h, weights)
            var at = 0f
            for (c in 0 until 8) at += weights[c] * f[corners[s * 8 + c]]
            val area = surfelArea(cloud, s)
            level += at.toDouble() * area
            areas += area
        }
        progress(SOLVE_SHARE)
        return Field(f, if (areas > 0.0) (level / areas).toFloat() else 0f, iterations, residual)
    }

    /** `A·p` into [ap]: the grid's Laplacian, the screening at the samples, and ε. */
    @Suppress("LongParameterList")
    private fun apply(
        band: Band,
        cloud: DenseCloud,
        corners: IntArray,
        alphas: FloatArray,
        p: FloatArray,
        ap: FloatArray,
        weights: FloatArray,
    ) {
        val n = band.nodes
        for (m in 0 until n) ap[m] = REGULARIZATION * p[m]
        // Each edge once, from its lower node: +x, +y, +z.
        for (m in 0 until n) {
            val pm = p[m]
            for (axis in 0 until 3) {
                val o = band.neighbour(m, axis, up = true)
                if (o < 0) continue
                val d = pm - p[o]
                ap[m] += d
                ap[o] -= d
            }
        }
        val h = band.h
        for (s in 0 until cloud.count) {
            val base = s * 8
            if (corners[base] < 0) continue
            sampleWeights(cloud, s, h, weights)
            var at = 0f
            for (c in 0 until 8) at += weights[c] * p[corners[base + c]]
            val a = alphas[s] * at
            for (c in 0 until 8) ap[corners[base + c]] += a * weights[c]
        }
    }

    /**
     * The divergence of [value] on node [m]'s edges along [axis] into [r]: half the sample's
     * normal component steps each edge that leaves or reaches [m], when the band has it.
     */
    private fun splatEdge(band: Band, r: FloatArray, m: Int, axis: Int, value: Float) {
        if (value == 0f) return
        val below = band.neighbour(m, axis, up = false)
        if (below >= 0) {
            r[m] += value
            r[below] -= value
        }
        val above = band.neighbour(m, axis, up = true)
        if (above >= 0) {
            r[above] += value
            r[m] -= value
        }
    }

    /**
     * The field near the samples as a fresh [RerunTsdf] of weight-1 voxels, its distance in
     * metres (`(f − iso) · 2h`), and the samples' colours on their cells' corners. Nodes further
     * than [TRIM] from every sample are left out.
     */
    @Suppress("NestedBlockDepth")
    private fun output(cloud: DenseCloud, normals: FloatArray, band: Band, f: FloatArray, iso: Float): RerunTsdf {
        val h = band.h
        val distance = ByteArray(band.nodes) { (TRIM + 1).toByte() }
        forEachSample(cloud, normals, h) { x, y, z, _, _, _, _, _ ->
            val gx = floor(x / h).toInt()
            val gy = floor(y / h).toInt()
            val gz = floor(z / h).toInt()
            for (c in 0 until 8) {
                val m = band.node(gx + (c and 1), gy + ((c shr 1) and 1), gz + (c shr 2))
                if (m >= 0) distance[m] = 0
            }
        }
        for (d in 0 until TRIM) {
            for (m in 0 until band.nodes) {
                if (distance[m].toInt() != d) continue
                for (axis in 0 until 3) {
                    for (up in BOOLEANS) {
                        val o = band.neighbour(m, axis, up)
                        if (o >= 0 && distance[o] > d + 1) distance[o] = (d + 1).toByte()
                    }
                }
            }
        }
        trimOpenEdges(cloud, normals, band, f, iso, distance)
        val tsdf = RerunTsdf(voxelM = h)
        val scale = METRES_PER_UNIT_NODES * h
        val at = IntArray(3)
        for (m in 0 until band.nodes) {
            if (distance[m] > TRIM) continue
            band.coordinates(m, at)
            tsdf.update(at[0], at[1], at[2], (f[m] - iso) * scale, 1f)
        }
        forEachSample(cloud, normals, h) { x, y, z, _, _, _, _, colour ->
            if (colour == 0) return@forEachSample
            val gx = floor(x / h).toInt()
            val gy = floor(y / h).toInt()
            val gz = floor(z / h).toInt()
            for (c in 0 until 8) {
                val i = gx + (c and 1)
                val j = gy + ((c shr 1) and 1)
                val k = gz + (c shr 2)
                // Colour only: a zero weight leaves the voxel's distance as it is.
                if (tsdf.distanceAt(i, j, k).isFinite()) tsdf.update(i, j, k, 0f, 0f, colour)
            }
        }
        return tsdf
    }

    /**
     * Where the field crosses its level away from the samples (1 to [TRIM] nodes off), keeps
     * the node only where the samples surround it: their surface covers at least [COVERED] of
     * a disk [COVERAGE_REACH] nodes round it ([Coverage]). A hole they ring keeps its fill; past
     * the edge of what was seen — a doorway, the top of a wall seen from below — the field would
     * carry the plane on for [TRIM] nodes, and the surface stops at the last samples instead. As
     * PoissonRecon's trimming by sample density, on the nodes that would make triangles.
     */
    @Suppress("LongParameterList")
    private fun trimOpenEdges(cloud: DenseCloud, normals: FloatArray, band: Band, f: FloatArray, iso: Float, distance: ByteArray) {
        val coverage = Coverage(cloud, normals, band.h)
        val h = band.h
        val at = IntArray(3)
        for (m in 0 until band.nodes) {
            val d = distance[m]
            if (d < 1 || d > TRIM || !crosses(band, f, iso, distance, m)) continue
            band.coordinates(m, at)
            if (coverage.of(at[0] * h, at[1] * h, at[2] * h) < COVERED) distance[m] = DROPPED
        }
        for (m in 0 until band.nodes) {
            if (distance[m] == DROPPED) distance[m] = (TRIM + 1).toByte()
        }
    }

    /** Whether node [m]'s field and a kept neighbour's lie either side of [iso]: a triangle's corner. */
    private fun crosses(band: Band, f: FloatArray, iso: Float, distance: ByteArray, m: Int): Boolean {
        val above = f[m] > iso
        for (axis in 0 until 3) {
            for (up in BOOLEANS) {
                val o = band.neighbour(m, axis, up)
                // A node already dropped still counts: the outcome must not depend on the order.
                if (o >= 0 && distance[o] <= TRIM && (f[o] > iso) != above) return true
            }
        }
        return false
    }

    /**
     * How much of a disk round a point [cloud]'s samples cover: their area within `radius`
     * across the nearest sample's plane and within `radius` along its normal, over the disk's
     * (`radius` = [COVERAGE_REACH] nodes). About 1 inside a seen surface, ½ at its edge, less
     * past it, and `1 − (r / radius)²` at the middle of a hole of radius `r`. Surfels are
     * indexed by every cell of `radius` their square reaches.
     */
    private class Coverage(private val cloud: DenseCloud, private val normals: FloatArray, private val h: Float) {
        private val radius = COVERAGE_REACH * h
        private val heads = LongIntMap()
        private var surfels = IntArray(cloud.count.coerceAtLeast(1))
        private var next = IntArray(surfels.size)
        private var entries = 0
        private val stamp = IntArray(cloud.count)
        private var query = 0

        init {
            val positions = cloud.positions
            for (i in 0 until cloud.count) {
                val x = positions[i * 3]
                val y = positions[i * 3 + 1]
                val z = positions[i * 3 + 2]
                if (!(x.isFinite() && y.isFinite() && z.isFinite())) continue
                val reach = surfelSide(cloud, i) * HALF_DIAGONAL
                for (cz in cell(z - reach)..cell(z + reach)) {
                    for (cy in cell(y - reach)..cell(y + reach)) {
                        for (cx in cell(x - reach)..cell(x + reach)) add(RerunTsdf.blockKey(cx, cy, cz), i)
                    }
                }
            }
        }

        private fun cell(v: Float): Int = floor(v / radius).toInt()

        private fun add(key: Long, surfel: Int) {
            if (entries == surfels.size) {
                surfels = surfels.copyOf(entries * 2)
                next = next.copyOf(entries * 2)
            }
            surfels[entries] = surfel
            next[entries] = heads.get(key)
            heads.put(key, entries++)
        }

        /** The share of the disk round ([x], [y], [z]) the samples cover; 0 with none within reach. */
        fun of(x: Float, y: Float, z: Float): Float {
            val r2 = radius * radius
            var best = r2
            var nx = 0f
            var ny = 0f
            var nz = 0f
            forEachNear(x, y, z) { sx, sy, sz, snx, sny, snz, _ ->
                val dx = sx - x
                val dy = sy - y
                val dz = sz - z
                val d = dx * dx + dy * dy + dz * dz
                if (d < best) {
                    best = d
                    nx = snx
                    ny = sny
                    nz = snz
                }
            }
            if (nx == 0f && ny == 0f && nz == 0f) return 0f
            var area = 0f
            forEachNear(x, y, z) { sx, sy, sz, _, _, _, a ->
                val dx = sx - x
                val dy = sy - y
                val dz = sz - z
                val along = dx * nx + dy * ny + dz * nz
                if (abs(along) <= radius && dx * dx + dy * dy + dz * dz - along * along <= r2) area += a
            }
            return area / (PI_F * r2)
        }

        /** Every sample within a cell of ([x], [y], [z]), each surfel once. */
        private inline fun forEachNear(
            x: Float,
            y: Float,
            z: Float,
            visit: (x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, area: Float) -> Unit,
        ) {
            query++
            val cx = cell(x)
            val cy = cell(y)
            val cz = cell(z)
            for (kz in cz - 1..cz + 1) {
                for (ky in cy - 1..cy + 1) {
                    for (kx in cx - 1..cx + 1) {
                        var e = heads.get(RerunTsdf.blockKey(kx, ky, kz))
                        while (e >= 0) {
                            val s = surfels[e]
                            e = next[e]
                            if (stamp[s] == query) continue
                            stamp[s] = query
                            forEachSampleOf(cloud, normals, h, s) { sx, sy, sz, snx, sny, snz, a, _ -> visit(sx, sy, sz, snx, sny, snz, a) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Every sample of [cloud] at spacing [h] or finer: a surfel of level 0 as it is, a coarsened
     * one (level L, 2^L × 2^L level-0 surfels, #4629) as a grid of samples across its square,
     * each standing for its share of the area. Non-finite surfels are skipped.
     */
    @Suppress("LongParameterList")
    private inline fun forEachSample(
        cloud: DenseCloud,
        normals: FloatArray,
        h: Float,
        visit: (x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, area: Float, colour: Int) -> Unit,
    ) {
        for (i in 0 until cloud.count) forEachSampleOf(cloud, normals, h, i, visit)
    }

    /** Surfel [i]'s samples at spacing [h], as [forEachSample] gives them. */
    @Suppress("LongParameterList")
    private inline fun forEachSampleOf(
        cloud: DenseCloud,
        normals: FloatArray,
        h: Float,
        i: Int,
        visit: (x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, area: Float, colour: Int) -> Unit,
    ) {
        val positions = cloud.positions
        val px = positions[i * 3]
        val py = positions[i * 3 + 1]
        val pz = positions[i * 3 + 2]
        val nx = normals[i * 3]
        val ny = normals[i * 3 + 1]
        val nz = normals[i * 3 + 2]
        if (!(px.isFinite() && py.isFinite() && pz.isFinite() && nx.isFinite() && ny.isFinite() && nz.isFinite())) return
        val colour = cloud.colors[i]
        val side = surfelSide(cloud, i)
        val split = ceil(side / h).toInt().coerceAtLeast(1)
        if (split == 1) {
            visit(px, py, pz, nx, ny, nz, side * side, colour)
            return
        }
        // Two tangents of the surfel's plane, a sub-sample apart, as RerunTsdf.integrateSurfels.
        val ax = if (abs(ny) < TANGENT_SWITCH) 0f else 1f
        val ay = if (abs(ny) < TANGENT_SWITCH) 1f else 0f
        var tx = ay * nz
        var ty = -ax * nz
        var tz = ax * ny - ay * nx
        val tl = sqrt(tx * tx + ty * ty + tz * tz)
        if (tl < 1e-6f) return
        val step = side / split / tl
        tx *= step
        ty *= step
        tz *= step
        val bx = ny * tz - nz * ty
        val by = nz * tx - nx * tz
        val bz = nx * ty - ny * tx
        val centre = (split - 1) / 2f
        val area = side * side / (split * split)
        for (u in 0 until split) {
            for (v in 0 until split) {
                val a = u - centre
                val c = v - centre
                visit(px + tx * a + bx * c, py + ty * a + by * c, pz + tz * a + bz * c, nx, ny, nz, area, colour)
            }
        }
    }

    /** The side of surfel [i]'s square, in metres. */
    private fun surfelSide(cloud: DenseCloud, i: Int): Float =
        DenseFusion.VOXEL_M * (1 shl cloud.levelOf(i).coerceAtMost(DenseFusion.LEVEL_LIMIT))

    /** The surface surfel [i] stands for, in square metres. */
    private fun surfelArea(cloud: DenseCloud, i: Int): Float {
        val side = surfelSide(cloud, i)
        return side * side
    }

    /** Surfel [s]'s trilinear weights on its cell's corners, into [out]. */
    private fun sampleWeights(cloud: DenseCloud, s: Int, h: Float, out: FloatArray) {
        val x = cloud.positions[s * 3] / h
        val y = cloud.positions[s * 3 + 1] / h
        val z = cloud.positions[s * 3 + 2] / h
        trilinear(x - floor(x), y - floor(y), z - floor(z), out)
    }

    /** Corner `c` (bit 0: +x, bit 1: +y, bit 2: +z) of a cell, at fractions ([fx], [fy], [fz]) in it. */
    private fun trilinear(fx: Float, fy: Float, fz: Float, out: FloatArray) {
        for (c in 0 until 8) {
            val wx = if ((c and 1) == 0) 1f - fx else fx
            val wy = if (((c shr 1) and 1) == 0) 1f - fy else fy
            val wz = if ((c shr 2) == 0) 1f - fz else fz
            out[c] = wx * wy * wz
        }
    }

    /**
     * The tiles of [TILE]³ nodes within [REACH] nodes of a sample's cell, hashed by their
     * coordinates ([RerunTsdf.blockKey]) and numbered in the order they came: node `m` is
     * node `m % 64` of tile `m / 64`, x fastest. Each tile knows its six neighbours.
     */
    private class Band(val h: Float) {
        private val index = LongIntMap()
        private var coords = IntArray(INITIAL_TILES * 3)

        /** Per tile, its neighbour tile along -x, +x, -y, +y, -z, +z, or `-1`. */
        private var around = IntArray(0)
        var tiles = 0
            private set
        val nodes: Int get() = tiles * TILE_NODES

        private fun add(tx: Int, ty: Int, tz: Int) {
            val key = RerunTsdf.blockKey(tx, ty, tz)
            if (index.get(key) >= 0) return
            if (tiles * 3 == coords.size) coords = coords.copyOf(coords.size * 2)
            coords[tiles * 3] = tx
            coords[tiles * 3 + 1] = ty
            coords[tiles * 3 + 2] = tz
            index.put(key, tiles++)
        }

        private fun link() {
            around = IntArray(tiles * 6)
            for (t in 0 until tiles) {
                val tx = coords[t * 3]
                val ty = coords[t * 3 + 1]
                val tz = coords[t * 3 + 2]
                around[t * 6] = index.get(RerunTsdf.blockKey(tx - 1, ty, tz))
                around[t * 6 + 1] = index.get(RerunTsdf.blockKey(tx + 1, ty, tz))
                around[t * 6 + 2] = index.get(RerunTsdf.blockKey(tx, ty - 1, tz))
                around[t * 6 + 3] = index.get(RerunTsdf.blockKey(tx, ty + 1, tz))
                around[t * 6 + 4] = index.get(RerunTsdf.blockKey(tx, ty, tz - 1))
                around[t * 6 + 5] = index.get(RerunTsdf.blockKey(tx, ty, tz + 1))
            }
        }

        /** Node ([i], [j], [k])'s number, `-1` outside the band. */
        fun node(i: Int, j: Int, k: Int): Int {
            val t = index.get(RerunTsdf.blockKey(i shr 2, j shr 2, k shr 2))
            if (t < 0) return -1
            return (t shl 6) or ((k and LAST) shl 4) or ((j and LAST) shl 2) or (i and LAST)
        }

        /** Node [m]'s grid coordinates, into [out]. */
        fun coordinates(m: Int, out: IntArray) {
            val t = m ushr 6
            out[0] = coords[t * 3] * TILE + (m and LAST)
            out[1] = coords[t * 3 + 1] * TILE + ((m shr 2) and LAST)
            out[2] = coords[t * 3 + 2] * TILE + ((m shr 4) and LAST)
        }

        /** Node [m]'s neighbour one node along [axis] (0 x, 1 y, 2 z), above or below, `-1` outside the band. */
        fun neighbour(m: Int, axis: Int, up: Boolean): Int {
            val shift = axis * 2
            val local = (m shr shift) and LAST
            val stride = 1 shl shift
            if (up && local < LAST) return m + stride
            if (!up && local > 0) return m - stride
            val other = around[(m ushr 6) * 6 + axis * 2 + if (up) 1 else 0]
            if (other < 0) return -1
            // Same place in the next tile, this axis wrapped round.
            val wrapped = (m and (TILE_NODES - 1)) + if (up) -LAST * stride else LAST * stride
            return (other shl 6) or wrapped
        }

        /** Node [m]'s edges in the band. */
        fun degree(m: Int): Int {
            var edges = 0
            for (axis in 0 until 3) {
                if (neighbour(m, axis, up = true) >= 0) edges++
                if (neighbour(m, axis, up = false) >= 0) edges++
            }
            return edges
        }

        /** Each surfel's cell corners, eight a surfel; `-1` first for a surfel off the band. */
        fun corners(cloud: DenseCloud): IntArray {
            val out = IntArray(cloud.count * 8)
            for (s in 0 until cloud.count) {
                val x = cloud.positions[s * 3]
                val y = cloud.positions[s * 3 + 1]
                val z = cloud.positions[s * 3 + 2]
                if (!(x.isFinite() && y.isFinite() && z.isFinite())) {
                    out[s * 8] = -1
                    continue
                }
                val gx = floor(x / h).toInt()
                val gy = floor(y / h).toInt()
                val gz = floor(z / h).toInt()
                for (c in 0 until 8) {
                    val m = node(gx + (c and 1), gy + ((c shr 1) and 1), gz + (c shr 2))
                    if (m < 0) {
                        out[s * 8] = -1
                        break
                    }
                    out[s * 8 + c] = m
                }
            }
            return out
        }

        companion object {
            private const val INITIAL_TILES = 4096

            /** The band of [cloud]'s samples at spacing [h]. */
            fun around(cloud: DenseCloud, normals: FloatArray, h: Float): Band {
                val band = Band(h)
                var lastX = Int.MIN_VALUE
                var lastY = Int.MIN_VALUE
                var lastZ = Int.MIN_VALUE
                forEachSample(cloud, normals, h) { x, y, z, _, _, _, _, _ ->
                    val gx = floor(x / h).toInt()
                    val gy = floor(y / h).toInt()
                    val gz = floor(z / h).toInt()
                    if (gx == lastX && gy == lastY && gz == lastZ) return@forEachSample
                    lastX = gx
                    lastY = gy
                    lastZ = gz
                    for (tz in ((gz - REACH) shr 2)..((gz + 1 + REACH) shr 2)) {
                        for (ty in ((gy - REACH) shr 2)..((gy + 1 + REACH) shr 2)) {
                            for (tx in ((gx - REACH) shr 2)..((gx + 1 + REACH) shr 2)) band.add(tx, ty, tz)
                        }
                    }
                }
                band.link()
                return band
            }
        }
    }
}
