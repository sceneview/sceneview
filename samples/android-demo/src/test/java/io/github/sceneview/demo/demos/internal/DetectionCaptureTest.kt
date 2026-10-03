package io.github.sceneview.demo.demos.internal

import io.github.sceneview.ar.camera.CameraImageSize
import io.github.sceneview.ar.camera.CameraImageToViewMapping
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionCaptureTest {
    private val mapping = CameraImageToViewMapping.centerCrop(
        CameraImageSize(640, 480),
        CameraImageSize(1080, 1920),
        90,
    )

    @Test
    fun `ten degree camera yaw rejects the acquisition result`() {
        val halfAngle = Math.toRadians(5.0)
        assertFalse(
            isDetectionCaptureCompatible(
                capturedPose = pose(),
                currentPose = pose(qy = sin(halfAngle).toFloat(), qw = cos(halfAngle).toFloat()),
                capturedTimestampNanos = 1_000_000_000L,
                currentTimestampNanos = 1_100_000_000L,
                capturedMapping = mapping,
                currentMapping = mapping,
            )
        )
    }

    @Test
    fun `small motion with matching fresh geometry is accepted`() {
        assertTrue(
            isDetectionCaptureCompatible(
                capturedPose = pose(),
                currentPose = pose(tx = 0.01f),
                capturedTimestampNanos = 1_000_000_000L,
                currentTimestampNanos = 1_100_000_000L,
                capturedMapping = mapping,
                currentMapping = mapping,
            )
        )
    }

    @Test
    fun `changed display geometry rejects the result`() {
        val shifted = CameraImageToViewMapping(
            imageSize = mapping.imageSize,
            viewSize = mapping.viewSize,
            inputRotationDegrees = mapping.inputRotationDegrees,
            imagePixelsToView = mapping.imagePixelsToView.copy(
                m02 = mapping.imagePixelsToView.m02 + 1f,
            ),
        )

        assertFalse(
            isDetectionCaptureCompatible(
                capturedPose = pose(),
                currentPose = pose(),
                capturedTimestampNanos = 1_000_000_000L,
                currentTimestampNanos = 1_100_000_000L,
                capturedMapping = mapping,
                currentMapping = shifted,
            )
        )
    }

    @Test
    fun `old detector result is rejected even without camera motion`() {
        assertFalse(
            isDetectionCaptureCompatible(
                capturedPose = pose(),
                currentPose = pose(),
                capturedTimestampNanos = 1_000_000_000L,
                currentTimestampNanos = 1_600_000_000L,
                capturedMapping = mapping,
                currentMapping = mapping,
            )
        )
    }

    private fun pose(
        tx: Float = 0f,
        qy: Float = 0f,
        qw: Float = 1f,
    ) = DetectionCameraPose(tx, 0f, 0f, 0f, qy, 0f, qw)
}
