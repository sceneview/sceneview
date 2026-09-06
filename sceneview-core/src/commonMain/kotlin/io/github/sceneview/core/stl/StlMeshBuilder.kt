package io.github.sceneview.core.stl

import io.github.sceneview.core.threemf.FloatArrayBuilder
import io.github.sceneview.core.threemf.IntArrayBuilder
import io.github.sceneview.core.threemf.ThreeMfUnit
import kotlin.math.sqrt

/** Weld first, then accumulate geometric area vectors across all incident faces for smooth repair. */
internal class StlMeshBuilder(private val unit: ThreeMfUnit, private val maxTriangles: Int) {
    private val vertices = HashMap<Vertex, Int>()
    private val positions = FloatArrayBuilder()
    private val indices = IntArrayBuilder()
    private val faceNormals = FloatArrayBuilder()
    val triangleCount: Int get() = indices.size / 3

    fun checkTriangleCount(count: Long) {
        if (count > maxTriangles) stlError("STL exceeds $maxTriangles triangles (declared $count)")
    }

    fun add(facet: FloatArray) {
        checkTriangleCount(triangleCount.toLong() + 1)
        repeat(3) { faceNormals.add(facet[it]) }
        repeat(3) { corner ->
            val at = 3 + corner * 3
            val vertex = Vertex(coordinate(facet[at]), coordinate(facet[at + 1]), coordinate(facet[at + 2]))
            indices.add(vertices.getOrPut(vertex) {
                val index = positions.size / 3
                positions.add(vertex.x)
                positions.add(vertex.y)
                positions.add(vertex.z)
                index
            })
        }
    }

    fun build(): StlModel {
        if (triangleCount == 0) stlError("STL contains no triangles")
        val points = positions.toArray()
        val triangles = indices.toArray()
        val faces = faceNormals.toArray()
        // Double intermediates avoid overflow/underflow for finite float32 positions and normals.
        val sums = DoubleArray(points.size)
        for (triangle in 0 until triangleCount) {
            val at = triangle * 3
            val a = triangles[at] * 3
            val b = triangles[at + 1] * 3
            val c = triangles[at + 2] * 3
            val ux = points[b].toDouble() - points[a]
            val uy = points[b + 1].toDouble() - points[a + 1]
            val uz = points[b + 2].toDouble() - points[a + 2]
            val vx = points[c].toDouble() - points[a]
            val vy = points[c + 1].toDouble() - points[a + 1]
            val vz = points[c + 2].toDouble() - points[a + 2]
            repeat(3) { corner ->
                val vertex = triangles[at + corner] * 3
                sums[vertex] += uy * vz - uz * vy
                sums[vertex + 1] += uz * vx - ux * vz
                sums[vertex + 2] += ux * vy - uy * vx
            }
        }
        val normals = FloatArray(triangles.size * 3)
        for (corner in triangles.indices) {
            val face = corner / 3 * 3
            var x = faces[face].toDouble()
            var y = faces[face + 1].toDouble()
            var z = faces[face + 2].toDouble()
            var length = sqrt(x * x + y * y + z * z)
            if (length == 0.0 || !length.isFinite()) {
                val vertex = triangles[corner] * 3
                x = sums[vertex]
                y = sums[vertex + 1]
                z = sums[vertex + 2]
                length = sqrt(x * x + y * y + z * z)
            }
            val at = corner * 3
            if (length > 0.0 && length.isFinite()) {
                normals[at] = (x / length).toFloat()
                normals[at + 1] = (y / length).toFloat()
                normals[at + 2] = (z / length).toFloat()
            } else {
                normals[at + 1] = 1f
            }
        }
        return StlModel(points, triangles, normals, unit)
    }

    private fun coordinate(value: Float): Float {
        // Kotlin/JS stores Float as double; round to float32 before validation and welding.
        val rounded = Float.fromBits(value.toBits())
        if (!rounded.isFinite() || !Float.fromBits((rounded * unit.meters).toBits()).isFinite()) {
            stlError("STL position must be finite in model units and metres")
        }
        return if (rounded == 0f) 0f else rounded
    }

    private data class Vertex(val x: Float, val y: Float, val z: Float)
}
