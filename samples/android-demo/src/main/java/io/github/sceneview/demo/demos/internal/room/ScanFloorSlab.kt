package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.RerunMesh
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The floor of a room scan that stands as no room — a short walk, a third of a room — as one
 * clean slab with an edge, in the place of the floor its depth fused.
 *
 * Fused from a short walk, a floor is lace: the patches the camera saw, holes where it looked
 * away, ragged where the depth thinned out. Shown as it is, the result reads as shreds of parquet
 * (13/11 walk, 26 s on a Pixel 9). Here the floor the depth found is laid on a grid
 * ([CELL_M]): its gaps up to [CLOSE_M] across are closed, every hole inside it filled, its
 * stray patches dropped, and it is drawn flat at the floor's height in the colours it was seen
 * in, its outline smoothed, with a [EDGE_M] edge down from it all round. Everything else is kept
 * as fused — furniture, walls, what the walk saw across the room — but the stray pieces the
 * floor's removal leaves, and, under a floor that is known, what lies below it.
 *
 * Pure Kotlin, no Filament: blocking CPU work, off the main thread.
 */
internal object ScanFloorSlab {
    /** The slab and what it replaced. */
    class Result(
        val mesh: RerunMesh,
        val floorY: Float,
        /** The slab's area, m²: the floor the walk saw, gaps closed and holes filled. */
        val areaM2: Float,
        /** The floor's own triangles it took the place of. */
        val floorTriangles: Int,
        /** Other triangles dropped: under the floor, or left as stray pieces. */
        val droppedTriangles: Int,
    )

    /**
     * [mesh] with its floor as a slab. [floorY] when known (the room's own, or ARCore's), else
     * read off the mesh ([floorOf]). With [cutBelow], what lies more than [BELOW_M] under the
     * floor goes too: only under a floor that is known, as a guessed one may be a bed's top.
     * `null` when the mesh shows no floor big enough to stand as one ([MIN_AREA_M2]): it is then
     * shown as fused.
     */
    @Suppress("ReturnCount")
    fun of(mesh: RerunMesh, floorY: Float? = null, cutBelow: Boolean = floorY != null): Result? {
        if (mesh.triangleCount == 0) return null
        val y0 = floorY ?: floorOf(mesh) ?: return null
        val p = mesh.positions
        val ix = mesh.indices
        val tris = mesh.triangleCount
        val onFloor = BooleanArray(tris) { t -> isFloor(p, ix, t, y0) }
        val floorCount = onFloor.count { it }
        if (floorCount == 0) return null

        // The floor's cells, and the colours seen in each.
        var minX = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (t in 0 until tris) {
            if (!onFloor[t]) continue
            for (k in 0 until 3) {
                val v = ix[t * 3 + k]
                minX = min(minX, p[v * 3])
                maxX = max(maxX, p[v * 3])
                minZ = min(minZ, p[v * 3 + 2])
                maxZ = max(maxZ, p[v * 3 + 2])
            }
        }
        val pad = CLOSE_CELLS + 2
        val grid = Grid(
            originX = minX - pad * CELL_M,
            originZ = minZ - pad * CELL_M,
            w = ((maxX - minX) / CELL_M).toInt() + 1 + 2 * pad,
            h = ((maxZ - minZ) / CELL_M).toInt() + 1 + 2 * pad,
        )
        if (grid.w.toLong() * grid.h > MAX_CELLS) return null
        val seen = BooleanArray(grid.size)
        val r = FloatArray(grid.size)
        val g = FloatArray(grid.size)
        val b = FloatArray(grid.size)
        val n = IntArray(grid.size)
        for (t in 0 until tris) {
            if (!onFloor[t]) continue
            var cx = 0f
            var cz = 0f
            for (k in 0 until 3) {
                val v = ix[t * 3 + k]
                cx += p[v * 3] / 3f
                cz += p[v * 3 + 2] / 3f
                val c = grid.cell(p[v * 3], p[v * 3 + 2])
                seen[c] = true
                val argb = mesh.colors[v]
                r[c] += (argb shr 16 and 0xFF).toFloat()
                g[c] += (argb shr 8 and 0xFF).toFloat()
                b[c] += (argb and 0xFF).toFloat()
                n[c]++
            }
            seen[grid.cell(cx, cz)] = true
        }

        // Gaps closed, holes filled, strays dropped, spurs trimmed.
        var kept = grid.erode(grid.dilate(seen, CLOSE_CELLS), CLOSE_CELLS)
        kept = grid.fillHoles(kept)
        kept = grid.dilate(grid.erode(kept, 1), 1)
        kept = grid.largestPieces(kept, MIN_PIECE_SHARE)
        val cells = kept.count { it }
        val area = cells * CELL_M * CELL_M
        if (area < MIN_AREA_M2) return null
        val colors = grid.spreadColors(kept, r, g, b, n)

        // What is kept of the fused mesh: all but the floor, and what lies under a known one.
        val keep = BooleanArray(tris) { t ->
            if (onFloor[t]) return@BooleanArray false
            if (!cutBelow) return@BooleanArray true
            val cy = (p[ix[t * 3] * 3 + 1] + p[ix[t * 3 + 1] * 3 + 1] + p[ix[t * 3 + 2] * 3 + 1]) / 3f
            cy >= y0 - BELOW_M
        }
        dropSmallPieces(mesh, keep, MIN_PIECE_TRIANGLES)
        val keptCount = keep.count { it }
        val slab = SlabBuilder(grid, kept, colors, y0).build()
        val merged = merge(mesh, keep, keptCount, slab)
        return Result(
            mesh = merged,
            floorY = y0,
            areaM2 = area,
            floorTriangles = floorCount,
            droppedTriangles = tris - floorCount - keptCount,
        )
    }

