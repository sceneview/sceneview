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
