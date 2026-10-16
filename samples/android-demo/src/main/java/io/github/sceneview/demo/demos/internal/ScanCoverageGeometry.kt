package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** World-anchored coverage marks, kept pure so tests can inspect the triangles sent to Filament. */
internal object ScanCoverageGeometry {
    const val FIRST_DIAMETER = 0.16f
    const val SECOND_DIAMETER = 0.26f
    const val DENSE_DIAMETER = 0.36f
    const val FULL_SIDE = 0.9f
    private const val HEX_SIDES = 6

    fun append(mesh: DebugMesh, cell: ScanCoverage.Cell, cellM: Float) {
        val tier = cell.tier
        if (tier == 0) return
        val n = cell.normal()
        val x = centre(cell.x, cellM)
        val y = centre(cell.y, cellM)
        val z = centre(cell.z, cellM)
        // Project the grid centre onto the measured plane, rather than letting the mark float.
        val distance = (x - cell.x) * n[0] + (y - cell.y) * n[1] + (z - cell.z) * n[2]
        val centre = Vec3(x - distance * n[0], y - distance * n[1], z - distance * n[2])
        val u: Vec3
        val v: Vec3
        if (cell.kind == ScanCoverage.Kind.Wall) {
            val length = sqrt(n[0] * n[0] + n[2] * n[2])
            u = Vec3(n[2] / length, 0f, -n[0] / length) // up × normal
            v = Vec3(n[1] * u.z, n[2] * u.x - n[0] * u.z, -n[1] * u.x) // normal × u
        } else {
            u = Vec3(1f, 0f, 0f)
            v = Vec3(0f, 0f, 1f)
        }
        val half = cellM * when (tier) {
            1 -> FIRST_DIAMETER
            2 -> SECOND_DIAMETER
            3 -> DENSE_DIAMETER
            else -> FULL_SIDE
        } / 2f
        fun vertex(a: Float, b: Float): Int {
            val dx = u.x * a + v.x * b
            val dy = u.y * a + v.y * b
            val dz = u.z * a + v.z * b
            // World X/Z still follows a slightly tilted floor or ceiling.
            val offset = dx * n[0] + dy * n[1] + dz * n[2]
            return mesh.vertex(
                centre.x + dx - offset * n[0],
                centre.y + dy - offset * n[1],
                centre.z + dz - offset * n[2],
            )
        }
        if (tier == 4) {
            mesh.quad(vertex(-half, -half), vertex(half, -half), vertex(half, half), vertex(-half, half))
        } else {
            val middle = mesh.vertex(centre)
            val first = mesh.vertexCount
            repeat(HEX_SIDES) { side ->
                val angle = side * 2.0 * PI / HEX_SIDES
                vertex(cos(angle).toFloat() * half, sin(angle).toFloat() * half)
            }
            repeat(HEX_SIDES) { side -> mesh.triangle(middle, first + side, first + (side + 1) % HEX_SIDES) }
        }
    }

    private fun centre(value: Float, cellM: Float): Float = (floor(value / cellM) + 0.5f) * cellM
}
