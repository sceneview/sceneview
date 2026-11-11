package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.room.RoomBox
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
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
 * recording ([hasRecording]), its fused surface; else the model as before.
 */
internal fun resultKindOf(scope: ScanScope, hasRecording: Boolean, standsAsRoom: Boolean): ResultKind = when {
    scope == ScanScope.Room && standsAsRoom -> ResultKind.Shell
    hasRecording -> ResultKind.DepthModel
    else -> ResultKind.Model
}

/**
 * Bumped whenever [depthModelOf] makes a different surface of the same recording: a surface kept
 * by an earlier one is built again.
 */
internal const val DEPTH_MODEL_VERSION = 1

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
 * the cloud, so then it is left to the shell. [onRecording] runs once the recording is known to
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
    progress: (Float) -> Unit = {},
): RerunScanModel? {
    if (store == null || id == null) return null
    store.depthModel(id, DEPTH_MODEL_VERSION)?.let {
        val model = depthModel(it)
        if (model != null) progress(1f)
        return model
    }
    val depth = File(store.directoryOf(id), ScanDepthFormat.FILE).takeIf { it.isFile } ?: return null
    val standsAsRoom = scope == ScanScope.Room && (cloud == null || RoomBox.of(cloud, floorY) != null)
    if (resultKindOf(scope, hasRecording = true, standsAsRoom = standsAsRoom) != ResultKind.DepthModel) return null
    if (!ScanDepthFormat.readable(depth)) return null
    onRecording()
    progress(0f)
    val fused = RoomDepthMesh.fuse(depth) { progress(it * FUSE_SHARE) } ?: return null
    val mesh = capped(fused.mesh) { progress(FUSE_SHARE + (WRITE_START - FUSE_SHARE) * it) }
    if (mesh.triangleCount == 0) return null
    progress(WRITE_START)
    val glb = RerunMeshGlb.write(mesh, generator = "SceneView depth model")
    runCatching { store.saveDepthModel(id, glb, DEPTH_MODEL_VERSION) }
    progress(1f)
    return depthModel(glb, mesh, budgetReached = fused.droppedBlocks > 0)
}

/** [mesh] within [DEPTH_MODEL_MAX_TRIANGLES], simplified when past it. */
internal fun capped(mesh: RerunMesh, progress: (Float) -> Unit = {}): RerunMesh =
    if (mesh.triangleCount <= DEPTH_MODEL_MAX_TRIANGLES) {
        mesh
    } else {
        RerunMeshSimplifier.simplify(
            mesh,
            DEPTH_MODEL_MAX_TRIANGLES,
            maxErrorM = SIMPLIFY_ERROR_M,
            maxErrorCeilingM = SIMPLIFY_ERROR_CEILING_M,
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
