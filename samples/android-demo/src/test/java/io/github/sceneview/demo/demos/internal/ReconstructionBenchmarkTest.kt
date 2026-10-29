package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.model.GlbCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The end of a scan's reconstructions measured against each other on the noisy synthetic room
 * ([ReconstructionBench]): the fused TSDF a live scan meshes, the fused TSDF a reopened scan
 * rebuilds from its surfels, and [ScreenedPoisson] over those surfels. Prints the table the
 * pull request carries; asserts what the choice between them rests on.
 *
 * Times are this JVM's, the faster of two runs; peak heap is measured in a JVM capped at a
 * phone's 192 MB ([ReconstructionHeapProbe]).
 */
class ReconstructionBenchmarkTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `poisson leaves fewer holes, as accurate, near the same coverage and within time`() {
        val rows = ArrayList<String>()
        val measured = ArrayList<Triple<String, List<BenchRow>, BenchRow>>()
        for (scan in listOf(ReconstructionBench.FULL, ReconstructionBench.QUICK)) {
            val seen = ReconstructionBench.seen(scan)
            val tsdf = scan.liveTsdf()
            val live = timed { RerunMarchingCubes.extractWhole(tsdf, LIVE_MIN_WEIGHT).mesh }
            val cloud = scan.cloud()
            val surfels = timed {
                val t = RerunTsdf().apply { integrateSurfels(cloud) }
                RerunMarchingCubes.extractWhole(t).mesh
            }
            var solved: ScreenedPoisson.Result? = null
            val poisson = timed {
                val result = checkNotNull(ScreenedPoisson.reconstruct(cloud)) { "no reconstruction" }
                solved = result
                RerunMarchingCubes.extractWhole(result.tsdf).mesh
            }
            val fusedLive = ReconstructionBench.measure("Fused TSDF, live scan (today)", live.first, seen, live.second)
            val fusedSurfels = ReconstructionBench.measure("Fused TSDF from surfels, reopened scan (today)", surfels.first, seen, surfels.second)
            val screened = ReconstructionBench.measure("Screened Poisson from surfels (new)", poisson.first, seen, poisson.second)
            val solve = checkNotNull(solved)
            rows += "**${scan.name}** scan: ${scan.poses.size} frames, ${cloud.count} surfels; Poisson grid " +
                String.format(Locale.US, "%.1f cm, %,d nodes, %d iterations, residual %.4f", solve.voxelM * 100, solve.nodes, solve.iterations, solve.residual)
            rows += BenchRow.HEADER
            rows += BenchRow.RULE
            rows += listOf(fusedLive, fusedSurfels, screened).map(BenchRow::line)
            rows += ""
            for (mesh in listOf(live.first, surfels.first, poisson.first)) {
                assertEquals(emptyList<String>(), GlbCheck.problems(RerunMeshGlb.write(mesh)))
            }
            measured += Triple(scan.name, listOf(fusedLive, fusedSurfels), screened)
        }
        // The table first, so a failing run still shows what it measured.
        println(rows.joinToString("\n"))
        for ((label, fused, screened) in measured) {
            val (fusedLive, fusedSurfels) = fused
            // What the choice rests on: Poisson leaves fewer holes, as close to the room. It does
            // not see more of it: trimmed at the edge of what was seen, and on a grid twice as
            // coarse over the full room, it misses a little more than the TSDF of the same surfels.
            assertTrue(
                "$label: missed ${screened.missedShare} vs ${fusedSurfels.missedShare}",
                screened.missedShare < fusedSurfels.missedShare + MISSED_MARGIN,
            )
            assertTrue("$label: boundary ${screened.boundaryM} vs ${fusedSurfels.boundaryM}", screened.boundaryM < fusedSurfels.boundaryM)
            assertTrue("$label: boundary ${screened.boundaryM} vs ${fusedLive.boundaryM}", screened.boundaryM < fusedLive.boundaryM)
            assertTrue("$label: mean error ${screened.meanErrorM} vs ${fusedSurfels.meanErrorM}", screened.meanErrorM < fusedSurfels.meanErrorM + ACCURACY_MARGIN_M)
            assertTrue("$label: p90 error ${screened.p90ErrorM}", screened.p90ErrorM < P90_LIMIT_M)
            assertTrue("$label: ${screened.endMs} ms", screened.endMs < TIME_LIMIT_MS)
        }
    }

    @Test
    fun `each reconstruction ends the full scan within a phone's 192 MB heap`() {
        val lines = ArrayList<String>()
        for (method in ReconstructionHeapProbe.METHODS) {
            val output = probe(method)
            val peak = Regex("${ReconstructionHeapProbe.PEAK}(\\d+)").find(output)?.groupValues?.get(1)?.toLong()
            val held = Regex("${ReconstructionHeapProbe.HELD}(\\d+)").find(output)?.groupValues?.get(1)?.toLong()
            assertTrue("$method: ${output.takeLast(LOG_TAIL)}", output.contains(ReconstructionHeapProbe.DONE) && peak != null && held != null)
            lines += String.format(
                Locale.US, "| %s | %.1f | %.1f |", method, checkNotNull(held) / MB, checkNotNull(peak) / MB,
            )
        }
        println(
            "| Method | Live heap before (MB) | Live heap peak (MB) |\n|---|---:|---:|\n" + lines.joinToString("\n") +
                "\n(the app's own ${ReconstructionHeapProbe.APP_MB} MB included, ${ReconstructionHeapProbe.HEAP_MB} MB heap)",
        )
    }

    private fun probe(method: String): String {
        val log = folder.newFile("$method.log")
        val java = File(File(System.getProperty("java.home"), "bin"), "java").path
        val classpath = (
            System.getProperty("java.class.path").split(File.pathSeparator) +
                listOf(ReconstructionHeapProbe::class.java, ScreenedPoisson::class.java, Unit::class.java).mapNotNull { codeSource(it) }
            ).filter { it.isNotEmpty() }.distinct().joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            java, "-Xmx${ReconstructionHeapProbe.HEAP_MB}m", "-XX:+UseSerialGC", "-Djava.awt.headless=true",
            "-cp", classpath, ReconstructionHeapProbe::class.java.name, method,
        ).redirectErrorStream(true).redirectOutput(log).start()
        val done = process.waitFor(CHILD_TIMEOUT_S, TimeUnit.SECONDS)
        if (!done) process.destroyForcibly().waitFor()
        val output = log.readText()
        assertTrue("$method timed out: ${output.takeLast(LOG_TAIL)}", done)
        assertEquals("$method: ${output.takeLast(LOG_TAIL)}", 0, process.exitValue())
        return output
    }

    private fun codeSource(type: Class<*>): String? =
        runCatching { File(type.protectionDomain.codeSource.location.toURI()).path }.getOrNull()

    /** [block]'s result and the faster of two runs of it, in milliseconds: the second runs warm. */
    private fun <T> timed(block: () -> T): Pair<T, Long> {
        var result: T? = null
        var best = Long.MAX_VALUE
        repeat(RUNS) {
            val start = System.nanoTime()
            result = block()
            best = minOf(best, (System.nanoTime() - start) / NANOS_PER_MS)
        }
        @Suppress("UNCHECKED_CAST")
        return (result as T) to best
    }

    private companion object {
        /** RerunModelUi's weight for a TSDF fused from depth frames: one view. */
        const val LIVE_MIN_WEIGHT = 1f
        const val ACCURACY_MARGIN_M = 0.002

        /** Share of the seen room Poisson may miss beyond the fused surfels: 0.1 and 0.7 points measured. */
        const val MISSED_MARGIN = 0.01
        const val P90_LIMIT_M = 0.02
        const val TIME_LIMIT_MS = 30_000L
        const val RUNS = 2
        const val NANOS_PER_MS = 1_000_000L
        const val CHILD_TIMEOUT_S = 300L
        const val LOG_TAIL = 2000
        const val MB = 1024.0 * 1024.0
    }
}

