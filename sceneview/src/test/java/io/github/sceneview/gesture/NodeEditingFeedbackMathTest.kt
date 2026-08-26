package io.github.sceneview.gesture

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the gesture-feedback math behind [NodeEditingListener]:
 * the pinch clamp evaluation against the **absolute** `editableScaleRange`, and the
 * saturation-free yaw extraction that replaces `rotation.y` reads.
 */
class NodeEditingFeedbackMathTest {

    private val eps = 1e-3f
    private val defaultRange = 0.1f..10.0f

    // ── evaluateScaleEdit ─────────────────────────────────────────────────────────────────────

    @Test
    fun `in-range update is applied with no limit`() {
        val edit = evaluateScaleEdit(Float3(1f), 1.1f, defaultRange)

        assertTrue(edit.applied)
        assertNull(edit.limit)
        assertEquals(1.1f, edit.scale.x, eps)
    }

    @Test
    fun `grow past the upper bound is rejected as Max`() {
        val edit = evaluateScaleEdit(Float3(9.8f), 1.05f, defaultRange)

        assertFalse(edit.applied)
        assertEquals(NodeScaleLimit.Max, edit.limit)
    }

    @Test
    fun `shrink past the lower bound is rejected as Min`() {
        val edit = evaluateScaleEdit(Float3(0.102f), 0.95f, defaultRange)

        assertFalse(edit.applied)
        assertEquals(NodeScaleLimit.Min, edit.limit)
    }

    @Test
    fun `range is absolute local scale, not a factor - scaleToUnits trap`() {
        // A model placed with scaleToUnits can start at local scale 0.02 — below the
        // DEFAULT range's 0.1 floor. Every shrink AND every grow that keeps any axis
        // outside the window is rejected: the range is a window on absolute scale.
        val edit = evaluateScaleEdit(Float3(0.02f), 1.2f, defaultRange)

        assertFalse(edit.applied)
        // Growing (factor > 1) while still under the floor reports Max by direction
        // convention — the practical fix is an app-side range around the start scale.
        assertEquals(NodeScaleLimit.Max, edit.limit)
    }

    @Test
    fun `any single out-of-range axis rejects the whole update`() {
        val edit = evaluateScaleEdit(Float3(1f, 9.99f, 1f), 1.05f, defaultRange)

        assertFalse(edit.applied)
    }

    // ── quaternionYawDegrees ──────────────────────────────────────────────────────────────────

    private fun yQuaternion(degrees: Float) =
        Quaternion.fromAxisAngle(Float3(y = 1f), degrees)

    @Test
    fun `identity is zero yaw`() {
        assertEquals(0f, quaternionYawDegrees(Quaternion()), eps)
    }

    @Test
    fun `yaw tracks a Y rotation over the full turn without saturating at 90`() {
        // The rotation dot y Euler decomposition saturates at plus minus 90 — this must not.
        for (angle in listOf(30f, 90f, 120f, 179f, -45f, -90f, -135f)) {
            assertEquals("yaw($angle)", angle, quaternionYawDegrees(yQuaternion(angle)), 0.01f)
        }
    }

    @Test
    fun `yaw of accumulated small deltas matches the total`() {
        // Mirrors how NodeGestureDelegate applies a twist: many small Y-axis deltas
        // post-multiplied onto the node quaternion.
        var q = Quaternion()
        repeat(140) { q *= yQuaternion(1f) } // 140° — past the Euler saturation point

        assertEquals(140f, quaternionYawDegrees(q), 0.05f)
    }

    @Test
    fun `delta quaternion yaw is signed`() {
        assertEquals(-2.5f, quaternionYawDegrees(yQuaternion(-2.5f)), eps)
    }
}
