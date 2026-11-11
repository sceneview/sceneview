package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MatterGeometryTest {
    @Test fun sectionCompressesWhileItsSupportedBottomStaysFixed() {
        val mesh = MatterMeshes.sides()
        mesh.deform(18f, 0f, 0f, FeelMaterial.Foam)
        var frontMoved = false
        for (i in mesh.rest.indices step 3) {
            val before = mesh.rest[i + 1]
            val after = mesh.positions[i + 1]
            assertTrue(after >= -FeelSimulation.PAD_THICKNESS - 0.0001f)
            if (before == -FeelSimulation.PAD_THICKNESS) assertEquals(before, after, 0f)
            if (before == 0f && mesh.rest[i + 2] == FeelSimulation.PAD_HALF_LENGTH && abs(mesh.rest[i]) < 0.01f) {
                frontMoved = true
                assertTrue(after < -0.20f)
            }
        }
        assertTrue(frontMoved)
    }

    @Test fun centreUsesVisualScaleAndSharedTopEdgesStayWatertight() {
        val top = MatterMeshes.top()
        val sides = MatterMeshes.sides()
        val ends = MatterMeshes.sides(ends = true)
        for (mesh in listOf(top, sides, ends)) mesh.deform(18f, 0f, 0f, FeelMaterial.Foam)
        val centre = top.rest.indices.step(3).first { abs(top.rest[it]) < 0.001f && abs(top.rest[it + 2]) < 0.001f }
        assertEquals(-MatterGeometry.visualDepth(18f, FeelMaterial.Foam), top.positions[centre + 1], 0.0001f)
        for (side in listOf(sides, ends)) for (i in side.rest.indices step 3) if (side.rest[i + 1] == 0f) {
            val matching = top.rest.indices.step(3).firstOrNull {
                abs(top.rest[it] - side.rest[i]) < 0.0001f && abs(top.rest[it + 2] - side.rest[i + 2]) < 0.0001f
            } ?: continue
            assertEquals(top.positions[matching + 1], side.positions[i + 1], 0.0001f)
        }
    }

    @Test fun everyMeshHasValidIndicesAndTopFacesFaceTheCamera() {
        for (mesh in listOf(MatterMeshes.top(), MatterMeshes.sides(), MatterMeshes.sides(ends = true), MatterMeshes.lattice(), MatterMeshes.contact(true), MatterMeshes.contact(false))) {
            assertTrue(mesh.positions.size / 3 < 65536)
            assertTrue(mesh.indices.all { (it.toInt() and 0xffff) < mesh.positions.size / 3 })
            assertTrue(mesh.positions.all { it.isFinite() })
        }
        for (mesh in listOf(MatterMeshes.top(), MatterMeshes.contact(true), MatterMeshes.contact(false))) {
            for (i in mesh.indices.indices step 3) {
                val a = mesh.indices[i].toInt() * 3
                val b = mesh.indices[i + 1].toInt() * 3
                val c = mesh.indices[i + 2].toInt() * 3
                val normalY = (mesh.rest[b + 2] - mesh.rest[a + 2]) * (mesh.rest[c] - mesh.rest[a]) -
                    (mesh.rest[b] - mesh.rest[a]) * (mesh.rest[c + 2] - mesh.rest[a + 2])
                assertTrue("back-facing triangle $i", normalY >= -0.000001f)
            }
        }
    }

    @Test fun edgeContactsStayOnTheBlockAndSteelIsRigid() {
        val disc = MatterMeshes.contact(true)
        MatterMeshes.updateContact(disc, 2f, FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_LENGTH, 0f, FeelMaterial.Steel)
        for (i in disc.positions.indices step 3) {
            assertTrue(FeelSimulation.contains(disc.positions[i], disc.positions[i + 2]))
            assertEquals(0.006f, disc.positions[i + 1], 0.00001f)
        }
        val top = MatterMeshes.top()
        top.deform(0f, 0f, 0f, FeelMaterial.Steel)
        assertTrue(top.rest.contentEquals(top.positions))
    }

    @Test fun projectedTopAndFrontHaveComparableHeight() {
        val top = 2f * FeelSimulation.PAD_HALF_LENGTH * MatterStage.SIN
        val front = FeelSimulation.PAD_THICKNESS * MatterStage.COS
        assertTrue(top / front in 1.2f..2f)
    }

    @Test fun rippleIsThinFadesAndClipsWithoutFoldingOntoTheEdges() {
        assertEquals(350L, MatterMeshes.RIPPLE_MILLIS)
        assertTrue(MatterMeshes.rippleRadius(0) < MatterMeshes.rippleRadius(175))
        assertTrue(MatterMeshes.rippleAlpha(0) > MatterMeshes.rippleAlpha(175))
        assertEquals(0f, MatterMeshes.rippleAlpha(350), 0f)
        // Steel acknowledges the contact locally; it never launches a block-wide wave.
        assertTrue(MatterMeshes.rippleRadius(350) < FeelMaterial.Steel.radius * 1.2f)
        val mesh = MatterMeshes.contact(false)
        val radius = 0.35f
        val width = 0.02f
        for (x in listOf(0f, FeelSimulation.PAD_HALF_WIDTH)) {
            MatterMeshes.updateContact(mesh, radius, x, 0f, 0f, FeelMaterial.Steel, width)
            var area = 0f
            for (i in mesh.indices.indices step 3) {
                val a = mesh.indices[i].toInt() * 3
                val b = mesh.indices[i + 1].toInt() * 3
                val c = mesh.indices[i + 2].toInt() * 3
                val normal = (mesh.positions[b + 2] - mesh.positions[a + 2]) * (mesh.positions[c] - mesh.positions[a]) -
                    (mesh.positions[b] - mesh.positions[a]) * (mesh.positions[c + 2] - mesh.positions[a + 2])
                assertTrue(normal >= -0.000001f)
                area += normal / 2f
                for (v in listOf(a, b, c)) {
                    val px = mesh.positions[v]
                    val pz = mesh.positions[v + 2]
                    assertTrue(FeelSimulation.contains(px, pz))
                    if (normal > 0.000001f) {
                        val distance = kotlin.math.sqrt((px - x) * (px - x) + pz * pz)
                        assertTrue(abs(distance - radius) <= width / MatterStage.SIN)
                    }
                }
            }
            assertTrue(area > 0f && area < 0.1f)
        }
    }

    @Test fun displayAmplificationDoesNotChangePhysicalReadouts() {
        val foam = FeelMaterial.Foam
        val oak = FeelMaterial.Oak
        val simulation = FeelSimulation()
        simulation.begin(foam, 0f, 0f, 1f, 0f, 0)
        for (now in 0L..3000L step 10) simulation.advance(now)
        val physical = simulation.pads[0]
        val visible = MatterGeometry.visualDepth(physical.depthMm, foam)
        assertTrue(visible > physical.depthMm * FeelMaterial.WORLD_PER_MM * 1.5f)
        assertTrue(visible > FeelSimulation.PAD_THICKNESS * 0.6f)
        MatterMeshes.top().deform(physical.depthMm, 0f, 0f, foam)
        assertEquals(physical, simulation.pads[0])
        assertTrue(MatterGeometry.visualDepth(oak.stopMm, oak) in 0.04f..0.1f)
        assertEquals(0f, MatterGeometry.visualDepth(24f, FeelMaterial.Steel), 0f)
        for (kind in FeelMaterial.entries) {
            assertTrue(MatterGeometry.visualDepth(1000f, kind) < FeelSimulation.PAD_THICKNESS)
        }
    }

    @Test fun bowlFollowsTheFingerAcrossBothTopAxesAndCompressesTheNearbySection() {
        val top = MatterMeshes.top()
        fun at(x: Float, z: Float): Int = top.rest.indices.step(3).minBy {
            val dx = top.rest[it] - x
            val dz = top.rest[it + 2] - z
            dx * dx + dz * dz
        }
        val depth = 18f
        for ((x, z) in listOf(-1.2f to -0.525f, 1.2f to 0.525f)) {
            top.deform(depth, x, z, FeelMaterial.Foam)
            val deepest = top.positions.indices.step(3).minBy { top.positions[it + 1] }
            assertEquals(x, top.positions[deepest], 0.001f)
            assertEquals(z, top.positions[deepest + 2], 0.001f)
            assertTrue(top.positions[at(0f, 0f) + 1] > top.positions[deepest + 1])
            val sides = MatterMeshes.sides()
            sides.deform(depth, x, z, FeelMaterial.Foam)
            val underFinger = sides.rest.indices.step(3).first {
                abs(sides.rest[it] - x) < 0.001f && sides.rest[it + 1] == 0f &&
                    sides.rest[it + 2] == FeelSimulation.PAD_HALF_LENGTH
            }
            val opposite = sides.rest.indices.step(3).first {
                abs(sides.rest[it] + x) < 0.001f && sides.rest[it + 1] == 0f &&
                    sides.rest[it + 2] == FeelSimulation.PAD_HALF_LENGTH
            }
            assertTrue(sides.positions[underFinger + 1] < sides.positions[opposite + 1])
        }
        top.deform(FeelMaterial.Oak.stopMm, 1.2f, 0.525f, FeelMaterial.Oak)
        val oakCentre = top.positions[at(1.2f, 0.525f) + 1]
        val oakShoulder = top.positions[at(1.8f, 0.525f) + 1]
        assertTrue(abs(oakShoulder / oakCentre) < 0.5f)
    }

    @Test fun contactStaysOnTheAmplifiedBowl() {
        val disc = MatterMeshes.contact(true)
        val depth = 18f
        val x = 1.2f
        val z = 0.525f
        MatterMeshes.updateContact(disc, 0.2f, x, z, depth, FeelMaterial.Foam)
        for (i in disc.positions.indices step 3) {
            val surface = -MatterGeometry.visualDepth(depth, FeelMaterial.Foam) * MatterGeometry.well(
                disc.positions[i] - x, disc.positions[i + 2] - z, FeelMaterial.Foam.radius)
            assertEquals(surface + 0.006f, disc.positions[i + 1], 0.00001f)
        }
    }

    @Test fun crispTopStaysEntirelyInsideThePrecompiledShadersUnfadedRegion() {
        // cosmos_spacetime.mat starts the grid fade at radius 2 and the surface fade at 3.
        // Test every actual vertex, both at rest and when pressed against a corner.
        val top = MatterMeshes.top()
        top.deform(FeelMaterial.Foam.stopMm, FeelSimulation.PAD_HALF_WIDTH,
            FeelSimulation.PAD_HALF_LENGTH, FeelMaterial.Foam)
        for (i in top.positions.indices step 3) {
            val shaderX = top.positions[i] * MatterMeshes.SHADER_XZ_SCALE
            val shaderZ = top.positions[i + 2] * MatterMeshes.SHADER_XZ_SCALE
            assertTrue(shaderX * shaderX + shaderZ * shaderZ < 4f)
            assertEquals(top.rest[i], shaderX / MatterMeshes.SHADER_XZ_SCALE, 0.00001f)
            assertEquals(top.rest[i + 2], shaderZ / MatterMeshes.SHADER_XZ_SCALE, 0.00001f)
        }
        val lattice = MatterMeshes.lattice()
        assertTrue(lattice.rest.indices.step(3).any {
            abs(lattice.rest[it] - FeelSimulation.PAD_HALF_WIDTH) < 0.01f &&
                abs(lattice.rest[it + 2] - FeelSimulation.PAD_HALF_LENGTH) < 0.01f
        })
    }
}
