package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanWalkthrough
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * 3D Scan's live view, timed step by step on the JVM (#4743): a synthetic depth scan the length
 * of a real one replayed through the capture's own dense map, at the capture's cadence — a depth
 * frame every [ScanCapture.DEPTH_INTERVAL_NS], a live snapshot at most every
 * [ScanDenseMap.LIVE_INTERVAL_NS] — with the main thread's share of each snapshot (the bytes
 * `DebugLayerNode` packs for Filament and the colour atlas handed over) and what the snapshot asks
 * of the GPU from the scanning eye ([LiveDrawLoad]).
 *
 * Opt-in: set `SCENEVIEW_LIVE_BENCH` to a directory, which gets `scan-live-bench.md`.
 * ```
 * SCENEVIEW_LIVE_BENCH=/tmp/live-bench ./gradlew :samples:android-demo:testDebugUnitTest \
 *     --tests '*ScanLiveFrameBenchTest*'
 * ```
 */
class ScanLiveFrameBenchTest {

    private class Steps {
        val times = LinkedHashMap<String, MutableList<Double>>()
        fun add(step: String, ms: Double) {
            times.getOrPut(step) { ArrayList() } += ms
        }
    }

    private inline fun <T> Steps.time(step: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        add(step, (System.nanoTime() - start) / NANOS_PER_MS)
        return result
    }

    private class Session(val steps: Steps, val loads: List<LiveDrawLoad>, val dense: ScanDenseMap, val snapshots: Int)

    private fun replay(walk: ScanWalkthrough, frames: Int): Session {
        var now = 0L
        val dense = ScanDenseMap(maxPoints = PHONE_BUDGET, clock = { now })
        val preset = ScanPreset.ROOM
        val tsdf = RerunTsdf(voxelM = preset.modelVoxelM)
        val steps = Steps()
        val loads = ArrayList<LiveDrawLoad>()
        var scratch: ByteBuffer? = null
        var snapshots = 0
        for (i in 0 until frames) {
            now += ScanCapture.DEPTH_INTERVAL_NS
            val frame = walk.frame(i)
            val samples = steps.time("worker: project depth") { dense.project(frame, now, farM = preset.farM) }
            val before = dense.live
            val start = System.nanoTime()
            dense.add(samples)
            val addMs = (System.nanoTime() - start) / NANOS_PER_MS
            val live = dense.live
            val snapshot = live != null && live !== before
            steps.add(if (snapshot) "worker: add, with a snapshot" else "worker: add, merge only", addMs)
            steps.time("worker: TSDF integrate (own worker)") { tsdf.integrate(frame, farM = preset.farM) }
            if (snapshot) {
                snapshots++
                // The snapshot's parts again, one by one, on the map as it now stands.
                val fusion = dense.fusion
                val whole = steps.time("snapshot: stride cloud, for the floor cells") {
                    fusion.cloud(minViews = DenseFusion.MIN_VIEWS, maxPoints = ScanDenseMap.LIVE_MAX_SURFELS)
                }
                val eye = frame.pose
                val cloud = steps.time("snapshot: drawn cloud, merged by distance") {
                    fusion.liveCloud(eye.x, eye.y, eye.z, maxPoints = ScanDenseMap.LIVE_DRAWN_SURFELS)
                }
                val mesh = steps.time("snapshot: mesh") { DenseSurfels.mesh(cloud, fusion.voxelM) }
                steps.time("snapshot: atlas") {
                    DenseSurfels.atlas(cloud, fallback = ReplayDenseLayer.DENSE_FALLBACK_COLOR)
                }
                steps.time("snapshot: corner offsets") { DenseSurfels.cornerOffsets(mesh) }
                steps.time("snapshot: floor cells") { CoverageGrid.coveredKeys(whole.positions, whole.count) }
                // The main thread's share: what uploadDense and DebugLayerNode.write copy.
                steps.time("main: atlas handed over") {
                    ByteBuffer.allocateDirect(live!!.atlas.size).put(live.atlas)
                }
                scratch = steps.time("main: mesh packed") { pack(live!!, scratch) }
            }
            if (live != null) loads += LiveDrawLoad.of(live.cloud, live.voxelM, frame.pose)
        }
        return Session(steps, loads, dense, snapshots)
    }

