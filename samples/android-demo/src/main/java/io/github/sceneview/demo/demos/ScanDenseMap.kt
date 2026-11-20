package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFuseStats
import io.github.sceneview.demo.demos.internal.DenseLiveBuild
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.AdaptiveConfidence
import io.github.sceneview.demo.demos.internal.DenseSamples
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.LiveCoverageGround
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.MonotonicCount
import io.github.sceneview.demo.demos.internal.ScanDetail
import io.github.sceneview.demo.demos.internal.ScanMemoryBudget
import java.util.concurrent.atomic.AtomicInteger

/**
 * A depth scan's dense map as the capture screen sees it (#4629): [DenseFusion] on this phone's
 * share of the scan's [ScanMemoryBudget], the one count the HUD shows — the points the saved
 * scan keeps, so the live figure and the saved one agree — the live 3D card's snapshot, and
 * whether the map has just coarsened part of itself to make room ([detail]).
 *
 * [add] runs on one worker thread at a time; the rest is read from the main thread.
 */
internal class ScanDenseMap(
    maxPoints: Int = ScanMemoryBudget(Runtime.getRuntime().maxMemory()).densePoints,
    maxLevel: Int = DenseFusion.MAX_LEVEL,
    private val clock: () -> Long = System::nanoTime,
) {
    val fusion = DenseFusion(maxPoints = maxPoints, maxLevel = maxLevel)

    /** Retained primitive storage of the map and its current live snapshot, for memory tests. */
    internal val storageBytes: Long
        get() {
            val layer = live
            val pendingBytes = liveDrawn?.let { cloudBytes(it) } ?: 0L
            if (layer == null) return fusion.storageBytes + pendingBytes
            val cloud = layer.cloud
            val cloudBytes = cloudBytes(cloud)
            val mesh = layer.mesh
            val meshBytes = mesh.positions.size * 4L + mesh.indices.size * 4L + mesh.uvs.size * 4L
            val wholeBytes = if (layer.whole === cloud.positions) 0L else layer.whole.size * 4L
            return fusion.storageBytes + pendingBytes + cloudBytes + meshBytes + wholeBytes + layer.atlas.size +
                layer.offsets.size * 4L + layer.coveredCells.size * 8L + layer.weakCells.size * 8L + SNAPSHOT_OVERHEAD_BYTES
        }

    private val points = AtomicInteger(0)
    private val frame = AtomicInteger(0)

    @Volatile
    private var adjustedAtNanos = Long.MIN_VALUE

    @Volatile
    private var full = false

    /**
     * The live card's surfels, including those seen in one depth frame, from the last
     * [project]ed eye: at most [LIVE_DRAWN_SURFELS], near ones whole and far ones merged.
     * Without an eye, at most [LIVE_MAX_SURFELS]. Floor cells and [ReplayDenseLayer.whole]
     * use the confirmed map. Built on the worker, at most once per [LIVE_INTERVAL_NS].
     * A pending merge finishes within [LIVE_MAX_ADDS] adds or [LIVE_INTERVAL_NS] of starting,
     * plus the finishing work itself; compaction never resets that deadline. Capture drains
     * it after the add even if no new depth arrives. Thus published data is at most two
     * intervals old plus worker execution time, without relying on the next sensor frame.
     */
    @Volatile
    var live: ReplayDenseLayer? = null
        private set
    private var liveAtNanos = Long.MIN_VALUE
    private var liveBuild: DenseLiveBuild? = null
    private var liveVersion = 0
    private var liveDrawn: DenseCloud? = null
    private var liveEye: DebugPose? = null
    private var buildAtNanos = Long.MIN_VALUE
    private var buildAdds = 0
    internal var lastBuildSpanNs = 0L
        private set
    internal var lastBuildAdds = 0
        private set
    /** CPU split for the last add and its deferred completion; diagnostics only. */
    internal val work = ScanDenseWork()

    /** Where the last [project]ed depth frame was taken: the live view's eye. On the worker. */
    private var eye: DebugPose? = null

    /** The first projected frame's height: the floor's guess without a floor plane. On the worker. */
    private var firstEyeY = Float.NaN

    /** ARCore's floor plane height, set from the main thread as planes come; `null` before one. */
    @Volatile
    var floorPlaneY: Float? = null

    /** How long the last [add] took, and the longest so far, in milliseconds: a device log's figures. */
    @Volatile
    var lastAddMs = 0f
        private set

    @Volatile
    var maxAddMs = 0f
        private set

    /** The points the saved scan keeps so far: what the review and the saved scan say. */
    val count: Int get() = points.get()

    /**
     * The HUD's one count (#4717): every point the capture confirmed, which only grows — a
     * compaction merges points the map held, and the figure on screen does not drop for it.
     */
    val captured: Int get() = shown.get()
    private val rising = MonotonicCount()
    private val shown = AtomicInteger(0)

    /** The depth confidence a pixel needs, relaxed on a plain surface (#4717). */
    val confidence = AdaptiveConfidence.dense()

    /**
     * [frame]'s surfels at the [confidence] threshold now, which the frame then feeds back. A
     * relaxed frame keeps its depth to [AdaptiveConfidence.RELAXED_FAR_M] at most, where
     * [farM] is the scan's own reach. On the worker, before [add].
     */
    fun project(frame: DepthFrame, nanos: Long, farM: Float = DepthBackProjection.FAR_M): DenseSamples {
        val relaxed = confidence.relaxed
        val threshold = confidence.threshold.toInt()
        val reach = if (relaxed) minOf(farM, AdaptiveConfidence.RELAXED_FAR_M) else farM
        val samples = DepthBackProjection.project(frame, minConfidence = threshold, farM = reach)
        var atBase = samples.count
        if (relaxed) {
            atBase = 0
            for (i in 0 until samples.count) {
                if ((samples.confidences[i].toInt() and 0xFF) >= confidence.base) atBase++
            }
        }
        confidence.record(nanos, samples.count, atBase)
        eye = frame.pose
        if (firstEyeY.isNaN()) firstEyeY = frame.pose.y
        return samples
    }

    /** The depth frames merged so far ([DenseFusion.frames]). */
    val frames: Int get() = frame.get()

    /** Merges one depth frame's [samples], then takes [live] when it is due. On the worker. */
    fun add(samples: DenseSamples, deferLive: Boolean = false): DenseFuseStats {
        work.clear()
        val started = clock()
        val compactions = fusion.compactions
        val stats = work.measure(ScanDenseWork.FUSE) { fusion.add(samples) }
        if (fusion.compactions != compactions) adjustedAtNanos = clock()
        full = fusion.saturated
        points.set(stats.confirmed)
        shown.set(rising.update(stats.confirmed))
        frame.set(fusion.frames)
        snapshotLive(deferAssembly = deferLive)
        val ms = (clock() - started) / NANOS_PER_MS
        lastAddMs = ms
        if (ms > maxAddMs) maxAddMs = ms
        return stats
    }

    /** What the coaching line says of the map at [nowNanos] ([clock]'s time). */
    fun detail(nowNanos: Long = clock()): ScanDetail = when {
        full -> ScanDetail.Full
        adjustedAtNanos != Long.MIN_VALUE && nowNanos - adjustedAtNanos < ADJUSTING_SHOWN_NS -> ScanDetail.Adjusting
        else -> ScanDetail.Fine
    }

    /** The cloud the scan saves ([DenseFusion.savedCloud]): what the live view showed, less the dust. */
    fun savedCloud(): DenseCloud = fusion.savedCloud()

    /**
     * [events] with each [ArDebugEvent.DepthStats] total turned into the prefix of [savedCloud]
     * found by then, [atFrames] holding the [frames] count each was recorded at, in order: what
     * a replay reveals, exact through a compaction, and its last value the saved count.
     */
    fun revealed(events: List<ArDebugEvent>, atFrames: IntArray): List<ArDebugEvent> {
        val totals = fusion.savedRevealCounts(atFrames)
        var k = 0
        return events.map { e ->
            if (e !is ArDebugEvent.DepthStats || k >= totals.size) e else {
                ArDebugEvent.DepthStats(e.nanos, e.added, e.kept, totals[k++])
            }
        }
    }

    /** One line for a device log: the map's figures and what it costs. */
    fun logLine(): String {
        val levels = fusion.levelCounts().joinToString("/")
        return "dense kept ${fusion.count} saved $count drawn ${live?.cloud?.count ?: 0} " +
            "levels $levels compactions ${fusion.compactions} full $full " +
            "add ${"%.1f".format(lastAddMs)} ms (max ${"%.1f".format(maxAddMs)}) " +
            "live span ${"%.1f".format(lastBuildSpanNs / NANOS_PER_MS)} ms/$lastBuildAdds adds"
    }

    /** Finish on the fusion worker after add, even if the phone stops supplying depth. */
    fun finishPendingLive() {
        if (buildAtNanos != Long.MIN_VALUE) snapshotLive(force = true)
    }

    /** Final floor coverage, independent of a pending or not-yet-due live snapshot. On the worker. */
    fun coveredCells(): LongArray = CoverageGrid.coveredKeys(
        fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_MAX_SURFELS),
    )

    private fun snapshotLive(force: Boolean = false, deferAssembly: Boolean = false) {
        val now = clock()
        if (buildAtNanos == Long.MIN_VALUE) {
            if (liveAtNanos != Long.MIN_VALUE && now - liveAtNanos < LIVE_INTERVAL_NS) return
            liveAtNanos = now
            buildAtNanos = now
            buildAdds = 0
        }
        if (!force) buildAdds++
        // A compaction moves voxel indices. Restart against the new map, retaining the original
        // deadline and add count, so repeated compactions cannot starve publication.
        if (liveVersion != fusion.topologyVersion) {
            liveBuild = null
            liveDrawn = null
        }
        if (liveBuild == null && liveDrawn == null) {
            liveVersion = fusion.topologyVersion
            liveEye = eye
            val at = liveEye
            if (at != null) {
                // Over the camera a surfel one depth frame saw already shows: on a slow phone the
                // second view lags, and the coat must follow the camera, not the saved cloud.
                liveBuild = fusion.liveCloudBuild(at.x, at.y, at.z, maxPoints = LIVE_DRAWN_SURFELS, minViews = 1)
            } else {
                liveDrawn = work.measure(ScanDenseWork.MERGE) { fusion.cloud(minViews = 1, maxPoints = LIVE_MAX_SURFELS) }
            }
        }
        val overdue = now - buildAtNanos >= LIVE_INTERVAL_NS || buildAdds >= LIVE_MAX_ADDS
        if (liveDrawn == null) {
            liveDrawn = work.measure(ScanDenseWork.MERGE) {
                liveBuild?.advance(if (force || overdue) Long.MAX_VALUE else LIVE_WORK_NS, clock)
            } ?: return
            liveBuild = null
        }
        // Capture defers the full-map reads, sorting, weak-floor judgement, mesh and atlas until
        // after add returns. Their cost is measured separately; none is claimed to fit an 8 ms slice.
        if (deferAssembly && !overdue) return
        publishLive(checkNotNull(liveDrawn))
        lastBuildSpanNs = clock() - buildAtNanos
        lastBuildAdds = buildAdds
        buildAtNanos = Long.MIN_VALUE
        liveDrawn = null
    }

    private fun publishLive(drawn: DenseCloud) {
        val whole = work.measure(ScanDenseWork.WHOLE) {
            fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_MAX_SURFELS)
        }
        if (whole.isEmpty()) return
        val covered = work.measure(ScanDenseWork.COVERED) { CoverageGrid.coveredKeys(whole) }
        // Judge the whole map, not the far surfels randomly selected for drawing.
        val weak = work.measure(ScanDenseWork.WEAK) {
            if (liveEye == null || !LiveCoverageGround.SHOW_WEAK_CELLS) LongArray(0) else {
                LiveCoverageGround.weakCells(
                    fusion.surfels(), fusion.voxelM, covered, CoverageGrid.CELL_METRES,
                    LiveCoverageGround.floorHint(floorPlaneY, firstEyeY),
                )
            }
        }
        val mesh = work.measure(ScanDenseWork.MESH) {
            DenseSurfels.mesh(drawn, fusion.voxelM, halfSideVoxels = DenseSurfels.LIVE_HALF_SIDE_VOXELS)
        }
        val atlas = work.measure(ScanDenseWork.ATLAS) {
            DenseSurfels.atlas(drawn, fallback = ReplayDenseLayer.DENSE_FALLBACK_COLOR)
        }
        live = ReplayDenseLayer(drawn, fusion.voxelM, mesh, atlas, coveredCells = covered, whole = whole, weakCells = weak)
    }

    private fun cloudBytes(cloud: DenseCloud): Long = cloud.positions.size * 4L + cloud.colors.size * 4L +
        (cloud.normals?.size ?: 0) * 4L + (cloud.confidences?.size ?: 0) + (cloud.levels?.size ?: 0)

    companion object {
        private const val SNAPSHOT_OVERHEAD_BYTES = 2048L
        /** A second between two snapshots of the dense map for the live 3D card. */
        const val LIVE_INTERVAL_NS = 1_000_000_000L

        /** At 10 Hz, finish no later than the third add (200 ms after the starting add). */
        const val LIVE_MAX_ADDS = 3

        /** The live merge yields between small batches, leaving most of the 100 ms depth slot for fusion. */
        private const val LIVE_WORK_NS = 8_000_000L

        /**
         * The map's surfels a live snapshot reads at most for the floor cells it covers, and
         * draws at most when no depth frame has given it an eye yet.
         */
        const val LIVE_MAX_SURFELS = 150_000

        /**
         * Surfels the live card draws at most once it has an eye (#4743): 200 k vertices, a
         * third of the stride it drew before, the far surfaces merged rather than thinned, so the
         * card keeps its frame rate while the camera and the fusion run.
         */
        const val LIVE_DRAWN_SURFELS = 50_000

        /** How long "Adjusting detail" stays up after the map coarsened: long enough to read. */
        const val ADJUSTING_SHOWN_NS = 4_000_000_000L

        private const val NANOS_PER_MS = 1e6f
    }
}