    /**
     * The floor's height read off [mesh]: the lowest level where a good share of the upward faces
     * lie ([FLOOR_SHARE] of the most at any level). `null` with too few upward faces.
     */
    fun floorOf(mesh: RerunMesh): Float? {
        val p = mesh.positions
        val nr = mesh.normals
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        var up = 0
        for (v in 0 until mesh.vertexCount) {
            if (nr[v * 3 + 1] < UP_MIN_NY) continue
            lo = min(lo, p[v * 3 + 1])
            hi = max(hi, p[v * 3 + 1])
            up++
        }
        if (up < MIN_UP_VERTICES) return null
        val bins = IntArray(((hi - lo) / BIN_M).toInt() + 1)
        for (v in 0 until mesh.vertexCount) {
            if (nr[v * 3 + 1] < UP_MIN_NY) continue
            bins[((p[v * 3 + 1] - lo) / BIN_M).toInt()]++
        }
        val most = bins.max()
        val k = bins.indexOfFirst { it >= most * FLOOR_SHARE }
        // The bin's weighted centre with its neighbours: a floor straddles two bins.
        var sum = 0f
        var weight = 0f
        for (j in max(0, k - 1)..min(bins.size - 1, k + 1)) {
            sum += (lo + (j + 0.5f) * BIN_M) * bins[j]
            weight += bins[j]
        }
        return sum / weight
    }

    private fun isFloor(p: FloatArray, ix: IntArray, t: Int, y0: Float): Boolean {
        val a = ix[t * 3]
        val b = ix[t * 3 + 1]
        val c = ix[t * 3 + 2]
        if (abs(p[a * 3 + 1] - y0) > FLOOR_BAND_M || abs(p[b * 3 + 1] - y0) > FLOOR_BAND_M || abs(p[c * 3 + 1] - y0) > FLOOR_BAND_M) {
            return false
        }
        val ux = p[b * 3] - p[a * 3]
        val uy = p[b * 3 + 1] - p[a * 3 + 1]
        val uz = p[b * 3 + 2] - p[a * 3 + 2]
        val vx = p[c * 3] - p[a * 3]
        val vy = p[c * 3 + 1] - p[a * 3 + 1]
        val vz = p[c * 3 + 2] - p[a * 3 + 2]
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val l = sqrt(nx * nx + ny * ny + nz * nz)
        // Either side up: a floor seen from below the band is still the floor.
        return l > 0f && abs(ny) / l >= FLOOR_MIN_NY
    }

