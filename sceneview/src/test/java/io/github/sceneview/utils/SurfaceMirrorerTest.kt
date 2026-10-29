package io.github.sceneview.utils

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

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

    @Test
    fun `a new mirror wakes the scene that feeds it, once`() {
        val mirrorer = SurfaceMirrorer()
        var wakes = 0
        mirrorer.host = object : SurfaceMirrorer.Host {
            override val renderView: android.view.View? = null
            override fun requestRender() {
                wakes++
            }
        }
        val surface = newSurface()

        mirrorer.startMirroring(surface)
        mirrorer.startMirroring(surface)

        // A parked scene only reads `isMirroring` from inside a frame: without this wake a
        // recording started on a still scene would never receive one.
        assertEquals(1, wakes)
    }

    @Test
    fun `a wake requested off the main thread runs on the main thread`() {
        val mirrorer = SurfaceMirrorer()
        val wakeThreads = mutableListOf<Thread>()
        mirrorer.host = object : SurfaceMirrorer.Host {
            override val renderView: android.view.View? = null
            override fun requestRender() {
                wakeThreads += Thread.currentThread()
            }
        }
        val surface = newSurface()

        thread { mirrorer.startMirroring(surface) }.join()
        assertTrue(wakeThreads.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(Looper.getMainLooper().thread), wakeThreads)
    }

    @Test
    fun `losing the scene notifies the mirrorer's owner`() {
        val mirrorer = SurfaceMirrorer()
        var lost = 0
        mirrorer.onHostLost = { lost++ }

        mirrorer.host = host()
        mirrorer.host = host() // a scene handing over to another is not a loss
        assertEquals(0, lost)

        mirrorer.host = null
        assertEquals(1, lost)

        mirrorer.host = null // already gone
        assertEquals(1, lost)
    }

    @Test
    fun `destroying the scene notifies the mirrorer's owner`() {
        val mirrorer = SurfaceMirrorer()
        var mirroredWhenLost: List<Surface>? = null
        mirrorer.onHostLost = { mirroredWhenLost = mirrorer.mirroredSurfaces }
        mirrorer.startMirroring(newSurface())

        mirrorer.destroy()

        // Called after the mirrors are torn down: a recording stopping from there unmirrors nothing.
        assertEquals(emptyList<Surface>(), mirroredWhenLost)
    }

    private fun host() = object : SurfaceMirrorer.Host {
        override val renderView: android.view.View? = null
        override fun requestRender() = Unit
    }
}
