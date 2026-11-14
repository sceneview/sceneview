package io.github.sceneview.demo.demos.internal

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * A space's measure on real scans: every kept scan under `SCENEVIEW_SCAN_CORPUS` (a folder per
 * scan, its `capture-manifest.json` and `capture-media.bin`, and its `capture-session.jsonl` for
 * where the phone stood) is measured, and its answer written
 * as `<scan>-space.json` under `SCENEVIEW_SCAN_OUT`, with a line per scan on the console.
 *
 * Skipped when either variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> SCENEVIEW_SCAN_OUT=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*SpaceFitCorpusTest' --rerun`.
 */
class SpaceFitCorpusTest {
    @Test
    fun `every scan in the corpus, measured as a space`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS and $OUT not both set", corpus != null && out != null)
        val scans = corpus!!.listFiles { dir -> File(dir, MANIFEST).isFile && File(dir, MEDIA).isFile }.orEmpty().sortedBy { it.name }
        assumeTrue("no scan folder under $corpus", scans.isNotEmpty())
        out!!.mkdirs()
        for (scan in scans) {
            val manifest = ReplayManifest.parse(File(scan, MANIFEST).readText())
            val dense = manifest?.dense
            val span = dense?.let { manifest.media[it.path] }
            val cloud = span?.let { SvpcCodec.decode(File(scan, MEDIA).readBytes(), it.offset, it.length) }
            if (dense == null || cloud == null) {
                println("${scan.name}: no dense cloud")
                continue
            }
            val eye = File(scan, LOG).takeIf { it.isFile }?.let { log ->
                val trace = ArDebugTrace()
                log.useLines { lines -> parseArDebugLog(lines).filterIsInstance<ArDebugEvent.CameraPose>().forEach { trace.addPose(it.nanos, it.pose) } }
                SpaceFitter.eyeOf(trace)
            }
            val started = System.nanoTime()
            val fit = SpaceFitter.of(cloud, dense.voxelM, eye)
            val ms = (System.nanoTime() - started) / NANOS_PER_MS
            if (fit == null) {
                println("${scan.name}: ${cloud.count} points, no space ($ms ms)")
                continue
            }
            File(out, "${scan.name}-space.json").writeText(SpaceAnswer.json(fit, scan.name))
            println(
                "${scan.name}: ${cloud.count} points, inside %.2f x %.2f x %.2f m (W x H x D), open %s, %d obstacles, fits %s ($ms ms)"
                    .format(fit.width, fit.height, fit.depth, fit.open.map { it.key }, fit.obstacles.size,
                        fit.fits?.let { "%.2f x %.2f x %.2f".format(it.width, it.height, it.depth) }),
            )
        }
    }

    private companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val MANIFEST = "capture-manifest.json"
        const val MEDIA = "capture-media.bin"
        const val LOG = "capture-session.jsonl"
        const val NANOS_PER_MS = 1_000_000L
    }
}
