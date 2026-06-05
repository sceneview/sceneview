package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.rendering.Vertex
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a surface of revolution (lathe) by rotating a 2D profile curve around the Y axis.
 *
 * The profile is defined as a list of 2D points where X is the distance from the
 * revolution axis and Y is the height. The profile is rotated around the Y axis to
 * create the 3D surface.
 *
 * @param profile 2D profile points (x = radius from axis, y = height). Must have at least 2 points.
 *                Points should be ordered from bottom to top (increasing Y).
 * @param center Offset applied to every vertex position.
 * @param segments Number of radial subdivisions around the Y axis. Default 24.
 * @param closed Whether to close the mesh by connecting the last slice back to the first. Default true.
 */
fun generateLathe(
    profile: List<Float2>,
    center: Float3 = Float3(0f),
    segments: Int = 24,
    closed: Boolean = true
): GeometryData {
    require(profile.size >= 2) { "Lathe profile requires at least 2 points" }

    val vertices = mutableListOf<Vertex>()
    val indices = mutableListOf<Int>()

    val sliceCount = if (closed) segments else segments + 1

    for (slice in 0..segments) {
        val theta = TWO_PI * slice.toFloat() / segments
        val cosT = cos(theta)
        val sinT = sin(theta)
        val u = slice.toFloat() / segments

        for (profileIdx in profile.indices) {
            val point = profile[profileIdx]
            val x = point.x * cosT
            val z = point.x * sinT
            val y = point.y

            val position = Float3(x, y, z) + center

            // Compute normal from profile tangent
            val tangent = when (profileIdx) {
                0 -> Float2(profile[1].x - profile[0].x, profile[1].y - profile[0].y)
                profile.size - 1 -> Float2(
                    profile[profileIdx].x - profile[profileIdx - 1].x,
                    profile[profileIdx].y - profile[profileIdx - 1].y
                )
                else -> Float2(
                    profile[profileIdx + 1].x - profile[profileIdx - 1].x,
                    profile[profileIdx + 1].y - profile[profileIdx - 1].y
                )
            }
            // Normal perpendicular to the tangent in the profile plane, rotated
            val profileNormalX = tangent.y
            val profileNormalY = -tangent.x
            val normal = normalize(Float3(
                profileNormalX * cosT,
                profileNormalY,
                profileNormalX * sinT
            ))

            val v = profileIdx.toFloat() / (profile.size - 1)

            vertices.add(Vertex(position = position, normal = normal, uvCoordinate = Float2(u, v)))
        }
    }

    // Generate indices
    val rowSize = profile.size
    for (slice in 0 until segments) {
        for (row in 0 until profile.size - 1) {
            val a = slice * rowSize + row
            val b = a + rowSize
            val c = a + 1
            val d = b + 1

            indices.add(a); indices.add(b); indices.add(d)
            indices.add(a); indices.add(d); indices.add(c)
        }
    }

    return GeometryData(vertices, indices)
}