    /** A surfel layer's write: positions, UVs, corner offsets (into the reused scratch), indices. */
    private fun pack(layer: ReplayDenseLayer, scratch: ByteBuffer?): ByteBuffer {
        val mesh = layer.mesh
        val vc = mesh.vertexCount
        val ic = mesh.indexCount
        ByteBuffer.allocateDirect(vc * 3 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(mesh.positions, 0, vc * 3)
        ByteBuffer.allocateDirect(vc * 2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(mesh.uvs, 0, vc * 2)
        val bytes = vc * 3 * Float.SIZE_BYTES
        val offsets = scratch?.takeIf { it.capacity() >= bytes }
            ?: ByteBuffer.allocateDirect(Integer.highestOneBit(bytes - 1) shl 1).order(ByteOrder.nativeOrder())
        offsets.clear()
        offsets.asFloatBuffer().put(layer.offsets, 0, vc * 3)
        ByteBuffer.allocateDirect(ic * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .asIntBuffer().put(mesh.indices, 0, ic)
        return offsets
    }

    @Test
    fun `the live view's steps, timed over a 48 s synthetic scan`() {
        val out = System.getenv(BENCH_ENV)
        assumeTrue("$BENCH_ENV is not set: the live bench is opt-in", !out.isNullOrBlank())
        val walk = ScanWalkthrough(yawSteps = YAW_STEPS, revisit = false)
        val frames = minOf(FRAMES, walk.frameCount)
        replay(walk, frames) // warm-up: the JIT, the allocator
        val session = replay(walk, frames)
        val report = report(session, frames)
        File(out!!).apply { mkdirs() }.resolve(REPORT_FILE).writeText(report)
        println(report)
    }

    private fun report(session: Session, frames: Int): String = buildString {
        val dense = session.dense
        val live = dense.live
        val fusion = dense.fusion
        val seconds = frames * ScanCapture.DEPTH_INTERVAL_NS / 1e9
        appendLine("### Live view, synthetic scan")
        appendLine()
        appendLine(
            "${"%.1f".format(seconds)} s at 10 depth frames/s (${frames} frames, ${session.snapshots} snapshots); " +
                "dense map ${fusion.count} voxels, ${dense.savedCloud().count} confirmed, " +
                "levels ${fusion.levelCounts().joinToString("/")}, " +
                "budget $PHONE_BUDGET; live layer ${live?.cloud?.count ?: 0} surfels, " +
                "mesh ${mb(meshBytes(live))} MB + atlas ${mb(live?.atlas?.size?.toLong() ?: 0)} MB per upload.",
        )
        appendLine(
            "Host: ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} cores, " +
                "Java ${System.getProperty("java.version")}. CPU times; a phone's are several times longer.",
        )
        appendLine()
        appendLine("| Step | Runs | p50 ms | p90 ms | max ms |")
        appendLine("|---|---:|---:|---:|---:|")
        for ((step, times) in session.steps.times) {
            val sorted = times.sorted()
            appendLine(
                "| $step | ${sorted.size} | ${ms(sorted.pct(P50))} | ${ms(sorted.pct(P90))} | ${ms(sorted.last())} |",
            )
        }
        appendLine()
        appendLine("GPU load from the scanning eye, every depth frame once a snapshot is up (${session.loads.size}):")
        appendLine()
        appendLine("| Load | p50 | p90 | max |")
        appendLine("|---|---:|---:|---:|")
        val loads = session.loads
        row("Surfels drawn", loads.map { it.surfels.toDouble() }, "%.0f")
        row("Vertices submitted", loads.map { it.vertices.toDouble() }, "%.0f")
        row("Surfels in view", loads.map { it.inView.toDouble() }, "%.0f")
        row("Overdraw (fragments / screen px)", loads.map { it.overdraw.toDouble() }, "%.2f")
        row("Screen covered", loads.map { it.coverage.toDouble() }, "%.3f")
        row("Tiny splats (< 64 px)", loads.map { it.tiny.toDouble() }, "%.3f")
    }

    private fun StringBuilder.row(name: String, values: List<Double>, format: String) {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return
        appendLine(
            "| $name | ${format.format(sorted.pct(P50))} | ${format.format(sorted.pct(P90))} | " +
                "${format.format(sorted.last())} |",
        )
    }

    private fun meshBytes(layer: ReplayDenseLayer?): Long {
        layer ?: return 0
        val vc = layer.mesh.vertexCount.toLong()
        return vc * (3 + 2 + 3) * Float.SIZE_BYTES + layer.mesh.indexCount.toLong() * Int.SIZE_BYTES
    }

    /** Nearest rank. */
    private fun List<Double>.pct(p: Double): Double = this[(ceil(p * size).toInt() - 1).coerceIn(0, size - 1)]

    private fun ms(v: Double) = "%.2f".format(v)
    private fun mb(bytes: Long) = "%.1f".format(bytes / (1024.0 * 1024.0))

    private companion object {
        const val BENCH_ENV = "SCENEVIEW_LIVE_BENCH"
        const val REPORT_FILE = "scan-live-bench.md"

        /** Two rooms of the walkthrough, 243 depth frames each: 48.6 s at 10 frames a second. */
        const val YAW_STEPS = 27
        const val FRAMES = 486

        /** What [DenseFusion.budgetFor] gives a phone's 256 MB heap. */
        val PHONE_BUDGET = DenseFusion.budgetFor(256L shl 20)

        const val NANOS_PER_MS = 1e6
        const val P50 = 0.5
        const val P90 = 0.9
    }
}
