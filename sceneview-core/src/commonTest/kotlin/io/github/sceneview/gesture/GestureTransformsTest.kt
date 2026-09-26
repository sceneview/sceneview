package io.github.sceneview.gesture

import kotlin.test.Test
import kotlin.test.assertEquals

class GestureTransformsTest {

    private val epsilon = 0.001f

    // --- applyRotationGesture ---

    @Test
    fun rotationWithFullSensitivity() {
        val result = applyRotationGesture(currentAngle = 0f, deltaAngle = 90f, sensitivity = 1f)
        assertEquals(90f, result, epsilon)
    }

    @Test
    fun rotationWithDamping() {
        val result = applyRotationGesture(currentAngle = 45f, deltaAngle = 90f, sensitivity = 0.5f)
        assertEquals(90f, result, epsilon) // 45 + 90*0.5 = 90
    }

    @Test
    fun rotationNegativeDelta() {
        val result = applyRotationGesture(currentAngle = 180f, deltaAngle = -90f, sensitivity = 1f)
        assertEquals(90f, result, epsilon)
    }
}
