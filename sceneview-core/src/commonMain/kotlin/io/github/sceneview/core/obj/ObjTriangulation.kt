package io.github.sceneview.core.obj

import io.github.sceneview.core.threemf.IntArrayBuilder
import kotlin.math.abs

/** Convex fan fast path, then winding-preserving ear clipping for simple concave polygons. */
internal object ObjTriangulation {
    fun triangulate(vertices: List<Int>, positions: ObjFloats, line: Int): IntArray {
        if (vertices.size == 3) return intArrayOf(0, 1, 2)
        val points = project(vertices, positions)
        var area = 0.0
        for (i in vertices.indices) {
            val next = (i + 1) % vertices.size
            area += points.x[i] * points.y[next] - points.x[next] * points.y[i]
        }
        val direction = if (area >= 0.0) 1.0 else -1.0
        val convex = vertices.indices.all { i ->
            points.cross(i, (i + 1) % vertices.size, (i + 2) % vertices.size) * direction >= 0.0
        }
        if (convex) return IntArray((vertices.size - 2) * 3) { i ->
            when (i % 3) { 0 -> 0; 1 -> i / 3 + 1; else -> i / 3 + 2 }
        }
        return clip(points, direction, line)
    }

    private fun clip(points: Points, direction: Double, line: Int): IntArray {
        val remaining = points.x.indices.toMutableList()
        val triangles = IntArrayBuilder()
        while (remaining.size > 3) {
            val ear = remaining.indices.firstOrNull { i ->
                val a = remaining[(i + remaining.size - 1) % remaining.size]
                val b = remaining[i]
                val c = remaining[(i + 1) % remaining.size]
                points.cross(a, b, c) * direction > 0.0 && remaining.none { p ->
                    p != a && p != b && p != c &&
                        points.cross(a, b, p) * direction >= 0.0 &&
                        points.cross(b, c, p) * direction >= 0.0 &&
                        points.cross(c, a, p) * direction >= 0.0
                }
            } ?: objError("OBJ line $line: polygon cannot be triangulated (expected a simple planar face)")
            triangles.add(remaining[(ear + remaining.size - 1) % remaining.size])
            triangles.add(remaining[ear])
            triangles.add(remaining[(ear + 1) % remaining.size])
            remaining.removeAt(ear)
        }
        remaining.forEach { triangles.add(it) }
        return triangles.toArray()
    }

    /** Drop the dominant component of Newell's normal to support faces in any axis plane. */
    private fun project(vertices: List<Int>, positions: ObjFloats): Points {
        val normal = DoubleArray(3)
        for (i in vertices.indices) {
            val a = vertices[i] * 3
            val b = vertices[(i + 1) % vertices.size] * 3
            for (axis in 0 until 3) {
                val u = (axis + 1) % 3
                val v = (axis + 2) % 3
                normal[axis] += (positions[a + u].toDouble() - positions[b + u]) *
                    (positions[a + v].toDouble() + positions[b + v])
            }
        }
        val drop = normal.indices.maxBy { abs(normal[it]) }
        val u = (drop + 1) % 3
        val v = (drop + 2) % 3
        // Translate before area products to reduce cancellation on models far from the origin.
        return Points(
            DoubleArray(vertices.size) { positions[vertices[it] * 3 + u].toDouble() - positions[vertices[0] * 3 + u] },
            DoubleArray(vertices.size) { positions[vertices[it] * 3 + v].toDouble() - positions[vertices[0] * 3 + v] }
        )
    }

    private class Points(val x: DoubleArray, val y: DoubleArray) {
        fun cross(a: Int, b: Int, c: Int): Double =
            (x[b] - x[a]) * (y[c] - y[a]) - (y[b] - y[a]) * (x[c] - x[a])
    }
}
