package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.PI
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.rendering.Vertex
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates a capsule (cylinder with hemispherical caps) as vertices and triangle indices.
 *
 * The capsule is centered at [center] with its axis along Y.
 *
 * @param radius Radius of the capsule. Default 0.5.
 * @param height Full height including the hemispherical caps. Default 2.
 * @param center Offset applied to every vertex position.
 * @param slices Number of radial subdivisions. Default 24.
 * @param hemisphereStacks Number of stacks per hemisphere cap. Default 8.
 * @param cylinderStacks Number of stacks for the cylinder body. Default 1.
 */
fun generateCapsule(
    radius: Float = 0.5f,
    height: Float = 2f,
    center: Float3 = Float3(0f),
    slices: Int = 24,
    hemisphereStacks: Int = 8,
    cylinderStacks: Int = 1
): GeometryData {
    val cylinderHeight = (height - 2f * radius).coerceAtLeast(0f)
    val halfCylHeight = cylinderHeight / 2f

    val vertices = mutableListOf<Vertex>()
    val indices = mutableListOf<Int>()

    // --- Top hemisphere ---
    for (stack in 0..hemisphereStacks) {
        val phi = PI / 2f * stack.toFloat() / hemisphereStacks // 0 to PI/2
        for (slice in 0..slices) {
            val theta = TWO_PI * slice.toFloat() / slices
            val x = radius * cos(phi) * cos(theta)
            val y = radius * sin(phi) + halfCylHeight
            val z = radius * cos(phi) * sin(theta)
            val nx = cos(phi) * cos(theta)
            val ny = sin(phi)
            val nz = cos(phi) * sin(theta)
            val u = slice.toFloat() / slices
            val v = 0.5f - (y / height)

            vertices.add(Vertex(
                position = Float3(x, y, z) + center,
                normal = normalize(Float3(nx, ny, nz)),
                uvCoordinate = Float2(u, v)
            ))
        }
    }

    // --- Cylinder body ---
    for (stack in 0..cylinderStacks) {
        val y = halfCylHeight - cylinderHeight * stack.toFloat() / cylinderStacks
        for (slice in 0..slices) {
            val theta = TWO_PI * slice.toFloat() / slices
            val x = radius * cos(theta)
            val z = radius * sin(theta)
            val u = slice.toFloat() / slices
            val v = 0.5f - (y / height)

            vertices.add(Vertex(
                position = Float3(x, y, z) + center,
                normal = normalize(Float3(cos(theta), 0f, sin(theta))),
                uvCoordinate = Float2(u, v)
            ))
        }
    }

    // --- Bottom hemisphere ---
    for (stack in 0..hemisphereStacks) {
        val phi = PI / 2f * stack.toFloat() / hemisphereStacks // 0 to PI/2
        for (slice in 0..slices) {
            val theta = TWO_PI * slice.toFloat() / slices
            val x = radius * cos(phi) * cos(theta)
            val y = -radius * sin(phi) - halfCylHeight
            val z = radius * cos(phi) * sin(theta)
            val nx = cos(phi) * cos(theta)
            val ny = -sin(phi)
            val nz = cos(phi) * sin(theta)
            val u = slice.toFloat() / slices
            val v = 0.5f - (y / height)

            vertices.add(Vertex(
                position = Float3(x, y, z) + center,
                normal = normalize(Float3(nx, ny, nz)),
                uvCoordinate = Float2(u, v)
            ))
        }
    }

    // --- Generate indices ---
    //
    // The three vertex blocks above are NOT a single monotonic top→bottom sweep, so
    // they must be stitched independently — a single continuous grid loop over all rows
    // would bridge across the block boundaries and produce a malformed mesh (an inverted
    // cap funnel from the north pole onto the cylinder rim, plus a degenerate zero-area
    // band where the cylinder bottom and the bottom-hemisphere equator coincide).
    //
    // Winding also differs per block. For the shared (a,b,d)/(a,d,c) quad pattern, whether a
    // face points outward depends on the Y direction the rows sweep: cross(rowDir, colDir)
    // points outward only where the rows climb. The top hemisphere sweeps equator→pole going
    // *up* in Y, so it winds outward as-is; the cylinder (top→bottom) and the bottom
    // hemisphere (equator→pole going *down*) both descend, so cross(rowDir, colDir) points
    // *inward* and their quads are emitted with reversed winding to keep every face outward.
    //
    // Each hemisphere's pole row collapses all (slices + 1) vertices onto a single apex,
    // so a quad touching the pole has a zero-length edge. There it degenerates to a single
    // triangle (apex + the base edge) — the same cap handling as generateSphere — instead
    // of two triangles, one of which would be a zero-area sliver.
    val verticesPerRow = slices + 1
    val topRows = hemisphereStacks + 1
    val cylRows = cylinderStacks + 1
    val botRows = hemisphereStacks + 1

    // A hemisphere's pole ring does not collapse to *exactly* one point — cos(PI/2) is a
    // tiny non-zero float, so the apex vertices differ by ~1e-8 — hence a tolerance test
    // rather than an exact position compare. The tolerance scales with the radius and stays
    // far below the spacing of any genuine (non-pole) ring.
    val collapseEps2 = (radius * 1e-4f).let { it * it } + 1e-12f
    fun collapsed(i: Int, j: Int): Boolean {
        val d = vertices[i].position - vertices[j].position
        return dot(d, d) <= collapseEps2
    }

    fun stitch(startRow: Int, rowCount: Int, reverseWinding: Boolean) {
        for (row in startRow until startRow + rowCount - 1) {
            for (col in 0 until slices) {
                val a = row * verticesPerRow + col
                val b = a + verticesPerRow
                val c = a + 1
                val d = b + 1

                // Whole quad collapsed (the two rows coincide). Happens for the cylinder
                // body when height == 2 * radius — a sphere — so the cylinder has zero
                // length: emit nothing, the two hemispheres already meet at the equator.
                if (collapsed(a, b) && collapsed(c, d)) continue

                // Far edge (row + 1) collapsed to a pole apex → single triangle, base a→c.
                val farPole = collapsed(b, d)
                // Near edge (row) collapsed to a pole apex → single triangle, base b→d.
                val nearPole = collapsed(a, c)

                if (reverseWinding) {
                    when {
                        farPole -> { indices.add(a); indices.add(c); indices.add(b) }
                        nearPole -> { indices.add(a); indices.add(d); indices.add(b) }
                        else -> {
                            indices.add(a); indices.add(d); indices.add(b)
                            indices.add(a); indices.add(c); indices.add(d)
                        }
                    }
                } else {
                    when {
                        farPole -> { indices.add(a); indices.add(b); indices.add(c) }
                        nearPole -> { indices.add(a); indices.add(b); indices.add(d) }
                        else -> {
                            indices.add(a); indices.add(b); indices.add(d)
                            indices.add(a); indices.add(d); indices.add(c)
                        }
                    }
                }
            }
        }
    }

    stitch(0, topRows, reverseWinding = false)                  // top hemisphere: equator → pole (up)
    stitch(topRows, cylRows, reverseWinding = true)             // cylinder body: top → bottom (down)
    stitch(topRows + cylRows, botRows, reverseWinding = true)   // bottom hemisphere: equator → pole (down)

    return GeometryData(vertices, indices)
}
