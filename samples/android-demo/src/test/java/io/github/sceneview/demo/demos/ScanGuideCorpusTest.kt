package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The guide on real scans: every `.svscan` and `.rrd` under `SCENEVIEW_SCAN_CORPUS` replayed
 * through the live screen's walls ([ScanGuideCorpus.replay]), written to `SCENEVIEW_SCAN_OUT`:
 *
 * - `<scan>-guide.png`: on the left the old turn hint over time, held as the old screen held it;
 *   on the right the new heading and distance to the target over time, the line-ups and ticks;
 *   under each, the changes of the text on screen and the floor from above with where each guide
 *   led. At most 1,000 px wide.
 * - `<scan>-guide.csv`: one row every 0.1 s — the pose, the old turn and text, the new target,
 *   heading, distance, words, arrow, tick, ribbon and text.
 * - `guide-summary.txt`: per scan, how often each text changed and its longest stretch unchanged.
 *
 * Skipped when either variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> SCENEVIEW_SCAN_OUT=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*ScanGuideCorpusTest' --rerun`.
 */
class ScanGuideCorpusTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `every scan in the corpus, the guide before and after`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS and $OUT not both set", corpus != null && out != null)
        val results = ScanGuideCorpus.run(corpus!!, out!!)
        assumeTrue("no .svscan or .rrd under $corpus", results.isNotEmpty())
    }

    @Test
    fun `a synthetic scan whose camera turns, end to end from its file`() {
        val corpus = temp.newFolder("corpus")
        val out = System.getenv(SYNTHETIC_OUT)?.takeIf { it.isNotBlank() }?.let(::File) ?: temp.newFolder("out")
        File(corpus, "synthetic-turn.svscan").writeBytes(ScanGuideCorpus.syntheticRecording())
        val results = ScanGuideCorpus.run(corpus, out)
        assertEquals(1, results.size)
        val result = results.single()
        assertTrue(result.toString(), result.newChanges > result.oldChanges && result.ticks >= 1)
        assertTrue(File(out, "synthetic-turn.svscan-guide.png").length() > 0)
        val csv = File(out, "synthetic-turn.svscan-guide.csv").readLines()
        assertEquals(result.samples.size + 1, csv.size)
        assertTrue(File(out, ScanGuideCorpus.SUMMARY).readText().contains("synthetic-turn.svscan:"))
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"

        /** Where the synthetic run's board goes when set, to look at; a temp folder otherwise. */
        const val SYNTHETIC_OUT = "SCENEVIEW_SYNTHETIC_OUT"
    }
}
