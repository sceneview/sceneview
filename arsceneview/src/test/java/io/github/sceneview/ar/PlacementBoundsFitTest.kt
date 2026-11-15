package io.github.sceneview.ar

import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Size
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toQuaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry only: no Compose, Filament engine, ARCore session or mocked hit tests. */
class PlacementBoundsFitTest {
    private val square = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)

    private fun fit(
        halfExtent: Size = Size(0.5f),
        center: Position = Position(0f),
        transform: Transform = Transform(),
        polygon: FloatArray = square,
    ) = placementBoundsFit(center, halfExtent, transform, polygon)

    private fun assertMargin(expected: Float, result: PlacementBoundsFit?) {
        requireNotNull(result)
        assertEquals(expected, result.marginMeters, 1e-5f)
        assertEquals(expected >= 0f, result.fits)
    }

    @Test fun `contained box reports its smallest clearance in metres`() {
        assertMargin(0.5f, fit())
        assertMargin(0.25f, fit(transform = translation(Position(0.25f, 0f, 0f))))
    }

    @Test fun `a valid hit at the centre does not mean the whole model fits`() {
        val result = requireNotNull(fit(halfExtent = Size(1.25f, 0.5f, 0.5f)))
        assertFalse(result.fits)
        assertMargin(-0.25f, result)
    }

    @Test fun `contact with a polygon edge fits with zero clearance`() {
        assertMargin(0f, fit(halfExtent = Size(1f)))
        assertMargin(0f, fit(transform = translation(Position(0.5f, 0f, 0f))))
    }

    @Test fun `contact classification tolerates ten micrometres without rounding the margin`() {
        for (margin in listOf(-1e-5f, -2.3841858e-7f, 0f, 1e-5f)) {
            val result = PlacementBoundsFit(margin)
            assertTrue("contact within numerical tolerance: $margin", result.fits)
            assertEquals("preserve the measured margin", margin, result.marginMeters, 0f)
        }
    }

    @Test fun `overhang beyond the contact tolerance does not fit`() {
        for (margin in listOf(Math.nextDown(-1e-5f), -1e-4f, -0.001f, -1f)) {
            assertFalse("overhang beyond numerical tolerance: $margin", PlacementBoundsFit(margin).fits)
        }
    }

    @Test fun `box centre offset is transformed along with its bounds`() {
        assertMargin(-0.25f, fit(center = Position(0.75f, 0f, 0f)))
        assertMargin(0.5f, fit(
            center = Position(2f, 0f, 0f),
            transform = translation(Position(-2f, 0f, 0f)),
        ))
    }

    @Test fun `rotation changes the footprint even with an unchanged hit pose`() {
        val rectangle = floatArrayOf(-1.5f, -0.5f, 1.5f, -0.5f, 1.5f, 0.5f, -1.5f, 0.5f)
        val half = Size(1f, 0.1f, 0.25f)
        assertTrue(requireNotNull(fit(halfExtent = half, polygon = rectangle)).fits)
        assertMargin(-0.5f, fit(
            halfExtent = half,
            polygon = rectangle,
            transform = rotation(Rotation(y = 90f).toQuaternion()),
        ))
    }

    @Test fun `diagonal rotation uses the rotated box rather than its original size`() {
        assertMargin(1f - kotlin.math.sqrt(2f), fit(
            halfExtent = Size(1f, 0.1f, 1f),
            transform = rotation(Rotation(y = 45f).toQuaternion()),
        ))
    }

    @Test fun `nonuniform and negative scales preserve the full footprint`() {
        assertMargin(-0.5f, fit(transform = scale(Scale(3f, 1f, 0.5f))))
        assertMargin(-0.5f, fit(transform = scale(Scale(-3f, 1f, 0.5f))))
    }

    @Test fun `an affine shear projects the whole box`() {
        val shear = Transform().apply { y.x = 2f }
        assertMargin(-0.5f, fit(transform = shear))
    }

    @Test fun `tilting projects the height too rather than checking only the base corners`() {
        assertMargin(-0.5f, fit(
            halfExtent = Size(0.25f, 1.5f, 0.25f),
            transform = rotation(Rotation(x = 90f).toQuaternion()),
        ))
    }

    @Test fun `height above a horizontal plane does not affect footprint clearance`() {
        assertMargin(0.5f, fit(
            halfExtent = Size(0.5f, 20f, 0.5f),
            transform = translation(Position(0f, 10f, 0f)),
        ))
    }

    @Test fun `vertical translated plane uses its local XZ not world XZ`() {
        val planeToWorld = translation(Position(5f, 2f, -3f)) *
            rotation(Rotation(x = 90f).toQuaternion())
        val modelToWorld = planeToWorld * translation(Position(0.25f, 0f, 0f))
        assertMargin(0.25f, fit(transform = inverse(planeToWorld) * modelToWorld))
    }

    @Test fun `polygon shape matters even when its rectangular extents contain the box`() {
        val diamond = floatArrayOf(0f, -1f, 1f, 0f, 0f, 1f, -1f, 0f)
        assertMargin(-0.5f / kotlin.math.sqrt(2f), fit(halfExtent = Size(0.75f), polygon = diamond))
        assertMargin(0.5f / kotlin.math.sqrt(2f), fit(halfExtent = Size(0.25f), polygon = diamond))
    }

    @Test fun `clockwise and counterclockwise polygons give the same clearance`() {
        val reversed = floatArrayOf(-1f, 1f, 1f, 1f, 1f, -1f, -1f, -1f)
        assertMargin(0.5f, fit(polygon = reversed))
        assertMargin(-0.25f, fit(center = Position(0f, 0f, 0.75f), polygon = reversed))
    }

    @Test fun `duplicate and collinear polygon vertices do not change the fit`() {
        assertMargin(0.5f, fit(polygon = floatArrayOf(
            -1f, -1f, 0f, -1f, 1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, -1f, -1f,
        )))
    }

    @Test fun `missing malformed and degenerate polygons have no verdict`() {
        for (polygon in listOf(
            floatArrayOf(), floatArrayOf(0f, 0f), floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 2f),
            floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f),
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f),
            floatArrayOf(0f, 0f, Float.NaN, 1f, 1f, 0f),
            floatArrayOf(0f, 0f, Float.POSITIVE_INFINITY, 1f, 1f, 0f),
        )) assertNull(fit(polygon = polygon))
    }

    @Test fun `concave and self-intersecting polygons have no verdict`() {
        assertNull(fit(polygon = floatArrayOf(0f, 0f, 2f, 0f, 1f, 1f, 2f, 2f, 0f, 2f)))
        assertNull(fit(polygon = floatArrayOf(-1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f)))
    }

    @Test fun `invalid or empty bounds have no verdict but a flat model is valid`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f)) {
            assertNull(fit(halfExtent = Size(bad, 0.5f, 0.5f)))
        }
        assertNull(fit(halfExtent = Size(0f)))
        assertNull(fit(halfExtent = Size(-0f)))
        assertNull(fit(center = Position(Float.NaN, 0f, 0f)))
        assertMargin(0.5f, fit(halfExtent = Size(0.5f, 0f, 0.5f)))
    }

    @Test fun `nonfinite and projective transforms have no verdict`() {
        assertNull(fit(transform = translation(Position(Float.NaN, 0f, 0f))))
        assertNull(fit(transform = scale(Scale(Float.POSITIVE_INFINITY))))
        val projective = Transform().apply { x.w = 0.5f }
        assertNull(fit(transform = projective))
    }

    @Test fun `fit does not mutate caller geometry`() {
        val original = square.copyOf()
        val center = Position(0f)
        val halfExtent = Size(0.5f)
        val transform = Transform()
        assertMargin(0.5f, fit(halfExtent, center, transform))
        assertTrue(original.contentEquals(square))
        assertEquals(Position(0f), center)
        assertEquals(Size(0.5f), halfExtent)
        assertEquals(Transform(), transform)
    }
}
