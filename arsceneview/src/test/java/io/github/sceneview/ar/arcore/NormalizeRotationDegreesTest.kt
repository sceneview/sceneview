package io.github.sceneview.ar.arcore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM regression test for [normalizeRotationDegrees] — the rotation-quadrant snapping used
 * by [Image.toArgbBitmap] when making a captured AR camera frame upright (#1553).
 *
 * The Android `Bitmap` / `Image` work the conversion does is impossible to run under pure JVM,
 * so the rotation contract was extracted into [normalizeRotationDegrees]; this test pins it:
 *
 *  - exact quadrant values pass through unchanged,
 *  - off-quadrant values snap to the nearest quadrant,
 *  - negative inputs and inputs ≥ 360 wrap into `[0, 360)` first.
 */
class NormalizeRotationDegreesTest {

    @Test
    fun `exact quadrant values pass through unchanged`() {
        assertEquals(0, normalizeRotationDegrees(0))
        assertEquals(90, normalizeRotationDegrees(90))
        assertEquals(180, normalizeRotationDegrees(180))
        assertEquals(270, normalizeRotationDegrees(270))
    }

    @Test
    fun `off-quadrant values snap to the nearest quadrant`() {
        assertEquals(0, normalizeRotationDegrees(44))
        assertEquals(90, normalizeRotationDegrees(45))
        assertEquals(90, normalizeRotationDegrees(134))
        assertEquals(180, normalizeRotationDegrees(135))
        assertEquals(270, normalizeRotationDegrees(314))
        assertEquals(0, normalizeRotationDegrees(315))
    }

    @Test
    fun `360 wraps to 0`() {
        assertEquals(0, normalizeRotationDegrees(360))
    }

    @Test
    fun `values above 360 wrap before snapping`() {
        assertEquals(90, normalizeRotationDegrees(450)) // 450 - 360 = 90
        assertEquals(180, normalizeRotationDegrees(540)) // 540 - 360 = 180
    }

    @Test
    fun `negative values wrap into the positive range before snapping`() {
        assertEquals(270, normalizeRotationDegrees(-90)) // -90 -> 270
        assertEquals(180, normalizeRotationDegrees(-180)) // -180 -> 180
        assertEquals(0, normalizeRotationDegrees(-360)) // -360 -> 0
    }
}
