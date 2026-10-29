package io.github.sceneview.demo.demos.internal

/**
 * The parts of a scan worth scanning again ([RescanZones]) as the result's 3D view and its plan
 * draw them (#4736): each zone's box as its twelve edges, thin square beams — a wall zone reads
 * as a frame standing on the wall, a floor zone as a frame lying on the floor, seen from the side
 * as from above.
 *
 * Plain geometry, built off the main thread; the layer uploads it on the main thread.
 */
object RescanZoneMesh {
    /** A beam's side, metres: thin enough to leave the scan readable, thick enough at room scale. */
    const val BEAM_M = 0.02f

    private const val EDGES = 12
    private const val FACES = 6
    private const val CORNERS = 8

    /** The triangles of one zone's frame: twelve beams of six faces. */
    const val TRIANGLES_PER_ZONE = EDGES * FACES * 2

    /** The frames of [zones], beams [beam] metres across. */
    fun of(zones: List<RescanZone>, beam: Float = BEAM_M): DebugMesh {
        val mesh = DebugMesh(zones.size * EDGES * CORNERS)
        val h = beam / 2f
        for (zone in zones) {
            val b = zone.box
            val x = floatArrayOf(b[0], b[3])
            val y = floatArrayOf(b[1], b[4])
            val z = floatArrayOf(b[2], b[5])
            for (j in 0..1) {
                for (k in 0..1) {
                    // Along x at (y[j], z[k]), along y at (x[j], z[k]), along z at (x[j], y[k]):
                    // each runs a half-beam past its ends, so the corners close.
                    cuboid(mesh, x[0] - h, y[j] - h, z[k] - h, x[1] + h, y[j] + h, z[k] + h)
                    cuboid(mesh, x[j] - h, y[0] - h, z[k] - h, x[j] + h, y[1] + h, z[k] + h)
                    cuboid(mesh, x[j] - h, y[k] - h, z[0] - h, x[j] + h, y[k] + h, z[1] + h)
                }
            }
        }
        return mesh
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
