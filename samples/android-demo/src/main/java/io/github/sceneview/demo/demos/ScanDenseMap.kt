package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFuseStats
import io.github.sceneview.demo.demos.internal.CooperativeBuild
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
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

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
            val ready = readyLive
            val pendingBytes = if (ready != null) snapshotBytes(ready) else liveDrawn?.let { cloudBytes(it) } ?: 0L
            return fusion.storageBytes + pendingBytes + (layer?.let { snapshotBytes(it) } ?: 0L)
        }

    private val points = AtomicInteger(0)
    private val frame = AtomicInteger(0)

    @Volatile
    private var adjustedAtNanos = Long.MIN_VALUE

    @Volatile
    private var full = false

    /**
     * The last complete live view. Every stage yields on the fusion worker; a build keeps its
     * original deadline and array view through compaction. Capture also pumps it while depth is
     * stale. The normal budget targets one second; only a build older than two seconds drains.
     */
    @Volatile
    var live: ReplayDenseLayer? = null
        private set
    private var liveAtNanos = Long.MIN_VALUE
    private var liveBuild: CooperativeBuild<ReplayDenseLayer>? = null
    private var liveDrawn: DenseCloud? = null
    private var readyLive: ReplayDenseLayer? = null
    private var liveSourceAtNanos = Long.MIN_VALUE
    private val ageSinceLogNs = AtomicLong()
    private val timings = ScanDenseTimings()
    internal val hasPendingLive: Boolean get() = liveBuild != null
    internal val snapshotAgeNs: Long
        get() {
            val since = if (liveSourceAtNanos == Long.MIN_VALUE) buildAtNanos else liveSourceAtNanos
            return if (since == Long.MIN_VALUE) 0L else (clock() - since).coerceAtLeast(0L)
        }
    private var liveEye: DebugPose? = null
    private var buildAtNanos = Long.MIN_VALUE
    private var buildAdds = 0
    private var lastSlotStarted = Long.MIN_VALUE
    private var buildVersion = 0
    private var buildRestarted = false
    internal var lastBuildSpanNs = 0L
        private set
    internal var lastBuildAdds = 0
        private set
    internal var maxLiveBatchNanos = 0L
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
    fun add(samples: DenseSamples, deferLive: Boolean = false, slotAtNanos: Long = Long.MIN_VALUE): DenseFuseStats {
        work.clear()
        val started = clock()
        val compactions = fusion.compactions
        val stats = work.measure(ScanDenseWork.FUSE) { fusion.add(samples) }
        if (fusion.compactions != compactions) adjustedAtNanos = clock()
        full = fusion.saturated
        points.set(stats.confirmed)
        shown.set(rising.update(stats.confirmed))
        frame.set(fusion.frames)
        lastSlotStarted = if (slotAtNanos == Long.MIN_VALUE) started else slotAtNanos
        snapshotLive(lastSlotStarted, countAdd = true, deferPublication = deferLive)
        val elapsed = (clock() - started).coerceAtLeast(0L)
        val ms = elapsed / NANOS_PER_MS
        timings.add(elapsed)
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

    /** One line for a device log; age includes the build time, not just time since publication. */
    fun logLine(): String {
        val levels = fusion.levelCounts().joinToString("/")
        val age = takeSnapshotAgePeakNs()
        return "dense kept ${fusion.count} saved $count drawn ${live?.cloud?.count ?: 0} " +
            "levels $levels compactions ${fusion.compactions} full $full " +
            timings.line() + " live span ${ms(lastBuildSpanNs)} ms/$lastBuildAdds adds " +
            "live age max ${ms(age)} ms; pin copy max ${ms(fusion.maxLiveDetachNanos)} ms; " +
            "batch max ${ms(maxLiveBatchNanos)} ms; index clear ${ms(fusion.liveIndexClearNanos)} ms; ${work.line()}"
    }

    /** Also used by the JVM probe so a publication cannot hide the preceding age peak. */
    internal fun takeSnapshotAgePeakNs(): Long = maxOf(ageSinceLogNs.getAndSet(0L), snapshotAgeNs)

    /** Explicit drain for callers that require a complete view (never called per capture add). */
    fun finishPendingLive() {
        if (liveBuild != null) advanceLive(Long.MAX_VALUE, deferred = true)
    }

    /** Idle pump, serialized with adds but outside the depth gate; it never starts a new build. */
    fun advancePendingLive() {
        if (liveBuild == null) return
        snapshotLive(lastSlotStarted, countAdd = false)
    }

    /** Capture stopped: final reads use fusion, so no unfinished preview needs to retain arrays. */
    fun discardPendingLive() {
        liveBuild = null
        liveDrawn = null
        readyLive = null
        buildAtNanos = Long.MIN_VALUE
        fusion.releaseLiveRead()
    }

    /** Final floor coverage is always read from fusion, independently of the live build. */
    fun coveredCells(): LongArray = CoverageGrid.coveredKeys(
        fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_MAX_SURFELS),
    )

    private fun snapshotLive(slotStarted: Long, countAdd: Boolean, deferPublication: Boolean = false) {
        val now = clock()
        if (liveBuild == null) {
            if (!countAdd || liveAtNanos != Long.MIN_VALUE && now - liveAtNanos < LIVE_INTERVAL_NS) return
            liveAtNanos = now
            buildAtNanos = now
            buildAdds = 0
            liveEye = eye
            buildRestarted = false
            buildVersion = fusion.topologyVersion
            liveBuild = CooperativeBuild { pause -> assembleLive(pause) }
        }
        if (countAdd) buildAdds++
        // Refresh a young, small view once if compaction changed it. Reserve three quarters
        // of the deadline for the replacement. Thereafter the pinned array view wins: even a
        // compaction on every add cannot reset its progress or move its original deadline.
        if (!buildRestarted && fusion.count <= LIVE_DRAWN_SURFELS &&
            buildVersion != fusion.topologyVersion && now - buildAtNanos < LIVE_INTERVAL_NS / 4
        ) {
            fusion.releaseLiveRead()
            liveDrawn = null
            readyLive = null
            liveBuild = CooperativeBuild { pause -> assembleLive(pause) }
            buildVersion = fusion.topologyVersion
            buildRestarted = true
        }
        val budget = ScanLiveDeadline.budget(now - buildAtNanos, now - slotStarted, countAdd)
        if (budget > 0L) advanceLive(budget, deferred = !countAdd, deferPublication = deferPublication)
    }

    private fun advanceLive(budget: Long, deferred: Boolean, deferPublication: Boolean = false) {
        val started = clock()
        work.resume()
        val layer = try { readyLive ?: liveBuild?.advance(budget, clock, started) } finally { work.pause() }
        maxLiveBatchNanos = maxOf(maxLiveBatchNanos, liveBuild?.maxBatchNanos ?: 0L)
        val ended = clock()
        if (deferred) timings.deferred((ended - started).coerceAtLeast(0L))
        if (layer == null) {
            ageSinceLogNs.accumulateAndGet(if (liveSourceAtNanos == Long.MIN_VALUE) ended - buildAtNanos else ended - liveSourceAtNanos, ::maxOf)
            return
        }
        // A small deferred build may fit its very first add. Publish it from the idle pump
        // instead, preserving the caller's deferral without draining or repeating any stage.
        if (deferPublication && readyLive == null && buildAdds == 1 && ended - buildAtNanos < LIVE_INTERVAL_NS) {
            readyLive = layer
            return
        }
        ageSinceLogNs.accumulateAndGet(if (liveSourceAtNanos == Long.MIN_VALUE) ended - buildAtNanos else ended - liveSourceAtNanos, ::maxOf)
        if (layer.whole.isNotEmpty()) {
            live = layer
            liveSourceAtNanos = buildAtNanos
        }
        lastBuildSpanNs = ended - buildAtNanos
        lastBuildAdds = buildAdds
        buildAtNanos = Long.MIN_VALUE
        liveBuild = null
        liveDrawn = null
        readyLive = null
        fusion.releaseLiveRead()
    }

    private suspend fun assembleLive(pause: suspend () -> Unit): ReplayDenseLayer {
        work.stage(ScanDenseWork.MERGE)
        val read = fusion.liveRead()
        val at = liveEye
        val drawn = if (at != null) {
            read.buildLiveCloud(at.x, at.y, at.z, LIVE_DRAWN_SURFELS, 1, pause)
        } else {
            // Before projection (unit tests and sparse startup), retain the same stride cloud.
            read.cloudSliced(LIVE_MAX_SURFELS, pause)
        }
        liveDrawn = drawn
        work.stage(ScanDenseWork.WHOLE)
        val whole = read.cloudPositionsSliced(DenseFusion.MIN_VIEWS, LIVE_MAX_SURFELS, pause)
        work.stage(ScanDenseWork.COVERED)
        val covered = CoverageGrid.coveredKeysSliced(whole, pause)
        work.stage(ScanDenseWork.WEAK)
        val weak = if (at == null || !LiveCoverageGround.SHOW_WEAK_CELLS) LongArray(0) else {
            LiveCoverageGround.weakCellsSliced(read.surfels(), read.voxelM, covered, CoverageGrid.CELL_METRES,
                LiveCoverageGround.floorHint(floorPlaneY, firstEyeY), pause)
        }
        work.stage(ScanDenseWork.MESH)
        val mesh = DenseSurfels.meshSliced(drawn, read.voxelM, DenseSurfels.LIVE_HALF_SIDE_VOXELS, DenseFusion.LEVEL_LIMIT, pause)
        val offsets = DenseSurfels.cornerOffsetsSliced(mesh, pause)
        work.stage(ScanDenseWork.ATLAS)
        val atlas = DenseSurfels.atlasSliced(drawn, ReplayDenseLayer.DENSE_FALLBACK_COLOR, pause)
        return ReplayDenseLayer(drawn, read.voxelM, mesh, atlas, coveredCells = covered, whole = whole, weakCells = weak,
            offsets = offsets)
    }

    private fun ms(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / 1e6)

    private fun snapshotBytes(layer: ReplayDenseLayer): Long {
        val cloud = layer.cloud
        val mesh = layer.mesh
        val meshBytes = mesh.positions.size * 4L + mesh.indices.size * 4L + mesh.uvs.size * 4L
        val wholeBytes = if (layer.whole === cloud.positions) 0L else layer.whole.size * 4L
        return cloudBytes(cloud) + meshBytes + wholeBytes + layer.atlas.size + layer.offsets.size * 4L +
            layer.coveredCells.size * 8L + layer.weakCells.size * 8L + SNAPSHOT_OVERHEAD_BYTES
    }

    private fun cloudBytes(cloud: DenseCloud): Long = cloud.positions.size * 4L + cloud.colors.size * 4L +
        (cloud.normals?.size ?: 0) * 4L + (cloud.confidences?.size ?: 0) + (cloud.levels?.size ?: 0)

    companion object {
        private const val SNAPSHOT_OVERHEAD_BYTES = 2048L
        /** A second between two snapshots of the dense map for the live 3D card. */
        const val LIVE_INTERVAL_NS = 1_000_000_000L

        /** Retained for source compatibility with existing freshness checks. */
        const val LIVE_MAX_ADDS = 3

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
