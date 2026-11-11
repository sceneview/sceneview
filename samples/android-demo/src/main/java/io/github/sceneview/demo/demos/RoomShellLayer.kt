package io.github.sceneview.demo.demos

import android.util.Log
import androidx.compose.runtime.Composable
import io.github.sceneview.SceneScope
import io.github.sceneview.core.splat.SplatCloud
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.room.RoomBox
import io.github.sceneview.demo.demos.internal.room.RoomDepthMesh
import io.github.sceneview.demo.demos.internal.room.RoomShellBuilder
import io.github.sceneview.demo.demos.internal.room.RoomShellGlb
import kotlinx.coroutines.CancellationException
import java.io.File

/*
 * A room's result as a dollhouse (Live Room v2): its floor and walls — four, or an L's six — as flat planes textured
 * from the scan's photos, single-sided and facing into the room, and what stands in it
 * ([RoomShellBuilder]): a surface fused again from the scan's depth recording ([RoomDepthMesh]),
 * part of the shell's `.glb`, when the scan kept its depth; small coloured points otherwise. The
 * result opens on it; the Photo layer's splats stay one tap away. Built once off the main thread
 * and kept with the scan (`room.glb`, `room-objects.svpc` — no points when the furniture is a
 * mesh), so a reopen reads it back at once.
 */

/**
 * What a room's shell is built from: the scan's dense cloud, its key photos and its floor — and its
 * depth recording ([ScanDepthFormat]) when [depth] names one; else the kept session's own is used.
 */
internal class RoomShellInput(val cloud: DenseCloud, val photos: ModelPhotos?, val floorY: Float?, val depth: File? = null) {
    companion object {
        /** [media]'s shell input; `null` without a dense cloud with normals. */
        fun of(media: RerunReplayMedia): RoomShellInput? {
            val cloud = media.dense?.cloud?.takeIf { it.count > 0 && it.normals != null } ?: return null
            return RoomShellInput(cloud, RerunModelSource.keyPhotosOf(media), media.manifest.floorY)
        }
    }
}

/**
 * A room's shell as the result shows it: its `.glb` as the screen's model — its furniture in it
 * when that is a mesh — and its object points as splats, none then.
 */
internal class RoomShellShown(val model: RerunScanModel, val objects: SplatCloud)

/**
 * Bumped whenever the shell's builder makes a different shell of the same scan: a shell kept by
 * an earlier one is built again.
 */
internal const val ROOM_SHELL_VERSION = 3

/**
 * Session [id]'s room shell: read back from [store] when kept, else built from [input] and kept.
 * `null` when there is neither, or the scan stands as no room ([RoomShellBuilder.build]) — the
 * result then shows the model as before. Blocking CPU and disk work, off the main thread;
 * [progress] may throw to stop it.
 */
internal suspend fun roomShellOf(
    store: RerunSessionStore?,
    id: String?,
    input: RoomShellInput?,
    progress: (Float) -> Unit,
): RoomShellShown? {
    val started = System.nanoTime()
    val kept = if (store != null && id != null) runCatching { store.roomShell(id, ROOM_SHELL_VERSION) }.getOrNull() else null
    if (kept != null) {
        val (glb, objects) = kept
        Log.i(SHELL_TAG, "room shell read back: ${objects.count} object points in ${(System.nanoTime() - started) / NS_PER_MS} ms")
        return shown(glb, objects)
    }
    input ?: return null
    // A scan that stands as no room (outdoors, an object) is not fused for nothing.
    val depth = (input.depth ?: if (store != null && id != null) File(store.directoryOf(id), ScanDepthFormat.FILE) else null)
        ?.takeIf { it.isFile && RoomBox.of(input.cloud, input.floorY) != null }
    // The depth fused again first, then the shell: the fusion is the longer part when there is one.
    val fusedShare = if (depth != null) DEPTH_SHARE else 0f
    val fused = depth?.let { file ->
        runCatching { RoomDepthMesh.fuse(file) { progress(DEPTH_SHARE * it) } }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(SHELL_TAG, "depth not fused: objects as points", it) }
            .getOrNull()
    }
    fused?.let {
        Log.i(
            SHELL_TAG,
            "depth fused: ${it.fused}/${it.frames} frames, ${it.blocks} blocks (${it.bytes / KB} KB, ${it.droppedBlocks} dropped), " +
                "${it.mesh.triangleCount} triangles, fuse ${it.fuseMs} ms, mesh ${it.meshMs} ms",
        )
    }
    val shell = RoomShellBuilder.build(
        input.cloud, input.photos, input.floorY,
        progress = { progress(fusedShare + (1f - fusedShare) * it) },
        depthMesh = fused?.mesh,
    ) ?: run {
        Log.i(SHELL_TAG, "no room shell: the scan stands as no room")
        return null
    }
    val glb = RoomShellGlb.write(shell.faces, JpegAtlasEncoder, objects = shell.objectMesh) ?: return null
    Log.i(SHELL_TAG, "room shell: ${shell.box}, ${shell.stats}, glb ${glb.size / KB} KB")
    if (store != null && id != null) {
        runCatching { store.saveRoomShell(id, glb, shell.objects, ROOM_SHELL_VERSION) }
            .onFailure { Log.w(SHELL_TAG, "room shell not kept", it) }
    }
    return shown(glb, shell.objects)
}

private fun shown(glb: ByteArray, objects: DenseCloud): RoomShellShown? {
    val mesh = RerunGlbMesh.read(glb) ?: return null
    val model = RerunScanModel(
        kind = RerunScanModel.Kind.Mesh,
        glb = glb,
        bounds = RerunResultView.framingBounds(mesh),
        triangles = mesh.triangleCount,
        vertices = mesh.vertexCount,
    )
    return RoomShellShown(model, SurfelSplats.points(objects))
}

/**
 * The shell's object points in the scene, while [shown]: small opaque splats, sorted against the
 * scene's camera on their own (`SplatNode`).
 */
@Composable
internal fun SceneScope.RoomObjectsLayer(objects: SplatCloud?, shown: Boolean) {
    objects?.takeIf { shown && it.count > 0 }?.let { SplatNode(splatCloud = it) }
}

/** The share of a shell's progress its depth fusion takes, when it has one. */
private const val DEPTH_SHARE = 0.5f
private const val SHELL_TAG = "RoomShell"
private const val NS_PER_MS = 1_000_000L
private const val KB = 1024
