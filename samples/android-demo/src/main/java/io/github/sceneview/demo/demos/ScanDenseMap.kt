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
            val pendingBytes = if (liveWhole === layer?.whole) 0L else liveWhole.size * 4L
            if (layer == null) return fusion.storageBytes + pendingBytes
            val cloud = layer.cloud
            val cloudBytes = cloud.positions.size * 4L + cloud.colors.size * 4L +
                (cloud.normals?.size ?: 0) * 4L + (cloud.confidences?.size ?: 0) + (cloud.levels?.size ?: 0)
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
     * The dense map as the live 3D card draws it: a snapshot started at most every
     * [LIVE_INTERVAL_NS], merged in bounded batches after successive fusions, of the surfels at least two depth frames saw — from the last [project]ed
     * frame's eye, [LIVE_DRAWN_SURFELS] at most, near ones whole and far ones merged
     * ([DenseFusion.liveCloud]); without one, [LIVE_MAX_SURFELS] at most — meshed and coloured off
     * the main thread, with the floor cells the map covers and its even stride
     * ([ReplayDenseLayer.whole]). `null` until the first one.
     */
    @Volatile
    var live: ReplayDenseLayer? = null
        private set
    private var liveAtNanos = Long.MIN_VALUE
    private var liveBuild: DenseLiveBuild? = null
    private var liveVersion = 0
    private var liveWhole = FloatArray(0)

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
    fun add(samples: DenseSamples): DenseFuseStats {
        val started = clock()
        val compactions = fusion.compactions
        val stats = fusion.add(samples)
        if (fusion.compactions != compactions) adjustedAtNanos = clock()
        full = fusion.saturated
        points.set(stats.confirmed)
        shown.set(rising.update(stats.confirmed))
        frame.set(fusion.frames)
        snapshotLive()
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
            "add ${"%.1f".format(lastAddMs)} ms (max ${"%.1f".format(maxAddMs)})"
    }

    private fun snapshotLive() {
        if (liveBuild != null && liveVersion != fusion.topologyVersion) {
            liveBuild = null
            liveAtNanos = Long.MIN_VALUE
        }
        val now = clock()
        val at = eye
        if (liveBuild == null) {
            if (liveAtNanos != Long.MIN_VALUE && now - liveAtNanos < LIVE_INTERVAL_NS) return
            liveAtNanos = now
            liveWhole = fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_MAX_SURFELS)
            if (liveWhole.isEmpty()) return
            liveVersion = fusion.topologyVersion
            if (at != null) {
                liveBuild = fusion.liveCloudBuild(at.x, at.y, at.z, maxPoints = LIVE_DRAWN_SURFELS, minViews = 1)
            }
        }
        val drawn = if (at == null) {
            fusion.cloud(minViews = 1, maxPoints = LIVE_MAX_SURFELS)
        } else {
            liveBuild?.advance(LIVE_WORK_NS) ?: return
        }
        liveBuild = null
        val whole = liveWhole
        val covered = CoverageGrid.coveredKeys(whole)
        // The floor to scan again, judged on the whole map read in place: the drawn surfels are
        // merged and, past their budget, picked at random far off.
        val weak = if (at == null || !LiveCoverageGround.SHOW_WEAK_CELLS) LongArray(0) else {
            LiveCoverageGround.weakCells(
                fusion.surfels(), fusion.voxelM, covered, CoverageGrid.CELL_METRES,
                LiveCoverageGround.floorHint(floorPlaneY, firstEyeY),
            )
        }
        live = ReplayDenseLayer(
            cloud = drawn,
            voxelM = fusion.voxelM,
            mesh = DenseSurfels.mesh(drawn, fusion.voxelM, halfSideVoxels = DenseSurfels.LIVE_HALF_SIDE_VOXELS),
            atlas = DenseSurfels.atlas(drawn, fallback = ReplayDenseLayer.DENSE_FALLBACK_COLOR),
            // The floor cells are found here too, on the worker, from the map as before: the
            // live map and the area read them, however far the drawn surfels are merged.
            coveredCells = covered,
            // The saving orbit and the open top read the map as it stands, not the merged
            // surfels, dense by the eye and sparse away from it (#4792).
            whole = whole,
            weakCells = weak,
        )
    }

    companion object {
        private const val SNAPSHOT_OVERHEAD_BYTES = 2048L
        /** A second between two snapshots of the dense map for the live 3D card. */
        const val LIVE_INTERVAL_NS = 1_000_000_000L

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