    /** Clears from [keep] the pieces (sharing vertices) of fewer than [minTriangles] kept triangles. */
    private fun dropSmallPieces(mesh: RerunMesh, keep: BooleanArray, minTriangles: Int) {
        val parent = IntArray(mesh.vertexCount) { it }
        fun root(v: Int): Int {
            var x = v
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val ix = mesh.indices
        for (t in keep.indices) {
            if (!keep[t]) continue
            val a = root(ix[t * 3])
            val b = root(ix[t * 3 + 1])
            val c = root(ix[t * 3 + 2])
            parent[b] = a
            parent[root(c)] = a
        }
        val size = HashMap<Int, Int>()
        for (t in keep.indices) if (keep[t]) size.merge(root(ix[t * 3]), 1, Int::plus)
        for (t in keep.indices) if (keep[t] && (size[root(ix[t * 3])] ?: 0) < minTriangles) keep[t] = false
    }

    /** [mesh]'s [keep] triangles, re-indexed onto the vertices they use, then [slab]. */
    private fun merge(mesh: RerunMesh, keep: BooleanArray, kept: Int, slab: RerunMesh): RerunMesh {
        val map = IntArray(mesh.vertexCount) { -1 }
        var used = 0
        for (t in keep.indices) {
            if (!keep[t]) continue
            for (k in 0 until 3) {
                val v = mesh.indices[t * 3 + k]
                if (map[v] < 0) map[v] = used++
            }
        }
        val vertices = used + slab.vertexCount
        val positions = FloatArray(vertices * 3)
        val normals = FloatArray(vertices * 3)
        val colors = IntArray(vertices)
        for (v in 0 until mesh.vertexCount) {
            val to = map[v]
            if (to < 0) continue
            System.arraycopy(mesh.positions, v * 3, positions, to * 3, 3)
            System.arraycopy(mesh.normals, v * 3, normals, to * 3, 3)
            colors[to] = mesh.colors[v]
        }
        System.arraycopy(slab.positions, 0, positions, used * 3, slab.positions.size)
        System.arraycopy(slab.normals, 0, normals, used * 3, slab.normals.size)
        System.arraycopy(slab.colors, 0, colors, used, slab.colors.size)
        val indices = IntArray(kept * 3 + slab.indices.size)
        var i = 0
        for (t in keep.indices) {
            if (!keep[t]) continue
            for (k in 0 until 3) indices[i++] = map[mesh.indices[t * 3 + k]]
        }
        for (s in slab.indices) indices[i++] = s + used
        return RerunMesh(positions, normals, colors, indices)
    }

    /** A grid of [CELL_M] cells over the floor, `x` along columns and `z` along rows. */
    private class Grid(val originX: Float, val originZ: Float, val w: Int, val h: Int) {
        val size = w * h

        fun cell(x: Float, z: Float): Int = cellOrNull(x, z) ?: error("off the grid")

        fun cellOrNull(x: Float, z: Float): Int? {
            val i = floor((x - originX) / CELL_M).toInt()
            val j = floor((z - originZ) / CELL_M).toInt()
            return if (i in 0 until w && j in 0 until h) j * w + i else null
        }

        fun dilate(on: BooleanArray, cells: Int): BooleanArray = morph(on, cells, grow = true)

        fun erode(on: BooleanArray, cells: Int): BooleanArray = morph(on, cells, grow = false)

        /** Round enough: steps alternate the four and the eight neighbours, an octagon. */
        private fun morph(on: BooleanArray, cells: Int, grow: Boolean): BooleanArray {
            var cur = on
            repeat(cells) { step ->
                val diagonal = step % 2 == 1
                val next = BooleanArray(size)
                for (j in 0 until h) {
                    for (i in 0 until w) {
                        val c = j * w + i
                        var any = cur[c]
                        var all = cur[c]
                        for (dj in -1..1) {
                            for (di in -1..1) {
                                if (di == 0 && dj == 0) continue
                                if (!diagonal && di != 0 && dj != 0) continue
                                val ii = i + di
                                val jj = j + dj
                                val v = ii in 0 until w && jj in 0 until h && cur[jj * w + ii]
                                any = any || v
                                all = all && v
                            }
                        }
                        next[c] = if (grow) any else all
                    }
                }
                cur = next
            }
            return cur
        }

        /** Every empty cell that cannot reach the grid's edge through empty cells is filled. */
        fun fillHoles(on: BooleanArray): BooleanArray {
            val outside = BooleanArray(size)
            val queue = ArrayDeque<Int>()
            for (i in 0 until w) {
                queue += i
                queue += (h - 1) * w + i
            }
            for (j in 0 until h) {
                queue += j * w
                queue += j * w + w - 1
            }
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                if (on[c] || outside[c]) continue
                outside[c] = true
                val i = c % w
                val j = c / w
                if (i > 0) queue += c - 1
                if (i < w - 1) queue += c + 1
                if (j > 0) queue += c - w
                if (j < h - 1) queue += c + w
            }
            return BooleanArray(size) { !outside[it] }
        }

        /** The 8-connected pieces of [on] of at least [share] of the largest. */
        fun largestPieces(on: BooleanArray, share: Float): BooleanArray {
            val label = IntArray(size) { -1 }
            val sizes = ArrayList<Int>()
            val queue = ArrayDeque<Int>()
            for (start in 0 until size) {
                if (!on[start] || label[start] >= 0) continue
                val id = sizes.size
                var count = 0
                label[start] = id
                queue += start
                while (queue.isNotEmpty()) {
                    val c = queue.removeFirst()
                    count++
                    val i = c % w
                    val j = c / w
                    for (dj in -1..1) {
                        for (di in -1..1) {
                            val ii = i + di
                            val jj = j + dj
                            if (ii !in 0 until w || jj !in 0 until h) continue
                            val d = jj * w + ii
                            if (on[d] && label[d] < 0) {
                                label[d] = id
                                queue += d
                            }
                        }
                    }
                }
                sizes += count
            }
            val most = sizes.maxOrNull() ?: return BooleanArray(size)
            return BooleanArray(size) { label[it] >= 0 && sizes[label[it]] >= most * share }
        }

        /**
         * A colour for every [kept] cell: the mean of what was seen in it, a cell seen in nothing
         * taking its seen neighbours' as they reach it. The corners average the cells round them.
         */
        fun spreadColors(kept: BooleanArray, r: FloatArray, g: FloatArray, b: FloatArray, n: IntArray): IntArray {
            val rr = FloatArray(size)
            val gg = FloatArray(size)
            val bb = FloatArray(size)
            val has = BooleanArray(size)
            val queue = ArrayDeque<Int>()
            for (c in 0 until size) {
                if (!kept[c] || n[c] == 0) continue
                rr[c] = r[c] / n[c]
                gg[c] = g[c] / n[c]
                bb[c] = b[c] / n[c]
                has[c] = true
                queue += c
            }
            if (queue.isEmpty()) {
                for (c in 0 until size) {
                    rr[c] = NEUTRAL
                    gg[c] = NEUTRAL
                    bb[c] = NEUTRAL
                }
            }
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                val i = c % w
                val j = c / w
                for ((di, dj) in FOUR) {
                    val ii = i + di
                    val jj = j + dj
                    if (ii !in 0 until w || jj !in 0 until h) continue
                    val d = jj * w + ii
                    if (!kept[d] || has[d]) continue
                    rr[d] = rr[c]
                    gg[d] = gg[c]
                    bb[d] = bb[c]
                    has[d] = true
                    queue += d
                }
            }
            return IntArray(size) { c -> if (kept[c]) argb(rr[c], gg[c], bb[c]) else 0 }
        }
    }

    /** The slab's top, flat at the floor, and its edge, [EDGE_M] down all round. */
    private class SlabBuilder(val grid: Grid, val kept: BooleanArray, val cellColors: IntArray, val y0: Float) {
        private val w = grid.w
        private val h = grid.h
        private val cornerW = w + 1
        private val corner = IntArray(cornerW * (h + 1)) { -1 }
        private val positions = ArrayList<Float>()
        private val normals = ArrayList<Float>()
        private val colors = ArrayList<Int>()
        private val indices = ArrayList<Int>()

        private fun on(i: Int, j: Int) = i in 0 until w && j in 0 until h && kept[j * w + i]

        private fun cornerOf(i: Int, j: Int): Int {
            val k = j * cornerW + i
            if (corner[k] < 0) {
                corner[k] = colors.size
                positions += grid.originX + i * CELL_M
                positions += y0
                positions += grid.originZ + j * CELL_M
                normals += 0f
                normals += 1f
                normals += 0f
                colors += cornerColor(i, j)
            }
            return corner[k]
        }

        /** The mean of the kept cells round corner (i, j). */
        private fun cornerColor(i: Int, j: Int): Int {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var count = 0
            for (dj in -1..0) {
                for (di in -1..0) {
                    if (!on(i + di, j + dj)) continue
                    val c = cellColors[(j + dj) * w + i + di]
                    sr += (c shr 16 and 0xFF)
                    sg += (c shr 8 and 0xFF)
                    sb += (c and 0xFF)
                    count++
                }
            }
            return argb(sr / count, sg / count, sb / count)
        }

        fun build(): RerunMesh {
            for (j in 0 until h) {
                for (i in 0 until w) {
                    if (!kept[j * w + i]) continue
                    val a = cornerOf(i, j)
                    val b = cornerOf(i, j + 1)
                    val c = cornerOf(i + 1, j)
                    val d = cornerOf(i + 1, j + 1)
                    // Counter-clockwise seen from above: the top faces +Y.
                    indices += listOf(a, b, c, c, b, d)
                }
            }
            val outline = outlineEdges()
            smoothOutline(outline)
            edge(outline)
            return RerunMesh(positions.toFloatArray(), normals.toFloatArray(), colors.toIntArray(), indices.toIntArray())
        }

        /**
         * The outline: every cell side between a kept cell and an empty one, as two corner indices
         * and the outward direction (from the kept cell to the empty one).
         */
        private fun outlineEdges(): List<IntArray> = buildList {
            for (j in 0 until h) {
                for (i in 0 until w) {
                    if (!kept[j * w + i]) continue
                    if (!on(i - 1, j)) add(intArrayOf(cornerOf(i, j), cornerOf(i, j + 1), -1, 0))
                    if (!on(i + 1, j)) add(intArrayOf(cornerOf(i + 1, j), cornerOf(i + 1, j + 1), 1, 0))
                    if (!on(i, j - 1)) add(intArrayOf(cornerOf(i, j), cornerOf(i + 1, j), 0, -1))
                    if (!on(i, j + 1)) add(intArrayOf(cornerOf(i, j + 1), cornerOf(i + 1, j + 1), 0, 1))
                }
            }
        }

        /** The grid's steps rounded off the outline: its corners drawn towards their outline neighbours. */
        private fun smoothOutline(outline: List<IntArray>) {
            val next = HashMap<Int, MutableList<Int>>()
            for (e in outline) {
                next.getOrPut(e[0]) { ArrayList(2) } += e[1]
                next.getOrPut(e[1]) { ArrayList(2) } += e[0]
            }
            // A pinch (four outline sides at a corner) stays where it is.
            val movable = next.filterValues { it.size == 2 }
            val home = movable.keys.associateWith { positions[it * 3] to positions[it * 3 + 2] }
            repeat(SMOOTH_PASSES) {
                val moved = HashMap<Int, Pair<Float, Float>>(movable.size * 2)
                for ((v, ns) in movable) {
                    val mx = (positions[ns[0] * 3] + positions[ns[1] * 3]) / 2f
                    val mz = (positions[ns[0] * 3 + 2] + positions[ns[1] * 3 + 2]) / 2f
                    val x = positions[v * 3]
                    val z = positions[v * 3 + 2]
                    // Never more than a third of a cell from where it was: the quads it shares
                    // with the inside keep their side, none folds over.
                    val (hx, hz) = home.getValue(v)
                    moved[v] = (x + (mx - x) * SMOOTH_STEP).coerceIn(hx - MAX_SHIFT_M, hx + MAX_SHIFT_M) to
                        (z + (mz - z) * SMOOTH_STEP).coerceIn(hz - MAX_SHIFT_M, hz + MAX_SHIFT_M)
                }
                for ((v, xz) in moved) {
                    positions[v * 3] = xz.first
                    positions[v * 3 + 2] = xz.second
                }
            }
        }

        /** A strip down from every outline side, facing out, a shade darker than the floor beside it. */
        private fun edge(outline: List<IntArray>) {
            for (e in outline) {
                val a = e[0]
                val b = e[1]
                val ax = positions[a * 3]
                val az = positions[a * 3 + 2]
                val bx = positions[b * 3]
                val bz = positions[b * 3 + 2]
                // Outward normal of the smoothed side, on the outward side of the cell.
                var nx = bz - az
                var nz = -(bx - ax)
                if (nx * e[2] + nz * e[3] < 0f) {
                    nx = -nx
                    nz = -nz
                }
                val l = sqrt(nx * nx + nz * nz).takeIf { it > 0f } ?: continue
                nx /= l
                nz /= l
                val shade = darker(colors[a])
                val base = colors.size
                for ((x, z, y) in listOf(Triple(ax, az, y0), Triple(bx, bz, y0), Triple(ax, az, y0 - EDGE_M), Triple(bx, bz, y0 - EDGE_M))) {
                    positions += x
                    positions += y
                    positions += z
                    normals += nx
                    normals += 0f
                    normals += nz
                    colors += shade
                }
                // (a's foot − a) × (b − a) points along (−uz, 0, ux): wound so the strip faces out.
                val faces = -(bz - az) * nx + (bx - ax) * nz
                if (faces >= 0f) {
                    indices += listOf(base, base + 2, base + 1, base + 1, base + 2, base + 3)
                } else {
                    indices += listOf(base, base + 1, base + 2, base + 1, base + 3, base + 2)
                }
            }
        }
    }

    private fun argb(r: Float, g: Float, b: Float): Int =
        (0xFF shl 24) or (r.roundToInt().coerceIn(0, 255) shl 16) or (g.roundToInt().coerceIn(0, 255) shl 8) or
            b.roundToInt().coerceIn(0, 255)

    private fun darker(c: Int): Int =
        argb((c shr 16 and 0xFF) * EDGE_SHADE, (c shr 8 and 0xFF) * EDGE_SHADE, (c and 0xFF) * EDGE_SHADE)

    private val FOUR = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    /** 4 cm: twice the fusion's voxel, so a cell always holds a fused vertex where the floor was seen. */
    const val CELL_M = 0.04f

    /** Gaps up to 40 cm across in the floor seen are closed: lace, the strip between two looks. */
    const val CLOSE_M = 0.4f
    private val CLOSE_CELLS = (CLOSE_M / 2f / CELL_M).roundToInt()

    /** The slab's edge, down from the floor: thick enough to read as an edge from the opening view. */
    const val EDGE_M = 0.03f
    private const val EDGE_SHADE = 0.62f

    /** A triangle within 4 cm of the floor, facing up or down, is the floor's. */
    const val FLOOR_BAND_M = 0.04f
    private const val FLOOR_MIN_NY = 0.8f

    /** Under the floor by more than this is noise (a reflection, a stair's depth through glass). */
    const val BELOW_M = 0.08f

    /** Below this, the floor seen is not one to lay a slab over: half a square metre. */
    const val MIN_AREA_M2 = 0.5f

    /** Floor pieces under a fifth of the largest are strays. */
    private const val MIN_PIECE_SHARE = 0.2f

    /** Fused pieces left under this, once the floor is out, are strays too: about 5 × 5 cm² of surface. */
    const val MIN_PIECE_TRIANGLES = 60

    private const val UP_MIN_NY = 0.9f
    private const val MIN_UP_VERTICES = 200
    private const val BIN_M = 0.02f
    private const val FLOOR_SHARE = 0.3f
    private const val SMOOTH_PASSES = 4
    private const val SMOOTH_STEP = 0.5f
    private const val MAX_SHIFT_M = CELL_M / 3f
    private const val NEUTRAL = 160f

    /** 40 × 40 m at 4 cm: past this a walk is no room's. */
    private const val MAX_CELLS = 1_000_000L
}
