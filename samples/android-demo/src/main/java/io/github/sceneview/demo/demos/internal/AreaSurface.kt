package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How much of an outdoor area's scan its surface holds ([ScanScope.Area]). Outdoors the points
 * are thin and far apart, and the surface built from them comes out in pieces: on the first
 * areas scanned by hand (2026-11-14 and 15) the model was a few scraps of ground where the
 * points drew the whole terrace. The result then stays on the points, and says so.
 *
 * The measure is ground against ground: the level part of the model's surface ([levelM2])
 * against the ground the scan's points cover ([groundM2]).
 */
object AreaSurface {
    /** The area of [mesh]'s triangles that lie level enough to be ground, m². */
    fun levelM2(mesh: RerunMesh): Float {
        val p = mesh.positions
        var total = 0.0
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.indices[t * 3] * 3
            val b = mesh.indices[t * 3 + 1] * 3
            val c = mesh.indices[t * 3 + 2] * 3
            val ux = p[b] - p[a]
            val uy = p[b + 1] - p[a + 1]
            val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]
            val vy = p[c + 1] - p[a + 1]
            val vz = p[c + 2] - p[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val twice = sqrt(nx * nx + ny * ny + nz * nz)
            if (twice > 0f && abs(ny) >= twice * LEVEL_NORMAL_Y) total += twice / 2.0
        }
        return total.toFloat()
    }

    /** The ground [ground]'s scan covers, m². */
    fun groundM2(ground: ScanGround): Float = ground.seen * ground.cellM * ground.cellM

    /**
     * Whether a model holding [modelM2] of ground is in pieces beside the [scannedM2] its scan
     * covers: under [MIN_SHARE] of it. `false` for a scan with no ground to compare it to.
     */
    fun inPieces(modelM2: Float, scannedM2: Float): Boolean = scannedM2 > 0f && modelM2 < scannedM2 * MIN_SHARE

    /** A triangle within about 45° of level is ground: a slope and a step's tread are, a wall is not. */
    const val LEVEL_NORMAL_Y = 0.7f

    /** A surface holding under half the ground its scan covers does not stand for the place. */
    const val MIN_SHARE = 0.5f
}
