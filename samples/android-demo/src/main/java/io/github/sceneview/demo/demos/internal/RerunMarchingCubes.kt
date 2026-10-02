package io.github.sceneview.demo.demos.internal

import kotlin.math.sqrt

/*
 * The Rerun demo's final model, step two: the room's surface pulled out of the TSDF
 * ([RerunTsdf]) by marching cubes, as one indexed, coloured triangle mesh ([RerunMesh]) that
 * [RerunMeshGlb] writes as a glTF binary. Pure Kotlin, off the main thread.
 */

/**
 * An indexed triangle mesh: [positions] and [normals] flat xyz (metres, unit), [colors]
 * `0xFFRRGGBB` (sRGB) one per vertex, [indices] three per triangle, counter-clockwise seen from
 * the free space the camera moved through.
 */
class RerunMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val colors: IntArray,
    val indices: IntArray,
) {
    val vertexCount: Int get() = colors.size
    val triangleCount: Int get() = indices.size / 3

    init {
        require(positions.size == colors.size * 3 && normals.size == positions.size) {
            "one position, normal, colour a vertex"
        }
        require(indices.size % 3 == 0) { "three indices a triangle" }
    }

    /** `[minX, minY, minZ, maxX, maxY, maxZ]`, all zero for an empty mesh. */
    fun bounds(): FloatArray {
        if (vertexCount == 0) return FloatArray(6)
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE,
        )
        for (i in 0 until vertexCount) {
            for (a in 0 until 3) {
                val v = positions[i * 3 + a]
                if (v < b[a]) b[a] = v
                if (v > b[a + 3]) b[a + 3] = v
            }
        }
        return b
    }

    companion object {
        val Empty = RerunMesh(FloatArray(0), FloatArray(0), IntArray(0), IntArray(0))
    }
}

/** What [RerunMarchingCubes.extract] built, and what it threw away. */
data class MeshExtraction(
    val mesh: RerunMesh,
    /** Triangles marching cubes emitted, before the small pieces went. */
    val rawTriangles: Int,
    /** Connected pieces dropped for having fewer than the minimum of triangles. */
    val droppedComponents: Int,
    /** `true` when the triangle cap cut the extraction short. */
    val capped: Boolean,
)

/**
 * Marching cubes over a sparse TSDF: every cell whose eight corners were observed (weight ≥
 * `minWeight`) and straddle the zero level emits the triangles of the classic table
 * (Lorensen & Cline, Bourke/Bloyd's table). A vertex sits on a cell edge where the signed distance
 * crosses zero, shared by every cell around that edge; its normal is the field's gradient there
 * (pointing to the free space), its colour the voxels' averaged camera colour.
 */
object RerunMarchingCubes {
    /** Pieces smaller than this are sensor noise, floaters, not the room. */
    const val MIN_COMPONENT_TRIANGLES = 500

    /** 1.5 M triangles: ~54 MB of vertices and indices, the most a phone renders at ease. */
    const val MAX_TRIANGLES = 1_500_000

    /** A voxel no colour reached. */
    const val NO_COLOR = 0xFF9E9E9E.toInt()

