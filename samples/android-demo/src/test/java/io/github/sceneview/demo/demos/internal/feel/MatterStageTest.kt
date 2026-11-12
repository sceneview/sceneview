package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatterStageTest {
    private fun stage(width: Int = 1080, height: Int = 1600) = MatterStage().apply {
        widthPx = width; heightPx = height; frame()
    }
    private fun project(stage: MatterStage, x: Float, y: Float, z: Float): Pair<Float, Float> {
        val cy = y - stage.targetY
        val right = x * MatterStage.COS_AZIMUTH - z * MatterStage.SIN_AZIMUTH
        val up = x * MatterStage.UP_X + cy * MatterStage.COS + z * MatterStage.UP_Z
        val depth = stage.distance - (x * MatterStage.BACK_X + cy * MatterStage.SIN + z * MatterStage.BACK_Z)
        val pixels = stage.widthPx / (stage.halfWidth * 2f) * stage.distance / depth
        return stage.widthPx / 2f + right * pixels to stage.heightPx / 2f - up * pixels
    }
    private fun hit(stage: MatterStage, x: Float, y: Float, z: Float) = stage.pointOnSlab(
        stage.eyeX, stage.eyeY, stage.eyeZ, x - stage.eyeX, y - stage.eyeY, z - stage.eyeZ)

    @Test fun heroFillsAbout75PercentOfPortraitWidthAndFitsShortWindows() {
        val mesh = MatterMeshes.slab()
        for ((width, height) in listOf(1080 to 1600, 360 to 400, 900 to 300)) {
            val stage = stage(width, height)
            val points = mesh.positions.indices.step(3).map {
                project(stage, mesh.positions[it], mesh.positions[it + 1], mesh.positions[it + 2])
            }
            for (p in points) {
                assertTrue(p.first in 0f..width.toFloat())
                assertTrue(p.second in 0f..height.toFloat())
            }
            if (height > width) {
                val occupied = (points.maxOf { it.first } - points.minOf { it.first }) / width
                assertTrue("hero width is $occupied", occupied in .74f.. .76f)
            }
            val centreY = (points.maxOf { it.second } + points.minOf { it.second }) / 2f
            assertEquals("silhouette centres with room for its contact shadow", height * .48f, centreY, height * .012f)
            if (width == 1080) {
                assertEquals(30f, MatterStage.ELEVATION, 0f)
                assertTrue("dent view is more top-down", stage.eyeY / stage.distance > .30f)
            }
            assertFalse(stage.frame())
            stage.heightPx += 40
            assertTrue(stage.frame())
        }
    }

    @Test fun roundedSilhouettePicksTopFrontAndSideButRejectsEmptyBevelCorner() {
        val stage = stage()
        val w = FeelSimulation.PAD_HALF_WIDTH; val l = FeelSimulation.PAD_HALF_LENGTH
        for ((x, y, z) in listOf(Triple(-.8f, 0f, -.4f), Triple(.5f, -.5f, l), Triple(w, -.5f, -.2f))) {
            val point = hit(stage, x, y, z)!!
            assertEquals(x, point.first, .001f)
            assertEquals(z, point.second, .001f)
        }
        assertNull(stage.pointOnSlab(w - .002f, 2f, l - .002f, 0f, -1f, 0f))
        assertNull(hit(stage, w + .5f, 0f, 0f))
        assertNull(stage.pointOnSlab(0f, 1f, 0f, 0f, 1f, 0f))
        assertNull(stage.pointOnSlab(0f, 1f, 0f, 1f, 0f, 0f))
        assertNull(stage.pointOnSlab(0f, 1f, 0f, 0f, 0f, 0f))
    }

    @Test fun capturedRayFollowsTheDentIncludingSignedGelRebound() {
        val stage = stage()
        for (depth in listOf(-.12f, 0f, .65f)) for ((x, z) in listOf(-1.2f to -.5f, 1.2f to .5f)) {
            val point = stage.pointOnPad(stage.eyeX, stage.eyeY, stage.eyeZ,
                x - stage.eyeX, -depth - stage.eyeY, z - stage.eyeZ, depth)!!
            assertEquals(x, point.first, .00001f)
            assertEquals(z, point.second, .00001f)
        }
    }

    @Test fun dentIsVisibleAtPhoneResolutionAndOpeningLiftsOutOfTheViewport() {
        val stage = stage()
        val rest = project(stage, 0f, 0f, 0f)
        val pressed = project(stage, 0f, -MatterSurfaces.visualDepth(3f, FeelMaterial.MemoryFoam), 0f)
        assertTrue(pressed.second - rest.second > 45f)
        assertFalse(MatterOpening.SHOW_BRACKETS)
        for ((width, height) in listOf(1080 to 1600, 360 to 1200, 900 to 300)) {
            val framed = stage(width, height)
            val bottom = project(framed, 0f, framed.openingTravel - FeelSimulation.PAD_THICKNESS, 0f)
            assertTrue("mark clears top at $width x $height", bottom.second <= .001f)
        }
    }
}
