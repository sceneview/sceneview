package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.rendering.Vertex

/**
 * Generates a mesh by extruding a 2D cross-section along a 3D path.
 *
 * The cross-section is defined in local 2D space and oriented perpendicular
 * to the path direction at each path point using a Frenet frame.
 *
 * @param crossSection 2D cross-section points (should form a closed loop). At least 3 points.
 * @param path 3D path points to extrude along. At least 2 points.
 * @param closed Whether to connect the last path point back to the first. Default false.
 */
fun generateExtrude(
    crossSection: List<Float2>,
    path: List<Float3>,
    closed: Boolean = false
): GeometryData {
    require(crossSection.size >= 3) { "Cross-section requires at least 3 points" }
    require(path.size >= 2) { "Path requires at least 2 points" }

    val vertices = mutableListOf<Vertex>()
    val indices = mutableListOf<Int>()

    // Compute Frenet frames along the path
    val frames = computeFrenetFrames(path, closed)

    val csCount = crossSection.size
    val pathCount = if (closed) path.size + 1 else path.size

    for (i in 0 until pathCount) {
        val pathIdx = i % path.size
        val frame = frames[pathIdx]
        val pathPoint = path[pathIdx]
        val v = i.toFloat() / (pathCount - 1)

        for (j in crossSection.indices) {
            val csPoint = crossSection[j]
            // Transform cross-section point to world space using the Frenet frame
            val position = Float3(
                pathPoint.x + frame.normal.x * csPoint.x + frame.binormal.x * csPoint.y,
                pathPoint.y + frame.normal.y * csPoint.x + frame.binormal.y * csPoint.y,
                pathPoint.z + frame.normal.z * csPoint.x + frame.binormal.z * csPoint.y
            )

            // Normal points outward from the extrusion center
            val normal = normalize(Float3(
                frame.normal.x * csPoint.x + frame.binormal.x * csPoint.y,
                frame.normal.y * csPoint.x + frame.binormal.y * csPoint.y,
                frame.normal.z * csPoint.x + frame.binormal.z * csPoint.y
            ))

            val u = j.toFloat() / csCount
            vertices.add(Vertex(position = position, normal = normal, uvCoordinate = Float2(u, v)))
        }
    }

    // Generate indices
    for (i in 0 until pathCount - 1) {
        for (j in 0 until csCount) {
            val a = i * csCount + j
            val b = a + csCount
            val c = i * csCount + (j + 1) % csCount
            val d = c + csCount

            indices.add(a); indices.add(b); indices.add(d)
            indices.add(a); indices.add(d); indices.add(c)
        }
    }

    return GeometryData(vertices, indices)
}

/**
 * A Frenet frame at a point along a path.
 */
internal data class FrenetFrame(
    val tangent: Float3,
    val normal: Float3,
    val binormal: Float3
)

/**
 * Compute Frenet frames along a 3D path using the parallel transport method
 * to avoid twist discontinuities.
 */
internal fun computeFrenetFrames(path: List<Float3>, closed: Boolean): List<FrenetFrame> {
    val n = path.size
    val tangents = Array(n) { Float3(0f) }
    val normals = Array(n) { Float3(0f) }
    val binormals = Array(n) { Float3(0f) }

    // Compute tangents
    for (i in 0 until n) {
        val next = if (i < n - 1) i + 1 else if (closed) 0 else i
        val prev = if (i > 0) i - 1 else if (closed) n - 1 else i
        tangents[i] = normalize(path[next] - path[prev])
    }

    // Compute initial normal using the smallest component of the first tangent
    val t0 = tangents[0]
    val initial = if (kotlin.math.abs(t0.x) < kotlin.math.abs(t0.y)) {
        if (kotlin.math.abs(t0.x) < kotlin.math.abs(t0.z)) Float3(1f, 0f, 0f) else Float3(0f, 0f, 1f)
    } else {
        if (kotlin.math.abs(t0.y) < kotlin.math.abs(t0.z)) Float3(0f, 1f, 0f) else Float3(0f, 0f, 1f)
    }

    normals[0] = normalize(cross(t0, initial))
    binormals[0] = cross(t0, normals[0])

    // Parallel transport
    for (i in 1 until n) {
        val b = normalize(cross(tangents[i - 1], tangents[i]))
        if (dev.romainguy.kotlin.math.length(b) < 1e-6f) {
            normals[i] = normals[i - 1]
        } else {
            val theta = kotlin.math.acos(
                dev.romainguy.kotlin.math.dot(tangents[i - 1], tangents[i]).coerceIn(-1f, 1f)
            )
            // Rotate previous normal around the binormal axis by theta
            normals[i] = rotateVector(normals[i - 1], b, theta)
        }
        binormals[i] = cross(tangents[i], normals[i])
    }

    return List(n) { FrenetFrame(tangents[it], normals[it], binormals[it]) }
}

/**
 * Rotate a vector around an axis by an angle (Rodrigues' rotation formula).
 */
private fun rotateVector(v: Float3, axis: Float3, angle: Float): Float3 {
    val cosA = kotlin.math.cos(angle)
    val sinA = kotlin.math.sin(angle)
    val dot = dev.romainguy.kotlin.math.dot(axis, v)
    val crossV = cross(axis, v)
    return Float3(
        v.x * cosA + crossV.x * sinA + axis.x * dot * (1f - cosA),
        v.y * cosA + crossV.y * sinA + axis.y * dot * (1f - cosA),
        v.z * cosA + crossV.z * sinA + axis.z * dot * (1f - cosA)
    )
}