    /**
     * The surface of [tsdf]. Cells with a corner of weight under [minWeight] are skipped (never
     * observed or observed too little). Connected pieces of fewer than [minComponentTriangles]
     * triangles are dropped; extraction stops at [maxTriangles]. [progress] gets the share done.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
    fun extract(
        tsdf: RerunTsdf,
        minWeight: Float = DEFAULT_MIN_WEIGHT,
        minComponentTriangles: Int = MIN_COMPONENT_TRIANGLES,
        maxTriangles: Int = MAX_TRIANGLES,
        progress: (Float) -> Unit = {},
    ): MeshExtraction {
        val out = Builder()
        val cache = BlockCache(tsdf)
        val coord = IntArray(3)
        val value = FloatArray(8)
        val cornerX = IntArray(8)
        val cornerY = IntArray(8)
        val cornerZ = IntArray(8)
        val edgeVertex = IntArray(12)
        var capped = false
        val blocks = tsdf.blockCount
        blocks@ for (block in 0 until blocks) {
            if (block % PROGRESS_BLOCKS == 0) progress(PROGRESS_MC * block / blocks.coerceAtLeast(1))
            tsdf.blockCoord(block, coord)
            cache.load(coord[0], coord[1], coord[2])
            for (lz in 0 until RerunTsdf.BLOCK) {
                for (ly in 0 until RerunTsdf.BLOCK) {
                    cells@ for (lx in 0 until RerunTsdf.BLOCK) {
                        var cube = 0
                        for (c in 0 until 8) {
                            val x = lx + CORNER_X[c]
                            val y = ly + CORNER_Y[c]
                            val z = lz + CORNER_Z[c]
                            if (cache.weight(x, y, z) < minWeight) continue@cells
                            val v = cache.sdf(x, y, z)
                            value[c] = v
                            cornerX[c] = x
                            cornerY[c] = y
                            cornerZ[c] = z
                            if (v < 0f) cube = cube or (1 shl c)
                        }
                        val row = TRIANGLES[cube]
                        if (row.isEmpty()) continue
                        if (out.triangles + row.size / 3 > maxTriangles) {
                            capped = true
                            break@blocks
                        }
                        val edges = EDGES[cube]
                        for (e in 0 until 12) {
                            if (edges and (1 shl e) == 0) continue
                            val a = EDGE_A[e]
                            val b = EDGE_B[e]
                            edgeVertex[e] = out.vertex(
                                tsdf, cache, coord,
                                cornerX[a], cornerY[a], cornerZ[a], value[a],
                                cornerX[b], cornerY[b], cornerZ[b], value[b],
                            )
                        }
                        var t = 0
                        while (t < row.size) {
                            // The table winds its triangles facing the inside (negative) side:
                            // swapped, they face the free space the camera saw them from.
                            out.triangle(edgeVertex[row[t]], edgeVertex[row[t + 2]], edgeVertex[row[t + 1]])
                            t += 3
                        }
                    }
                }
            }
        }
        progress(PROGRESS_MC)
        val raw = out.triangles
        val (mesh, dropped) = out.build(minComponentTriangles)
        progress(1f)
        return MeshExtraction(mesh, raw, dropped, capped)
    }

    /** The field around one block: its 8³ voxels and a one-voxel rim of its neighbours'. */
    private class BlockCache(private val tsdf: RerunTsdf) {
        private val sdf = FloatArray(SIDE * SIDE * SIDE)
        private val weight = FloatArray(SIDE * SIDE * SIDE)
        private val color = IntArray(SIDE * SIDE * SIDE)
        private val neighbours = IntArray(27)

        fun load(bx: Int, by: Int, bz: Int) {
            for (n in 0 until 27) {
                neighbours[n] = tsdf.blockAt(bx + n % 3 - 1, by + (n / 3) % 3 - 1, bz + n / 9 - 1)
            }
            for (z in -1 until SIDE - 1) {
                val nz = if (z < 0) 0 else if (z >= RerunTsdf.BLOCK) 2 else 1
                val vz = (z + RerunTsdf.BLOCK) and 7
                for (y in -1 until SIDE - 1) {
                    val ny = if (y < 0) 0 else if (y >= RerunTsdf.BLOCK) 2 else 1
                    val vy = (y + RerunTsdf.BLOCK) and 7
                    for (x in -1 until SIDE - 1) {
                        val nx = if (x < 0) 0 else if (x >= RerunTsdf.BLOCK) 2 else 1
                        val vx = (x + RerunTsdf.BLOCK) and 7
                        val at = index(x, y, z)
                        val block = neighbours[nz * 9 + ny * 3 + nx]
                        if (block < 0) {
                            weight[at] = 0f
                            sdf[at] = 0f
                            color[at] = 0
                            continue
                        }
                        val v = (vz shl 6) or (vy shl 3) or vx
                        weight[at] = tsdf.weight[block][v]
                        sdf[at] = tsdf.sdf[block][v]
                        color[at] = if (tsdf.colorWeight[block][v].toInt() == 0) {
                            0
                        } else {
                            val rgb = tsdf.rgb[block]
                            (0xFF shl 24) or
                                ((rgb[v * 3].toInt() and 0xFF) shl 16) or
                                ((rgb[v * 3 + 1].toInt() and 0xFF) shl 8) or
                                (rgb[v * 3 + 2].toInt() and 0xFF)
                        }
                    }
                }
            }
        }

