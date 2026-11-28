package io.github.sceneview.demo.demos

import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.room.RoomBox
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
import io.github.sceneview.demo.demos.internal.room.ScanFloorSlab
import io.github.sceneview.demo.demos.internal.ScanBox
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import java.io.File

/*
 * The result of a scan that kept its depth recording and stands as no room — an object, a garden,
 * a room scan that found no walls: its whole surface fused again from the recording
 * ([RoomDepthMesh]), floor included, as the Model layer. A room that stands as one opens on its
 * shell instead (RoomShellLayer.kt), and a scan without a recording on the model as before. Built
 * once off the main thread and kept with the scan (`depth-model.glb`), so a reopen reads it back.
 */

/** What a scan's result opens on. */
internal enum class ResultKind {
    /** A room's dollhouse ([roomShellOf]): its furniture a mesh with a recording, points without. */
    Shell,

    /** The whole surface fused from the depth recording ([depthModelOf]). */
    DepthModel,

    /** The model as before: the saved `model.glb`, or the finish from the live TSDF or the surfels. */
    Model,
}

/**
 * What a scan of [scope] opens on: a room that [standsAsRoom] its shell; else, with a depth
 * recording ([hasRecording]), its fused surface; else the model as before. An object big enough
 * to be filled ([objectFilled], [objectReconstruction] picks Poisson, #5114) keeps the model: the
 * fused surface would open on its unfilled holes.
 */
internal fun resultKindOf(
    scope: ScanScope,
    hasRecording: Boolean,
    standsAsRoom: Boolean,
    objectFilled: Boolean = false,
): ResultKind = when {
    scope == ScanScope.Room && standsAsRoom -> ResultKind.Shell
    scope == ScanScope.Object && objectFilled -> ResultKind.Model
    hasRecording -> ResultKind.DepthModel
    else -> ResultKind.Model
}

/**
 * Bumped whenever [depthModelOf] makes a different surface of the same recording: a surface kept
 * by an earlier one is built again.
 */
internal const val DEPTH_MODEL_VERSION = 4

/**
 * The most triangles the fused surface is shown with: past it, it is simplified down to it. 250k
 * is ~7 MB of `.glb` (positions, normals, colours, 32-bit indices), held about three times while
 * the result opens — the surface, its `.glb`, the renderer's copy — ~21 MB, a quarter of the old
 * finish's 1M-triangle raw budget, and drawn at frame rate by a Pixel 4a-class GPU. The scans
 * measured stay under it but a garden (316k), which loses nothing visible at 250k; 50k, the old
 * finish's, re-opens holes in the thin parts (a plant's leaves, a speaker's edge).
 */
internal const val DEPTH_MODEL_MAX_TRIANGLES = 250_000

/**
 * Session [id]'s fused surface as the result's model: read back from [store] when kept, else
 * fused from its depth recording and kept. `null` when there is no store or session, no readable
 * recording, or the scan is not one to open on it ([resultKindOf]): a [ScanScope.Room] scan stands
 * as a room when [cloud] (its dense cloud, floor at [floorY]) finds a room box — unknown without
 * the cloud, so then it is left to the shell; an [objectFilled] object is left to the model,
 * a surface kept for it before included. An object's surface is cropped to its [subject]
 * ([cropToSubject]) when one is found. [onRecording] runs once the recording is known to
 * read, before the fusion: the live TSDF can go then. Blocking CPU and disk work, off the main
 * thread; [progress] may throw to stop it.
 */
