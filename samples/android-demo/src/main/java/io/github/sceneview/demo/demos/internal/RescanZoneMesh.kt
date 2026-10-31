package io.github.sceneview.demo.demos.internal

import kotlin.math.roundToInt

/**
 * The parts of a scan worth scanning again ([RescanZones]) as the result's 3D views draw them
 * (#4736, #4735): each zone's box as a dashed frame — its twelve edges cut into short square
 * dashes, closed at the corners — over a translucent veil of the box itself. A wall zone reads as
 * a dashed panel standing on the wall, a floor zone as one lying on the floor: a part marked "not
 * scanned yet", not a glitch on the model.
 *
 * Plain geometry, built off the main thread; the layer uploads it on the main thread.
 */
object RescanZoneMesh {
    /** A dash's side, metres: thin enough to leave the scan readable, thick enough at room scale. */
    const val BEAM_M = 0.02f

    /** A dash's length along its edge, metres: about the plan's own dash, at room scale. */
    const val DASH_M = 0.12f

    /** The gap between two dashes, metres. */
    const val GAP_M = 0.08f

    private const val EDGES = 12
    private const val FACES = 6
    private const val CORNERS = 8

    /** The triangles of one dash: a beam of six faces. */
    const val TRIANGLES_PER_DASH = FACES * 2

    /** The triangles of one zone's veil: its box, six faces. */
    const val TRIANGLES_PER_VEIL = FACES * 2

    /** How many dashes an edge [length] metres long is cut into: at least one. */
    fun dashes(length: Float): Int = ((length + GAP_M) / (DASH_M + GAP_M)).roundToInt().coerceAtLeast(1)

    /** The dashed frames of [zones], dashes [beam] metres across. */
    fun of(zones: List<RescanZone>, beam: Float = BEAM_M): DebugMesh {
        val mesh = DebugMesh(zones.size * EDGES * CORNERS * 4)
        val h = beam / 2f
        for (zone in zones) {
            val b = zone.box
            val x = floatArrayOf(b[0], b[3])
            val y = floatArrayOf(b[1], b[4])
            val z = floatArrayOf(b[2], b[5])
            for (j in 0..1) {
                for (k in 0..1) {
                    // Along x at (y[j], z[k]), along y at (x[j], z[k]), along z at (x[j], y[k]).
                    run(x[0], x[1], h) { from, to -> cuboid(mesh, from, y[j] - h, z[k] - h, to, y[j] + h, z[k] + h) }
                    run(y[0], y[1], h) { from, to -> cuboid(mesh, x[j] - h, from, z[k] - h, x[j] + h, to, z[k] + h) }
                    run(z[0], z[1], h) { from, to -> cuboid(mesh, x[j] - h, y[k] - h, from, x[j] + h, y[k] + h, to) }
                }
            }
        }
        return mesh
    }

    /** The translucent boxes under [zones]' frames: each zone's box, closed. */
    fun veil(zones: List<RescanZone>): DebugMesh {
        val mesh = DebugMesh(zones.size * CORNERS)
        for (zone in zones) {
            val b = zone.box
            cuboid(mesh, b[0], b[1], b[2], b[3], b[4], b[5])
        }
        return mesh
    }

    /**
     * Cuts the edge from [start] to [end] into its [dashes], each handed to [dash] as its two ends.
     * The run reaches a half-beam [h] past both ends, its first and last dash on them, so every
     * corner is closed by the three edges that meet there.
     */
    private inline fun run(start: Float, end: Float, h: Float, dash: (Float, Float) -> Unit) {
        val n = dashes(end - start)
        val span = end - start + 2 * h
        val length = span * DASH_M / (n * DASH_M + (n - 1) * GAP_M)
        val step = length * (DASH_M + GAP_M) / DASH_M
        val from = start - h
        for (i in 0 until n) {
            val at = from + i * step
            // The last dash ends on the run's end, whatever the rounding.
            dash(at, if (i == n - 1) end + h else at + length)
        }
    }

    /** An axis-aligned box from its two corners: six faces, drawn from both sides by the layer. */
    private fun cuboid(mesh: DebugMesh, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        val a = mesh.vertex(x0, y0, z0)
        val b = mesh.vertex(x1, y0, z0)
        val c = mesh.vertex(x1, y0, z1)
        val d = mesh.vertex(x0, y0, z1)
        val e = mesh.vertex(x0, y1, z0)
        val f = mesh.vertex(x1, y1, z0)
        val g = mesh.vertex(x1, y1, z1)
        val i = mesh.vertex(x0, y1, z1)
        mesh.quad(a, b, c, d)
        mesh.quad(e, i, g, f)
        mesh.quad(a, e, f, b)
        mesh.quad(b, f, g, c)
        mesh.quad(c, g, i, d)
        mesh.quad(d, i, e, a)
    }
}