        fun sdf(x: Int, y: Int, z: Int) = sdf[index(x, y, z)]
        fun weight(x: Int, y: Int, z: Int) = weight[index(x, y, z)]
        fun color(x: Int, y: Int, z: Int) = color[index(x, y, z)]

        /** The field's gradient at voxel ([x], [y], [z]) into [out], one-sided where a neighbour is unobserved. */
        fun gradient(x: Int, y: Int, z: Int, out: FloatArray, offset: Int) {
            out[offset] = difference(x, y, z, 1, 0, 0)
            out[offset + 1] = difference(x, y, z, 0, 1, 0)
            out[offset + 2] = difference(x, y, z, 0, 0, 1)
        }

        private fun difference(x: Int, y: Int, z: Int, dx: Int, dy: Int, dz: Int): Float {
            val centre = sdf(x, y, z)
            val inside = x + dx < SIDE - 1 && y + dy < SIDE - 1 && z + dz < SIDE - 1
            val hasPlus = inside && weight(x + dx, y + dy, z + dz) > 0f
            val hasMinus = x - dx >= -1 && y - dy >= -1 && z - dz >= -1 && weight(x - dx, y - dy, z - dz) > 0f
            val plus = if (hasPlus) sdf(x + dx, y + dy, z + dz) else centre
            val minus = if (hasMinus) sdf(x - dx, y - dy, z - dz) else centre
            val span = (if (hasPlus) 1 else 0) + (if (hasMinus) 1 else 0)
            return if (span == 0) 0f else (plus - minus) / span
        }

        private fun index(x: Int, y: Int, z: Int) = ((z + 1) * SIDE + (y + 1)) * SIDE + (x + 1)

        companion object {
            /** −1 … 9 an axis: a block's cells, their far corners, and the gradient's reach. */
            const val SIDE = RerunTsdf.BLOCK + 3
        }
    }

    /** Vertices shared by edge, triangles, and the pass that drops the small pieces. */
    private class Builder {
        private val edgeIndex = LongIntMap(1 shl 16)
        private var positions = FloatArray(INITIAL * 3)
        private var normals = FloatArray(INITIAL * 3)
        private var colors = IntArray(INITIAL)
        private var indices = IntArray(INITIAL * 3)
        private val gradA = FloatArray(3)
        private val gradB = FloatArray(3)
        var vertices = 0
            private set
        var triangles = 0
            private set

        @Suppress("LongParameterList")
        fun vertex(
            tsdf: RerunTsdf,
            cache: BlockCache,
            block: IntArray,
            ax: Int, ay: Int, az: Int, va: Float,
            bx: Int, by: Int, bz: Int, vb: Float,
        ): Int {
            // One key a grid edge: its lower corner and its axis, whatever the cell asking.
            val swap = ax > bx || ay > by || az > bz
            val lx = block[0] * RerunTsdf.BLOCK + if (swap) bx else ax
            val ly = block[1] * RerunTsdf.BLOCK + if (swap) by else ay
            val lz = block[2] * RerunTsdf.BLOCK + if (swap) bz else az
            val axis = if (ax != bx) 0 else if (ay != by) 1 else 2
            val key = ((lx + OFFSET20).toLong() and MASK20 shl 42) or
                ((ly + OFFSET20).toLong() and MASK20 shl 22) or
                ((lz + OFFSET20).toLong() and MASK20 shl 2) or axis.toLong()
            val known = edgeIndex.get(key)
            if (known >= 0) return known
            // Interpolated from the lower corner, so both cells around an edge agree.
            val (lowV, highV) = if (swap) vb to va else va to vb
            val t = (lowV / (lowV - highV)).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: HALF
            val (fx, fy, fz) = if (swap) Triple(bx, by, bz) else Triple(ax, ay, az)
            val (gx, gy, gz) = if (swap) Triple(ax, ay, az) else Triple(bx, by, bz)
            grow()
            val v = vertices++
            val voxel = tsdf.voxelM
            positions[v * 3] = (block[0] * RerunTsdf.BLOCK + fx + t * (gx - fx)) * voxel
            positions[v * 3 + 1] = (block[1] * RerunTsdf.BLOCK + fy + t * (gy - fy)) * voxel
            positions[v * 3 + 2] = (block[2] * RerunTsdf.BLOCK + fz + t * (gz - fz)) * voxel
            cache.gradient(fx, fy, fz, gradA, 0)
            cache.gradient(gx, gy, gz, gradB, 0)
            val nx = gradA[0] + t * (gradB[0] - gradA[0])
            val ny = gradA[1] + t * (gradB[1] - gradA[1])
            val nz = gradA[2] + t * (gradB[2] - gradA[2])
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            if (length > EPSILON) {
                normals[v * 3] = nx / length
                normals[v * 3 + 1] = ny / length
                normals[v * 3 + 2] = nz / length
            } // else left zero: [build] gives it its triangles' normal.
            colors[v] = mix(cache.color(fx, fy, fz), cache.color(gx, gy, gz), t)
            edgeIndex.put(key, v)
            return v
        }

