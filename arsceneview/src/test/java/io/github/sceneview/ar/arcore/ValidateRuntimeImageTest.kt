package io.github.sceneview.ar.arcore

import android.graphics.Bitmap
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric regression test for [validateRuntimeImage] — the pure-logic pre-flight checks for
 * [RuntimeAugmentedImageDatabase.addImage] (#1553).
 *
 * ARCore's `AugmentedImageDatabase.addImage` rejects bad arguments with a generic
 * `IllegalArgumentException` thrown deep inside a JNI call; [validateRuntimeImage] surfaces the
 * same constraints in plain Kotlin so the failure reason is actionable and unit-testable.
 * Robolectric provides a working `Bitmap` so the `ARGB_8888`/size checks can run on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class ValidateRuntimeImageTest {

    private fun argb8888(width: Int = 64, height: Int = 64): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    @Test
    fun `valid ARGB_8888 bitmap with null width passes`() {
        assertNull(validateRuntimeImage("photo", argb8888(), widthInMeters = null))
    }

    @Test
    fun `valid ARGB_8888 bitmap with positive width passes`() {
        assertNull(validateRuntimeImage("photo", argb8888(), widthInMeters = 0.15f))
    }

    @Test
    fun `blank name is rejected`() {
        val reason = validateRuntimeImage("   ", argb8888(), widthInMeters = null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("name", ignoreCase = true))
    }

    @Test
    fun `empty name is rejected`() {
        assertNotNull(validateRuntimeImage("", argb8888(), widthInMeters = null))
    }

    @Test
    fun `non ARGB_8888 bitmap is rejected`() {
        val rgb565 = Bitmap.createBitmap(64, 64, Bitmap.Config.RGB_565)
        val reason = validateRuntimeImage("photo", rgb565, widthInMeters = null)
        assertNotNull(reason)
        assertTrue(reason!!.contains("ARGB_8888"))
    }

    @Test
    fun `zero width in meters is rejected`() {
        val reason = validateRuntimeImage("photo", argb8888(), widthInMeters = 0f)
        assertNotNull(reason)
        assertTrue(reason!!.contains("widthInMeters"))
    }

    @Test
    fun `negative width in meters is rejected`() {
        assertNotNull(validateRuntimeImage("photo", argb8888(), widthInMeters = -1f))
    }
}
