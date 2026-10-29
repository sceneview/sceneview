package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RescanZonesTest.Companion.filter
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Lining a re-scan up on real scans (#4736): for every `.svscan` and `.rrd` under
 * `SCENEVIEW_SCAN_CORPUS` with normals, a part around the wall farthest from the room's middle
 * is cut out of the scan, and what stands within [REACH_M] of it is the re-scan, moved by each
 * known turn and shift of [RescanRegistrationTest.OFFSETS] and given 5 mm of noise. Each must
 * come back within 2 cm and 1°, or be refused — never lined up wrong. One line per scan and move
 * is printed: how it lined up, how far off, in how long.
 *
 * Skipped when the variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*RescanRegistrationCorpusTest' --rerun -i`.
 */
class RescanRegistrationCorpusTest {
    @Test
    fun `a part cut out of every scan in the corpus lines up again, or is refused`() {
        val corpus = System.getenv(RescanZonesCorpusTest.CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("${RescanZonesCorpusTest.CORPUS} not set", corpus != null)
        val scans = corpus!!.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in RescanZonesCorpusTest.EXTENSIONS }
            .sortedBy { it.path }
            .toList()
        assumeTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        val report = StringBuilder()
        val wrong = ArrayList<String>()
        for (file in scans) {
            val cloud = runCatching { RescanZonesCorpusTest.cloudOf(file) }.getOrNull()
            if (cloud?.normals == null) {
                report.appendLine("${file.name}: no dense cloud with normals")
                continue
            }
            val part = partOf(cloud)
            val scan = cloud.filter { x, y, z -> !RescanRegistrar.inside(part, x, y, z, 0f) }
            val cx = (part[0] + part[3]) / 2f
            val cz = (part[2] + part[5]) / 2f
            val around = cloud.filter { x, _, z -> (x - cx) * (x - cx) + (z - cz) * (z - cz) <= REACH_M * REACH_M }
            val reference = requireNotNull(RescanReference.of(scan))
            report.appendLine("${file.name}: ${cloud.count} points, part ${part.joinToString { "%.2f".format(Locale.ROOT, it) }}, re-scan ${around.count} points")
            for ((k, offset) in RescanRegistrationTest.OFFSETS.withIndex()) {
                val session = RescanRegistrationTest.noisy(offset.inverse().moved(around), SEED + k)
                val started = System.nanoTime()
                val found = RescanRegistrar.register(reference, session, listOf(part))
                val ms = (System.nanoTime() - started) / NS_PER_MS
                val line = when (found) {
                    is RescanAlignment.Refused -> "refused ${found.why}"
                    is RescanAlignment.Lined -> {
                        val turn = abs(found.pose.turnFrom(offset))
                        val gap = worstGap(found.pose, offset, session)
                        if (turn > MAX_TURN_DEG || gap > MAX_GAP_M) wrong += "${file.name} move $k: $found"
                        "lined up ${"%.2f".format(Locale.ROOT, turn)}° and ${"%.1f".format(Locale.ROOT, gap * MM)} mm off, " +
                            "overlap ${"%.2f".format(Locale.ROOT, found.overlap)}, rms ${"%.1f".format(Locale.ROOT, found.rmsM * MM)} mm"
                    }
                }
                report.appendLine("  move $k ($offset): $line in $ms ms")
            }
        }
        println(report)
        assertTrue("lined up wrong:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    /**
     * A part to cut out of [cloud]: a 1.2 m square, floor to top, around the point of a wall
     * farthest from the cloud's middle.
     */
    private fun partOf(cloud: DenseCloud): FloatArray {
        val b = cloud.bounds()
        val mx = (b[0] + b[3]) / 2f
        val mz = (b[2] + b[5]) / 2f
        val n = requireNotNull(cloud.normals)
        var far = -1
        var farthest = 0f
        for (i in 0 until cloud.count) {
            if (abs(n[i * 3 + 1]) > LEVEL) continue
            val dx = cloud.positions[i * 3] - mx
            val dz = cloud.positions[i * 3 + 2] - mz
            val d = sqrt(dx * dx + dz * dz)
            if (d > farthest) {
                farthest = d
                far = i
            }
        }
        val x = if (far >= 0) cloud.positions[far * 3] else b[3]
        val z = if (far >= 0) cloud.positions[far * 3 + 2] else mz
        return floatArrayOf(x - HALF_M, b[1], z - HALF_M, x + HALF_M, b[4], z + HALF_M)
    }

    /** The farthest [session]'s points land between [pose] and the move that undoes [truth], metres. */
    private fun worstGap(pose: RescanPose, truth: RescanPose, session: DenseCloud): Float {
        var worst = 0f
        for (i in 0 until session.count) {
            worst = maxOf(worst, pose.gap(truth, session.positions[i * 3], session.positions[i * 3 + 1], session.positions[i * 3 + 2]))
        }
        return worst
    }

    private companion object {
        const val SEED = 4736
        const val REACH_M = 2.5f
        const val HALF_M = 0.6f
        const val LEVEL = 0.3f
        const val MAX_TURN_DEG = 1f
        const val MAX_GAP_M = 0.02f
        const val MM = 1000f
        const val NS_PER_MS = 1_000_000L
    }
}
