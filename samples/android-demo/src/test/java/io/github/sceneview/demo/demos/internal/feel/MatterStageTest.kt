package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MatterStageTest {
    private fun stage(width: Int = 1080, height: Int = 1830, caption: Int = 84) = MatterStage().apply {
        widthPx = width
        heightPx = height
        captionPx.fill(caption)
        frame()
    }

    private fun world(stage: MatterStage, row: Int, x: Float, y: Float, z: Float): FloatArray =
        MatterStage.rotate(x, y, z, stage.tilt[row]).let {
            floatArrayOf(stage.x[row] + it[0] * stage.scale[row],
                stage.y[row] + it[1] * stage.scale[row], stage.z[row] + it[2] * stage.scale[row])
        }

    private fun project(stage: MatterStage, p: FloatArray): Pair<Float, Float> {
        val right = p[0] * MatterStage.COS_AZIMUTH - p[2] * MatterStage.SIN_AZIMUTH
        val up = p[0] * MatterStage.UP_X + p[1] * MatterStage.COS + p[2] * MatterStage.UP_Z
        val depth = MatterStage.DISTANCE -
            (p[0] * MatterStage.BACK_X + p[1] * MatterStage.SIN + p[2] * MatterStage.BACK_Z)
        val pixels = stage.widthPx / (stage.halfWidth * 2f) * MatterStage.DISTANCE / depth
        return stage.widthPx / 2f + right * pixels to stage.heightPx / 2f - up * pixels
    }

    @Test fun allThreeSlabsFillTheirRowsWithoutCoveringCaptions() {
        // The real 1080×2340 phone leaves a smaller measured viewport after system/app bars.
        for ((width, height, caption) in listOf(Triple(1080, 1830, 84), Triple(360, 540, 28), Triple(360, 540, 96))) {
            val stage = stage(width, height, caption)
            val rowHeight = height / 3f
            for (row in 0..2) {
                val points = mutableListOf<Pair<Float, Float>>()
                for (x in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                    for (y in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                        for (z in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                            val p = project(stage, world(stage, row, x, y, z))
                            points += p
                            assertTrue(p.first in 0f..width.toFloat())
                            assertTrue(p.second >= row * rowHeight)
                            assertTrue(p.second <= (row + 1) * rowHeight - caption)
                        }
                val occupied = points.maxOf { it.second } - points.minOf { it.second }
                assertTrue("row $row only occupies $occupied px", occupied > (rowHeight - caption) * 0.8f)
            }
            assertFalse(stage.frame())
            stage.captionPx[0] += 12
            assertTrue(stage.frame())
        }
    }

    @Test fun eachRowShowsTopFrontAndRightSideWithConvergingLattice() {
        val stage = stage()
        for (row in 0..2) {
            val eye = MatterStage.rotate(MatterStage.DISTANCE * MatterStage.BACK_X - stage.x[row],
                MatterStage.DISTANCE * MatterStage.SIN - stage.y[row],
                MatterStage.DISTANCE * MatterStage.BACK_Z - stage.z[row], -stage.tilt[row])
            assertTrue(eye[0] > FeelSimulation.PAD_HALF_WIDTH * stage.scale[row])
            assertTrue(eye[1] > 0f)
            assertTrue(eye[2] > FeelSimulation.PAD_HALF_LENGTH * stage.scale[row])
            val elevation = eye[1] / hypot(eye[0], eye[2])
            assertTrue("row $row must be viewed from above", elevation in 0.65f..1.15f)
            fun width(z: Float): Float {
                val a = project(stage, world(stage, row, -FeelSimulation.PAD_HALF_WIDTH, 0f, z))
                val b = project(stage, world(stage, row, FeelSimulation.PAD_HALF_WIDTH, 0f, z))
                return hypot(b.first - a.first, b.second - a.second)
            }
            assertTrue("row $row needs visible perspective convergence",
                width(FeelSimulation.PAD_HALF_LENGTH) > width(-FeelSimulation.PAD_HALF_LENGTH) * 1.05f)
        }
    }

    @Test fun exaggeratedFoamDipIsVisibleAtPhoneResolution() {
        val stage = stage()
        val resting = project(stage, world(stage, 0, 0f, 0f, 0f))
        val pressed = project(stage, world(stage, 0, 0f, -MatterGeometry.visualDepth(18f, FeelMaterial.Foam), 0f))
        assertTrue("foam should sink by tens of pixels", pressed.second - resting.second > 60f)
    }

    @Test fun touchRayFindsOffCentreDentAfterRowTranslationScaleAndTilt() {
        val stage = stage()
        val ox = MatterStage.DISTANCE * MatterStage.BACK_X
        val oy = MatterStage.DISTANCE * MatterStage.SIN
        val oz = MatterStage.DISTANCE * MatterStage.BACK_Z
        for (row in 0..2) for ((x, z) in listOf(-1.2f to -0.525f, 1.2f to 0.525f)) {
            val depth = MatterGeometry.visualDepth(18f, FeelMaterial.Foam)
            val p = world(stage, row, x, -depth, z)
            val hit = stage.pointOnPad(row, ox, oy, oz, p[0] - ox, p[1] - oy, p[2] - oz, depth)!!
            assertEquals(x, hit.first, 0.0001f)
            assertEquals(z, hit.second, 0.0001f)
        }
    }
}
