package io.github.sceneview.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #5202: a context's pbuffer must be released with it, once, and never leak. */
class ContextSurfacesTest {

    @Test
    fun restoringAContextsSurfaceDoesNotReleaseItsOwnership() {
        val surfaces = ContextSurfaces<String, String>()
        surfaces.adopt("context", "pbuffer")
        repeat(500) { assertEquals("pbuffer", surfaces.surface("context")) }
        assertEquals(1, surfaces.size)
        assertEquals("pbuffer", surfaces.release("context"))
        assertNull(surfaces.surface("context"))
    }

    @Test
    fun releaseReturnsTheAdoptedSurfaceOnce() {
        val surfaces = ContextSurfaces<String, String>()
        surfaces.adopt("context", "pbuffer")

        assertEquals("pbuffer", surfaces.release("context"))
        assertNull("a second destroy must not destroy the surface again", surfaces.release("context"))
        assertEquals(0, surfaces.size)
    }

    @Test
    fun aContextNotCreatedBySceneViewHasNoSurface() {
        val surfaces = ContextSurfaces<String, String>()

        assertNull(surfaces.release("caller's own context"))
    }

    @Test
    fun fastOpenAndCloseLeavesNothingBehind() {
        val surfaces = ContextSurfaces<Int, String>()
        repeat(100) { engine ->
            surfaces.adopt(engine, "pbuffer-$engine")
            if (engine > 0) surfaces.release(engine - 1)
        }

        assertEquals("only the last engine's context is still alive", 1, surfaces.size)
        assertEquals("pbuffer-99", surfaces.release(99))
    }

    @Test
    fun adoptingAgainReplacesTheSurface() {
        val surfaces = ContextSurfaces<String, String>()
        surfaces.adopt("context", "first")
        surfaces.adopt("context", "second")

        assertEquals(1, surfaces.size)
        assertEquals("second", surfaces.release("context"))
    }
}
