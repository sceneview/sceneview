package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ReconstructionBench
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Opt in with DENSE_FUSION_BENCH=1; the measured child always has a 512 MiB heap. */
class ScanDenseBenchmarkTest {
    @Test
    fun `depth projection and dense add through the phone cap`() {
        assumeTrue(System.getenv("DENSE_FUSION_BENCH") == "1")
        val types = listOf(ScanDenseBenchmarkProbe::class.java, ScanDenseMap::class.java, Unit::class.java,
            kotlinx.coroutines.CoroutineScope::class.java)
        val classpath = (System.getProperty("java.class.path").split(File.pathSeparator) +
            types.map { File(it.protectionDomain.codeSource.location.toURI()).path }).distinct().joinToString(File.pathSeparator)
        val log = File.createTempFile("scan-dense-benchmark", ".log")
        try {
            val process = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path, "-Xmx512m", "-XX:+UseSerialGC",
                "-cp", classpath, ScanDenseBenchmarkProbe::class.java.name,
            ).redirectErrorStream(true).redirectOutput(log).start()
            val done = process.waitFor(5, TimeUnit.MINUTES)
            if (!done) process.destroyForcibly().waitFor()
            val output = log.readText()
            println(output)
            assertTrue(output, done)
            assertEquals(output, 0, process.exitValue())
        } finally {
            log.delete()
        }
    }
}

/** A noisy furnished room repeated at translated locations, as a walk through adjoining rooms. */
object ScanDenseBenchmarkProbe {
    @JvmStatic
    fun main(args: Array<String>) = kotlinx.coroutines.runBlocking {
        warmUp()
        val cap = DenseFusion.budgetFor(512L * 1024 * 1024)
        // Disable coarsening only in the cap run so it actually reaches 600k; normal compaction
        // is exercised separately by the determinism test and the existing budget suites.
        var frameNanos = 0L
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0)
        val rows = ArrayList<DoubleArray>()
        println("heap_bytes=${Runtime.getRuntime().maxMemory()} cap=$cap")
        println("frame,voxels,confirmed,project_ms,add_ms,build_span_ms,max_snapshot_age_ms")
        loop@ for (room in 0 until 12) {
            for ((i, source) in ReconstructionBench.FULL.frames().withIndex()) {
                val pose = source.pose.copy(x = source.pose.x + room * 5f)
                val frame = DepthFrame(source.width, source.height, source.depthMm, source.confidence,
                    source.colors, source.fx, source.fy, source.cx, source.cy, pose)
                frameNanos += ScanCapture.DEPTH_INTERVAL_NS
                val before = System.nanoTime()
                val samples = map.project(frame, frameNanos)
                val projected = System.nanoTime()
                val beforeLayer = map.live
                map.add(samples)
                val added = System.nanoTime()
                val maxAge = map.takeSnapshotAgePeakNs()
                val span = if (map.live !== beforeLayer) map.lastBuildSpanNs.toDouble() else Double.NaN
                val row = doubleArrayOf(map.fusion.count.toDouble(), (projected - before) / 1e6,
                    (added - projected) / 1e6, span / 1e6, maxAge / 1e6)
                rows += row
                println("${room * 270 + i},${map.fusion.count},${map.count}," +
                    row.drop(1).joinToString(",") { String.format(Locale.ROOT, "%.3f", it) })
                if (map.fusion.count == cap) break@loop
            }
        }
        map.finishPendingLive()
        check(map.fusion.count == cap) { "did not reach $cap: ${map.fusion.count}" }
        val all = ScanDenseDeterminismTest.digest(map.fusion.cloud())
        val saved = ScanDenseDeterminismTest.digest(map.savedCloud())
        println("FINAL all $all")
        println("FINAL saved $saved")
        // Recorded with the original synchronous implementation, over this exact cap sequence.
        check(all == "f935ff8c223acf9ac2d70fefbe28de72a0a5a57a2a47f583485219bc4608ebfa")
        check(saved == "9a9bfd8512a63a95ad124350e93d03866636c900ba69a94ec2345cad94bfc1cd")
        for (low in listOf(0, 50_000, 100_000, 200_000, 400_000, 550_000)) {
            val high = when (low) { 0 -> 50_000; 50_000 -> 100_000; 100_000 -> 200_000; 200_000 -> 400_000; 400_000 -> 550_000; else -> cap + 1 }
            val bin = rows.filter { it[0] >= low && it[0] < high }
            if (bin.isEmpty()) continue
            val spans = bin.map { it[3] }.filter { it.isFinite() }
            println(String.format(Locale.ROOT,
                "BIN %d..%d n=%d add_mean/max=%.3f/%.3f span_mean/max=%.3f/%.3f age_max=%.3f",
                low, high, bin.size, bin.map { it[2] }.average(), bin.maxOf { it[2] },
                spans.average(), spans.maxOrNull() ?: 0.0, bin.maxOf { it[4] }))
        }
        map.discardPendingLive()
    }

    /** Warm projection, fusion and the live view before measuring either implementation. */
    private fun warmUp() {
        var now = 0L
        val map = ScanDenseMap(maxPoints = 150_000, clock = { now }, liveDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        ReconstructionBench.QUICK.frames().forEach { frame ->
            now += 100_000_000L
            map.add(map.project(frame, now))
        }
    }

}
