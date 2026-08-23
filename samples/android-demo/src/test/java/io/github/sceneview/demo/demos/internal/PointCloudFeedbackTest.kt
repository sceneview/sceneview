package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM unit tests for [PointCloudFeedback] (#3270). */
class PointCloudFeedbackTest {

    @Test
    fun `not stuck while not tracking`() {
        assertFalse(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = false,
                pointCount = 0,
                zeroPointsSinceMs = 0L,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun `not stuck once points are flowing`() {
        assertFalse(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 42,
                zeroPointsSinceMs = 0L,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun `not stuck when zeroPointsSinceMs has not been recorded yet`() {
        assertFalse(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 0,
                zeroPointsSinceMs = null,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun `not stuck before the threshold elapses`() {
        assertFalse(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 0,
                zeroPointsSinceMs = 9_000L,
                nowMs = 10_000L,
                stuckAfterMs = 2_000L,
            )
        )
    }

    @Test
    fun `stuck once the threshold has elapsed while tracking with zero points`() {
        assertTrue(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 0,
                zeroPointsSinceMs = 0L,
                nowMs = 2_001L,
                stuckAfterMs = 2_000L,
            )
        )
    }

    @Test
    fun `default threshold matches STUCK_AFTER_MS`() {
        assertTrue(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 0,
                zeroPointsSinceMs = 0L,
                nowMs = PointCloudFeedback.STUCK_AFTER_MS + 1,
            )
        )
        assertFalse(
            PointCloudFeedback.zeroPointsStuck(
                isTracking = true,
                pointCount = 0,
                zeroPointsSinceMs = 0L,
                nowMs = PointCloudFeedback.STUCK_AFTER_MS,
            )
        )
    }
}