/**
 * One reconstruction of the full noisy scan in a JVM capped at a phone's heap, beside what the
 * app holds then: its own [APP_MB], the scan's surfels, and — for a live scan — its TSDF. Prints
 * the live heap (after a full collection, garbage gone) before the end of the scan, and its peak.
 */
object ReconstructionHeapProbe {
    const val HEAP_MB = 192
    const val APP_MB = 32
    const val PEAK = "PEAK "
    const val HELD = "HELD "
    const val DONE = "DONE"
    const val FUSED_LIVE = "fused-live"
    const val FUSED_SURFELS = "fused-surfels"
    const val POISSON_LIVE = "poisson-live"
    const val POISSON_SURFELS = "poisson-surfels"
    val METHODS = listOf(FUSED_LIVE, FUSED_SURFELS, POISSON_LIVE, POISSON_SURFELS)
    private const val PROBE_STEP = 0.05f

    @JvmStatic
    fun main(args: Array<String>) {
        val method = args.first()
        val app = ByteArray(APP_MB * 1024 * 1024)
        val scan = ReconstructionBench.FULL
        val cloud = scan.cloud()
        val tsdf = if (method == FUSED_LIVE || method == POISSON_LIVE) scan.liveTsdf() else null
        val runtime = Runtime.getRuntime()
        var peak = 0L
        var next = 0f
        // This JVM runs nothing but the probe: a full collection first leaves only what is held,
        // so the reading is the live heap a phone would need, not garbage awaiting collection.
        // The JVM has no other way to read its live heap.
        @Suppress("ExplicitGarbageCollectionCall")
        fun probe() {
            System.gc()
            peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
        }
        val progress: (Float) -> Unit = { share ->
            if (share >= next) {
                next = share + PROBE_STEP
                probe()
            }
        }
        probe()
        val held = peak
        val mesh = when (method) {
            FUSED_LIVE -> RerunMarchingCubes.extractWhole(checkNotNull(tsdf), 1f, progress = progress).mesh
            FUSED_SURFELS -> {
                val fused = RerunTsdf().apply { integrateSurfels(cloud) { progress(it / 2) } }
                next = 0.5f
                RerunMarchingCubes.extractWhole(fused) { progress(0.5f + it / 2) }.mesh
            }
            else -> {
                val result = checkNotNull(ScreenedPoisson.reconstruct(cloud) { progress(it / 2) })
                next = 0.5f
                RerunMarchingCubes.extractWhole(result.tsdf) { progress(0.5f + it / 2) }.mesh
            }
        }
        probe()
        val glb = RerunMeshGlb.write(mesh)
        probe()
        println("$HELD$held")
        println("$PEAK$peak")
        println("triangles ${mesh.triangleCount}, glb ${glb.size} B, app ${app.size} B, cloud ${cloud.count}, tsdf ${tsdf?.bytes}")
        println(DONE)
    }
}
