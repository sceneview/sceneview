package io.github.sceneview.gesture

import dev.romainguy.kotlin.math.length
import io.github.sceneview.DefaultCameraNode
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pins the `orbitRadius` → eye-position derivation behind the `orbitRadius` overloads of
 * `rememberCameraManipulator` / `createDefaultCameraManipulator` /
 * `DefaultCameraManipulator` (#2932). Pure maths, no Filament instance needed.
 */
class OrbitEyePositionTest {

    private val eps = 1e-5f

    @Test fun defaultDirection_isUnitLength() {
        assertEquals(1f, length(DEFAULT_ORBIT_DIRECTION), eps)
    }

    @Test fun defaultDirection_pointsAlongTheDefaultCameraPosition() {
        val expectedLength = sqrt(
            DefaultCameraNode.DEFAULT_Y * DefaultCameraNode.DEFAULT_Y +
                DefaultCameraNode.DEFAULT_Z * DefaultCameraNode.DEFAULT_Z
        )
        assertEquals(0f, DEFAULT_ORBIT_DIRECTION.x, eps)
        assertEquals(DefaultCameraNode.DEFAULT_Y / expectedLength, DEFAULT_ORBIT_DIRECTION.y, eps)
        assertEquals(DefaultCameraNode.DEFAULT_Z / expectedLength, DEFAULT_ORBIT_DIRECTION.z, eps)
    }

    @Test fun radius_isTheDistanceToTheTarget_originTarget() {
        val eye = orbitEyePosition(orbitRadius = 2.5f)
        assertEquals(2.5f, length(eye), eps)
    }

    @Test fun radius_isTheDistanceToTheTarget_offsetTarget() {
        val target = Position(1f, 2f, -3f)
        val eye = orbitEyePosition(orbitRadius = 4f, targetPosition = target)
        assertEquals(4f, length(eye - target), eps)
    }

    @Test fun defaultCameraDistance_reproducesTheDefaultCameraPosition() {
        // |(0, 0.4, 2.75)| ≈ 2.7789 m — the stock SceneView framing.
        val stock = Position(0f, DefaultCameraNode.DEFAULT_Y, DefaultCameraNode.DEFAULT_Z)
        val eye = orbitEyePosition(orbitRadius = length(stock))
        assertEquals(stock.x, eye.x, eps)
        assertEquals(stock.y, eye.y, eps)
        assertEquals(stock.z, eye.z, eps)
    }

    @Test fun eye_isAlwaysOnTheDefaultRayFromTheTarget() {
        val target = Position(0.5f, 0f, 0.5f)
        val eye = orbitEyePosition(orbitRadius = 3f, targetPosition = target)
        val offset = eye - target
        // Same direction: offset / |offset| == DEFAULT_ORBIT_DIRECTION.
        assertEquals(DEFAULT_ORBIT_DIRECTION.x, offset.x / 3f, eps)
        assertEquals(DEFAULT_ORBIT_DIRECTION.y, offset.y / 3f, eps)
        assertEquals(DEFAULT_ORBIT_DIRECTION.z, offset.z / 3f, eps)
        // And the camera is above the target (3/4 angle looks slightly down), in front of it (+Z).
        assert(eye.y > target.y)
        assert(eye.z > target.z)
    }

    @Test fun radius_mustBePositiveAndFinite() {
        assertThrows(IllegalArgumentException::class.java) { orbitEyePosition(0f) }
        assertThrows(IllegalArgumentException::class.java) { orbitEyePosition(-1f) }
        assertThrows(IllegalArgumentException::class.java) { orbitEyePosition(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) {
            orbitEyePosition(Float.POSITIVE_INFINITY)
        }
    }
}
