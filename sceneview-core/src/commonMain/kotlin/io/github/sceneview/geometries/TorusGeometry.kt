package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.rendering.Vertex
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a torus (donut shape) as a list of vertices and triangle indices.
 *
 * The torus is centered at the origin in the XZ plane with Y as the up axis.
 *
 * @param majorRadius Distance from the center of the torus to the center of the tube. Default 1.
 * @param minorRadius Radius of the tube. Default 0.3.
 * @param center Offset applied to every vertex position.
 * @param majorSegments Number of segments around the main ring. Default 24.
 * @param minorSegments Number of segments around the tube cross-section. Default 12.
 */
fun generateTorus(
    majorRadius: Float = 1f,
    minorRadius: Float = 0.3f,
    center: Float3 = Float3(0f),
    majorSegments: Int = 24,
    minorSegments: Int = 12
): GeometryData {
    val vertices = buildList {
        for (i in 0..majorSegments) {
            val theta = TWO_PI * i.toFloat() / majorSegments
            val cosTheta = cos(theta)
            val sinTheta = sin(theta)

            for (j in 0..minorSegments) {
                val phi = TWO_PI * j.toFloat() / minorSegments
                val cosPhi = cos(phi)
                val sinPhi = sin(phi)

                val x = (majorRadius + minorRadius * cosPhi) * cosTheta
                val y = minorRadius * sinPhi
                val z = (majorRadius + minorRadius * cosPhi) * sinTheta

                // Normal points from the tube center toward the surface
                val nx = cosPhi * cosTheta
                val ny = sinPhi
                val nz = cosPhi * sinTheta

                val position = Float3(x, y, z) + center
                val normal = normalize(Float3(nx, ny, nz))
                val uv = Float2(
                    i.toFloat() / majorSegments,
                    j.toFloat() / minorSegments
                )

                add(Vertex(position = position, normal = normal, uvCoordinate = uv))
            }
        }
    }

    val indices = buildList {
        for (i in 0 until majorSegments) {
            for (j in 0 until minorSegments) {
                val a = i * (minorSegments + 1) + j
                val b = a + minorSegments + 1
                val c = a + 1
                val d = b + 1

                // Counter-clockwise (outward-facing) winding so the front face matches the
                // outward shading normal under the default single-sided material (Filament /
                // Filament.js back-face-cull clockwise triangles). See issues #2469 / #2475.
                add(a); add(d); add(b)
                add(a); add(c); add(d)
            }
        }
    }

    return GeometryData(vertices, indices)
}
