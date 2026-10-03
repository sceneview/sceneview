package io.github.sceneview

import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import io.github.sceneview.math.viewToRay
import io.github.sceneview.math.viewToWorld
import io.github.sceneview.math.worldToView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.tan

/**
 * The `contentPadding` contract — "the visible area is the camera's viewport" — pinned on the
 * maths alone: [paddedViewport], the matrix Filament applies after the projection, and the
 * screen ↔ world conversions that have to go through it.
 *
 * Every expectation is stated in pixels of the surface and derived from the contract, not from the
 * production formula: where the optical axis lands, how wide the visible area sees, where a touch
 * on a known world point comes from.
 */
class ContentPaddingTest {

    private val width = 1080
    private val height = 2400
    private val fov = 46.4f

    /** Portrait phone with a bottom sheet over half of it and a title bar. */
    private val sheet = ViewportPadding(top = 200f, bottom = 1200f)

    /** Landscape-style side panel on the right, on the same surface. */
    private val sidePanel = ViewportPadding(left = 40f, right = 400f)

    private val eye = Float3(0.6f, 1.4f, 3.2f)
    private val target = Float3(0.1f, 0.2f, -0.3f)

    /**
     * The camera's model matrix, in Filament's convention: it looks down its own `-Z`, `+Y` up.
     * Written out here rather than borrowed from a helper so the test owns its conventions.
     */
    private val camera: Mat4 = run {
        val back = normalize(eye - target)
        val right = normalize(cross(Float3(0f, 1f, 0f), back))
        val up = cross(back, right)
        Mat4(Float4(right, 0f), Float4(up, 0f), Float4(back, 0f), Float4(eye, 1f))
    }

    /** World → camera. */
    private val viewMatrix: Mat4 = inverse(camera)

    /** A point given in the camera's own frame, [ahead] units in front of it, in world space. */
    private fun inFrontOfCamera(x: Float, y: Float, ahead: Float): Position =
        (camera * Float4(x, y, -ahead, 1f)).xyz

    /**
     * The projection the camera is given for [padding]: the lens, at the visible area's aspect.
     * An OpenGL-style perspective matrix (clip `w = -z`), as Filament's `setLensProjection` builds.
     */
    private fun lens(padding: ViewportPadding): Mat4 {
        val aspect = paddedViewport(width, height, padding).aspect.toFloat()
        val f = 1f / tan(Math.toRadians(fov / 2.0)).toFloat()
        val near = 0.05f
        val far = 100f
        return Mat4(
            Float4(f / aspect, 0f, 0f, 0f),
            Float4(0f, f, 0f, 0f),
            Float4(0f, 0f, (far + near) / (near - far), -1f),
            Float4(0f, 0f, 2f * far * near / (near - far), 0f)
        )
    }

    /** The projection that is drawn: the lens, then the padding's scaling and shift. */
    private fun rendered(padding: ViewportPadding): Mat4 =
        paddedViewport(width, height, padding).postProjectionTransform * lens(padding)

    /** A normalized view coordinate (y up) as a pixel of the surface (y down). */
    private fun Float2.toPixel() = Float2(x * width, (1f - y) * height)

    private fun pixel(x: Float, y: Float) = Float2(x / width, 1f - y / height)

    // ── paddedViewport ───────────────────────────────────────────────────────────────────────────

    @Test
    fun noPaddingIsTheIdentity() {
        val padded = paddedViewport(width, height, ViewportPadding.Zero)
        assertTrue(padded.isIdentity)
        assertEquals(width.toDouble() / height, padded.aspect, 1e-12)
    }

    @Test
    fun aBottomSheetShrinksTheHeightAndMovesTheCentreUp() {
        val padded = paddedViewport(width, height, sheet)
        // 2400 − 200 − 1200 = 1000 px visible, centred 700 px from the top: 500 px above centre.
        assertEquals(1080.0 / 1000.0, padded.aspect, 1e-9)
        assertEquals(1.0, padded.scaleX, 1e-12)
        assertEquals(1000.0 / 2400.0, padded.scaleY, 1e-12)
        assertEquals(0.0, padded.shiftX, 1e-12)
        assertEquals(500.0 / 2400.0, padded.shiftY, 1e-12)
    }

    @Test
    fun aSidePanelShrinksTheWidthAndMovesTheCentreSideways() {
        val padded = paddedViewport(width, height, sidePanel)
        // 640 px visible, centred at x = 360: 180 px left of centre.
        assertEquals(640.0 / 2400.0, padded.aspect, 1e-9)
        assertEquals(640.0 / 1080.0, padded.scaleX, 1e-12)
        assertEquals(1.0, padded.scaleY, 1e-12)
        assertEquals(-180.0 / 1080.0, padded.shiftX, 1e-12)
    }

    @Test
    fun theMappingIsLinearInThePadding() {
        // What makes it animable on the panel's own curve: half the padding is half the offset.
        val full = paddedViewport(width, height, ViewportPadding(bottom = 1000f))
        val half = paddedViewport(width, height, ViewportPadding(bottom = 500f))
        assertEquals(full.shiftY / 2.0, half.shiftY, 1e-12)
        assertEquals((1.0 + full.scaleY) / 2.0, half.scaleY, 1e-12)
    }

