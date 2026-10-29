package io.github.sceneview.demo.demos

import android.util.Log
import androidx.annotation.StringRes
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.model.ModelGlb

/*
 * The 3D model the export sheet saves and shares (#4334): not the full marching-cubes mesh the
 * result screen draws — several hundred thousand triangles in the session's world space — but a
 * light copy of it, about 100,000 triangles, resting on its floor and centred, so it opens at its
 * recipient's.
 */

/**
 * The `.glb` the export sheet writes, and what there is to say about it ([note]): `null` when it
 * is the light copy as planned, or a model with nothing to lighten (the points fallback).
 */
internal class RerunSharedModel(val glb: ByteArray, val note: RerunShareNote? = null)

/** What the model's row says when the light copy is not what was written, or nothing was. */
internal enum class RerunShareNote(@StringRes val message: Int) {
    /** Too little memory to simplify the mesh: the whole one was written. */
    SentWhole(R.string.scan_export_model_whole),

    /** The mesh was lightened, but the simplifier stopped well above its target. */
    StillHeavy(R.string.scan_export_model_heavy),

    /** Too little memory to write even the whole mesh: nothing was written. */
    OutOfMemory(R.string.scan_export_out_of_memory),

    /** The file could not be written. */
    Failed(R.string.scan_export_failed),
    ;

    companion object {
        /** The note for an [error] that stopped the file. */
        fun of(error: Throwable): RerunShareNote = if (error is OutOfMemoryError) OutOfMemory else Failed
    }
}

/**
 * The `.glb` the export sheet writes for [model]: its mesh simplified to
 * [RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES], resting on its floor (Y = 0) and centred in X/Z.
 * Seconds of blocking CPU work: run it on a worker; [progress] follows the simplification and
 * stops it by throwing.
 *
 * A mesh whose simplification does not fit in the [free] heap ([lightShareFits]), or runs out of
 * it on the way, is written whole instead, still on its floor. Writing the whole one can still run
 * out of memory: that error is the caller's to catch. A model of points, or one this app did not
 * write, is passed on as it is.
 */
internal fun sharedRerunModel(
    model: RerunScanModel,
    free: Long = freeHeapBytes(),
    progress: (Float) -> Unit = {},
): RerunSharedModel {
    if (model.kind != RerunScanModel.Kind.Mesh) return RerunSharedModel(model.glb)
    // The finished, photo-textured model is already within a phone's budget: sent as built, on its floor.
    if (ModelGlb.isTextured(model.glb)) return RerunSharedModel(ModelGlb.onFloor(model.glb) ?: model.glb)
    val mesh = RerunGlbMesh.read(model.glb)?.takeIf { it.triangleCount > 0 } ?: return RerunSharedModel(model.glb)
    val started = System.nanoTime()
    var reduced = mesh.triangleCount
    val light = if (lightShareFits(mesh.vertexCount, mesh.triangleCount, free)) {
        try {
            RerunMeshGlb.writeShared(mesh, onReduced = { reduced = it }, progress = progress)
        } catch (ignored: OutOfMemoryError) {
            // The check is an estimate, and other threads allocate too.
            null
        }
    } else {
        null
    }
    val glb = light ?: RerunMeshGlb.writeShared(mesh, fullResolution = true)
    Log.i(
        TAG,
        "shared model: ${mesh.triangleCount} triangles, ${model.glb.size / KB} KB -> ${glb.size / KB} KB " +
            "in ${(System.nanoTime() - started) / NS_PER_MS} ms (${free / MB} MB free, sent whole ${light == null}); " +
            "triangles ${mesh.triangleCount} in, $reduced out, target ${RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES}",
    )
    val note = when {
        light == null -> RerunShareNote.SentWhole
        stillHeavy(reduced) -> RerunShareNote.StillHeavy
        else -> null
    }
    return RerunSharedModel(glb, note)
}

/**
 * Whether a light copy of [triangles] is still far above the simplifier's target: it stops when a
 * sweep gains under 1 %, so a mesh whose collapses are mostly illegal comes out heavy. Beyond
 * [HEAVY_OVERSHOOT] times the target the sheet says so.
 */
internal fun stillHeavy(
    triangles: Int,
    target: Int = RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES,
): Boolean = triangles > target * HEAVY_OVERSHOOT

/**
 * Whether simplifying a mesh of this size fits in [free] bytes of heap: the simplifier's working
 * arrays may take half of it, the rest is left to the model it writes and to the 3D view.
 */
internal fun lightShareFits(vertices: Int, triangles: Int, free: Long): Boolean =
    RerunMeshSimplifier.workspaceBytes(vertices, triangles) <= free / SHARE_MEMORY_DIVISOR

/** The heap this process may still take. */
private fun freeHeapBytes(): Long {
    val runtime = Runtime.getRuntime()
    return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
}

private const val TAG = "RerunModel"
private const val MB = 1024L * 1024L
private const val KB = 1024
private const val NS_PER_MS = 1_000_000L

/** A light copy more than this many times the triangle target is called heavy on the model's row. */
private const val HEAVY_OVERSHOOT = 1.5

/** The simplifier's working arrays may take half of the heap left, no more. */
private const val SHARE_MEMORY_DIVISOR = 2
