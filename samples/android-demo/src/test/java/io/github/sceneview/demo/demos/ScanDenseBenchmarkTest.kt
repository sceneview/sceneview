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
        var realAt = System.nanoTime()
        var frameNanos = 0L
        var previousEnd = 0L
        val map = ScanDenseMap(maxPoints = cap, maxLevel = 0, clock = { now + System.nanoTime() - realAt })
        val rows = ArrayList<DoubleArray>()
        println("heap_bytes=${Runtime.getRuntime().maxMemory()} cap=$cap")
        println("frame,voxels,confirmed,project_ms,add_ms,slice_per_add_ms,build_span_ms,max_snapshot_age_ms")
        loop@ for (room in 0 until 12) {
            for ((i, source) in ReconstructionBench.FULL.frames().withIndex()) {
                val pose = source.pose.copy(x = source.pose.x + room * 5f)
                val frame = DepthFrame(source.width, source.height, source.depthMm, source.confidence,
                    source.colors, source.fx, source.fy, source.cx, source.cy, pose)
                frameNanos += ScanLiveDeadline.SLOT_NS
                now = maxOf(frameNanos, previousEnd)
                realAt = System.nanoTime()
                val slot = frameNanos
                val before = System.nanoTime()
                val samples = map.project(frame, frameNanos)
                val projected = System.nanoTime()
                val pendingBeforeAdd = map.hasPendingLive
                map.add(samples, deferLive = true, slotAtNanos = slot)
                val added = System.nanoTime()
                var maxAge = map.takeSnapshotAgePeakNs()
                var span = if (pendingBeforeAdd && !map.hasPendingLive) map.lastBuildSpanNs.toDouble() else Double.NaN
                // Same short idle pump as capture, stopped before the next depth slot.
                val nextSlot = slot + ScanLiveDeadline.SLOT_NS
                while (map.hasPendingLive && now + System.nanoTime() - realAt + 6_000_000L < nextSlot) {
                    now += 1_000_000L
                    map.advancePendingLive()
                    maxAge = maxOf(maxAge, map.takeSnapshotAgePeakNs())
                    if (!map.hasPendingLive) span = map.lastBuildSpanNs.toDouble()
                }
                previousEnd = now + System.nanoTime() - realAt
                val sliceMs = map.work.nanos.drop(1).sum() / 1e6
                val row = doubleArrayOf(map.fusion.count.toDouble(), (projected - before) / 1e6,
                    (added - projected) / 1e6, sliceMs, span / 1e6, maxAge / 1e6)
                rows += row
                println("${room * 270 + i},${map.fusion.count},${map.count}," +
                    row.drop(1).joinToString(",") { String.format(Locale.ROOT, "%.3f", it) })
                if (map.fusion.count == cap) break@loop
            }
        }
        println("UNINTERRUPTED batch_max_ms=${map.maxLiveBatchNanos / 1e6} copy_max_ms=${map.fusion.maxLiveDetachNanos / 1e6}")
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
            val spans = bin.map { it[4] }.filter { it.isFinite() }
            println(String.format(Locale.ROOT,
                "BIN %d..%d n=%d add_mean/max=%.3f/%.3f slice_mean/max=%.3f/%.3f span_mean/max=%.3f/%.3f age_max=%.3f",
                low, high, bin.size, bin.map { it[2] }.average(), bin.maxOf { it[2] },
                bin.map { it[3] }.average(), bin.maxOf { it[3] }, spans.average(), spans.maxOrNull() ?: 0.0,
                bin.maxOf { it[5] }))
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
