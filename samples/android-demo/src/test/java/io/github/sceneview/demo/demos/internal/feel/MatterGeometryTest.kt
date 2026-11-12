package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class MatterGeometryTest {
    private val mesh = MatterMeshes.slab()

    @Test fun denseSoftTopAndRigidSilhouetteFitTheirVertexBudgets() {
        assertTrue(mesh.positions.size / 3 < 19000)
        val rigid = MatterMeshes.slab(rigid = true)
        assertTrue(rigid.positions.size / 3 < 5000)
        assertEquals(mesh.positions.min(), rigid.positions.min(), .00001f)
        for (i in rigid.positions.indices step 3) {
            assertEquals(0f, MatterMeshes.distance(rigid.positions[i], rigid.positions[i + 1],
                rigid.positions[i + 2]), .00001f)
        }
    }

    @Test fun atlasCoordinatesRecoverTheSameRoundedRestPositionOnEveryFace() {
        val point = FloatArray(3)
        for (i in mesh.uv0.indices step 2) {
            val u = mesh.uv0[i]; val v = mesh.uv0[i + 1]
            val chart = MatterDetailAtlas.charts.single {
                u >= it.u(0f) - .000001f && u <= it.u(1f) + .000001f &&
                    v >= it.v(0f) - .000001f && v <= it.v(1f) + .000001f
            }
            chart.point((u - chart.u(0f)) / (chart.u(1f) - chart.u(0f)),
                (v - chart.v(0f)) / (chart.v(1f) - chart.v(0f)), point)
            for (axis in 0..2) assertEquals(mesh.positions[i / 2 * 3 + axis], point[axis], .00001f)
        }
    }

    @Test fun denseTopHasAtLeast128By64FlatQuadsPlusASmoothBevel() {
        val top = mesh.positions.indices.step(3).filter { abs(mesh.positions[it + 1]) < .000001f }
        assertTrue(top.size >= 129 * 65)
        assertEquals(FeelSimulation.PAD_THICKNESS * .1f, MatterMeshes.BEVEL, .00001f)
        assertTrue(mesh.normals.indices.step(3).count {
            mesh.normals[it + 1] in .01f.. .99f
        } > 1000)
    }

    @Test fun verticesLieOnRoundedSolidWithFixedBottomAndNoSharpBoxCorners() {
        for (i in mesh.positions.indices step 3) {
            val x = mesh.positions[i]; val y = mesh.positions[i + 1]; val z = mesh.positions[i + 2]
            assertTrue(x.isFinite() && y.isFinite() && z.isFinite())
            assertEquals(0f, MatterMeshes.distance(x, y, z), .00001f)
            assertTrue(y >= -FeelSimulation.PAD_THICKNESS - .00001f && y <= .00001f)
            assertTrue(!(abs(x) >= FeelSimulation.PAD_HALF_WIDTH - .00001f && abs(y) < .00001f))
        }
        assertTrue(mesh.positions.indices.step(3).any {
            abs(mesh.positions[it + 1] + FeelSimulation.PAD_THICKNESS) < .00001f
        })
    }

    @Test fun packedFramesDecodeToUnitNormalsAndOrthogonalTangents() {
        val count = mesh.positions.size / 3
        assertEquals(count * 4, mesh.tangents.size)
        assertEquals(count * 2, mesh.uv0.size)
        repeat(count) { i ->
            val x = mesh.tangents[i * 4]; val y = mesh.tangents[i * 4 + 1]
            val z = mesh.tangents[i * 4 + 2]; val w = mesh.tangents[i * 4 + 3]
            assertEquals(1f, x*x + y*y + z*z + w*w, .00001f)
            assertTrue(w > 0f)
            val n = floatArrayOf(2f * (x*z + w*y), 2f * (y*z - w*x), 1f - 2f * (x*x + y*y))
            val t = floatArrayOf(1f - 2f * (y*y + z*z), 2f * (x*y + w*z), 2f * (x*z - w*y))
            assertEquals(0f, n.indices.sumOf { (n[it] * t[it]).toDouble() }.toFloat(), .00001f)
            assertEquals(1f, sqrt(n.sumOf { (it * it).toDouble() }).toFloat(), .00001f)
            for (axis in 0..2) assertEquals(mesh.normals[i * 3 + axis], n[axis], .0001f)
        }
    }

    @Test fun triangleWindingMatchesOutwardNormalsAndAllIndicesAreValid() {
        assertTrue(mesh.positions.size / 3 < 65536)
        assertTrue(mesh.indices.all { (it.toInt() and 0xffff) < mesh.positions.size / 3 })
        for (i in mesh.indices.indices step 3) {
            val a = (mesh.indices[i].toInt() and 0xffff) * 3
            val b = (mesh.indices[i + 1].toInt() and 0xffff) * 3
            val c = (mesh.indices[i + 2].toInt() and 0xffff) * 3
            val u = FloatArray(3) { mesh.positions[b + it] - mesh.positions[a + it] }
            val v = FloatArray(3) { mesh.positions[c + it] - mesh.positions[a + it] }
            val n = floatArrayOf(u[1]*v[2] - u[2]*v[1], u[2]*v[0] - u[0]*v[2], u[0]*v[1] - u[1]*v[0])
            assertTrue("inward triangle $i", n.indices.sumOf { (n[it] * mesh.normals[a + it]).toDouble() } > 0.0)
        }
    }
}
