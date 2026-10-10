package io.github.sceneview

import com.google.android.filament.Fence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the surface callbacks wait for the backend, see [surfaceWaitNanos]: bounded only where
 * the engine is destroyed off the thread — a view that owns its engine, or one that was given an
 * engine created by `rememberEngine()`.
 */
class SurfaceWaitTest {

    @Test
    fun `a view whose engine teardown is deferred waits no longer than the bound`() {
        assertEquals(
            SURFACE_DETACH_WAIT_NANOS,
            surfaceWaitNanos(engineTeardownDeferred = true, boundNanos = SURFACE_DETACH_WAIT_NANOS)
        )
        assertEquals(
            SURFACE_RESIZE_WAIT_NANOS,
            surfaceWaitNanos(engineTeardownDeferred = true, boundNanos = SURFACE_RESIZE_WAIT_NANOS)
        )
    }

    @Test
    fun `a view on an engine destroyed by its owner waits as 2_3_3 did, whatever the bound`() {
        // Engine.flushAndWait() is flushAndWait(Fence.WAIT_FOR_EVER), and the resize fence was
        // waited for with that same timeout: an engine the app created and destroys keeps both.
        for (boundNanos in listOf(0L, SURFACE_RESIZE_WAIT_NANOS, SURFACE_DETACH_WAIT_NANOS)) {
            assertEquals(
                Fence.WAIT_FOR_EVER,
                surfaceWaitNanos(engineTeardownDeferred = false, boundNanos = boundNanos)
            )
        }
    }

    @Test
    fun `no bound can be mistaken for the unbounded wait`() {
        assertTrue(Fence.WAIT_FOR_EVER < 0L)
        assertTrue(SURFACE_DETACH_WAIT_NANOS > 0L)
        assertTrue(SURFACE_RESIZE_WAIT_NANOS > 0L)
    }
}
