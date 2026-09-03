package io.github.sceneview.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sign

class CameraGestureMathTest {

    @Test fun pinchZoomDelta_zeroSeparationDelta_returnsZero() {
        assertEquals(0f, pinchZoomDelta(100f, 100f, speed = 0.1f, damping = 0.7f), 1e-6f)
    }

    @Test fun pinchZoomDelta_subPixelDelta_isLinear() {
        val d = pinchZoomDelta(100f, 99.5f, speed = 1f, damping = 0.7f)
        assertEquals(0.5f, d, 1e-6f)
    }

    @Test fun pinchZoomDelta_subPixelNegative_isLinear() {
        val d = pinchZoomDelta(99.5f, 100f, speed = 1f, damping = 0.7f)
        assertEquals(-0.5f, d, 1e-6f)
    }

    @Test fun pinchZoomDelta_dampingOne_isLinearInLargeDelta() {
        val d = pinchZoomDelta(300f, 100f, speed = 1f, damping = 1f)
        assertEquals(200f, d, 1e-3f)
    }

    @Test fun pinchZoomDelta_signPreserved_pinchIn() {
        val d = pinchZoomDelta(300f, 100f, speed = 1f, damping = 0.7f)
        assertTrue("expected positive delta for pinch-in (prev > curr)", d > 0f)
    }

    @Test fun pinchZoomDelta_signPreserved_pinchOut() {
        val d = pinchZoomDelta(100f, 300f, speed = 1f, damping = 0.7f)
        assertTrue("expected negative delta for pinch-out (prev < curr)", d < 0f)
    }

    @Test fun pinchZoomDelta_speedScalesOutput() {
        val baseline = pinchZoomDelta(300f, 100f, speed = 1f, damping = 0.7f)
        val scaled = pinchZoomDelta(300f, 100f, speed = 0.5f, damping = 0.7f)
        assertEquals(baseline * 0.5f, scaled, 1e-4f)
    }

    @Test fun pinchZoomDelta_dampingBelowOne_softensLargeDeltas() {
        val rawAbs = abs(300f - 100f) // 200
        val damped = abs(pinchZoomDelta(300f, 100f, speed = 1f, damping = 0.7f))
        assertTrue("damping<1 should yield |damped| < |raw| for |delta|>1", damped < rawAbs)
    }

    @Test fun pinchZoomDelta_defaultConstants_haveExpectedValues() {
        // #1427 re-tuned this 1/30 → 1/18 while the dolly step was still a fixed number of world
        // units. #3426 made the step a *ratio* of the camera-to-target distance, so the constant's
        // unit changed with it (log-distance per damped pixel) and 1/60 is the value that puts one
        // full-screen pinch at ≈ ln 2. See RelativeZoomTest for the behaviour that pins it.
        assertEquals(1f / 60f, CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_SPEED, 1e-6f)
        assertEquals(0.7f, CameraGestureDetector.DefaultCameraManipulator.DEFAULT_PINCH_ZOOM_DAMPING, 1e-6f)
    }

    @Test fun nextFov_pinchOut_decreasesFov() {
        val out = nextFov(currentFov = 60.0, prevSeparation = 100f, currSeparation = 300f,
            range = 10f..120f, speed = 0.05f)
        assertTrue("pinch-out (zoom in) should decrease FOV", out < 60.0)
    }

    @Test fun nextFov_pinchIn_increasesFov() {
        val out = nextFov(currentFov = 60.0, prevSeparation = 300f, currSeparation = 100f,
            range = 10f..120f, speed = 0.05f)
        assertTrue("pinch-in (zoom out) should increase FOV", out > 60.0)
    }

    @Test fun nextFov_clampsToMinimum() {
        val out = nextFov(currentFov = 11.0, prevSeparation = 0f, currSeparation = 1000f,
            range = 10f..120f, speed = 0.05f)
        assertEquals(10.0, out, 1e-9)
    }

    @Test fun nextFov_clampsToMaximum() {
        val out = nextFov(currentFov = 119.0, prevSeparation = 1000f, currSeparation = 0f,
            range = 10f..120f, speed = 0.05f)
        assertEquals(120.0, out, 1e-9)
    }

    @Test fun nextFov_zeroSeparationDelta_isNoOp() {
        val out = nextFov(currentFov = 60.0, prevSeparation = 200f, currSeparation = 200f,
            range = 10f..120f, speed = 0.05f)
        assertEquals(60.0, out, 1e-9)
    }
}
