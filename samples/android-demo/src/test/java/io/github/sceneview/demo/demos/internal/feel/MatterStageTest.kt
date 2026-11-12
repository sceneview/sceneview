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
        val right = (x - stage.targetX) * MatterStage.COS_AZIMUTH -
            (z - stage.targetZ) * MatterStage.SIN_AZIMUTH
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
            assertEquals("silhouette centres in the visible scene", height * .5f, centreY, height * .012f)
            if (width == 1080) {
                assertEquals(30f, MatterStage.ELEVATION, 0f)
                assertTrue("dent view is more top-down", stage.eyeY / stage.distance > .30f)
            }
            assertFalse(stage.frame())
            stage.heightPx += 40
            assertTrue(stage.frame())
        }
    }

    @Test fun pixel4aFramesInTheNativeSurfaceAboveTheMeasuredPanel() {
        // adb layout + SurfaceFlinger, v15 on Pixel 4a: full window is NOT the viewport.
        // SceneView buffer/displayFrame = [0,356]..[1080,1919]; no scaling or panel overlap.
        val surfaceWidth = 1080
        val surfaceHeight = 2340
        val topCover = 356
        val bottomInset = 132
        val panelHeight = 289 // FeelControls bounds: [0,1919]..[1080,2208].
        val visibleHeight = surfaceHeight - topCover - panelHeight - bottomInset
        assertEquals(1563, visibleHeight)
        val stage = stage(surfaceWidth, visibleHeight)
        val mesh = MatterMeshes.slab()
        // Independent pinhole projection with an explicitly HORIZONTAL 48 degree FOV.
        // Feeding 2340 as height, or treating 48 as vertical, must not satisfy this test.
        val tanHorizontal = kotlin.math.tan(Math.toRadians(48.0 / 2.0))
        val tanVertical = tanHorizontal * stage.heightPx / stage.widthPx
        val points = mesh.positions.indices.step(3).map {
            val x = mesh.positions[it] - stage.targetX
            val y = mesh.positions[it + 1] - stage.targetY
            val z = mesh.positions[it + 2] - stage.targetZ
            val depth = stage.distance - x * MatterStage.BACK_X - y * MatterStage.SIN - z * MatterStage.BACK_Z
            val right = x * MatterStage.COS_AZIMUTH - z * MatterStage.SIN_AZIMUTH
            val up = x * MatterStage.UP_X + y * MatterStage.COS + z * MatterStage.UP_Z
            stage.widthPx * (.5 + right / (2 * depth * tanHorizontal)) to
                topCover + stage.heightPx * (.5 - up / (2 * depth * tanVertical))
        }
        val left = points.minOf { it.first }; val right = points.maxOf { it.first }
        val top = points.minOf { it.second }; val bottom = points.maxOf { it.second }
        assertEquals("75% of 1080, not of the full-window height", 810.0, right - left, 12.0)
        assertEquals("horizontal silhouette centre", 540.0, (left + right) / 2, 3.0)
        assertEquals("centre of [356,1919] above controls", 1137.5, (top + bottom) / 2, 6.0)
        assertTrue(bottom < surfaceHeight - panelHeight - bottomInset)
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
