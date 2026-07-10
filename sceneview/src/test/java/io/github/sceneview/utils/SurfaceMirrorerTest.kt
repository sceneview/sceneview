package io.github.sceneview.utils

import android.graphics.SurfaceTexture
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * State-machine contract for the public [SurfaceMirrorer] API (#2626).
 *
 * [SurfaceMirrorer.startMirroring] is JNI-free by design — the Filament swap chain is created
 * lazily on the render thread at the next frame — so the full start/stop lifecycle is testable
 * on the JVM with Robolectric [Surface]s and no Filament native library:
 *
 * - start registers the surface exactly once (double-start is a guarded no-op)
 * - stop deregisters and is idempotent (stop-twice, stop-unknown, stop-before-any-frame)
 * - multiple surfaces can be mirrored simultaneously and stopped independently
 * - destroy clears every mirror
 */
@RunWith(RobolectricTestRunner::class)
class SurfaceMirrorerTest {

    private fun newSurface() = Surface(SurfaceTexture(0))

    @Test
    fun `startMirroring registers the surface`() {
        val mirrorer = SurfaceMirrorer()
        val surface = newSurface()

        assertFalse(mirrorer.isMirroring(surface))
        mirrorer.startMirroring(surface)

        assertTrue(mirrorer.isMirroring(surface))
        assertEquals(listOf(surface), mirrorer.mirroredSurfaces)
    }

    @Test
    fun `startMirroring twice on the same surface is a no-op`() {
        val mirrorer = SurfaceMirrorer()
        val surface = newSurface()

        mirrorer.startMirroring(surface)
        mirrorer.startMirroring(surface, width = 1280, height = 720)

        assertEquals("double-start must not add a second mirror", 1, mirrorer.mirroredSurfaces.size)
    }

    @Test
    fun `stopMirroring deregisters the surface`() {
        val mirrorer = SurfaceMirrorer()
        val surface = newSurface()

        mirrorer.startMirroring(surface)
        mirrorer.stopMirroring(surface)

        assertFalse(mirrorer.isMirroring(surface))
        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
    }

    @Test
    fun `stopMirroring is idempotent`() {
        val mirrorer = SurfaceMirrorer()
        val surface = newSurface()

        // Stop on a never-started surface — no-op, no throw.
        mirrorer.stopMirroring(surface)

        mirrorer.startMirroring(surface)
        mirrorer.stopMirroring(surface)
        // Second stop — no-op, no throw.
        mirrorer.stopMirroring(surface)

        assertFalse(mirrorer.isMirroring(surface))
    }

    @Test
    fun `stopMirroring before any rendered frame is safe`() {
        // No frame has run → no engine bound, no swap chain created. The whole
        // start/stop cycle must work without a single Filament JNI call.
        val mirrorer = SurfaceMirrorer()
        val surface = newSurface()

        mirrorer.startMirroring(surface, left = 10, bottom = 20, width = 640, height = 480)
        mirrorer.stopMirroring(surface)

        assertTrue(mirrorer.mirroredSurfaces.isEmpty())
    }

    @Test
    fun `multiple surfaces are mirrored simultaneously and stopped independently`() {
        val mirrorer = SurfaceMirrorer()
        val recorderSurface = newSurface()
        val previewSurface = newSurface()

        mirrorer.startMirroring(recorderSurface, width = 1280, height = 720)
        mirrorer.startMirroring(previewSurface)

        assertEquals(2, mirrorer.mirroredSurfaces.size)

        mirrorer.stopMirroring(recorderSurface)

        assertFalse(mirrorer.isMirroring(recorderSurface))
        assertTrue("stopping one surface must not affect the other", mirrorer.isMirroring(previewSurface))
    }

    @Test
    fun `destroy clears every mirror`() {
        val mirrorer = SurfaceMirrorer()
        val surface1 = newSurface()
        val surface2 = newSurface()

        mirrorer.startMirroring(surface1)
        mirrorer.startMirroring(surface2)
        mirrorer.destroy()

        assertTrue(mirrorer.mirroredSurfaces.isEmpty())

        // The mirrorer stays usable after destroy — a new scene can re-wire it.
        mirrorer.startMirroring(surface1)
        assertTrue(mirrorer.isMirroring(surface1))
    }
}
