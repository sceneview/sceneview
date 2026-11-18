package io.github.sceneview

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.viewToRay
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportCoordinatesTest {
    @Test
    fun asymmetricPaddingUsesBottomLeftOrigin() {
        assertEquals(
            ViewportCoordinates(1080, 2400, 80, 600, 880, 1600),
            clippedViewport(1080, 2400, ViewportPadding(80f, 200f, 120f, 600f)),
        )
    }

    @Test
    fun zeroPaddingRestoresTheFullSurface() {
        assertEquals(ViewportCoordinates(1080, 2400, 0, 0, 1080, 2400),
            clippedViewport(1080, 2400, ViewportPadding.Zero))
    }

    @Test
    fun overCoveredAndUnsizedSurfacesRetainOnePixelInsideTheSurface() {
        assertEquals(ViewportCoordinates(100, 200, 99, 199, 1, 1),
            clippedViewport(100, 200, ViewportPadding(500f, 500f, 500f, 500f)))
        assertEquals(ViewportCoordinates(100, 200, 0, 0, 1, 1),
            clippedViewport(100, 200, ViewportPadding(top = 500f, right = 500f)))
        assertEquals(ViewportCoordinates(1, 1, 0, 0, 1, 1),
            clippedViewport(0, -1, ViewportPadding(500f, 500f, 500f, 500f)))
    }

    @Test
    fun fractionalEdgesRoundAndInvalidEdgesAreZero() {
        assertEquals(ViewportCoordinates(100, 200, 11, 20, 85, 179),
            clippedViewport(100, 200, ViewportPadding(10.6f, 1.2f, 4.1f, 20.4f)))
        assertEquals(ViewportCoordinates(100, 200, 0, 0, 100, 200),
            clippedViewport(100, 200, ViewportPadding(-1f, Float.NaN, Float.POSITIVE_INFINITY)))
    }

    @Test
    fun composeStartAndEndResolveBeforeClippingInBothLayoutDirections() {
        val padding = PaddingValues(start = 10.dp, end = 30.dp, top = 20.dp, bottom = 40.dp)
        val ltr = clippedViewport(400, 800, padding.toViewportPadding(Density(2f), LayoutDirection.Ltr))
        val rtl = clippedViewport(400, 800, padding.toViewportPadding(Density(2f), LayoutDirection.Rtl))
        assertEquals(ViewportCoordinates(400, 800, 20, 80, 320, 680), ltr)
        assertEquals(ViewportCoordinates(400, 800, 60, 80, 320, 680), rtl)
    }

    @Test
    fun pickingAndManipulatorCoordinatesSubtractLeftAndBottomAfterFlippingSurfaceY() {
        val viewport = clippedViewport(1000, 800, ViewportPadding(100f, 50f, 200f, 150f))
        assertEquals(Float2(0f, 600f), viewport.toViewportPixels(100f, 50f))
        assertEquals(Float2(350f, 300f), viewport.toViewportPixels(450f, 350f))
        assertEquals(Float2(700f, 0f), viewport.toViewportPixels(800f, 650f))
    }

    @Test
    fun normalizedConversionsRoundTripIncludingPointsOutsideTheVisibleArea() {
        val viewport = clippedViewport(1000, 800, ViewportPadding(100f, 50f, 200f, 150f))
        listOf(Float2(0f), Float2(1f), Float2(0.45f, 0.5625f), Float2(-0.1f, 1.1f)).forEach {
            val actual = viewport.toViewNormalized(viewport.toViewportNormalized(it))
            assertEquals(it.x, actual.x, 1e-6f)
            assertEquals(it.y, actual.y, 1e-6f)
        }
    }

    @Test
    fun theSameViewPixelHasTheSameRayWithClippingOnAndOff() {
        val width = 1080
        val height = 2400
        val padding = ViewportPadding(80f, 200f, 120f, 600f)
        val viewport = clippedViewport(width, height, padding)
        val padded = paddedViewport(width, height, padding)
        val lens = Mat4(
            Float4((2.0 / padded.aspect).toFloat(), 0f, 0f, 0f),
            Float4(0f, 2f, 0f, 0f),
            Float4(0f, 0f, -1.002002f, -1f),
            Float4(0f, 0f, -0.2002002f, 0f),
        )
        // Exercise perspective and orthographic projections, with off-centre touches.
        listOf(lens, Mat4()).forEach { projection ->
            listOf(Float2(80f, 200f), Float2(520f, 1000f), Float2(800f, 1400f)).forEach { pixel ->
                val normalized = Float2(pixel.x / width, 1f - pixel.y / height)
                val unclipped = viewToRay(normalized, padded.postProjectionTransform * projection, Mat4())
                val clipped = viewToRay(viewport.toViewportNormalized(normalized), projection, Mat4())
                for (i in 0..2) {
                    assertEquals(unclipped.origin[i], clipped.origin[i], 1e-4f)
                    // Far-plane inversion can change the arbitrary direction magnitude; a ray
                    // is defined by its origin and unit direction, not that magnitude.
                    assertEquals(normalize(unclipped.direction)[i], normalize(clipped.direction)[i], 1e-5f)
                }
            }
        }
    }
}
