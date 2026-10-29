package io.github.sceneview.utils

import io.github.sceneview.SURFACE_DETACH_WAIT_NANOS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [SurfaceMirrorer.stopMirroring] does with the swap chains of the mirrors it stops, run
 * against [releaseSwapChains] — the one sequence it goes through, with the Filament calls
 * replaced by recorders.
 *
 * A mirror's swap chain is created at its first frame, and Filament only queues that creation:
 * the backend runs it later, and it reads the [android.view.Surface] then (`eglSurfaceAttrib` on
 * the window). Destroying the swap chain only queues another command, so a recorder that released
 * its input surface as soon as `stopMirroring` returned could free it before the backend reached
 * the creation, and Filament aborted the process. `stopMirroring` now waits for the backend, with
 * the bound a scene's own surface detach uses.
 */
class SurfaceMirrorerReleaseTest {

    private val calls = mutableListOf<String>()

    private fun release(swapChains: List<String>, backendCatchesUp: Boolean = true) =
        releaseSwapChains(
            swapChains,
            destroySwapChain = { calls += "destroy $it" },
            flushAndWait = { timeoutNanos ->
                calls += "wait $timeoutNanos"
                backendCatchesUp
            },
        )

    @Test
    fun `the swap chains are destroyed, then the backend is waited for`() {
        assertTrue(release(listOf("recorder", "preview")))

        assertEquals(
            listOf("destroy recorder", "destroy preview", "wait $SURFACE_DETACH_WAIT_NANOS"),
            calls,
        )
    }

    @Test
    fun `a mirror stopped before its first frame waits for nothing`() {
        assertTrue(release(emptyList()))

        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `a backend still busy at the bound is reported, not waited for longer`() {
        assertFalse(release(listOf("recorder"), backendCatchesUp = false))

        assertEquals(listOf("destroy recorder", "wait $SURFACE_DETACH_WAIT_NANOS"), calls)
    }
}