        fun triangle(a: Int, b: Int, c: Int) {
            if (a == b || b == c || a == c) return // collapsed onto a corner
            if (triangles * 3 + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
            indices[triangles * 3] = a
            indices[triangles * 3 + 1] = b
            indices[triangles * 3 + 2] = c
            triangles++
        }

        /** The mesh without its pieces under [minTriangles] triangles, and how many pieces went. */
        @Suppress("CyclomaticComplexMethod", "LongMethod")
        fun build(minTriangles: Int): Pair<RerunMesh, Int> {
            val parent = IntArray(vertices) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) {
                    parent[r] = parent[parent[r]]
                    r = parent[r]
                }
                return r
            }
            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
            for (t in 0 until triangles) {
                union(indices[t * 3], indices[t * 3 + 1])
                union(indices[t * 3], indices[t * 3 + 2])
            }
            val size = IntArray(vertices)
            for (t in 0 until triangles) size[find(indices[t * 3])]++
            var dropped = 0
            for (v in 0 until vertices) if (find(v) == v && size[v] in 1 until minTriangles) dropped++
            // Zero normals (a flat gradient) take the sum of their triangles' face normals.
            val faceSum = FloatArray(vertices * 3)
            val keep = BooleanArray(triangles) { size[find(indices[it * 3])] >= minTriangles }
            val remap = IntArray(vertices) { -1 }
            var kept = 0
            var keptTriangles = 0
            for (t in 0 until triangles) {
                if (!keep[t]) continue
                keptTriangles++
                val a = indices[t * 3]
                val b = indices[t * 3 + 1]
                val c = indices[t * 3 + 2]
                val ux = positions[b * 3] - positions[a * 3]
                val uy = positions[b * 3 + 1] - positions[a * 3 + 1]
                val uz = positions[b * 3 + 2] - positions[a * 3 + 2]
                val wx = positions[c * 3] - positions[a * 3]
                val wy = positions[c * 3 + 1] - positions[a * 3 + 1]
                val wz = positions[c * 3 + 2] - positions[a * 3 + 2]
                val fx = uy * wz - uz * wy
                val fy = uz * wx - ux * wz
                val fz = ux * wy - uy * wx
                for (v in intArrayOf(a, b, c)) {
                    if (remap[v] < 0) remap[v] = kept++
                    faceSum[v * 3] += fx
                    faceSum[v * 3 + 1] += fy
                    faceSum[v * 3 + 2] += fz
                }
            }
            val outPositions = FloatArray(kept * 3)
            val outNormals = FloatArray(kept * 3)
            val outColors = IntArray(kept)
            for (v in 0 until vertices) {
                val to = remap[v]
                if (to < 0) continue
                for (a in 0 until 3) outPositions[to * 3 + a] = positions[v * 3 + a]
                var nx = normals[v * 3]
                var ny = normals[v * 3 + 1]
                var nz = normals[v * 3 + 2]
                if (nx == 0f && ny == 0f && nz == 0f) {
                    val fx = faceSum[v * 3]
                    val fy = faceSum[v * 3 + 1]
                    val fz = faceSum[v * 3 + 2]
                    val l = sqrt(fx * fx + fy * fy + fz * fz)
                    if (l > 0f) {
                        nx = faceSum[v * 3] / l
                        ny = faceSum[v * 3 + 1] / l
                        nz = faceSum[v * 3 + 2] / l
                    } else {
                        ny = 1f
                    }
                }
                outNormals[to * 3] = nx
                outNormals[to * 3 + 1] = ny
                outNormals[to * 3 + 2] = nz
                outColors[to] = colors[v]
            }
            val outIndices = IntArray(keptTriangles * 3)
            var at = 0
            for (t in 0 until triangles) {
                if (!keep[t]) continue
                for (k in 0 until 3) outIndices[at++] = remap[indices[t * 3 + k]]
            }
            return RerunMesh(outPositions, outNormals, outColors, outIndices) to dropped
        }

