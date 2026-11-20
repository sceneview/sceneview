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
        val types = listOf(ScanDenseBenchmarkProbe::class.java, ScanDenseMap::class.java, Unit::class.java)
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
    fun main(args: Array<String>) {
        warmUp()
        var now = 0L
        val cap = DenseFusion.budgetFor(512L * 1024 * 1024)
        // Disable coarsening only in the cap run so it actually reaches 600k; normal compaction
        // is exercised separately by the determinism test and the existing budget suites.
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0, clock = { now })
        val rows = ArrayList<DoubleArray>()
        println("heap_bytes=${Runtime.getRuntime().maxMemory()} cap=$cap")
        println("frame,voxels,confirmed,project_ms,add_ms,snapshot_due")
        loop@ for (room in 0 until 12) {
            for ((i, source) in ReconstructionBench.FULL.frames().withIndex()) {
                val pose = source.pose.copy(x = source.pose.x + room * 5f)
                val frame = DepthFrame(source.width, source.height, source.depthMm, source.confidence,
                    source.colors, source.fx, source.fy, source.cx, source.cy, pose)
                now += 100_000_000L
                val before = System.nanoTime()
                val samples = map.project(frame, now)
                val projected = System.nanoTime()
                map.add(samples)
                val end = System.nanoTime()
                val row = doubleArrayOf(map.fusion.count.toDouble(), (projected - before) / 1e6, (end - projected) / 1e6)
                rows += row
                println(String.format(Locale.ROOT, "%d,%d,%d,%.3f,%.3f,%s", room * 270 + i,
                    map.fusion.count, map.count, row[1], row[2], (room * 270 + i) % 10 == 0))
                if (map.fusion.count == cap) break@loop
            }
        }
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
            val adds = bin.map { it[2] }.sorted()
            println(String.format(Locale.ROOT, "BIN %d..%d n=%d project=%.3f add_median=%.3f add_p95=%.3f add_max=%.3f",
                low, high, bin.size, bin.map { it[1] }.average(), adds[adds.size / 2], adds[(adds.size - 1) * 95 / 100], adds.last()))
        }
    }

    /** Warm projection, fusion and the live view before measuring either implementation. */
    private fun warmUp() {
        var now = 0L
        val map = ScanDenseMap(maxPoints = 150_000, clock = { now })
        ReconstructionBench.QUICK.frames().forEach { frame ->
            now += 100_000_000L
            map.add(map.project(frame, now))
        }
    }

}
