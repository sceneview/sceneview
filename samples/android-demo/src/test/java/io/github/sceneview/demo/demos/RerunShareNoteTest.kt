package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a share says when the light copy is not what went out, and when it gives up on making it. */
class RerunShareNoteTest {

    @Test
    fun `the light copy is made only when its working arrays fit in half the free heap`() {
        val workspace = RerunMeshSimplifier.workspaceBytes(vertices = 200_000, triangles = 400_000)
        assertTrue(lightShareFits(200_000, 400_000, free = workspace * 2))
        assertFalse(lightShareFits(200_000, 400_000, free = workspace * 2 - 2))
        assertFalse(lightShareFits(200_000, 400_000, free = 0))
    }

    @Test
    fun `running out of memory is named, anything else is a share that failed`() {
        assertEquals(RerunShareNote.OutOfMemory, RerunShareNote.of(OutOfMemoryError()))
        assertEquals(RerunShareNote.Failed, RerunShareNote.of(IllegalArgumentException("no triangles")))
        assertEquals(RerunShareNote.Failed, RerunShareNote.of(java.io.IOException("disk full")))
    }

    @Test
    fun `each note has its own line`() {
        val lines = RerunShareNote.entries.map { it.message }
        assertEquals(lines.size, lines.distinct().size)
    }
}
