package io.github.sceneview.demo.demos

import io.github.sceneview.demo.DEMO_FRAMING_FILL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.tan

/**
 * Pins the wall demo's "View in 3D" preview (#3864).
 *
 * The preview opened on what read as a black screen: the near-black TV sat at SceneView's stock
 * 2.78 m pose, a tenth of the square, on the default black skybox. These tests hold the two
 * things the fix depends on that a unit test can see: the framing distance, and the bright room
 * the TV is shown in actually shipping with the app.
 */
class WallTvPreviewTest {

    /** Vertical FOV of SceneView's default 28 mm lens. The preview is square, so it is also the horizontal one. */
    private val fov = io.github.sceneview.verticalFovDegreesForFocalLength(28.0)

    /** Share of the square preview's width the TV spans with the camera [distance] metres away. */
    private fun widthFill(distance: Float): Float {
        val halfFrame = distance * tan(Math.toRadians(fov) / 2.0).toFloat()
        return (wallTvExtent.x / 2f) / halfFrame
    }

    @Test
    fun `the stock camera pose left the TV a small rectangle in the middle`() {
        // What the bug report shows: "a tiny black rectangle in the middle".
        assertTrue(widthFill(2.78f) < 0.2f)
    }

    @Test
    fun `the preview frames the TV to fill the square`() {
        val fill = widthFill(wallTvPreviewOrbitRadius())
        assertTrue("The TV must fill most of the preview, filled $fill", fill >= 0.75f)
        assertTrue("The TV must not touch the preview's edges, filled $fill", fill <= DEMO_FRAMING_FILL + 0.01f)
    }

    @Test
    fun `the framed TV is the 0_3 m preview the sheet states`() {
        assertEquals(0.3f, wallTvExtent.x, 1e-6f)
        assertTrue(wallTvExtent.y < wallTvExtent.x)
        assertTrue(wallTvExtent.z < wallTvExtent.y)
    }

    @Test
    fun `the room the TV is shown in ships with the app`() {
        // rememberHDREnvironment returns null for a missing asset, and the preview would then
        // show its loading spinner forever.
        assertTrue(File("src/main/assets/$WALL_TV_PREVIEW_HDR").isFile)
    }
}
