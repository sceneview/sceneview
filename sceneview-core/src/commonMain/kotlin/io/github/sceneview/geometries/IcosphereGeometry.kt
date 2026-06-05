package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.rendering.Vertex
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Generates an icosphere — a sphere created by subdividing an icosahedron.
 *
 * Unlike UV spheres, icospheres have nearly uniform triangle distribution
 * across the surface, avoiding polar pinching artifacts. This makes them
 * ideal for procedural planet generation, particle systems, and sphere
 * colliders.
 *
 * @param radius Sphere radius. Default 1.
 * @param center Offset applied to every vertex position.
 * @param subdivisions Number of recursive subdivisions. 0 = icosahedron (12 vertices),
 *                     1 = 42 vertices, 2 = 162 vertices, etc. Each level quadruples
 *                     the triangle count. Default 2.
 */
fun generateIcosphere(
    radius: Float = 1f,
    center: Float3 = Float3(0f),
    subdivisions: Int = 2
): GeometryData {
    require(subdivisions >= 0) { "subdivisions must be >= 0" }
    require(subdivisions <= 6) { "subdivisions > 6 creates too many triangles" }

    // Golden ratio for icosahedron construction
    val t = (1f + sqrt(5f)) / 2f

    // 12 vertices of an icosahedron (not yet normalized)
    val baseVertices = mutableListOf(
        Float3(-1f, t, 0f), Float3(1f, t, 0f), Float3(-1f, -t, 0f), Float3(1f, -t, 0f),
        Float3(0f, -1f, t), Float3(0f, 1f, t), Float3(0f, -1f, -t), Float3(0f, 1f, -t),
        Float3(t, 0f, -1f), Float3(t, 0f, 1f), Float3(-t, 0f, -1f), Float3(-t, 0f, 1f)
    ).map { normalize(it) }.toMutableList()

    // 20 faces of an icosahedron
    var faces = mutableListOf(
        Triple(0, 11, 5), Triple(0, 5, 1), Triple(0, 1, 7), Triple(0, 7, 10), Triple(0, 10, 11),
        Triple(1, 5, 9), Triple(5, 11, 4), Triple(11, 10, 2), Triple(10, 7, 6), Triple(7, 1, 8),
        Triple(3, 9, 4), Triple(3, 4, 2), Triple(3, 2, 6), Triple(3, 6, 8), Triple(3, 8, 9),
        Triple(4, 9, 5), Triple(2, 4, 11), Triple(6, 2, 10), Triple(8, 6, 7), Triple(9, 8, 1)
    )

    // Midpoint cache for subdivision
    val midpointCache = mutableMapOf<Long, Int>()

    fun getMidpoint(v1: Int, v2: Int): Int {
        val smaller = minOf(v1, v2)
        val larger = maxOf(v1, v2)
        val key = smaller.toLong() * 100000L + larger.toLong()
        midpointCache[key]?.let { return it }

        val p1 = baseVertices[v1]
        val p2 = baseVertices[v2]
        val mid = normalize(Float3(
            (p1.x + p2.x) / 2f,
            (p1.y + p2.y) / 2f,
            (p1.z + p2.z) / 2f
        ))
        val index = baseVertices.size
        baseVertices.add(mid)
        midpointCache[key] = index
        return index
    }

    // Subdivide
    for (sub in 0 until subdivisions) {
        val newFaces = mutableListOf<Triple<Int, Int, Int>>()
        for ((a, b, c) in faces) {
            val ab = getMidpoint(a, b)
            val bc = getMidpoint(b, c)
            val ca = getMidpoint(c, a)
            newFaces.add(Triple(a, ab, ca))
            newFaces.add(Triple(b, bc, ab))
            newFaces.add(Triple(c, ca, bc))
            newFaces.add(Triple(ab, bc, ca))
        }
        faces = newFaces
        midpointCache.clear()
    }

    // Convert to GeometryData
    val vertices = baseVertices.map { pos ->
        val scaledPos = pos * radius + center
        val normal = pos // Already normalized

        // Compute UV from spherical coordinates
        val u = 0.5f + atan2(pos.z, pos.x) / (2f * PI.toFloat())
        val v = 0.5f - kotlin.math.asin(pos.y.coerceIn(-1f, 1f)) / PI.toFloat()

        Vertex(position = scaledPos, normal = normal, uvCoordinate = Float2(u, v))
    }

    val indices = ArrayList<Int>(faces.size * 3)
    for (face in faces) {
        indices.add(face.first)
        indices.add(face.second)
        indices.add(face.third)
    }

    return GeometryData(vertices, indices)
}
