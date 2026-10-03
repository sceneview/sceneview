package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the replay says when it has no surface to show: the reason it gives is the real one. */
class RerunSurfaceFailureTest {

    @Test
    fun `a mesh that came out empty is the scan's doing`() {
        assertEquals(RerunSurfaceFailure.PoorScan, RerunSurfaceFailure.of(null))
    }

    @Test
    fun `running out of memory is not blamed on the scan`() {
        assertEquals(RerunSurfaceFailure.OutOfMemory, RerunSurfaceFailure.of(OutOfMemoryError()))
    }

    @Test
    fun `a build or a model that failed is not blamed on the scan either`() {
        assertEquals(RerunSurfaceFailure.Unavailable, RerunSurfaceFailure.of(IllegalStateException("build")))
        // What the model loader throws on a `.glb` it cannot parse.
        assertEquals(RerunSurfaceFailure.Unavailable, RerunSurfaceFailure.of(IllegalArgumentException("parse")))
    }

    @Test
    fun `each reason has its own line`() {
        val lines = RerunSurfaceFailure.entries.map { it.message }
        assertEquals(lines.size, lines.distinct().size)
    }
}
