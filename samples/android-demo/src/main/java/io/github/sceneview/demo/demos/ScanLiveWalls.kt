package io.github.sceneview.demo.demos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.LiveWallMotion
import io.github.sceneview.demo.demos.internal.LiveWallSnapshot
import io.github.sceneview.demo.demos.internal.LiveWallSource
import io.github.sceneview.demo.demos.internal.PlaneWallSource
import io.github.sceneview.demo.demos.internal.ScanGuide
import io.github.sceneview.demo.demos.internal.ScanTurn
import io.github.sceneview.demo.demos.internal.WallExtent
import io.github.sceneview.demo.demos.internal.WallMesh
import io.github.sceneview.demo.demos.internal.lostBetween
import io.github.sceneview.demo.demos.internal.openFloorEdges
import io.github.sceneview.demo.demos.internal.update
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import io.github.sceneview.loaders.MaterialLoader
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The walls 3D Scan recognises while it scans a room (#4540 live walls), fed frame by frame from
 * the live view's `observe` after [ScanCoverageState]: the [source]'s walls, eased by [motion], the
 * floor edges no wall stands on yet, and what the HUD reads off them — [wallCount], the
 * [completion] gauge, [done], and the [turn] to the next gap. Compose state changes only when its
 * value does; the rest is read by [ScanWallLayer] on the render thread's frame.
 */
internal class ScanWallsState(private val source: LiveWallSource = PlaneWallSource()) {
    val motion = LiveWallMotion()

    /** The walls as drawn: the [source]'s, each cut back to the points seen on it ([WallExtent.clip]). */
    var snapshot: LiveWallSnapshot = LiveWallSnapshot.Empty
        private set

    /** The [source]'s walls as it found them, before [WallExtent.clip]: what the room's edges and gauge read. */
    private var found: LiveWallSnapshot = LiveWallSnapshot.Empty
    private var clippedFor: Any? = null
    private var clippedPoints = -1

    /** What the cut reads of the depth map ([depthOf]), and the snapshot it was read from. */
    private var read: FloatArray? = null
    private var readFor: ReplayDenseLayer? = null

    /** The floor edges seen with no wall on them: where the room still opens onto the unscanned. */
    var openEdges: List<FloatArray> = emptyList()
        private set

    /** The gap the cue points at, `null` without one. */
    var cueEdge: FloatArray? = null
        private set

    var wallCount by mutableIntStateOf(0)
        private set

    /** The room's scan done so far, `0..1` ([ScanGuide.completion]); it only grows. */
    var completion by mutableFloatStateOf(0f)
        private set

    /** The walls close the room, or the gauge is full: the HUD invites Finish. */
    var done by mutableStateOf(false)
        private set

    var turn by mutableStateOf<ScanTurn?>(null)
        private set

    /**
     * The phone faces a wall the scan has seen only part of ([WallExtent.facingUnfinished]): the
     * HUD asks to move along it, which is how a wall gets finished.
     */
    var facingUnfinished by mutableStateOf(false)
        private set

    private var extentFor: List<Pair<Float, Float>>? = null
    private var gridVersion = -1

    /** The trace time of the last frame observed, seconds; `NaN` before the first. */
    private var observedAt = Float.NaN

    /**
     * [frame], the trace's latest, after [coverage] observed it. The recorder writes nothing while
     * ARCore has lost tracking: [record], the trace being recorded ([ScanCoverageState.recorded]),
     * has no camera pose for that long, and the first frame after the gap is not tracked
     * ([lostBetween]); the walls skip it. How long since the last frame observed says nothing.
     * [dense], the depth map's live snapshot, cuts the walls back with the frame's feature points
     * ([depthOf]).
     */
    @Suppress("LongParameterList") // the frame, its coverage, the phone, and the two point sources
    fun observe(
        frame: ArDebugFrame,
        coverage: ScanCoverageState,
        pose: DebugPose?,
        record: ArDebugTrace? = coverage.recorded,
        dense: ReplayDenseLayer? = null,
    ) {
        val tracking = record?.lostBetween(observedAt, frame.time) != true
        observedAt = frame.time
        val next = source.update(frame, tracking)
        val wallsChanged = next !== found
        found = next
        // Cut back to the points seen on each wall: again when the walls or the points change.
        val points = frame.mapPointCount
        if (dense !== readFor) {
            readFor = dense
            read = dense?.let(::depthOf)
        }
        val depth = read
        if (wallsChanged || depth !== clippedFor || points != clippedPoints) {
            clippedFor = depth
            clippedPoints = points
            val clouds = listOfNotNull(frame.mapPoints, depth)
            val walls = WallExtent.clip(next.walls, clouds, next.floorY, next.height)
            val drawn = if (walls === next.walls) next else next.copy(walls = walls)
            if (drawn.walls != snapshot.walls || drawn.floorY != snapshot.floorY || drawn.height != snapshot.height ||
                drawn.ceilingSeen != snapshot.ceilingSeen
            ) {
                snapshot = drawn
            }
        }
        val facing = WallExtent.facingUnfinished(pose, next.walls, snapshot.walls) != null
        if (facing != facingUnfinished) facingUnfinished = facing
        if (wallsChanged || coverage.extent !== extentFor || coverage.grid.version != gridVersion) {
            extentFor = coverage.extent
            gridVersion = coverage.grid.version
            openEdges = openFloorEdges(coverage.extent, next.walls)
            val closed = ScanGuide.closed(next.walls.size, openEdges)
            val share = coverage.grid.coveredShareOf(coverage.extent)
            completion = max(completion, ScanGuide.completion(next.walls.size, share, closed))
            if (next.walls.size != wallCount) wallCount = next.walls.size
            val finished = closed || completion >= 1f
            if (finished != done) done = finished
        }
        val cue = if (done) null else ScanGuide.cue(pose, openEdges)
        cueEdge = cue?.edge
        if (cue?.turn != turn) turn = cue?.turn
    }

    fun reset() {
        source.reset()
        motion.reset()
        snapshot = LiveWallSnapshot.Empty
        found = LiveWallSnapshot.Empty
        clippedFor = null
        clippedPoints = -1
        read = null
        readFor = null
        facingUnfinished = false
        openEdges = emptyList()
        cueEdge = null
        extentFor = null
        gridVersion = -1
        observedAt = Float.NaN
        wallCount = 0
        completion = 0f
        done = false
        turn = null
    }

    companion object {
        /**
         * What the cut reads of [layer], the depth map's live snapshot: the map's even stride
         * ([ReplayDenseLayer.whole]), thinned evenly to one read of the cut ([WallExtent.MAX_READ]),
         * so a wall's far end is cut where the map saw it and the cut costs no more than the cloud
         * drawn did. Not the cloud drawn: it merges the far surfels into coarse cells
         * ([io.github.sceneview.demo.demos.internal.DenseFusion.liveCloud]), dense by the eye and
         * sparse away from it, so a far wall lost the end the map had seen, and moved each time
         * the eye did. Once a snapshot, on the main thread.
         */
        fun depthOf(layer: ReplayDenseLayer): FloatArray {
            val whole = layer.whole
            val n = whole.size / 3
            val stride = (n + WallExtent.MAX_READ - 1) / WallExtent.MAX_READ
            if (stride <= 1) return whole
            val read = FloatArray((n + stride - 1) / stride * 3)
            for (k in 0 until read.size / 3) whole.copyInto(read, k * 3, k * stride * 3, k * stride * 3 + 3)
            return read
        }
    }
}

/**
 * Draws a [ScanWallsState] in the live view: its walls through the shared [LiveWallLayer] (the
 * same walls Measure shows while it measures, #4732), and the floor edges no wall stands on yet
 * dashed in `accentGuidance` with a low ghost wall, the one the cue points at standing a little
 * taller. The layers go under the points, so the points stay on top.
 */
internal class ScanWallLayer(engine: Engine, private val materialLoader: MaterialLoader) {
    private val walls = LiveWallLayer(engine, materialLoader)
    private val openMaterial = layerMaterial(ArOverlay.accentGuidance)
    private val ghostMaterial = layerMaterial(ArOverlay.accentGuidance.copy(alpha = GHOST_ALPHA))

    private val open = DebugLayerNode(engine, openMaterial, priority = LiveWallLayer.LINE_PRIORITY)
    private val ghost = DebugLayerNode(engine, ghostMaterial, priority = LiveWallLayer.FILL_PRIORITY)

    val nodes = listOf(ghost) + walls.nodes + open

    private val openMesh = DebugMesh()
    private val ghostMesh = DebugMesh()
    private var openFor: List<FloatArray>? = null
    private var cueFor: FloatArray? = null
    private var openFloorY = Float.NaN

    /**
     * Eases the walls [dt] seconds on and re-uploads what moved, or hides them all while not
     * [shown] — the save's orbit shows the cloud alone. On the main thread.
     */
    fun sync(state: ScanWallsState, dt: Float, shown: Boolean = true) {
        listOf(ghost, open).forEach { if (it.isVisible != shown) it.isVisible = shown }
        walls.sync(state.motion, state.snapshot, dt, shown)
        if (!shown) return
        val motion = state.motion
        val floorY = motion.floorY
        if (state.openEdges !== openFor || state.cueEdge !== cueFor || floorY != openFloorY) {
            openFor = state.openEdges
            cueFor = state.cueEdge
            openFloorY = floorY
            openMesh.clear()
            ghostMesh.clear()
            if (motion.height > 0f) {
                for (edge in state.openEdges) {
                    dashes(openMesh, edge, floorY + LIFT_M)
                    val tall = if (edge === state.cueEdge) CUE_GHOST_M else GHOST_M
                    val base = ghostMesh.vertexCount
                    WallMesh.quad(edge[0], floorY, edge[1], edge[2], floorY, edge[3], tall) { x, y, z ->
                        ghostMesh.vertex(x, y, z)
                    }
                    ghostMesh.quad(base, base + 1, base + 2, base + 3)
                }
            }
            open.upload(openMesh)
            ghost.upload(ghostMesh)
        }
    }

    /** On the main thread, after [nodes] left the scene. */
    fun destroy() {
        walls.destroy()
        open.destroy()
        ghost.destroy()
        listOf(openMaterial, ghostMaterial).forEach { materialLoader.destroyMaterialInstance(it) }
    }

    private fun layerMaterial(color: Color): MaterialInstance =
        materialLoader.createUnlitColorInstance(color).apply { setCullingMode(Material.CullingMode.NONE) }

    private companion object {
        /** The open edge's dashes, lifted off the floor so the floor's points do not hide them. */
        const val DASH_M = 0.12f
        const val GAP_M = 0.08f
        const val LIFT_M = 0.01f

        /** The ghost of a wall not seen yet: knee-high, and waist-high where the cue points. */
        const val GHOST_M = 0.35f
        const val CUE_GHOST_M = 0.9f
        const val GHOST_ALPHA = 0.18f

        /** The open [edge] dashed on the floor at [y], each dash a flat ribbon. */
        fun dashes(mesh: DebugMesh, edge: FloatArray, y: Float) {
            val dx = edge[2] - edge[0]
            val dz = edge[3] - edge[1]
            val length = sqrt(dx * dx + dz * dz)
            if (length < 1e-4f) return
            val ux = dx / length
            val uz = dz / length
            // Across the edge, on the floor: twice a wall line, so it reads from standing height.
            val wx = -uz * LiveWallLayer.LINE_M * 2f
            val wz = ux * LiveWallLayer.LINE_M * 2f
            var t = 0f
            while (t < length) {
                val end = minOf(t + DASH_M, length)
                LiveWallLayer.ribbon(
                    mesh,
                    edge[0] + ux * t, y, edge[1] + uz * t,
                    edge[0] + ux * end, y, edge[1] + uz * end,
                    wx, 0f, wz,
                )
                t += DASH_M + GAP_M
            }
        }
    }
}
