package io.github.sceneview.demo.demos.internal.model

import kotlin.math.sqrt

/*
 * The mesh connectivity the model's clean-up works on: which triangles meet at a vertex, which
 * triangle lies across each edge, and the pieces the mesh falls into. Flat arrays only — a room is
 * hundreds of thousands of triangles, and this runs on a phone.
 */

/** Which triangles use each vertex, and which triangle lies across each triangle's edges. */
internal class MeshTopology(val vertexCount: Int, val indices: IntArray) {
    val triangleCount: Int = indices.size / 3

    /** Triangles of vertex `v`: [vertexTriangles] from `vertexStart[v]` until `vertexStart[v + 1]`. */
    val vertexStart = IntArray(vertexCount + 1)
    val vertexTriangles = IntArray(indices.size)

    /**
     * The triangle across edge `k` (corner `k` to corner `k + 1`) of triangle `t`, at `t * 3 + k`:
     * the one that runs that edge the other way. `-1` on a border, or where more than two
     * triangles share the edge.
     */
    val neighbours = IntArray(indices.size)

    init {
        for (i in indices) vertexStart[i + 1]++
        for (v in 0 until vertexCount) vertexStart[v + 1] += vertexStart[v]
        val fill = vertexStart.copyOf(vertexCount)
        for (c in indices.indices) vertexTriangles[fill[indices[c]]++] = c / 3
        for (t in 0 until triangleCount) {
            for (k in 0 until 3) {
                val a = indices[t * 3 + k]
                val b = indices[t * 3 + (k + 1) % 3]
                neighbours[t * 3 + k] = across(t, a, b)
            }
        }
    }

    /** The one triangle other than [t] that runs edge [a] → [b] the other way, `-1` for none or several. */
    private fun across(t: Int, a: Int, b: Int): Int {
        var found = -1
        for (i in vertexStart[b] until vertexStart[b + 1]) {
            val u = vertexTriangles[i]
            if (u == t) continue
            for (k in 0 until 3) {
                if (indices[u * 3 + k] == b && indices[u * 3 + (k + 1) % 3] == a) {
                    if (found >= 0 && found != u) return -1
                    found = u
                }
            }
        }
        return found
    }

    /** How many edges have no triangle across: 0 for a closed, consistently wound surface. */
    fun borderEdges(): Int = neighbours.count { it < 0 }

    /**
     * The connected piece of each vertex (triangles sharing a vertex are connected), numbered
     * from 0; vertices no triangle uses get -1. Returns the piece of each vertex and the count.
     */
    fun components(): Pair<IntArray, Int> {
        val parent = IntArray(vertexCount) { it }
        fun find(v: Int): Int {
            var r = v
            while (parent[r] != r) r = parent[r]
            var x = v
            while (parent[x] != r) {
                val next = parent[x]
                parent[x] = r
                x = next
            }
            return r
        }
        for (t in 0 until triangleCount) {
            val a = find(indices[t * 3])
            val b = find(indices[t * 3 + 1])
            if (a != b) parent[a] = b
            val c = find(indices[t * 3 + 2])
            val ab = find(b)
            if (c != ab) parent[c] = ab
        }
        val label = IntArray(vertexCount) { -1 }
        val rootLabel = IntArray(vertexCount) { -1 }
        var count = 0
        for (v in 0 until vertexCount) {
            if (vertexStart[v + 1] == vertexStart[v]) continue
            val r = find(v)
            if (rootLabel[r] < 0) rootLabel[r] = count++
            label[v] = rootLabel[r]
        }
        return label to count
    }
}

/** Per-triangle geometry: unit normal, centroid and area, recomputed from [positions] on demand. */
internal class FaceGeometry(private val positions: FloatArray, private val indices: IntArray) {
    val count = indices.size / 3
    val normals = FloatArray(count * 3)
    val centroids = FloatArray(count * 3)
    val areas = FloatArray(count)

    init {
        update()
    }

    fun update() {
        for (t in 0 until count) {
            val a = indices[t * 3] * 3
            val b = indices[t * 3 + 1] * 3
            val c = indices[t * 3 + 2] * 3
            val ux = positions[b] - positions[a]
            val uy = positions[b + 1] - positions[a + 1]
            val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]
            val vy = positions[c + 1] - positions[a + 1]
            val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val length = sqrt(nx * nx + ny * ny + nz * nz)
            areas[t] = length / 2f
            if (length > 0f) {
                normals[t * 3] = nx / length
                normals[t * 3 + 1] = ny / length
                normals[t * 3 + 2] = nz / length
            } else {
                normals[t * 3] = 0f
                normals[t * 3 + 1] = 0f
                normals[t * 3 + 2] = 0f
            }
            centroids[t * 3] = (positions[a] + positions[b] + positions[c]) / 3f
            centroids[t * 3 + 1] = (positions[a + 1] + positions[b + 1] + positions[c + 1]) / 3f
            centroids[t * 3 + 2] = (positions[a + 2] + positions[b + 2] + positions[c + 2]) / 3f
        }
    }

    fun dot(t: Int, u: Int): Float =
        normals[t * 3] * normals[u * 3] + normals[t * 3 + 1] * normals[u * 3 + 1] + normals[t * 3 + 2] * normals[u * 3 + 2]
}

/**
 * Area-weighted vertex normals of the triangles [indices] over [positions]: what a renderer
 * shades with. A vertex no triangle uses gets +Y.
 */
internal fun vertexNormals(positions: FloatArray, indices: IntArray): FloatArray {
    val normals = FloatArray(positions.size)
    for (t in 0 until indices.size / 3) {
        val a = indices[t * 3] * 3
        val b = indices[t * 3 + 1] * 3
        val c = indices[t * 3 + 2] * 3
        val ux = positions[b] - positions[a]
        val uy = positions[b + 1] - positions[a + 1]
        val uz = positions[b + 2] - positions[a + 2]
        val vx = positions[c] - positions[a]
        val vy = positions[c + 1] - positions[a + 1]
        val vz = positions[c + 2] - positions[a + 2]
        // The cross product's length is twice the area: the sum is area-weighted as it stands.
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        for (corner in intArrayOf(a, b, c)) {
            normals[corner] += nx
            normals[corner + 1] += ny
            normals[corner + 2] += nz
        }
    }
    for (v in 0 until positions.size / 3) {
        val x = normals[v * 3]
        val y = normals[v * 3 + 1]
        val z = normals[v * 3 + 2]
        val length = sqrt(x * x + y * y + z * z)
        if (length > 0f) {
            normals[v * 3] = x / length
            normals[v * 3 + 1] = y / length
            normals[v * 3 + 2] = z / length
        } else {
            normals[v * 3 + 1] = 1f
        }
    }
    return normals
}
