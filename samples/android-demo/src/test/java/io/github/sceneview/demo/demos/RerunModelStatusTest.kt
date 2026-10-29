package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.RerunScanModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the model screen says under the model, from building it to the raw surface left in its place. */
class RerunModelStatusTest {
    @Test
    fun `a big scan's raw surface shown meanwhile says the model is being finished`() {
        assertEquals(ModelStatus.Creating, ModelStatus.of(model = null, settled = false))
        assertEquals(ModelStatus.Finishing, ModelStatus.of(mesh(finished = false), settled = false))
    }

    @Test
    fun `a raw surface finished again as its scan reopens says so, not the first finish's line`() {
        // Kept for running out of memory, with attempts left: finished again behind it.
        val again = mesh(finished = false, outOfMemoryInHeap = HEAP)
        assertTrue(again.retriesFinish)
        val status = ModelStatus.of(again, settled = false)
        assertTrue("says $status", status != null && status != ModelStatus.Finishing && status != ModelStatus.Creating)
        assertTrue("$status keeps the raw lines' room", status in ModelStatus.RAW_LINES)
        val slot = checkNotNull(ModelStatus.slot(again, settled = false, rawShownFirst = true))
        assertEquals(status, slot.status)
        assertTrue(slot.visible)
        val ends = listOf(ModelStatus.Finished, ModelStatus.RawOutOfMemory, ModelStatus.RawUnfinished)
        assertTrue("reserved: ${slot.reserve}", slot.reserve.containsAll(ends))
    }

    @Test
    fun `the raw surface left in the finished model's place never goes without a word`() {
        val outOfMemory = mesh(finished = false, outOfMemoryInHeap = HEAP, attempts = RerunScanModel.OUT_OF_MEMORY_ATTEMPTS)
        assertEquals(ModelStatus.RawOutOfMemory, ModelStatus.of(outOfMemory, settled = true))
        assertEquals(ModelStatus.RawUnfinished, ModelStatus.of(mesh(finished = false), settled = true))
    }

    @Test
    fun `a raw surface out of memory with finishes left says the next open finishes it, not that the phone is too small`() {
        // Its first finish ran out of memory: the next open finishes it again, so the phone's
        // memory is not the last word yet.
        val retried = mesh(finished = false, outOfMemoryInHeap = HEAP)
        assertTrue(retried.retriesFinish)
        val status = ModelStatus.of(retried, settled = true)
        assertTrue("says $status", status != null && status != ModelStatus.RawOutOfMemory)
        assertTrue("$status keeps the raw lines' room", status in ModelStatus.RAW_LINES)
        // Its last finish spent: kept as scanned for good, for the phone's memory.
        val spent = mesh(finished = false, outOfMemoryInHeap = HEAP, attempts = RerunScanModel.OUT_OF_MEMORY_ATTEMPTS)
        assertFalse(spent.retriesFinish)
        assertEquals(ModelStatus.RawOutOfMemory, ModelStatus.of(spent, settled = true))
    }

    @Test
    fun `the finished model replacing a big scan's raw surface is said once, then the line fades in place`() {
        // While the raw surface finishes, the line shows over it.
        val finishing = checkNotNull(ModelStatus.slot(mesh(finished = false), settled = false, rawShownFirst = false))
        assertEquals(ModelStatus.Finishing, finishing.status)
        assertTrue(finishing.visible)
        // The swap: the line says the model is finished, heard once as it changes.
        val swapped = checkNotNull(ModelStatus.slot(mesh(finished = true), settled = true, rawShownFirst = true))
        assertEquals(ModelStatus.Finished, swapped.status)
        assertTrue(swapped.visible)
        // Said, it fades but keeps its room: the model is framed above this slot, and a line that
        // went would move the finished model just after it took the raw one's place.
        val said = checkNotNull(
            ModelStatus.slot(mesh(finished = true), settled = true, rawShownFirst = true, finishedSaid = true),
        )
        assertEquals(ModelStatus.Finished, said.status)
        assertFalse(said.visible)
        // A finish that fails after the raw surface was shown still says why, and stays.
        val failed = checkNotNull(
            ModelStatus.slot(mesh(finished = false), settled = true, rawShownFirst = true, finishedSaid = true),
        )
        assertEquals(ModelStatus.RawUnfinished, failed.status)
        assertTrue(failed.visible)
    }

    @Test
    fun `a big scan's line holds the room of every way its finish can end`() {
        val finishing = checkNotNull(ModelStatus.slot(mesh(finished = false), settled = false, rawShownFirst = false))
        val endings = listOf(
            ModelStatus.slot(mesh(finished = true), settled = true, rawShownFirst = true),
            ModelStatus.slot(mesh(finished = false, outOfMemoryInHeap = HEAP), settled = true, rawShownFirst = true),
            ModelStatus.slot(mesh(finished = false), settled = true, rawShownFirst = true),
        ).map { checkNotNull(it) }
        val ends = listOf(ModelStatus.Finished, ModelStatus.RawOutOfMemory, ModelStatus.RawUnfinished)
        assertTrue("reserved: ${finishing.reserve}", finishing.reserve.containsAll(ends + ModelStatus.Finishing))
        endings.forEach { assertEquals("${it.status} reserves", finishing.reserve, it.reserve) }
    }

    @Test
    fun `a small scan or a reopened model never had a line under it, so none is laid out`() {
        assertNull(ModelStatus.slot(mesh(finished = true), settled = true, rawShownFirst = false))
        val creating = checkNotNull(ModelStatus.slot(model = null, settled = false, rawShownFirst = false))
        assertEquals(ModelStatus.Creating, creating.status)
        assertTrue(creating.visible)
    }

    @Test
    fun `a finished model needs no word, a failure or the points do`() {
        assertNull(ModelStatus.of(mesh(finished = true), settled = true))
        assertEquals(ModelStatus.Failed, ModelStatus.of(model = null, settled = true))
        val points = RerunScanModel(RerunScanModel.Kind.Points, ByteArray(0), FloatArray(BOUNDS), 1, 3, finished = false)
        assertEquals(ModelStatus.Partial, ModelStatus.of(points, settled = true))
    }

    private fun mesh(finished: Boolean, outOfMemoryInHeap: Long? = null, attempts: Int = 1) = RerunScanModel(
        RerunScanModel.Kind.Mesh,
        ByteArray(0),
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
    }
}