    @Test
    fun theVisibleAreaNeverCollapses() {
        val padded = paddedViewport(width, height, ViewportPadding(top = 3000f, bottom = 9000f))
        assertTrue(padded.scaleY > 0.0)
        assertEquals(1.0 / height, padded.scaleY, 1e-9)
        assertTrue(padded.aspect.isFinite())
    }

    @Test
    fun junkPaddingAndUnsizedSurfacesAreHarmless() {
        val junk = ViewportPadding(left = -50f, top = Float.NaN, bottom = Float.NEGATIVE_INFINITY)
        assertTrue(junk.isZero)
        assertTrue(paddedViewport(width, height, junk).isIdentity)
        val unsized = paddedViewport(0, 0, sheet)
        assertTrue(unsized.isIdentity)
        assertEquals(1.0, unsized.aspect, 0.0)
    }

    // ── What is drawn ────────────────────────────────────────────────────────────────────────────

    @Test
    fun theOpticalAxisLandsOnTheCentreOfTheVisibleArea() {
        for (padding in listOf(sheet, sidePanel, ViewportPadding(40f, 200f, 400f, 1200f))) {
            val onScreen = worldToView(target, rendered(padding), viewMatrix)
            assertNotNull(onScreen)
            val px = onScreen!!.toPixel()
            val expectedX = padding.left + (width - padding.left - padding.right) / 2f
            val expectedY = padding.top + (height - padding.top - padding.bottom) / 2f
            assertEquals("x for $padding", expectedX, px.x, 0.5f)
            assertEquals("y for $padding", expectedY, px.y, 0.5f)
        }
    }

    @Test
    fun theLensFieldOfViewSpansTheVisibleArea() {
        // A point on the top edge of the lens's vertical FOV, 4 units ahead, must be drawn on the
        // top edge of the visible area — not of the surface.
        val depth = 4f
        val halfHeight = depth * tan(Math.toRadians(fov / 2.0)).toFloat()
        val halfWidth = halfHeight * paddedViewport(width, height, sheet).aspect.toFloat()
        val topRight = inFrontOfCamera(halfWidth, halfHeight, depth)
        val px = worldToView(topRight, rendered(sheet), viewMatrix)!!.toPixel()
        assertEquals(width - sheet.right, px.x, 0.5f)
        assertEquals(sheet.top, px.y, 0.5f)
    }

    @Test
    fun pixelsStaySquare() {
        // One world unit across and one world unit up, at the same depth, cover the same number
        // of pixels — whatever the padding does to the aspect ratio.
        fun at(x: Float, y: Float) = inFrontOfCamera(x, y, ahead = 3f)
        for (padding in listOf(sheet, sidePanel)) {
            val origin = worldToView(at(0f, 0f), rendered(padding), viewMatrix)!!.toPixel()
            val across = worldToView(at(0.5f, 0f), rendered(padding), viewMatrix)!!.toPixel()
            val above = worldToView(at(0f, 0.5f), rendered(padding), viewMatrix)!!.toPixel()
            assertEquals(across.x - origin.x, origin.y - above.y, 0.5f)
        }
    }

    // ── Picking ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aTouchOnAProjectedPointComesBackToThatPoint() {
        val ball = Position(-0.35f, 0.15f, 0.4f)
        for (padding in listOf(ViewportPadding.Zero, sheet, sidePanel)) {
            val projection = rendered(padding)
            val touch = worldToView(ball, projection, viewMatrix)!!
            val ray = viewToRay(touch, projection, viewMatrix)
            // Distance from the ball to the ray.
            val toBall = ball - ray.origin
            val along = normalize(ray.direction)
            val miss = length(toBall - along * dot(toBall, along))
            assertTrue("ray misses the ball by $miss for $padding", miss < 2e-3f)
        }
    }

    @Test
    fun screenToWorldAndBackIsStableUnderPadding() {
        val projection = rendered(sheet)
        for (touch in listOf(pixel(540f, 700f), pixel(120f, 260f), pixel(1000f, 1150f))) {
            val world = viewToWorld(touch, 0.5f, projection, viewMatrix)
            val back = worldToView(world, projection, viewMatrix)!!
            assertEquals(touch.x, back.x, 1e-3f)
            assertEquals(touch.y, back.y, 1e-3f)
        }
    }

    @Test
    fun unprojectingThroughTheBareProjectionMissesByTheShift() {
        // The bug this fixes, kept as a negative control: Filament's projection getters leave the
        // scaling and the shift out, so picking through them answers for the centre of the
        // *surface* — half a sheet away from where the subject is drawn.
        val throughGetter = worldToView(target, lens(sheet), viewMatrix)!!.toPixel()
        val drawn = worldToView(target, rendered(sheet), viewMatrix)!!.toPixel()
        assertEquals(height / 2f, throughGetter.y, 0.5f)
        assertEquals(500f, abs(throughGetter.y - drawn.y), 0.5f)
    }
}