@Suppress("LongParameterList", "ReturnCount")
internal fun depthModelOf(
    store: RerunSessionStore?,
    id: String?,
    scope: ScanScope,
    cloud: DenseCloud?,
    floorY: Float?,
    onRecording: () -> Unit = {},
    objectFilled: Boolean = false,
    subject: ScanBox? = null,
    progress: (Float) -> Unit = {},
): RerunScanModel? {
    if (store == null || id == null) return null
    if (scope == ScanScope.Object && objectFilled) return null
    store.depthModel(id, DEPTH_MODEL_VERSION)?.let {
        val model = depthModel(it)
        if (model != null) progress(1f)
        return model
    }
    val depth = File(store.directoryOf(id), ScanDepthFormat.FILE).takeIf { it.isFile } ?: return null
    val standsAsRoom = scope == ScanScope.Room && (cloud == null || RoomBox.of(cloud, floorY) != null)
    if (resultKindOf(scope, hasRecording = true, standsAsRoom = standsAsRoom) != ResultKind.DepthModel) return null
    val header = runCatching { depth.inputStream().buffered().use(ScanDepthFormat::readHeader) }.getOrNull() ?: return null
    onRecording()
    progress(0f)
    // A room or a garden reaches as far as the depth is sure; a close scan (an object, a space,
    // ScanPreset.OBJECT) as far as it was scanned.
    val close = scope.preset == ScanPreset.OBJECT
    val settings = RoomDepthMesh.Settings(farM = if (close) minOf(header.farM, RoomDepthMesh.RELIABLE_FAR_M) else RoomDepthMesh.RELIABLE_FAR_M)
    // The pose guard runs again over the recording: a slip the phone let through is kept out too.
    val fused = RoomDepthMesh.fuse(
        depth, settings, guard = RoomDepthMesh.replayGuard(),
        traceFloor = BuildConfig.DEBUG && scope == ScanScope.Room, floorY = floorY,
    ) { progress(it * FUSE_SHARE) } ?: return null
    // A room that stands as no room — a short walk, part of a room — stands on a clean slab of
    // floor, at the room's floor or ARCore's; a Home's levels and stairs are left as fused.
    val sure = floorY ?: fused.floorPlaneY
    val slab = if (scope == ScanScope.Room) {
        ScanFloorSlab.of(fused.mesh, sure, cutBelow = sure != null, trace = BuildConfig.DEBUG)
    } else {
        null
    }
    if (BuildConfig.DEBUG) {
        val source = if (floorY != null) "scan" else if (fused.floorPlaneY != null) "arcore" else null
        ScanBench().depthModel("depth_model", fused, sure, source, slab)
    }
    // An object is its own: cropped to the box its scan went round ([subject]), the table or the
    // floor it stood on left out. Its box comes from the saved cloud and the trace's aims, so a
    // reopen crops it the same, and it is kept cropped.
    val cropped = subject?.takeIf { scope == ScanScope.Object }?.let { cropToSubject(fused.mesh, it) }
    // A box that crops nothing out is no subject: the surface is shown whole, never kept so —
    // the next open, its subject read again, tries once more.
    val keeps = subject == null || scope != ScanScope.Object || cropped != null
    val surface = slab?.mesh ?: cropped ?: fused.mesh
    val mesh = capped(surface, consume = true) { progress(FUSE_SHARE + (WRITE_START - FUSE_SHARE) * it) }
    if (mesh.triangleCount == 0) return null
    progress(WRITE_START)
    val glb = RerunMeshGlb.write(mesh, generator = "SceneView depth model")
    if (keeps) runCatching { store.saveDepthModel(id, glb, DEPTH_MODEL_VERSION) }
    progress(1f)
    return depthModel(glb, mesh, budgetReached = fused.droppedBlocks > 0)
}

/**
 * [mesh] cropped to [box], an object's box ([ScanSubject]): the triangles whose middle lies within
 * it, [SUBJECT_MARGIN_M] round its sides and over its top — less what it stands on. The support
 * is found round the object, never on it ([supportOf]): the height most upward-facing triangles in
 * a ring outside its footprint lie at, at or under its base. Only upward triangles within
 * [SUPPORT_BAND_M] of it go, and what lies below it; an object's own low parts stay. With no such
 * evidence round it, nothing is cut but the box. `null` when nothing is left.
 */
internal fun cropToSubject(mesh: RerunMesh, box: ScanBox): RerunMesh? {
    val p = mesh.positions
    val n = mesh.normals
    val ix = mesh.indices
    val c = cos(box.yaw)
    val s = sin(box.yaw)
    val halfA = box.length / 2 + SUBJECT_MARGIN_M
    val halfB = box.depth / 2 + SUBJECT_MARGIN_M
    val top = box.baseY + box.height + SUBJECT_MARGIN_M
    val tris = mesh.triangleCount
    val my = FloatArray(tris)
    val ny = FloatArray(tris)
    // Round the footprint, outside it: where the table or the floor it stands on shows.
    val ring = BooleanArray(tris)
    val inside = BooleanArray(tris) { t ->
        var mx = 0f
        var mz = 0f
        for (k in 0 until 3) {
            val v = ix[t * 3 + k]
            mx += p[v * 3] / 3f
            my[t] += p[v * 3 + 1] / 3f
            mz += p[v * 3 + 2] / 3f
            ny[t] += n[v * 3 + 1] / 3f
        }
        val dx = mx - box.centerX
        val dz = mz - box.centerZ
        val a = dx * c + dz * s
        val b = -dx * s + dz * c
        val outside = abs(a) > box.length / 2 || abs(b) > box.depth / 2
        ring[t] = outside && abs(a) <= box.length / 2 + SUPPORT_RING_M && abs(b) <= box.depth / 2 + SUPPORT_RING_M
        abs(a) <= halfA && abs(b) <= halfB && my[t] <= top
    }
    val support = supportOf(my, ny, ring, box.baseY)
    val remap = IntArray(mesh.vertexCount) { -1 }
    val kept = ArrayList<Int>()
    var next = 0
    for (t in 0 until tris) {
        if (!inside[t]) continue
        if (support != null) {
            if (my[t] < support - SUPPORT_BAND_M) continue
            if (ny[t] >= SUPPORT_MIN_NY && abs(my[t] - support) <= SUPPORT_BAND_M) continue
        }
        for (k in 0 until 3) {
            val v = ix[t * 3 + k]
            if (remap[v] < 0) remap[v] = next++
            kept += remap[v]
        }
    }
    if (kept.isEmpty()) return null
    val positions = FloatArray(next * 3)
    val normals = FloatArray(next * 3)
    val colors = IntArray(next)
    for (v in 0 until mesh.vertexCount) {
        val to = remap[v]
        if (to < 0) continue
        for (k in 0 until 3) {
            positions[to * 3 + k] = p[v * 3 + k]
            normals[to * 3 + k] = n[v * 3 + k]
        }
        colors[to] = mesh.colors[v]
    }
    return RerunMesh(positions, normals, colors, kept.toIntArray())
}

