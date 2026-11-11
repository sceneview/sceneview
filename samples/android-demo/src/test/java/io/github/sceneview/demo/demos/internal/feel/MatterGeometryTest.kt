package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MatterGeometryTest {
    @Test fun sectionCompressesWhileItsSupportedBottomStaysFixed() {
        val mesh = MatterMeshes.sides()
        mesh.deform(18f, 0f, 0f, FeelMaterial.Foam.radius)
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

    @Test fun centreDepthAndSharedTopEdgeAgreeWithPhysics() {
        val top = MatterMeshes.top()
        val sides = MatterMeshes.sides()
        for (mesh in listOf(top, sides)) mesh.deform(18f, 0f, 0f, FeelMaterial.Foam.radius)
        val centre = top.rest.indices.step(3).first { abs(top.rest[it]) < 0.001f && abs(top.rest[it + 2]) < 0.001f }
        assertEquals(-18f * FeelMaterial.WORLD_PER_MM, top.positions[centre + 1], 0.0001f)
        for (i in sides.rest.indices step 3) if (sides.rest[i + 1] == 0f) {
            val matching = top.rest.indices.step(3).firstOrNull {
                abs(top.rest[it] - sides.rest[i]) < 0.0001f && abs(top.rest[it + 2] - sides.rest[i + 2]) < 0.0001f
            } ?: continue
            assertEquals(top.positions[matching + 1], sides.positions[i + 1], 0.0001f)
        }
    }

    @Test fun everyMeshHasValidIndicesAndTopFacesFaceTheCamera() {
        for (mesh in listOf(MatterMeshes.top(), MatterMeshes.sides(), MatterMeshes.lattice(), MatterMeshes.contact(true), MatterMeshes.contact(false))) {
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
        top.deform(0f, 0f, 0f, FeelMaterial.Steel.radius)
        assertTrue(top.rest.contentEquals(top.positions))
    }

    @Test fun projectedTopAndFrontHaveComparableHeight() {
        val top = 2f * FeelSimulation.PAD_HALF_LENGTH * MatterStage.SIN
        val front = FeelSimulation.PAD_THICKNESS * MatterStage.COS
        assertTrue(top / front in 0.75f..1.5f)
    }

    @Test fun rippleIsThinFadesAndClipsWithoutFoldingOntoTheEdges() {
        assertEquals(350L, MatterMeshes.RIPPLE_MILLIS)
        assertTrue(MatterMeshes.rippleRadius(0) < MatterMeshes.rippleRadius(175))
        assertTrue(MatterMeshes.rippleAlpha(0) > MatterMeshes.rippleAlpha(175))
        assertEquals(0f, MatterMeshes.rippleAlpha(350), 0f)
        val reach = kotlin.math.sqrt(FeelSimulation.PAD_HALF_WIDTH * FeelSimulation.PAD_HALF_WIDTH +
            FeelSimulation.PAD_HALF_LENGTH * FeelSimulation.PAD_HALF_LENGTH)
        assertEquals(reach, MatterMeshes.rippleRadius(350), 0.00001f)
        assertTrue(MatterMeshes.rippleRadius(350, FeelSimulation.PAD_HALF_WIDTH) > reach)
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

    @Test fun portraitRowsReserveCaptionsAndFillTheWidth() {
        val stage = MatterStage().apply {
            widthPx = 360
            heightPx = 540
            captionPx.fill(48)
            frame()
        }
        assertEquals(0.9f, FeelSimulation.PAD_HALF_WIDTH / stage.halfWidth, 0.001f)
        assertTrue(stage.scale.all { it == 1f })
        for (i in 0..2) {
            val up = stage.y[i] * MatterStage.COS - stage.z[i] * MatterStage.SIN
            val bottom = up - (FeelSimulation.PAD_HALF_LENGTH * MatterStage.SIN + FeelSimulation.PAD_THICKNESS * MatterStage.COS) * stage.scale[i]
            val captionTop = stage.halfHeight - (i + 1) * stage.halfHeight * 2f / 3f + 48f * stage.halfWidth * 2f / 360f
            assertTrue(bottom >= captionTop)
        }
        stage.captionPx.fill(96)
        stage.frame()
        assertTrue(stage.scale.all { it in 0f..1f })
    }
}
