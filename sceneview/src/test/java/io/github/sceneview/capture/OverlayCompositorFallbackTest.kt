package io.github.sceneview.capture

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The default video overlay compositor is the GL one (no CPU wait on the scene's frames); a device
 * where it cannot be set up falls back to the ImageReader one instead of losing the overlay.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayCompositorFallbackTest {

    private class Stub : OverlayCompositor {
        override val inputSurface = Surface(SurfaceTexture(0))
        override fun release() = Unit
    }

    @Test
    fun `the primary compositor is used when it sets up`() {
        val primary = Stub()
        var fallbacks = 0

        val chosen = createFirstWorking(primary = { primary }, fallback = { fallbacks++; Stub() })

        assertSame(primary, chosen)
        assertEquals(0, fallbacks)
    }

    @Test
    fun `a primary that throws falls back`() {
        val fallback = Stub()

        val chosen = createFirstWorking(
            primary = { throw IllegalStateException("No recordable EGL config") },
            fallback = { fallback },
        )

        assertSame(fallback, chosen)
    }

    @Test(expected = IllegalStateException::class)
    fun `a fallback that throws too fails the recording`() {
        createFirstWorking(
            primary = { throw IllegalStateException("eglCreateWindowSurface failed") },
            fallback = { throw IllegalStateException("ImageReader failed") },
        )
    }
}
