package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImageToViewMappingTest {

    // ── The ARCore path: three mapped corners -> affine ──────────────────────────────────────
    //
    // `Frame.cameraImageToViewMapping` asks ARCore where three corners of the CPU image land in
    // the view and solves the affine from them. ARCore is not available on the JVM, so
    // [displayedAt] plays its part: an independent, step-by-step model of what a camera preview
    // does to an image pixel (quarter turn, optional mirror, fill, centred crop). The solver only
    // ever sees three corners; every assertion below is on a pixel it was not given.

    @Test
    fun `three mapped corners rebuild the transform for every quarter turn`() {
        VIEWS.forEach { view ->
            QUARTER_TURNS.forEach { rotation ->
                val transform = solvedTransform(IMAGE, view, rotation, mirrored = false)

                PROBES.forEach { (x, y) ->
                    assertPoint(
                        actual = transform.map(x, y),
                        expected = displayedAt(x, y, IMAGE, view, rotation, mirrored = false),
                        message = "pixel ($x, $y) at $rotation degrees in $view",
                    )
                }
            }
        }
    }

    @Test
    fun `three mapped corners rebuild a mirrored front-camera transform`() {
        VIEWS.forEach { view ->
            QUARTER_TURNS.forEach { rotation ->
                val transform = solvedTransform(IMAGE, view, rotation, mirrored = true)

                PROBES.forEach { (x, y) ->
                    assertPoint(
                        actual = transform.map(x, y),
                        expected = displayedAt(x, y, IMAGE, view, rotation, mirrored = true),
                        message = "mirrored pixel ($x, $y) at $rotation degrees in $view",
                    )
                }
                // A mirror flips the orientation of the plane; a rotation never does.
                assertTrue(
                    "mirrored transform at $rotation degrees keeps its handedness",
                    transform.determinant() < 0f,
                )
                assertTrue(
                    "rear transform at $rotation degrees is mirrored",
                    solvedTransform(IMAGE, view, rotation, mirrored = false).determinant() > 0f,
                )
            }
        }
    }

    @Test
    fun `the image centre lands on the view centre whatever the rotation and the mirror`() {
        VIEWS.forEach { view ->
            QUARTER_TURNS.forEach { rotation ->
                listOf(false, true).forEach { mirrored ->
                    val centre = solvedTransform(IMAGE, view, rotation, mirrored)
                        .map(IMAGE.width / 2f, IMAGE.height / 2f)

                    assertPoint(
                        actual = centre,
                        expected = CameraImagePoint(view.width / 2f, view.height / 2f),
                        message = "centre at $rotation degrees, mirrored=$mirrored, in $view",
                    )
                }
            }
        }
    }

    @Test
    fun `the cropped axis overflows the view by the same amount on both sides`() {
        // 640x480 turned upright is 480x640 (3:4); a 1080x1920 view is 9:16, narrower. Filling
        // the height scales by 3, the image is 1440 wide and 180 px are cut on each side.
        val transform = solvedTransform(IMAGE, PORTRAIT_VIEW, rotation = 90, mirrored = false)

        // Detector-upright left edge is image row y = height, right edge is y = 0.
        assertPoint(transform.map(0f, IMAGE.height.toFloat()), CameraImagePoint(-180f, 0f), "left")
        assertPoint(transform.map(0f, 0f), CameraImagePoint(1260f, 0f), "right")
        assertPoint(
            transform.map(IMAGE.width.toFloat(), 0f),
            CameraImagePoint(1260f, 1920f),
            "bottom right",
        )
    }

    @Test
    fun `a translated view origin is carried by the offsets only`() {
        // ARCore `VIEW` can start away from (0, 0) of the surface; the linear part must not move.
        val base = solvedTransform(IMAGE, PORTRAIT_VIEW, rotation = 90, mirrored = false)
        val shift = CameraImagePoint(37f, -12f)
        val shifted = cameraImageToViewTransform(
            imageSize = IMAGE,
            origin = base.map(0f, 0f) + shift,
            xAxisEnd = base.map(IMAGE.width.toFloat(), 0f) + shift,
            yAxisEnd = base.map(0f, IMAGE.height.toFloat()) + shift,
        )

        assertEquals(base.m00, shifted.m00, EPSILON)
        assertEquals(base.m01, shifted.m01, EPSILON)
        assertEquals(base.m10, shifted.m10, EPSILON)
        assertEquals(base.m11, shifted.m11, EPSILON)
        assertPoint(shifted.map(123f, 45f), base.map(123f, 45f) + shift, "shifted pixel")
    }

    @Test
    fun `the solved transform and centerCrop agree`() {
        // Two implementations written from different ends: one solves three points, the other
        // composes the coefficients by hand.
        VIEWS.forEach { view ->
            QUARTER_TURNS.forEach { rotation ->
                val solved = solvedTransform(IMAGE, view, rotation, mirrored = false)
                val composed = CameraImageToViewMapping.centerCrop(IMAGE, view, rotation)
                    .imagePixelsToView

                PROBES.forEach { (x, y) ->
                    assertPoint(
                        actual = composed.map(x, y),
                        expected = solved.map(x, y),
                        message = "pixel ($x, $y) at $rotation degrees in $view",
                    )
                }
            }
        }
    }

    // ── Detector coordinates ─────────────────────────────────────────────────────────────────

    @Test
    fun `detector pixels map back to the image pixel they were read from`() {
        // A detector fed the image with a clockwise rotation reports in the rotated image. The
        // corner the detector calls its origin is a different image corner at each rotation.
        val w = IMAGE.width.toFloat()
        val h = IMAGE.height.toFloat()
        val detectorOrigin = mapOf(
            0 to CameraImagePoint(0f, 0f),
            90 to CameraImagePoint(0f, h),
            180 to CameraImagePoint(w, h),
            270 to CameraImagePoint(w, 0f),
        )

        detectorOrigin.forEach { (rotation, expected) ->
            val mapping = CameraImageToViewMapping.centerCrop(IMAGE, PORTRAIT_VIEW, rotation)

            assertPoint(
                actual = mapping.detectorPixelToImagePixel(0f, 0f),
                expected = expected,
                message = "detector origin at $rotation degrees",
            )
            assertPoint(
                actual = mapping.detectorPixelToImagePixel(
                    mapping.detectorWidth / 2f,
                    mapping.detectorHeight / 2f,
                ),
                expected = CameraImagePoint(w / 2f, h / 2f),
                message = "detector centre at $rotation degrees",
            )
        }
    }

    @Test
    fun `center crop maps rotated detector pixels for every quarter turn`() {
        QUARTER_TURNS.forEach { rotation ->
            val mapping = CameraImageToViewMapping.centerCrop(IMAGE, PORTRAIT_VIEW, rotation)
            val center = mapping.mapPixel(mapping.detectorWidth / 2f, mapping.detectorHeight / 2f)
            assertPoint(center, CameraImagePoint(540f, 960f), "centre at $rotation degrees")

            val leftQuarter = mapping.mapPixel(
                mapping.detectorWidth / 4f,
                mapping.detectorHeight / 2f,
            )
            val expectedX = if (rotation % 180 == 0) -100f else 180f
            assertPoint(
                leftQuarter,
                CameraImagePoint(expectedX, 960f),
                "left quarter at $rotation degrees",
            )
        }
    }

    @Test
    fun `normalized mapping uses rotated detector dimensions`() {
        val mapping = CameraImageToViewMapping.centerCrop(
            imageSize = IMAGE,
            viewSize = PORTRAIT_VIEW,
            inputRotationDegrees = 90,
        )

        assertPoint(mapping.mapNormalized(0.5f, 0.75f), CameraImagePoint(540f, 1440f))
        // An API that reports in the unrotated source image (MediaPipe) skips the rotation undo.
        assertPoint(mapping.mapImageNormalized(0.5f, 0.75f), CameraImagePoint(180f, 960f))
        assertPoint(mapping.mapImagePixel(320f, 360f), CameraImagePoint(180f, 960f))
        assertPoint(mapping.mapPixel(120f, 320f), CameraImagePoint(180f, 960f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-quarter-turn rotation`() {
        CameraImageToViewMapping.centerCrop(IMAGE, PORTRAIT_VIEW, 45)
    }

    // ── Reference model ──────────────────────────────────────────────────────────────────────

    private fun solvedTransform(
        image: CameraImageSize,
        view: CameraImageSize,
        rotation: Int,
        mirrored: Boolean,
    ): CameraImageToViewTransform {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        return cameraImageToViewTransform(
            imageSize = image,
            origin = displayedAt(0f, 0f, image, view, rotation, mirrored),
            xAxisEnd = displayedAt(w, 0f, image, view, rotation, mirrored),
            yAxisEnd = displayedAt(0f, h, image, view, rotation, mirrored),
        )
    }

    /**
     * Where a camera preview draws image pixel `(x, y)`: the image is turned clockwise by
     * [rotation], flipped left-right for a front camera, scaled until it fills the view, and
     * cropped equally on the overflowing axis.
     */
    @Suppress("LongParameterList")
    private fun displayedAt(
        x: Float,
        y: Float,
        image: CameraImageSize,
        view: CameraImageSize,
        rotation: Int,
        mirrored: Boolean,
    ): CameraImagePoint {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        // Clockwise quarter turns, one at a time: (x, y) in a WxH image -> (H - y, x) in HxW.
        var px = x
        var py = y
        var pw = w
        var ph = h
        repeat(rotation / 90) {
            val turnedX = ph - py
            val turnedY = px
            px = turnedX
            py = turnedY
            val swap = pw
            pw = ph
            ph = swap
        }
        if (mirrored) px = pw - px
        val scale = maxOf(view.width / pw, view.height / ph)
        return CameraImagePoint(
            x = (px - pw / 2f) * scale + view.width / 2f,
            y = (py - ph / 2f) * scale + view.height / 2f,
        )
    }

    private fun CameraImageToViewTransform.determinant(): Float = m00 * m11 - m01 * m10

    private operator fun CameraImagePoint.plus(other: CameraImagePoint) =
        CameraImagePoint(x + other.x, y + other.y)

    private fun assertPoint(
        actual: CameraImagePoint,
        expected: CameraImagePoint,
        message: String = "point",
    ) {
        assertEquals("$message x", expected.x, actual.x, EPSILON)
        assertEquals("$message y", expected.y, actual.y, EPSILON)
    }

    private companion object {
        const val EPSILON = 0.01f
        val IMAGE = CameraImageSize(640, 480)
        val PORTRAIT_VIEW = CameraImageSize(1080, 1920)
        val LANDSCAPE_VIEW = CameraImageSize(2400, 1080)
        val VIEWS = listOf(PORTRAIT_VIEW, LANDSCAPE_VIEW)
        val QUARTER_TURNS = listOf(0, 90, 180, 270)

        /** None of these is one of the three corners the solver is given. */
        val PROBES = listOf(
            320f to 240f,
            640f to 480f,
            123f to 45f,
            517f to 401f,
        )
    }
}
