package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/** Build 3D model's steps (#4903): each step's number, words and share of the bar. */
class ScanSaveProgressTest {
    @Test
    fun `a save runs five steps, each a fifth of the bar`() {
        val steps = listOf(
            ScanSaveStep.Finishing to "Step 1 of 5 · Finishing the last photos…",
            ScanSaveStep.Fusing to "Step 2 of 5 · Fusing 224k points…",
            ScanSaveStep.Painting to "Step 3 of 5 · Painting surfaces from photos…",
            ScanSaveStep.Checking to "Step 4 of 5 · Checking the scan…",
            ScanSaveStep.Writing to "Step 5 of 5 · Saving on this phone…",
        )
        steps.forEachIndexed { i, (step, line) ->
            val progress = ScanSaveProgress.start(linesUp = false).at(step)
            assertEquals(line, ScanSaveCopy.line(progress, points = 224_000))
            assertEquals(i / 5f, progress.fraction, EPSILON)
        }
    }

    @Test
    fun `a part scanned again lines up as a sixth step, before the check`() {
        val progress = ScanSaveProgress.start(linesUp = true)
        assertEquals(6, progress.total)
        assertEquals("Step 4 of 6 · Lining up the part…", ScanSaveCopy.line(progress.at(ScanSaveStep.LiningUp), 0))
        assertEquals("Step 5 of 6 · Checking the scan…", ScanSaveCopy.line(progress.at(ScanSaveStep.Checking), 0))
    }

    @Test
    fun `the painting fills its own share as the planes are painted`() {
        val painting = ScanSaveProgress(ScanSaveStep.Painting, inner = 0.5f)
        assertEquals(2.5f / 5, painting.fraction, EPSILON)
        assertEquals(3f / 5, painting.copy(inner = 1.5f).fraction, EPSILON)
        assertEquals(2f / 5, painting.at(ScanSaveStep.Painting).fraction, EPSILON)
    }

    @Test
    fun `a scan with no dense point fuses without a count`() {
        assertEquals("Fusing the points…", ScanSaveCopy.label(ScanSaveStep.Fusing, points = 0))
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