        private fun grow() {
            if (vertices < colors.size) return
            val capacity = colors.size * 2
            positions = positions.copyOf(capacity * 3)
            normals = normals.copyOf(capacity * 3)
            colors = colors.copyOf(capacity)
        }

        private fun mix(a: Int, b: Int, t: Float): Int = when {
            a == 0 && b == 0 -> NO_COLOR
            a == 0 -> b
            b == 0 -> a
            else -> {
                var out = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val ca = (a shr shift) and 0xFF
                    val cb = (b shr shift) and 0xFF
                    out = out or (((ca + t * (cb - ca)) + HALF).toInt().coerceIn(0, 255) shl shift)
                }
                out
            }
        }

        companion object {
            const val INITIAL = 1 shl 14
            const val OFFSET20 = 1 shl 19
            const val MASK20 = 0xFFFFFL
            const val EPSILON = 1e-9f
            const val HALF = 0.5f
        }
    }

    /** Cells without every corner at least this sure are left open rather than guessed. */
    const val DEFAULT_MIN_WEIGHT = 0.5f

    private const val PROGRESS_BLOCKS = 64
    private const val PROGRESS_MC = 0.9f

    // Corner c of a cell sits at (CORNER_X[c], CORNER_Y[c], CORNER_Z[c]).
    private val CORNER_X = intArrayOf(0, 1, 1, 0, 0, 1, 1, 0)
    private val CORNER_Y = intArrayOf(0, 0, 1, 1, 0, 0, 1, 1)
    private val CORNER_Z = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1)

    // Edge e joins corners EDGE_A[e] and EDGE_B[e].
    internal val EDGE_A = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0, 1, 2, 3)
    internal val EDGE_B = intArrayOf(1, 2, 3, 0, 5, 6, 7, 4, 4, 5, 6, 7)

    /**
     * The classic triangle table (Bourke, after Bloyd): for each of the 256 inside/outside
     * patterns of a cell's corners (bit c set = corner c inside, distance < 0), its triangles
     * as triples of cell edges.
     */
    internal val TRIANGLES: Array<IntArray> = TABLE.trim().split(';').map { row ->
        row.trim().takeIf { it.isNotEmpty() }?.split(' ')?.map(String::toInt)?.toIntArray() ?: IntArray(0)
    }.toTypedArray().also { check(it.size == 256) { "marching cubes table: ${it.size} rows" } }

    /** The edges each pattern's triangles use, as a 12-bit mask. */
    internal val EDGES: IntArray = IntArray(256) { cube -> TRIANGLES[cube].fold(0) { mask, e -> mask or (1 shl e) } }
}

