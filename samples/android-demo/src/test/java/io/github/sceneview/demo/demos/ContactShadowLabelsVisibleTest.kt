package io.github.sceneview.demo.demos

import io.github.sceneview.math.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When the Contact Shadow labels are shown (#3802) — the rule the demo reads from
 * `SceneView(onFrame = …)` instead of a `Node.onFrame` that kept the scene rendering (#4450).
 */
class ContactShadowLabelsVisibleTest {

    /** The home eye swung [yawDegrees] around the orbit target, at its own height and distance. */
    private fun eyeAt(yawDegrees: Double): Position {
        val dx = CONTACT_CAMERA_EYE.x - CONTACT_CAMERA_TARGET.x
        val dz = CONTACT_CAMERA_EYE.z - CONTACT_CAMERA_TARGET.z
        val radius = sqrt(dx * dx + dz * dz)
        val yaw = Math.toRadians(yawDegrees)
        return Position(
            x = CONTACT_CAMERA_TARGET.x + (sin(yaw) * radius).toFloat(),
            y = CONTACT_CAMERA_EYE.y,
            z = CONTACT_CAMERA_TARGET.z + (cos(yaw) * radius).toFloat(),
        )
    }

    @Test
    fun `the labels are shown from the home shot`() {
        assertTrue(contactShadowLabelsVisible(CONTACT_CAMERA_EYE))
        assertTrue(contactShadowLabelsVisible(contactShadowHomeShot(strip = true).eye))
    }

    @Test
    fun `the labels stay through a small orbit, on either side`() {
        assertTrue(contactShadowLabelsVisible(eyeAt(30.0)))
        assertTrue(contactShadowLabelsVisible(eyeAt(-30.0)))
    }

    @Test
    fun `the labels hide once the orbit nears broadside, and behind the wall`() {
        assertFalse(contactShadowLabelsVisible(eyeAt(50.0)))
        assertFalse(contactShadowLabelsVisible(eyeAt(-90.0)))
        assertFalse(contactShadowLabelsVisible(eyeAt(180.0)))
    }
}
