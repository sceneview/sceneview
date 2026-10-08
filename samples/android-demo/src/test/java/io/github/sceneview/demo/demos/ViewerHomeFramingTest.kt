package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.math.Size
import io.github.sceneview.verticalFovDegreesForFocalLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

/**
 * The Model Viewer's two home framings (#4326): upright the camera aims past the model to lift it
 * into the band between the title and the dock; on a phone held sideways that band is handed to
 * the SDK as `contentPadding` and the model is framed for it alone.
 */
class ViewerHomeFramingTest {

    // The Fox, in glTF units.
    private val fox = Size(26f, 79f, 155f)

    private fun framing(restingAspect: Float?, bottomInset: Float = 300f) = viewerHomeFraming(
        extents = fox,
        viewportWidth = 914f,
        viewportHeight = 411f,
        topInset = 80f,
        bottomInset = bottomInset,
        restingAspect = restingAspect,
    )

    @Test
    fun `sideways the camera aims at the model itself`() {
        // The free area is the whole frame as far as the lens is concerned: nothing to aim around.
        framing(restingAspect = 866f / 141f).targetOffset.toList().forEach { assertEquals(0f, it, 0f) }
    }

    @Test
    fun `sideways the framing depends on the free area's aspect and on nothing a sheet moves`() {
        val atRest = framing(restingAspect = 866f / 141f, bottomInset = 190f)
        val underASheet = framing(restingAspect = 866f / 141f, bottomInset = 330f)
        assertEquals(atRest, underASheet)
    }

    @Test
    fun `sideways the model spans the viewer's fill of the band's height`() {
        val fov = verticalFovDegreesForFocalLength(28.0)
        val distance = framing(restingAspect = 866f / 141f).distance
        val depthAllowance = fox.z * DemoMath.DEPTH_ALLOWANCE
        val spanned = (fox.y / 2f) / ((distance - depthAllowance) * tan(Math.toRadians(fov) / 2.0).toFloat())
        assertEquals(DemoMath.VIEWER_FILL, spanned, 0.001f)
    }

    @Test
    fun `a narrower free area puts the camera further back`() {
        // Width-limited: a long model in a band barely wider than tall.
        val long = Size(155f, 79f, 26f)
        fun distance(aspect: Float) = viewerHomeFraming(
            extents = long, viewportWidth = 0f, viewportHeight = 0f,
            topInset = 0f, bottomInset = 0f, restingAspect = aspect,
        ).distance
        assertTrue(distance(1.2f) > distance(6f))
    }

    @Test
    fun `upright the camera aims past the model to lift it above the dock`() {
        val framing = framing(restingAspect = null)
        // More covered at the bottom than at the top: the look-at point goes below the model's
        // centre, which draws the model above the window's.
        assertTrue(framing.targetOffset.second < 0f)
    }

    @Test
    fun `upright the camera backs away as the band between the insets shrinks`() {
        val underTheDock = framing(restingAspect = null, bottomInset = 120f)
        val underASheet = framing(restingAspect = null, bottomInset = 300f)
        assertTrue(underASheet.distance > underTheDock.distance)
    }

    @Test
    fun `the Lighting sheet covers the scene where it is taller than the dock`() {
        val chrome = PaddingValues(top = 24.dp, bottom = 96.dp)
        val closed = viewerSceneCover(chrome, lightingCover = 0.dp)
        assertEquals(24.dp, closed.calculateTopPadding())
        assertEquals(96.dp, closed.calculateBottomPadding())
        val open = viewerSceneCover(chrome, lightingCover = 205.dp)
        assertEquals(24.dp, open.calculateTopPadding())
        assertEquals(205.dp, open.calculateBottomPadding())
    }
}
