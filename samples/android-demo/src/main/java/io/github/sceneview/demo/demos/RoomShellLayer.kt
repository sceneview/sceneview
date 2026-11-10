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
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.SurfelSplats
import io.github.sceneview.demo.demos.internal.room.RoomShellBuilder
import io.github.sceneview.demo.demos.internal.room.RoomShellGlb

/*
 * A room's result as a dollhouse (Live Room v2): its floor and walls — four, or an L's six — as flat planes textured
 * from the scan's photos, single-sided and facing into the room, and what stands in it as small
 * coloured points ([RoomShellBuilder]). The result opens on it; the Photo layer's splats stay
 * one tap away. Built once off the main thread and kept with the scan (`room.glb`,
 * `room-objects.svpc`), so a reopen reads it back at once.
 */

/** What a room's shell is built from: the scan's dense cloud, its key photos and its floor. */
internal class RoomShellInput(val cloud: DenseCloud, val photos: ModelPhotos?, val floorY: Float?) {
    companion object {
        /** [media]'s shell input; `null` without a dense cloud with normals. */
        fun of(media: RerunReplayMedia): RoomShellInput? {
            val cloud = media.dense?.cloud?.takeIf { it.count > 0 && it.normals != null } ?: return null
            return RoomShellInput(cloud, RerunModelSource.keyPhotosOf(media), media.manifest.floorY)
        }
    }
}

/** A room's shell as the result shows it: its `.glb` as the screen's model, and its object points as splats. */
internal class RoomShellShown(val model: RerunScanModel, val objects: SplatCloud)

/**
 * Bumped whenever the shell's builder makes a different shell of the same scan: a shell kept by
 * an earlier one is built again.
 */
internal const val ROOM_SHELL_VERSION = 2

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
    val shell = RoomShellBuilder.build(input.cloud, input.photos, input.floorY, progress) ?: run {
        Log.i(SHELL_TAG, "no room shell: the scan stands as no room")
        return null
    }
    val glb = RoomShellGlb.write(shell.faces, JpegAtlasEncoder) ?: return null
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

private const val SHELL_TAG = "RoomShell"
private const val NS_PER_MS = 1_000_000L
private const val KB = 1024
