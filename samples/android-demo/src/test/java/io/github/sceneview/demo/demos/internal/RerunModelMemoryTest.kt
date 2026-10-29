package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * A big scan's raw surface shown while it finishes, in a phone's heap: what the result screen
 * adds beside the finish, and when running out of memory is final.
 */
class RerunModelMemoryTest {
    @Test
    fun `the ground is read off the model the screen settles on, not beside its finish`() {
        val raw = mesh(finished = false)
        assertNull("ground decoded beside the finish", groundSource(raw, settled = false))
        val finished = mesh(finished = true)
        assertSame(finished, groundSource(finished, settled = true))
        val kept = mesh(finished = false, outOfMemoryInHeap = HEAP)
        assertSame(kept, groundSource(kept, settled = true))
        assertNull(groundSource(model = null, settled = true))
    }

    @Test
    fun `a model the heap has no room to copy for the renderer is left unshown, not thrown`() {
        val model = mesh(finished = true, glb = GLB)
        val copied = runCatching { model.glbBuffer { throw OutOfMemoryError("no room for $it bytes") } }
        assertTrue("thrown: ${copied.exceptionOrNull()}", copied.isSuccess)
        assertNull(copied.getOrNull())

        val buffer = checkNotNull(model.glbBuffer(ByteBuffer::allocate))
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        assertArrayEquals(GLB, bytes)
    }

    @Test
    fun `a finish out of memory is tried again before the raw surface is kept for good`() {
        assertEquals(3, RerunScanModel.OUT_OF_MEMORY_ATTEMPTS)
        val first = mesh(finished = false, outOfMemoryInHeap = HEAP)
        assertTrue("one attempt", first.retriesFinish)
        val last = mesh(finished = false, outOfMemoryInHeap = HEAP, attempts = RerunScanModel.OUT_OF_MEMORY_ATTEMPTS)
        assertFalse("the last attempt", last.retriesFinish)
        assertFalse("finished", mesh(finished = true).retriesFinish)
        assertFalse("not kept: built again anyway", mesh(finished = false).retriesFinish)
    }

    @Test
    fun `each finish run again counts, until one finishes`() {
        val kept = mesh(finished = false, outOfMemoryInHeap = HEAP)
        val again = kept.finishedAgain(mesh(finished = false, outOfMemoryInHeap = HEAP))
        assertEquals(HEAP, again.outOfMemoryInHeap)
        assertEquals("attempts after running out again", 2, again.outOfMemoryAttempts)
        val failed = kept.finishedAgain(null)
        assertEquals("attempts after a finish that failed", 2, failed.outOfMemoryAttempts)
        assertTrue(failed.keptWithScan)
        val finished = mesh(finished = true)
        assertSame(finished, kept.finishedAgain(finished))
    }

    @Test
    fun `the attempts are kept with the scan`() {
        val kept = mesh(finished = false, outOfMemoryInHeap = HEAP, attempts = 2, glb = GLB)
        val read = checkNotNull(RerunScanModel.parse(kept.infoJson(), GLB, HEAP))
        assertEquals(2, read.outOfMemoryAttempts)
        assertTrue(read.retriesFinish)

        // A model.json written before attempts were counted: its one finish.
        val older = kept.infoJson().replace(Regex(""",\s*"outOfMemoryAttempts":\s*\d+"""), "")
        assertEquals(1, checkNotNull(RerunScanModel.parse(older, GLB, HEAP)).outOfMemoryAttempts)
    }

    private fun mesh(
        finished: Boolean,
        outOfMemoryInHeap: Long? = null,
        attempts: Int = 1,
        glb: ByteArray = ByteArray(0),
    ) = RerunScanModel(
        RerunScanModel.Kind.Mesh,
        glb,
        FloatArray(BOUNDS),
        1,
        3,
        finished = finished,
        outOfMemoryInHeap = outOfMemoryInHeap,
        outOfMemoryAttempts = attempts,
    )

    private companion object {
        const val BOUNDS = 6
        const val HEAP = 192L * 1024 * 1024

        /** A `.glb` header ([RerunScanModel.parse] reads the magic) and a few bytes. */
        val GLB = byteArrayOf(0x67, 0x6C, 0x54, 0x46, 2, 0, 0, 0, 16, 0, 0, 0, 1, 2, 3, 4)
    }
}
