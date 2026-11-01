package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.DenseCloud
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The Photo layer's faces ([SplatFaces]): from outside, a room's ceiling and near walls go, as the model's. */
class SplatFacesTest {
    @Test
    fun `seen from above a corner, the ceiling and the two near walls go, the floor and far walls stay`() {
        for (yawDegrees in listOf(0f, 30f, -55f)) {
            val room = room(yawDegrees)
            val faces = SplatFaces.of(SurfelSplats.of(room.cloud))
            // Up and out past the room's corner at its low u and low v: the dollhouse view.
            val (ex, ez) = toWorld(yawDegrees, -3f, -3f)
            val drawn = faces.cloud(faces.visible(ex, 4f, ez))

            val kept = room.kinds.indices.filter { i -> contains(drawn, room.cloud.positions, i) }.map { room.kinds[it] }.toSet()
            assertEquals("faces drawn at $yawDegrees°", setOf(Kind.Floor, Kind.FarU, Kind.FarV), kept)
        }
    }

    @Test
    fun `from above the middle every wall stays and only the ceiling goes`() {
        val room = room(20f)
        val faces = SplatFaces.of(SurfelSplats.of(room.cloud))
        val (ex, ez) = toWorld(20f, 0f, 0f)
        val drawn = faces.cloud(faces.visible(ex, 6f, ez))
        val kept = room.kinds.indices.filter { contains(drawn, room.cloud.positions, it) }.map { room.kinds[it] }.toSet()
        assertEquals(Kind.entries.toSet() - Kind.Ceiling, kept)
    }

    @Test
    fun `splats with no normal are drawn from everywhere, and every face is the whole cloud`() {
        val cloud = DenseCloud(
            positions = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f),
            colors = intArrayOf(0xFF808080.toInt(), 0xFF808080.toInt()),
        )
        val splats = SurfelSplats.of(cloud)
        val faces = SplatFaces.of(splats)
        assertEquals(2, faces.cloud(faces.visible(0f, -10f, 0f)).count)
        assertSame(splats, faces.cloud(SplatFaces.ALL))
    }

    @Test
    fun `a splat's normal is its rotation applied to +Z`() {
        val q = FloatArray(4)
        val n = FloatArray(3)
        SurfelSplats.rotationToNormal(0.3f, -0.8f, 0.52f, q, 0)
        SplatFaces.normalOf(q, 0, n, 0)
        val len = kotlin.math.sqrt(0.3f * 0.3f + 0.8f * 0.8f + 0.52f * 0.52f)
        assertEquals(0.3f / len, n[0], 1e-5f)
        assertEquals(-0.8f / len, n[1], 1e-5f)
        assertEquals(0.52f / len, n[2], 1e-5f)
    }

    private enum class Kind { Floor, Ceiling, NearU, FarU, NearV, FarV }

    private class Room(val cloud: DenseCloud, val kinds: List<Kind>)

    /**
     * A 4 × 3 m room, 2.6 m high, turned [yawDegrees] about Y: its six surfaces as surfels every
     * 20 cm with inward normals (as a scan fuses them), no two at the same spot.
     */
    private fun room(yawDegrees: Float): Room {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val kinds = ArrayList<Kind>()
        fun add(kind: Kind, u: Float, y: Float, v: Float, nu: Float, ny: Float, nv: Float) {
            val (x, z) = toWorld(yawDegrees, u, v)
            val (nx, nz) = toWorld(yawDegrees, nu, nv)
            positions += listOf(x, y, z)
            normals += listOf(nx, ny, nz)
            kinds += kind
        }
        val us = (0..20).map { -2f + it * 0.2f }
        val vs = (0..15).map { -1.5f + it * 0.2f }
        val ys = (0..12).map { 0.1f + it * 0.2f }
        val wallUs = (0..18).map { -1.8f + it * 0.2f }
        val wallVs = (0..13).map { -1.3f + it * 0.2f }
        for (u in us) for (v in vs) {
            add(Kind.Floor, u, 0f, v, 0f, 1f, 0f)
            add(Kind.Ceiling, u, 2.6f, v, 0f, -1f, 0f)
        }
        for (v in wallVs) for (y in ys) {
            add(Kind.NearU, -2f, y, v, 1f, 0f, 0f)
            add(Kind.FarU, 2f, y, v, -1f, 0f, 0f)
        }
        for (u in wallUs) for (y in ys) {
            add(Kind.NearV, u, y, -1.5f, 0f, 0f, 1f)
            add(Kind.FarV, u, y, 1.5f, 0f, 0f, -1f)
        }
        val count = kinds.size
        return Room(
            DenseCloud(
                positions = positions.toFloatArray(),
                colors = IntArray(count) { 0xFF808080.toInt() },
                normals = normals.toFloatArray(),
            ),
            kinds,
        )
    }

    private fun toWorld(yawDegrees: Float, u: Float, v: Float): Pair<Float, Float> {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        return (u * c - v * s) to (u * s + v * c)
    }

    private fun contains(drawn: io.github.sceneview.core.splat.SplatCloud, all: FloatArray, i: Int): Boolean {
        for (k in 0 until drawn.count) {
            if (drawn.positions[k * 3] == all[i * 3] &&
                drawn.positions[k * 3 + 1] == all[i * 3 + 1] &&
                drawn.positions[k * 3 + 2] == all[i * 3 + 2]
            ) {
                return true
            }
        }
        return false
    }
}
