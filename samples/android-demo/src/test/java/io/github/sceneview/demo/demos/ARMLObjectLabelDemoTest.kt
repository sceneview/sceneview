package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for [confidenceBucketPercent] — the bucketing that keeps the `ar-ml-object-label`
 * label-bitmap cache from re-rasterising on every sub-percent confidence jitter between detector
 * passes.
 */
class ARMLObjectLabelDemoTest {

    @Test
    fun `buckets down to the nearest 5 percent step by default`() {
        assertEquals(80, confidenceBucketPercent(0.84f))
        assertEquals(85, confidenceBucketPercent(0.85f))
        assertEquals(85, confidenceBucketPercent(0.89f))
    }

    @Test
    fun `0 and 1 confidence map to their own buckets`() {
        assertEquals(0, confidenceBucketPercent(0f))
        assertEquals(100, confidenceBucketPercent(1f))
    }

    @Test
    fun `out-of-range confidence is coerced into 0 to 1 before bucketing`() {
        assertEquals(0, confidenceBucketPercent(-0.5f))
        assertEquals(100, confidenceBucketPercent(1.5f))
    }

    @Test
    fun `custom step size changes the bucket width`() {
        assertEquals(70, confidenceBucketPercent(0.73f, step = 10))
        assertEquals(75, confidenceBucketPercent(0.79f, step = 25))
    }
}

/**
 * Pure-JVM tests for [bboxCentreToScreenPoint] — the CPU-image-space → AR-surface-space
 * mapping fed into `Frame.hitTest` (#3337). Before this fix the caller passed a hardcoded
 * 1000×1000 square instead of the real surface size, so on a tall portrait phone (e.g. a
 * Pixel 9 at 1080×2424) a detection in the lower half of the frame hit-tested against the
 * wrong point on screen and its label anchor landed off the object.
 */
class BboxCentreToScreenPointTest {

    @Test
    fun `centre of the image maps to centre of the display`() {
        val (x, y) = bboxCentreToScreenPoint(
            cx = 320, cy = 240, imageW = 640, imageH = 480, displayW = 1080, displayH = 2424,
        )
        assertEquals(540f, x, 0.01f)
        assertEquals(1212f, y, 0.01f)
    }

    @Test
    fun `a detection in the lower half of a tall portrait display lands past a 1000px square`() {
        // Regression for #3337: the old hardcoded 1000x1000 square could never produce a
        // y beyond 1000px, silently clamping every lower-half detection onto the wrong point
        // on a 2424px-tall real display.
        val (_, y) = bboxCentreToScreenPoint(
            cx = 320, cy = 400, imageW = 640, imageH = 480, displayW = 1080, displayH = 2424,
        )
        assert(y > 1000f) { "expected y > 1000f (past the old hardcoded square), was $y" }
    }

    @Test
    fun `zero-size image dimensions are coerced instead of dividing by zero`() {
        val (x, y) = bboxCentreToScreenPoint(
            cx = 10, cy = 10, imageW = 0, imageH = 0, displayW = 1080, displayH = 2424,
        )
        assertEquals(10f * 1080f, x, 0.01f)
        assertEquals(10f * 2424f, y, 0.01f)
    }
}
