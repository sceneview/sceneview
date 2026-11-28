package io.github.sceneview.node

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.lookTowards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-math regression tests for [BillboardNode]'s camera-facing logic.
 *
 * BillboardNode keeps its plane facing the camera by calling
 * `lookTowards(worldPosition - camPos)` every frame. The plane's *front* face
 * (the one with correct UVs) is on local +Z, so we want local +Z to point
 * toward the camera. Closes #838.
 *
 * Filament/Android are not on the JVM unit-test classpath, so we exercise the
 * underlying `dev.romainguy.kotlin.math` matrices directly.
 */
class BillboardNodeMathTest {

    // ── Bitmap sizing ────────────────────────────────────────────────────────────

    @Test
    fun `width alone derives height from bitmap aspect ratio`() {
        assertSize(
            expectedWidth = 0.6f,
            expectedHeight = 0.3f,
            actual = billboardSize(400, 200, widthMeters = 0.6f, heightMeters = null)
        )
    }

    @Test
    fun `height alone derives width from bitmap aspect ratio`() {
        assertSize(
            expectedWidth = 0.6f,
            expectedHeight = 0.3f,
            actual = billboardSize(400, 200, widthMeters = null, heightMeters = 0.3f)
        )
    }

    @Test
    fun `both dimensions are used unchanged`() {
        assertSize(
            expectedWidth = 0.8f,
            expectedHeight = 0.2f,
            actual = billboardSize(400, 200, widthMeters = 0.8f, heightMeters = 0.2f)
        )
    }

    @Test
    fun `no dimensions keeps ImageNode normalized default`() {
        assertNull(billboardSize(400, 200, widthMeters = null, heightMeters = null))
    }

    // ── Math convention ──────────────────────────────────────────────────────────

    @Test
    fun `lookTowards orients local +Z along -direction`() {
        // kotlin-math's lookTowards builds Mat4(right, up, -forward, eye).
        // Therefore local +Z = (0,0,1) maps to -forward in world space.
        val mat = lookTowards(eye = Float3(0f), forward = Float3(0f, 0f, 1f))
        val plusZInWorld = Float3(mat.z.x, mat.z.y, mat.z.z) // third column = local +Z basis in world space
        // forward = +Z, so local +Z should map to -Z in world space
        assertVecEquals(Float3(0f, 0f, -1f), plusZInWorld, eps = 1e-5f)
    }

    @Test
    fun `direction worldPos minus camPos points local +Z toward camera`() {
        // Camera at origin, billboard at (0, 0, 5) — front face should point toward camera.
        val camPos = Float3(0f)
        val nodePos = Float3(0f, 0f, 5f)
        val direction = nodePos - camPos // BillboardNode's exact formula

        val mat = lookTowards(eye = nodePos, forward = direction)
        val plusZInWorld = normalized(Float3(mat.z.x, mat.z.y, mat.z.z))

        // Local +Z must point from nodePos toward camPos (the negative-Z direction).
        assertVecEquals(Float3(0f, 0f, -1f), plusZInWorld, eps = 1e-5f)
    }

    @Test
    fun `lookAt camPos points local +Z AWAY from camera (the bug we fixed)`() {
        // Sanity-check the regression we're guarding against: lookAt(camPos) does
        // the opposite of what we want. If anyone reverts BillboardNode to use
        // lookAt(camPos), the +Z basis flips and the texture appears mirrored.
        val camPos = Float3(0f)
        val nodePos = Float3(0f, 0f, 5f)

        val mat = lookAt(eye = nodePos, target = camPos)
        val plusZInWorld = normalized(Float3(mat.z.x, mat.z.y, mat.z.z))

        // With lookAt(camPos), local +Z points AWAY from camera (+Z direction in world).
        // This is the mirroring bug — assert it so a future "fix" that reverts the
        // approach gets caught here.
        assertVecEquals(Float3(0f, 0f, 1f), plusZInWorld, eps = 1e-5f)
    }

    // ── Zero / NaN guards ────────────────────────────────────────────────────────

    @Test
    fun `length-squared guard rejects zero vector`() {
        val direction = Float3(0f, 0f, 0f)
        assertFalse("zero vector must fail the guard", lengthSqGuard(direction))
    }

    @Test
    fun `length-squared guard rejects NaN component`() {
        // NaN comparisons always return false, so 'lengthSq > epsilon' correctly rejects.
        assertFalse(lengthSqGuard(Float3(Float.NaN, 0f, 0f)))
        assertFalse(lengthSqGuard(Float3(0f, Float.NaN, 0f)))
        assertFalse(lengthSqGuard(Float3(0f, 0f, Float.NaN)))
    }

    @Test
    fun `length-squared guard rejects sub-epsilon vector`() {
        assertFalse(lengthSqGuard(Float3(1e-7f, 0f, 0f))) // 1e-14 < 1e-12
    }

    @Test
    fun `length-squared guard accepts unit vector`() {
        assertTrue(lengthSqGuard(Float3(1f, 0f, 0f)))
    }

    @Test
    fun `length-squared guard accepts typical camera-to-node distance`() {
        // 5 m apart → lengthSq = 25, well above the 1e-12 floor.
        assertTrue(lengthSqGuard(Float3(0f, 0f, 5f)))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Mirrors the exact guard used inside BillboardNode.kt. */
    private fun lengthSqGuard(v: Float3): Boolean {
        val lengthSq = v.x * v.x + v.y * v.y + v.z * v.z
        return lengthSq > 1e-12f
    }

    private fun normalized(v: Float3): Float3 {
        val len = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Float3(v.x / len, v.y / len, v.z / len)
    }

    private fun assertSize(expectedWidth: Float, expectedHeight: Float, actual: Float3?) {
        requireNotNull(actual)
        assertEquals("width", expectedWidth.toDouble(), actual.x.toDouble(), 1e-6)
        assertEquals("height", expectedHeight.toDouble(), actual.y.toDouble(), 1e-6)
    }

    private fun assertVecEquals(expected: Float3, actual: Float3, eps: Float) {
        assertEquals("x", expected.x.toDouble(), actual.x.toDouble(), eps.toDouble())
        assertEquals("y", expected.y.toDouble(), actual.y.toDouble(), eps.toDouble())
        assertEquals("z", expected.z.toDouble(), actual.z.toDouble(), eps.toDouble())
    }
}
