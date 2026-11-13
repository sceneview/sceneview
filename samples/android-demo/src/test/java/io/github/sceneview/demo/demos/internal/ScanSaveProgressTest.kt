package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSaveProgressTest {

    @Test
    fun `a room's save names its real steps, packing rather than painting`() {
        var progress = ScanSaveProgress.start(linesUp = false, paints = false)
        assertEquals("Step 1 of 5 · Finishing the last photos…", ScanSaveCopy.line(progress))
        progress = progress.at(ScanSaveStep.Fusing)
        assertEquals("Step 2 of 5 · Fusing 224k points…", ScanSaveCopy.line(progress, "Fusing 224k points…"))
        progress = progress.at(ScanSaveStep.Building)
        assertEquals("Step 3 of 5 · Packing the scan…", ScanSaveCopy.line(progress))
        progress = progress.at(ScanSaveStep.Writing)
        assertEquals("Step 5 of 5 · Saving on this phone…", ScanSaveCopy.line(progress))
        assertEquals(0.8f, progress.fraction, 1e-6f)
    }

    @Test
    fun `a capture with a plane layer paints, and a part scanned again lines up`() {
        val painting = ScanSaveProgress.start(linesUp = true).at(ScanSaveStep.Building)
        assertEquals("Step 3 of 6 · Painting surfaces from photos…", ScanSaveCopy.line(painting))
        assertEquals(4, painting.at(ScanSaveStep.LiningUp).number)
    }

    @Test
    fun `a save that does not line up never counts that step`() {
        val progress = ScanSaveProgress.start(linesUp = false).at(ScanSaveStep.LiningUp)
        assertEquals(4, progress.number)
        assertEquals(5, progress.total)
    }
}