// One row a pattern, `;`-separated: 256 rows, row 0 and row 255 empty.
private const val TABLE = """
;
0 8 3;
0 1 9;
1 8 3 9 8 1;
1 2 10;
0 8 3 1 2 10;
9 2 10 0 2 9;
2 8 3 2 10 8 10 9 8;
3 11 2;
0 11 2 8 11 0;
1 9 0 2 3 11;
1 11 2 1 9 11 9 8 11;
3 10 1 11 10 3;
0 10 1 0 8 10 8 11 10;
3 9 0 3 11 9 11 10 9;
9 8 10 10 8 11;
4 7 8;
4 3 0 7 3 4;
0 1 9 8 4 7;
4 1 9 4 7 1 7 3 1;
1 2 10 8 4 7;
3 4 7 3 0 4 1 2 10;
9 2 10 9 0 2 8 4 7;
2 10 9 2 9 7 2 7 3 7 9 4;
8 4 7 3 11 2;
11 4 7 11 2 4 2 0 4;
9 0 1 8 4 7 2 3 11;
4 7 11 9 4 11 9 11 2 9 2 1;
3 10 1 3 11 10 7 8 4;
1 11 10 1 4 11 1 0 4 7 11 4;
4 7 8 9 0 11 9 11 10 11 0 3;
4 7 11 4 11 9 9 11 10;
9 5 4;
9 5 4 0 8 3;
0 5 4 1 5 0;
8 5 4 8 3 5 3 1 5;
1 2 10 9 5 4;
3 0 8 1 2 10 4 9 5;
5 2 10 5 4 2 4 0 2;
2 10 5 3 2 5 3 5 4 3 4 8;
9 5 4 2 3 11;
0 11 2 0 8 11 4 9 5;
0 5 4 0 1 5 2 3 11;
2 1 5 2 5 8 2 8 11 4 8 5;
10 3 11 10 1 3 9 5 4;
4 9 5 0 8 1 8 10 1 8 11 10;
5 4 0 5 0 11 5 11 10 11 0 3;
5 4 8 5 8 10 10 8 11;
9 7 8 5 7 9;
9 3 0 9 5 3 5 7 3;
0 7 8 0 1 7 1 5 7;
1 5 3 3 5 7;
9 7 8 9 5 7 10 1 2;
10 1 2 9 5 0 5 3 0 5 7 3;
8 0 2 8 2 5 8 5 7 10 5 2;
2 10 5 2 5 3 3 5 7;
7 9 5 7 8 9 3 11 2;
9 5 7 9 7 2 9 2 0 2 7 11;
2 3 11 0 1 8 1 7 8 1 5 7;
11 2 1 11 1 7 7 1 5;
9 5 8 8 5 7 10 1 3 10 3 11;
5 7 0 5 0 9 7 11 0 1 0 10 11 10 0;
11 10 0 11 0 3 10 5 0 8 0 7 5 7 0;
11 10 5 7 11 5;
10 6 5;
0 8 3 5 10 6;
9 0 1 5 10 6;
1 8 3 1 9 8 5 10 6;
1 6 5 2 6 1;
1 6 5 1 2 6 3 0 8;
9 6 5 9 0 6 0 2 6;
5 9 8 5 8 2 5 2 6 3 2 8;
2 3 11 10 6 5;
11 0 8 11 2 0 10 6 5;
0 1 9 2 3 11 5 10 6;
5 10 6 1 9 2 9 11 2 9 8 11;
6 3 11 6 5 3 5 1 3;
0 8 11 0 11 5 0 5 1 5 11 6;
3 11 6 0 3 6 0 6 5 0 5 9;
6 5 9 6 9 11 11 9 8;
5 10 6 4 7 8;
4 3 0 4 7 3 6 5 10;
1 9 0 5 10 6 8 4 7;
10 6 5 1 9 7 1 7 3 7 9 4;
6 1 2 6 5 1 4 7 8;
1 2 5 5 2 6 3 0 4 3 4 7;
8 4 7 9 0 5 0 6 5 0 2 6;
7 3 9 7 9 4 3 2 9 5 9 6 2 6 9;
3 11 2 7 8 4 10 6 5;
5 10 6 4 7 2 4 2 0 2 7 11;
0 1 9 4 7 8 2 3 11 5 10 6;
9 2 1 9 11 2 9 4 11 7 11 4 5 10 6;
8 4 7 3 11 5 3 5 1 5 11 6;
5 1 11 5 11 6 1 0 11 7 11 4 0 4 11;
0 5 9 0 6 5 0 3 6 11 6 3 8 4 7;
6 5 9 6 9 11 4 7 9 7 11 9;
10 4 9 6 4 10;
4 10 6 4 9 10 0 8 3;
10 0 1 10 6 0 6 4 0;
8 3 1 8 1 6 8 6 4 6 1 10;
1 4 9 1 2 4 2 6 4;
3 0 8 1 2 9 2 4 9 2 6 4;
0 2 4 4 2 6;
8 3 2 8 2 4 4 2 6;
10 4 9 10 6 4 11 2 3;
0 8 2 2 8 11 4 9 10 4 10 6;
3 11 2 0 1 6 0 6 4 6 1 10;
6 4 1 6 1 10 4 8 1 2 1 11 8 11 1;
9 6 4 9 3 6 9 1 3 11 6 3;
8 11 1 8 1 0 11 6 1 9 1 4 6 4 1;
3 11 6 3 6 0 0 6 4;
6 4 8 11 6 8;
7 10 6 7 8 10 8 9 10;
0 7 3 0 10 7 0 9 10 6 7 10;
10 6 7 1 10 7 1 7 8 1 8 0;
10 6 7 10 7 1 1 7 3;
1 2 6 1 6 8 1 8 9 8 6 7;
2 6 9 2 9 1 6 7 9 0 9 3 7 3 9;
7 8 0 7 0 6 6 0 2;
7 3 2 6 7 2;
2 3 11 10 6 8 10 8 9 8 6 7;
2 0 7 2 7 11 0 9 7 6 7 10 9 10 7;
1 8 0 1 7 8 1 10 7 6 7 10 2 3 11;
11 2 1 11 1 7 10 6 1 6 7 1;
8 9 6 8 6 7 9 1 6 11 6 3 1 3 6;
0 9 1 11 6 7;
7 8 0 7 0 6 3 11 0 11 6 0;
7 11 6;
7 6 11;
3 0 8 11 7 6;
0 1 9 11 7 6;
8 1 9 8 3 1 11 7 6;
10 1 2 6 11 7;
1 2 10 3 0 8 6 11 7;
2 9 0 2 10 9 6 11 7;
6 11 7 2 10 3 10 8 3 10 9 8;
7 2 3 6 2 7;
7 0 8 7 6 0 6 2 0;
2 7 6 2 3 7 0 1 9;
1 6 2 1 8 6 1 9 8 8 7 6;
10 7 6 10 1 7 1 3 7;
10 7 6 1 7 10 1 8 7 1 0 8;
0 3 7 0 7 10 0 10 9 6 10 7;
7 6 10 7 10 8 8 10 9;
6 8 4 11 8 6;
3 6 11 3 0 6 0 4 6;
8 6 11 8 4 6 9 0 1;
9 4 6 9 6 3 9 3 1 11 3 6;
6 8 4 6 11 8 2 10 1;
1 2 10 3 0 11 0 6 11 0 4 6;
4 11 8 4 6 11 0 2 9 2 10 9;
10 9 3 10 3 2 9 4 3 11 3 6 4 6 3;
8 2 3 8 4 2 4 6 2;
0 4 2 4 6 2;
1 9 0 2 3 4 2 4 6 4 3 8;
1 9 4 1 4 2 2 4 6;
8 1 3 8 6 1 8 4 6 6 10 1;
10 1 0 10 0 6 6 0 4;
4 6 3 4 3 8 6 10 3 0 3 9 10 9 3;
10 9 4 6 10 4;
4 9 5 7 6 11;
0 8 3 4 9 5 11 7 6;
5 0 1 5 4 0 7 6 11;
11 7 6 8 3 4 3 5 4 3 1 5;
9 5 4 10 1 2 7 6 11;
6 11 7 1 2 10 0 8 3 4 9 5;
7 6 11 5 4 10 4 2 10 4 0 2;
3 4 8 3 5 4 3 2 5 10 5 2 11 7 6;
7 2 3 7 6 2 5 4 9;
9 5 4 0 8 6 0 6 2 6 8 7;
3 6 2 3 7 6 1 5 0 5 4 0;
6 2 8 6 8 7 2 1 8 4 8 5 1 5 8;
9 5 4 10 1 6 1 7 6 1 3 7;
1 6 10 1 7 6 1 0 7 8 7 0 9 5 4;
4 0 10 4 10 5 0 3 10 6 10 7 3 7 10;
7 6 10 7 10 8 5 4 10 4 8 10;
6 9 5 6 11 9 11 8 9;
3 6 11 0 6 3 0 5 6 0 9 5;
0 11 8 0 5 11 0 1 5 5 6 11;
6 11 3 6 3 5 5 3 1;
1 2 10 9 5 11 9 11 8 11 5 6;
0 11 3 0 6 11 0 9 6 5 6 9 1 2 10;
11 8 5 11 5 6 8 0 5 10 5 2 0 2 5;
6 11 3 6 3 5 2 10 3 10 5 3;
5 8 9 5 2 8 5 6 2 3 8 2;
9 5 6 9 6 0 0 6 2;
1 5 8 1 8 0 5 6 8 3 8 2 6 2 8;
1 5 6 2 1 6;
1 3 6 1 6 10 3 8 6 5 6 9 8 9 6;
10 1 0 10 0 6 9 5 0 5 6 0;
0 3 8 5 6 10;
10 5 6;
11 5 10 7 5 11;
11 5 10 11 7 5 8 3 0;
5 11 7 5 10 11 1 9 0;
10 7 5 10 11 7 9 8 1 8 3 1;
11 1 2 11 7 1 7 5 1;
0 8 3 1 2 7 1 7 5 7 2 11;
9 7 5 9 2 7 9 0 2 2 11 7;
7 5 2 7 2 11 5 9 2 3 2 8 9 8 2;
2 5 10 2 3 5 3 7 5;
8 2 0 8 5 2 8 7 5 10 2 5;
9 0 1 5 10 3 5 3 7 3 10 2;
9 8 2 9 2 1 8 7 2 10 2 5 7 5 2;
1 3 5 3 7 5;
0 8 7 0 7 1 1 7 5;
9 0 3 9 3 5 5 3 7;
9 8 7 5 9 7;
5 8 4 5 10 8 10 11 8;
5 0 4 5 11 0 5 10 11 11 3 0;
0 1 9 8 4 10 8 10 11 10 4 5;
10 11 4 10 4 5 11 3 4 9 4 1 3 1 4;
2 5 1 2 8 5 2 11 8 4 5 8;
0 4 11 0 11 3 4 5 11 2 11 1 5 1 11;
0 2 5 0 5 9 2 11 5 4 5 8 11 8 5;
9 4 5 2 11 3;
2 5 10 3 5 2 3 4 5 3 8 4;
5 10 2 5 2 4 4 2 0;
3 10 2 3 5 10 3 8 5 4 5 8 0 1 9;
5 10 2 5 2 4 1 9 2 9 4 2;
8 4 5 8 5 3 3 5 1;
0 4 5 1 0 5;
8 4 5 8 5 3 9 0 5 0 3 5;
9 4 5;
4 11 7 4 9 11 9 10 11;
0 8 3 4 9 7 9 11 7 9 10 11;
1 10 11 1 11 4 1 4 0 7 4 11;
3 1 4 3 4 8 1 10 4 7 4 11 10 11 4;
4 11 7 9 11 4 9 2 11 9 1 2;
9 7 4 9 11 7 9 1 11 2 11 1 0 8 3;
11 7 4 11 4 2 2 4 0;
11 7 4 11 4 2 8 3 4 3 2 4;
2 9 10 2 7 9 2 3 7 7 4 9;
9 10 7 9 7 4 10 2 7 8 7 0 2 0 7;
3 7 10 3 10 2 7 4 10 1 10 0 4 0 10;
1 10 2 8 7 4;
4 9 1 4 1 7 7 1 3;
4 9 1 4 1 7 0 8 1 8 7 1;
4 0 3 7 4 3;
4 8 7;
9 10 8 10 11 8;
3 0 9 3 9 11 11 9 10;
0 1 10 0 10 8 8 10 11;
3 1 10 11 3 10;
1 2 11 1 11 9 9 11 8;
3 0 9 3 9 11 1 2 9 2 11 9;
0 2 11 8 0 11;
3 2 11;
2 3 8 2 8 10 10 8 9;
9 10 2 0 9 2;
2 3 8 2 8 10 0 1 8 1 10 8;
1 10 2;
1 3 8 9 1 8;
0 9 1;
0 3 8;

"""
