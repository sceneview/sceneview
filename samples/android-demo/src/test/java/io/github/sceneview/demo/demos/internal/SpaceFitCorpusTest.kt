package io.github.sceneview.demo.demos.internal

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.sqrt

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
                "${scan.name}: ${cloud.count} points, inside %.2f x %.2f x %.2f m (W x H x D), share %.2f, open %s, %d obstacles, fits %s ($ms ms)"
                    .format(fit.width, fit.height, fit.depth, fit.share, fit.open.map { it.key }, fit.obstacles.size,
                        fit.fits?.let { "%.2f x %.2f x %.2f".format(it.width, it.height, it.depth) }),
            )
        }
    }

    /**
     * How thick real scans' surfaces are: for points sampled across each scan under
     * `SCENEVIEW_SCAN_CORPUS`, how far their neighbours within [NEAR_M] lie off the plane they
     * make (RMS), and how far the point's own normal leans off that plane's. What a space's
     * measure has to live with: [SpaceFitTest] builds its rough cupboard to these figures.
     */
    @Test
    fun `how thick the corpus's surfaces are`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS not set", corpus != null)
        val scans = corpus!!.listFiles { dir -> File(dir, MANIFEST).isFile && File(dir, MEDIA).isFile }.orEmpty().sortedBy { it.name }
        assumeTrue("no scan folder under $corpus", scans.isNotEmpty())
        for (scan in scans) {
            val manifest = ReplayManifest.parse(File(scan, MANIFEST).readText())
            val span = manifest?.dense?.let { manifest.media[it.path] }
            val cloud = span?.let { SvpcCodec.decode(File(scan, MEDIA).readBytes(), it.offset, it.length) }
            val normals = cloud?.normals
            if (cloud == null || normals == null) {
                println("${scan.name}: no dense cloud with normals")
                continue
            }
            val (thick, lean) = roughness(cloud, normals)
            if (thick.isEmpty()) {
                println("${scan.name}: ${cloud.count} points, none with neighbours enough")
                continue
            }
            println(
                "${scan.name}: ${cloud.count} points, ${thick.size} sampled; surfaces %.1f / %.1f cm thick (half, 90 %%); normals leaning %.0f / %.0f degrees"
                    .format(thick[thick.size / 2] * CM, thick[(thick.size - 1) * 9 / 10] * CM, lean[lean.size / 2], lean[(lean.size - 1) * 9 / 10]),
            )
        }
    }

    /**
     * For points sampled across [cloud]: how thick the surface is round each (metres), and how
     * far its normal leans off it (degrees), each list sorted.
     */
    private fun roughness(cloud: DenseCloud, normals: FloatArray): Pair<List<Float>, List<Float>> {
        val p = cloud.positions
        val n = cloud.count
        fun cell(v: Float) = floor(v / NEAR_M).toLong()
        fun key(x: Long, y: Long, z: Long) = (x and MASK) or ((y and MASK) shl SHIFT) or ((z and MASK) shl (2 * SHIFT))
        val cells = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until n) cells.getOrPut(key(cell(p[i * 3]), cell(p[i * 3 + 1]), cell(p[i * 3 + 2]))) { ArrayList() } += i
        val thick = ArrayList<Float>()
        val lean = ArrayList<Float>()
        for (i in 0 until n step maxOf(1, n / SAMPLES)) {
            val near = ArrayList<Int>()
            for (cx in -1L..1L) for (cy in -1L..1L) for (cz in -1L..1L) {
                cells[key(cell(p[i * 3]) + cx, cell(p[i * 3 + 1]) + cy, cell(p[i * 3 + 2]) + cz)]?.filterTo(near) { other ->
                    (0 until 3).sumOf { k -> (p[other * 3 + k] - p[i * 3 + k]).toDouble().let { it * it } } <= NEAR_M * NEAR_M
                }
            }
            if (near.size < MIN_NEAR) continue
            val plane = planeOf(p, near) ?: continue
            thick += plane[3]
            val dot = abs(normals[i * 3] * plane[0] + normals[i * 3 + 1] * plane[1] + normals[i * 3 + 2] * plane[2])
            lean += Math.toDegrees(acos(dot.coerceIn(0f, 1f).toDouble())).toFloat()
        }
        return thick.sorted() to lean.sorted()
    }

    /** The plane through points [of] of [p]: its normal, then how far they lie off it (RMS, metres); `null` when they are one point. */
    private fun planeOf(p: FloatArray, of: List<Int>): FloatArray? {
        val mean = DoubleArray(3)
        for (i in of) for (k in 0 until 3) mean[k] += p[i * 3 + k].toDouble() / of.size
        val spread = Array(3) { DoubleArray(3) }
        for (i in of) for (a in 0 until 3) for (b in 0 until 3) spread[a][b] += (p[i * 3 + a] - mean[a]) * (p[i * 3 + b] - mean[b]) / of.size
        // The direction they spread least along: the one (trace - spread) stretches most, found by repeating it.
        val trace = spread[0][0] + spread[1][1] + spread[2][2]
        var normal = doubleArrayOf(1.0, 1.0, 1.0)
        repeat(TURNS) {
            val next = DoubleArray(3) { a -> trace * normal[a] - (0 until 3).sumOf { b -> spread[a][b] * normal[b] } }
            val length = sqrt(next.sumOf { it * it })
            if (length <= 0.0) return null
            normal = DoubleArray(3) { next[it] / length }
        }
        val off = (0 until 3).sumOf { a -> normal[a] * (0 until 3).sumOf { b -> spread[a][b] * normal[b] } }
        return floatArrayOf(normal[0].toFloat(), normal[1].toFloat(), normal[2].toFloat(), sqrt(maxOf(0.0, off)).toFloat())
    }

    private companion object {
        const val NEAR_M = 0.1f
        const val MIN_NEAR = 30
        const val SAMPLES = 4000
        const val TURNS = 60
        const val SHIFT = 21
        const val MASK = 0x1FFFFFL
        const val CM = 100f
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val MANIFEST = "capture-manifest.json"
        const val MEDIA = "capture-media.bin"
        const val LOG = "capture-session.jsonl"
        const val NANOS_PER_MS = 1_000_000L
    }
}