/**
 * The height an object stands at: the [SUPPORT_BIN_M] bin holding the most upward-facing
 * triangles ([ny], their mean normal's y) of the [ring] round it, from [SUPPORT_SEARCH_M] under
 * [baseY] to [SUPPORT_BAND_M] over it — never higher, where the object's own top could win;
 * `null` with too few there: no support is seen, so none is cut.
 */
private fun supportOf(my: FloatArray, ny: FloatArray, ring: BooleanArray, baseY: Float): Float? {
    val bins = ((SUPPORT_SEARCH_M + SUPPORT_BAND_M) / SUPPORT_BIN_M).toInt() + 1
    val counts = IntArray(bins)
    for (t in my.indices) {
        if (!ring[t] || ny[t] < SUPPORT_MIN_NY) continue
        val k = ((my[t] - baseY + SUPPORT_SEARCH_M) / SUPPORT_BIN_M).toInt()
        if (k in 0 until bins) counts[k]++
    }
    val best = counts.indices.maxBy { counts[it] }
    if (counts[best] < MIN_SUPPORT_TRIANGLES) return null
    return baseY - SUPPORT_SEARCH_M + (best + 0.5f) * SUPPORT_BIN_M
}

/** What an object's crop keeps round its box: the fused surface stands a voxel or two out of it. */
internal const val SUBJECT_MARGIN_M = 0.05f

/** Facing up within this of the support's height is the support, not the object: 2.5 cm. */
internal const val SUPPORT_BAND_M = 0.025f
private const val SUPPORT_MIN_NY = 0.9f
private const val SUPPORT_SEARCH_M = 0.1f

/** The ring round an object's footprint its support is looked for in: 20 cm. */
private const val SUPPORT_RING_M = 0.2f
private const val SUPPORT_BIN_M = 0.01f
private const val MIN_SUPPORT_TRIANGLES = 20

/**
 * [mesh] within [DEPTH_MODEL_MAX_TRIANGLES], simplified when past it. With [consume], its colour
 * and index arrays may be reused: the caller must be done reading the input mesh.
 */
internal fun capped(mesh: RerunMesh, consume: Boolean = false, progress: (Float) -> Unit = {}): RerunMesh =
    if (mesh.triangleCount <= DEPTH_MODEL_MAX_TRIANGLES) {
        mesh
    } else {
        RerunMeshSimplifier.simplify(
            mesh,
            DEPTH_MODEL_MAX_TRIANGLES,
            maxErrorM = SIMPLIFY_ERROR_M,
            maxErrorCeilingM = SIMPLIFY_ERROR_CEILING_M,
            consume = consume,
            progress = progress,
        )
    }

private fun depthModel(glb: ByteArray, mesh: RerunMesh? = null, budgetReached: Boolean = false): RerunScanModel? {
    val read = mesh ?: RerunGlbMesh.read(glb) ?: return null
    return RerunScanModel(
        kind = RerunScanModel.Kind.Mesh,
        glb = glb,
        bounds = RerunResultView.framingBounds(read),
        triangles = read.triangleCount,
        vertices = read.vertexCount,
        budgetReached = budgetReached,
    )
}

private const val FUSE_SHARE = 0.85f
private const val WRITE_START = 0.98f

/** A collapse moves the surface less than a voxel; one stalled over the budget, up to two. */
private const val SIMPLIFY_ERROR_M = 0.01f
private const val SIMPLIFY_ERROR_CEILING_M = 0.04f

/** Real work can retry a stage; the displayed share never moves backwards or exceeds its range. */
internal data class ScanModelProgress(val fraction: Float = 0f) {
    fun advance(next: Float): ScanModelProgress =
        if (next.isFinite()) ScanModelProgress(maxOf(fraction, next.coerceIn(0f, 1f))) else this
}
