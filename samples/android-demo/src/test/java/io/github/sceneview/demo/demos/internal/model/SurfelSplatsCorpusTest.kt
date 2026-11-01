package io.github.sceneview.demo.demos.internal.model

import io.github.sceneview.demo.demos.internal.RerunScanFile
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Photo preview's splats for every recording of a corpus ([SurfelSplatsCorpus]):
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*SurfelSplatsCorpusTest*'
 * ```
 *
 * Skipped when either variable is unset. Writes `<out>/<scan>/splats.png` and `splats.json`.
 */
class SurfelSplatsCorpusTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `every scan of the corpus is drawn as splats`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val scans = checkNotNull(corpus).walkTopDown().filter(CleanModelCorpus::isScan).sortedBy { it.path }.toList()
        assumeTrue("no *.svscan, *.rrd or session folder in $corpus", scans.isNotEmpty())
        scans.forEach { scan -> println(SurfelSplatsCorpus.run(scan, File(checkNotNull(out), scan.nameWithoutExtension))) }
    }

    @Test
    fun `a synthetic recording is drawn as a whole room of splats`() {
        val scan = File(folder.newFolder("corpus"), "synthetic-room.${RerunScanFile.EXTENSION}")
        scan.writeBytes(RerunScanFile.write(CleanModelCorpusTest().syntheticRecording()))
        val out = System.getenv(OUT)?.let { File(it, "synthetic-room") } ?: folder.newFolder("out")

        val stats = SurfelSplatsCorpus.run(scan, out)

        println("splats, synthetic recording: $stats")
        assertTrue("$stats", "error" !in stats && "skipped" !in stats)
        assertTrue("$stats", stats["splats"]!!.jsonPrimitive.int > 1_000)
        // The dollhouse view of a room covers a good share of the image: no grid of dots, no holes.
        assertTrue("$stats", stats["covered_share"]!!.jsonPrimitive.float > 0.15f)
        val image = JvmImages.decode(File(out, "splats.png").readBytes())
        assertTrue("splats.png readable", image != null && image.width == SurfelSplatsCorpus.IMAGE_PX)
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
    }
}
