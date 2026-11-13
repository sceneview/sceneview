package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFuseStats
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
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

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
    liveDispatcher: CoroutineDispatcher? = null,
    /** Test seam: virtual build cost or a suspended build, before reading the owned snapshot. */
    private val beforeBuild: suspend () -> Unit = {},
    private val previewPriority: () -> Int = {
        android.os.Process.setThreadPriority(PREVIEW_NICE)
        android.os.Process.getThreadPriority(android.os.Process.myTid())
    },
    private val previewLog: (String) -> Unit = { android.util.Log.i("SceneView-ScanPreview", it); Unit },
    private val snapshotAllocation: () -> Unit = {},
    private val fuse: (DenseFusion, DenseSamples) -> DenseFuseStats = { map, samples -> map.add(samples) },
) {
    val fusion = DenseFusion(maxPoints = maxPoints, maxLevel = maxLevel)

    /** Includes the owned snapshot, its workspace, and the published layer. */
    internal val storageBytes: Long
        get() = fusion.storageBytes + snapshotStorageBytes + (live?.let { snapshotBytes(it) } ?: 0L)

    internal val snapshotStorageBytes: Long
        get() = (snapshot?.let { it.storageBytes + it.workspaceBytes } ?: 0L) +
            coverageScratch.storageBytes

    private val points = AtomicInteger(0)
    private val frame = AtomicInteger(0)

    @Volatile
    private var adjustedAtNanos = Long.MIN_VALUE

    @Volatile
    private var full = false

    /** One atomic hand-off publishes layer/time and seals publication without taking the gate. */
    private data class Published(val layer: ReplayDenseLayer? = null, val nanos: Long = 0L, val closed: Boolean = false)
    private val published = AtomicReference(Published())
    val live: ReplayDenseLayer? get() = published.get().layer
    private val publicationGate = Any()
    private val closed = AtomicBoolean(false)
    private val buildRoot = SupervisorJob()
    private val ownedDispatcher: ExecutorCoroutineDispatcher? = if (liveDispatcher == null) {
        Executors.newSingleThreadExecutor { task ->
            Thread({
                // Android's Java priorities below NORM switch scheduling groups. Set only nice.
                runCatching { previewLog("preview thread nice ${previewPriority()}") }
                task.run()
            }, "SceneView-ScanPreview").apply { isDaemon = true; priority = Thread.NORM_PRIORITY }
        }.asCoroutineDispatcher()
    } else null
    private val buildScope = CoroutineScope(buildRoot + (liveDispatcher ?: checkNotNull(ownedDispatcher)))
    @Volatile private var buildJob: Job? = null
    @Volatile private var snapshot: DenseFusion.LiveSnapshot? = null
    private var forceTrailing = false
    private var flushSignal = CompletableDeferred<Unit>()
    private var buildAtNanos = Long.MIN_VALUE
    private var snapshotFrame = 0
    private val coverageScratch = CoverageGrid.KeyScratch()
    private val ageSinceLogNs = AtomicLong()
    private val timings = ScanDenseTimings()
    internal val hasPendingLive: Boolean get() = buildJob?.isCompleted == false
    internal val snapshotAgeNs: Long
        get() = published.get().let { if (it.layer == null) 0L else (clock() - it.nanos).coerceAtLeast(0L) }
    @Volatile internal var lastBuildSpanNs = 0L
        private set
    internal val work = ScanDenseWork(clock)

    /** Where the last [project]ed depth frame was taken: the live view's eye. On the worker. */
    @Volatile private var eye: DebugPose? = null

    /** The first projected frame's height: the floor's guess without a floor plane. On the worker. */
    @Volatile private var firstEyeY = Float.NaN

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
    fun add(samples: DenseSamples): DenseFuseStats = synchronized(publicationGate) {
        val started = clock()
        val compactions = fusion.compactions
        val stats = work.measure(ScanDenseWork.FUSE) { fuse(fusion, samples) }
        if (fusion.compactions != compactions) adjustedAtNanos = clock()
        full = fusion.saturated
        points.set(stats.confirmed)
        shown.set(rising.update(stats.confirmed))
        frame.set(fusion.frames)
        recordLiveAge()
        snapshotLive()
        val elapsed = (clock() - started).coerceAtLeast(0L)
        val ms = elapsed / NANOS_PER_MS
        timings.add(elapsed)
        lastAddMs = ms
        if (ms > maxAddMs) maxAddMs = ms
        stats
    }

    /**
     * One depth frame through the fusion: [project] then [add]. The scan's path on the phone and the
     * replay's of a recorded scan ([ScanDepthReplay]) alike, so both give the same cloud. On the worker.
     */
    fun fuse(frame: DepthFrame, nanos: Long, farM: Float): Pair<DenseSamples, DenseFuseStats> {
        val samples = project(frame, nanos, farM)
        return samples to add(samples)
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
            timings.line() + " live age max ${ms(age)} ms; ${work.line()}"
    }

    private fun recordLiveAge() {
        ageSinceLogNs.accumulateAndGet(snapshotAgeNs, ::maxOf)
    }

    internal fun takeSnapshotAgePeakNs(): Long = maxOf(ageSinceLogNs.getAndSet(0L), snapshotAgeNs)

    /** QA and explicit end states await the last snapshot and any completion-triggered build. */
    suspend fun finishPendingLive() {
        do {
            val pending = synchronized(publicationGate) { buildJob } ?: return
            pending.join()
        } while (synchronized(publicationGate) { buildJob } !== pending)
    }

    /** QA end state: bypass the interval even when the last add did not start a build. */
    fun flushLive() = synchronized(publicationGate) {
        forceTrailing = true
        flushSignal.complete(Unit)
        snapshotLive(force = true)
    }

    /** Stop publication immediately; callers join this alongside the fusion job before release. */
    fun close(): Job {
        closed.set(true)
        published.updateAndGet { it.copy(closed = true) }
        buildRoot.cancel()
        return buildRoot
    }

    /** Save never joins an uninterruptible build; release as soon as its reader exits. */
    fun closeForSave() {
        close().invokeOnCompletion { releaseBuffers(clearLayer = false) }
    }

    /** No buffer is released under a CPU reader, even when cancellation cannot interrupt it. */
    suspend fun discardPendingLive() {
        close().join()
        // Saving or retrying still displays the last complete layer. Exit drops it below.
        releaseBuffers(clearLayer = false)
    }

    /** Called only after both fusion and preview workers have joined. */
    internal fun releaseLiveBuffers() = releaseBuffers(clearLayer = true)

    private fun releaseBuffers(clearLayer: Boolean) = synchronized(publicationGate) {
        check(buildRoot.isCompleted)
        snapshot = null
        coverageScratch.release()
        if (clearLayer) published.updateAndGet { it.copy(layer = null) }
        buildJob = null
        ownedDispatcher?.close()
    }

    fun coveredCells(): LongArray = CoverageGrid.coveredKeys(
        fusion.cloudPositions(minViews = DenseFusion.MIN_VIEWS, maxPoints = LIVE_MAX_SURFELS),
    )

    /** Called under publicationGate, which also protects fusion while completion takes a copy. */
    private fun snapshotLive(force: Boolean = false) {
        val now = clock()
        if (closed.get()) return
        if (hasPendingLive) {
            if (now - buildAtNanos >= LIVE_INTERVAL_NS) flushSignal.complete(Unit)
            return
        }
        if (!force && buildAtNanos != Long.MIN_VALUE && now - buildAtNanos < LIVE_INTERVAL_NS) {
            scheduleTrailing()
        } else {
            try {
                startLive(now)
            } catch (failure: Throwable) {
                dropPreview(failure)
            }
        }
    }

    /** Holds the build slot until the next interval, without holding the fusion gate. */
    private fun scheduleTrailing() {
        val due = buildAtNanos + LIVE_INTERVAL_NS
        val wake = CompletableDeferred<Unit>()
        flushSignal = wake
        val job = buildScope.launch(start = CoroutineStart.LAZY) {
            val remaining = due - clock()
            if (remaining > 0L) {
                withTimeoutOrNull((remaining + 999_999L) / 1_000_000L) { wake.await() }
            }
            try {
                synchronized(publicationGate) {
                    coroutineContext.ensureActive()
                    buildJob = null
                    if (fusion.frames != snapshotFrame) snapshotLive(force = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(publicationGate) { dropPreview(failure) }
            }
        }
        buildJob = job
        job.start()
    }

    /** Holds publicationGate; the previous build has finished reading before reusing this copy. */
    private fun startLive(now: Long) {
        val read = snapshot ?: DenseFusion.LiveSnapshot(fusion, snapshotAllocation).also { snapshot = it }
        val copyAt = clock()
        read.copyFrom(fusion)
        timings.copy((clock() - copyAt).coerceAtLeast(0L))
        val at = eye
        val floor = LiveCoverageGround.floorHint(floorPlaneY, firstEyeY)
        buildAtNanos = now
        val sourceFrame = fusion.frames
        snapshotFrame = sourceFrame
        forceTrailing = false
        val job = buildScope.launch(start = CoroutineStart.LAZY) {
            try {
                beforeBuild()
                coroutineContext.ensureActive()
                val context = coroutineContext
                val layer = assembleLive(read, at, floor) { context.ensureActive() }
                val ended = clock()
                timings.build((ended - now).coerceAtLeast(0L))
                lastBuildSpanNs = ended - now
                synchronized(publicationGate) {
                    context.ensureActive()
                    if (!closed.get()) {
                        if (layer.whole.isNotEmpty()) {
                            recordLiveAge()
                            published.updateAndGet { if (it.closed) it else Published(layer, now) }
                            recordLiveAge()
                        }
                    }
                    buildJob = null
                    if (!closed.get() && fusion.frames != sourceFrame) snapshotLive(force = forceTrailing)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                synchronized(publicationGate) { dropPreview(failure) }
            }
        }
        buildJob = job
        job.start()
    }

    /** Holds publicationGate. A partial copy is never reused after allocation failure. */
    private fun dropPreview(failure: Throwable) {
        snapshot = null
        buildJob = null
        // Back off one interval: under memory pressure, retrying the allocation on every depth
        // frame would stall fusion under the gate.
        buildAtNanos = clock()
        runCatching { previewLog("preview dropped: ${failure.javaClass.simpleName}") }
    }

    /** All inputs belong to the copied buffer; no fusion arrays or dense gate are read here. */
    private fun assembleLive(read: DenseFusion.LiveSnapshot, at: DebugPose?, floor: Float, checkActive: () -> Unit): ReplayDenseLayer {
        val drawn = work.measure(ScanDenseWork.MERGE) {
            if (at != null) read.liveCloud(at.x, at.y, at.z, LIVE_DRAWN_SURFELS) else read.cloud(LIVE_MAX_SURFELS)
        }
        checkActive()
        val whole = work.measure(ScanDenseWork.WHOLE) { read.cloudPositions(DenseFusion.MIN_VIEWS, LIVE_MAX_SURFELS) }
        val covered = work.measure(ScanDenseWork.COVERED) { CoverageGrid.coveredKeys(whole, scratch = coverageScratch) }
        checkActive()
        val weak = work.measure(ScanDenseWork.WEAK) {
            if (at == null || !LiveCoverageGround.SHOW_WEAK_CELLS) LongArray(0) else {
                LiveCoverageGround.weakCells(read.surfels(), read.voxelM, covered, CoverageGrid.CELL_METRES, floor)
            }
        }
        checkActive()
        val mesh = work.measure(ScanDenseWork.MESH) {
            DenseSurfels.mesh(drawn, read.voxelM, DenseSurfels.LIVE_HALF_SIDE_VOXELS, DenseFusion.LEVEL_LIMIT)
        }
        val offsets = work.measure(ScanDenseWork.MESH) { DenseSurfels.cornerOffsets(mesh) }
        checkActive()
        val atlas = work.measure(ScanDenseWork.ATLAS) { DenseSurfels.atlas(drawn, ReplayDenseLayer.DENSE_FALLBACK_COLOR) }
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
        /** Device A/B seam: keep Java priority normal and change only Linux nice. */
        const val PREVIEW_NICE = 2
        private const val SNAPSHOT_OVERHEAD_BYTES = 2048L
        /** A second between two snapshots of the dense map for the live 3D card. */
        const val LIVE_INTERVAL_NS = 1_000_000_000L

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
