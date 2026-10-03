package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.FileProvider
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.model
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The scan's surface: the room as one meshed, coloured 3D model — its raw depth fused into a TSDF
 * ([RerunTsdf]) while it recorded, meshed by marching cubes ([RerunMarchingCubes]) on demand, drawn
 * by the replay's own 3D view (Points | Surface) and shared as a `.glb` ([RerunMeshGlb]).
 */

/** What a room model is built from. */
internal sealed interface RerunModelSource {
    /** The TSDF the recording fused from ARCore's raw depth, frame by frame: the full model. */
    class Live(val tsdf: RerunTsdf) : RerunModelSource

    /** A kept scan's surfel cloud, when its raw depth is gone: the scan reopened later. */
    class Surfels(val cloud: DenseCloud) : RerunModelSource

    /** QA only: the ray-cast room, the emulator's stand-in for a camera (it cannot run AR). */
    data object Synthetic : RerunModelSource

    companion object {
        /** The model [pack] can build: its live TSDF when this process recorded it, else [media]'s surfels. */
        fun of(pack: RerunCapturePack?, media: RerunReplayMedia?): RerunModelSource? =
            RerunLiveModels.find(pack)?.let(::Live)
                ?: media?.dense?.cloud?.takeIf { it.normals != null && it.count > 0 }?.let(::Surfels)
    }
}

/**
 * The recording's TSDF, fed the same raw-depth copies as the surfel map — the depth image's own
 * lens, fresh depth only — on its own worker: a frame that arrives while one integrates is left
 * to the surfel map alone, so neither slows the other.
 */
internal class RerunLiveModel {
    val tsdf = RerunTsdf()
    private val busy = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    /** Integrates [frame] on [scope]'s Default dispatcher, unless an integration is under way. */
    fun offer(frame: DepthFrame, scope: CoroutineScope) {
        if (!busy.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.Default) {
            try {
                val step = tsdf.integrate(frame)
                if (tsdf.frames % LOG_EVERY_FRAMES == 1 || step.dropped > 0) {
                    Log.i(
                        TAG,
                        "tsdf: ${tsdf.frames} frames, ${tsdf.blockCount} blocks, " +
                            "${tsdf.bytes / MB} MB of ${tsdf.maxBytes / MB}, dropped ${tsdf.droppedBlocks}",
                    )
                }
            } finally {
                busy.set(false)
            }
        }
    }

    /** Waits for the integration under way. */
    suspend fun join() {
        job?.join()
    }

    /** Keeps this TSDF for [pack], the scan it recorded, so its replay can build the model. */
    fun keepFor(pack: RerunCapturePack?) {
        if (pack != null && tsdf.frames > 0) RerunLiveModels.keep(pack, tsdf)
    }
}

/**
 * The last recording's TSDF, kept in memory with the scan it belongs to (one slot: ~50 MB at
 * most). A scan reopened from the list in the same process finds it by its manifest's bytes.
 */
internal object RerunLiveModels {
    @Volatile
    private var kept: Pair<ByteArray, RerunTsdf>? = null

    fun keep(pack: RerunCapturePack, tsdf: RerunTsdf) {
        kept = pack.manifest to tsdf
    }

    fun find(pack: RerunCapturePack?): RerunTsdf? {
        pack ?: return null
        return kept?.takeIf { it.first.contentEquals(pack.manifest) }?.second
    }
}

/** A built model: its `.glb`, its size, and what building it took. */
internal class RerunModelBuild(
    val glb: ByteArray,
    val bounds: FloatArray,
    val triangles: Int,
    val vertices: Int,
    val buildMs: Long,
    val voxelBytes: Long,
    val budgetReached: Boolean,
)

/** Builds the model of [source] — fuse (unless done), mesh, write — reporting its share done to [progress]. */
internal fun buildRerunModel(source: RerunModelSource, progress: (Float) -> Unit): RerunModelBuild {
    val started = System.nanoTime()
    val (tsdf, minWeight) = when (source) {
        is RerunModelSource.Live -> source.tsdf to LIVE_MIN_WEIGHT
        is RerunModelSource.Surfels -> RerunTsdf().also { t ->
            t.integrateSurfels(source.cloud) { progress(it * FUSE_SHARE) }
        } to RerunMarchingCubes.DEFAULT_MIN_WEIGHT
        RerunModelSource.Synthetic -> RerunTsdf().also { t ->
            val poses = RerunSyntheticRoom.poses()
            poses.forEachIndexed { i, pose ->
                t.integrate(RerunSyntheticRoom.scene.render(pose))
                progress(FUSE_SHARE * (i + 1) / poses.size)
            }
        } to RerunMarchingCubes.DEFAULT_MIN_WEIGHT
    }
    val fused = System.nanoTime()
    val base = if (source is RerunModelSource.Live) 0f else FUSE_SHARE
    val extraction = RerunMarchingCubes.extract(tsdf, minWeight) { progress(base + (MESH_SHARE - base) * it) }
    val mesh = extraction.mesh
    val glb = if (mesh.triangleCount > 0) RerunMeshGlb.write(mesh) else ByteArray(0)
    progress(1f)
    val done = System.nanoTime()
    Log.i(
        TAG,
        "model (${source::class.simpleName}): ${mesh.triangleCount} triangles, ${mesh.vertexCount} vertices " +
            "(${extraction.rawTriangles} raw, ${extraction.droppedComponents} pieces dropped, " +
            "capped ${extraction.capped}); " +
            "fused ${(fused - started) / NS_PER_MS} ms, meshed ${(done - fused) / NS_PER_MS} ms; " +
            "tsdf ${tsdf.frames} frames, ${tsdf.blockCount} blocks, ${tsdf.bytes / MB} MB, " +
            "dropped ${tsdf.droppedBlocks}; " +
            "glb ${glb.size / KB} KB",
    )
    return RerunModelBuild(
        glb = glb,
        bounds = mesh.bounds(),
        triangles = mesh.triangleCount,
        vertices = mesh.vertexCount,
        buildMs = (done - started) / NS_PER_MS,
        voxelBytes = tsdf.bytes,
        budgetReached = tsdf.budgetReached,
    )
}

/**
 * The room's surface in the replay (#4306): asked for with the Points | Surface switch, built off
 * the main thread the first time, then kept — switching back and forth costs nothing. It is drawn
 * by the replay's own 3D view, under the same camera: there is no second screen to open.
 */
@Immutable
internal class RerunSurfaceState(
    /** Surface is the view asked for. */
    val wanted: Boolean,
    /** The build's share done, 0–1. */
    val progress: Float,
    val build: RerunModelBuild?,
    /** The scan gave no surface: the points stay on screen. */
    val failed: Boolean,
    /** The mesh, once loaded; on screen while [ReplaySurface.shown]. */
    val surface: ReplaySurface?,
) {
    val building: Boolean get() = wanted && build == null && !failed

    /** What the timeline card says instead of its gesture hint, while Surface is asked for. */
    val caption: String?
        get() = when {
            !wanted -> null
            failed -> ModelCopy.FAILED
            build == null -> ModelCopy.BUILDING
            else -> ModelCopy.stats(build)
        }
}

/**
 * The surface of [source], built the first time it is [wanted]. Call it above the 3D view: the
 * model instance is then released after the node that draws it (Compose forgets in reverse).
 */
@Composable
internal fun rememberRerunSurface(
    source: RerunModelSource?,
    wanted: Boolean,
    modelLoader: ModelLoader,
): RerunSurfaceState {
    var progress by remember(source) { mutableFloatStateOf(0f) }
    var result by remember(source) { mutableStateOf<Result<RerunModelBuild>?>(null) }
    LaunchedEffect(source, wanted) {
        val from = source?.takeIf { wanted && result == null } ?: return@LaunchedEffect
        result = withContext(Dispatchers.Default) { runCatching { buildRerunModel(from) { progress = it } } }
    }
    val build = result?.getOrNull()?.takeIf { it.triangles > 0 }
    val instance = remember(build) {
        build?.let { built ->
            val buffer = ByteBuffer.allocateDirect(built.glb.size).order(ByteOrder.nativeOrder()).put(built.glb)
            buffer.rewind()
            runCatching { modelLoader.createModelInstance(buffer) }.getOrNull()
        }
    }
    DisposableEffect(instance) { onDispose { instance?.let { modelLoader.destroyModel(it.model) } } }
    val shown = wanted && source != null
    val surface = remember(instance, build, shown) {
        if (instance == null || build == null) {
            null
        } else {
            // The synthetic room is not the sample's room: it stands alone, framed by its own box.
            ReplaySurface(instance, build.bounds, aligned = source !is RerunModelSource.Synthetic, shown = shown)
        }
    }
    return RerunSurfaceState(
        wanted = shown,
        progress = progress,
        build = build,
        failed = result != null && surface == null,
        surface = surface,
    )
}

/**
 * Points | Surface, the head of the replay's timeline card: what the 3D view above draws. The
 * surface builds on the first tap (its progress runs under the switch) and its `.glb` is shared
 * from the button beside it.
 */
@Composable
internal fun RerunSurfaceSwitch(
    state: RerunSurfaceState,
    onWanted: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalStageChrome.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ConnectedChoiceRow(
                options = listOf(false, true),
                selected = state.wanted,
                onSelect = onWanted,
                label = { if (it) ModelCopy.SURFACE else ModelCopy.POINTS },
                modifier = Modifier.weight(1f).testTag(RERUN_SURFACE_SWITCH_TAG),
                optionTestTag = { if (it) RERUN_BUILD_MODEL_TAG else RERUN_POINTS_TAG },
                colors = ToggleButtonDefaults.colors(
                    containerColor = chrome.track,
                    contentColor = chrome.onCard,
                    checkedContainerColor = chrome.accent,
                    checkedContentColor = chrome.onAccent,
                ),
            )
            val built = state.build
            if (state.wanted && built != null) {
                IconButton(
                    onClick = {
                        if (sharing) return@IconButton
                        sharing = true
                        scope.launch {
                            shareModelFile(context, title, built.glb)
                            sharing = false
                        }
                    },
                    enabled = !sharing,
                    modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).testTag(RERUN_SHARE_MODEL_TAG),
                ) {
                    Icon(Icons.Rounded.IosShare, contentDescription = ModelCopy.SHARE, tint = chrome.onCard)
                }
            }
        }
        if (state.building) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = chrome.accent,
                trackColor = chrome.track,
                drawStopIndicator = {},
            )
        }
    }
}

/** [glb] as `<title> model.glb` in a fresh cache directory, handed to the share sheet. */
private suspend fun shareModelFile(context: Context, title: String, glb: ByteArray) {
    val file = withContext(Dispatchers.IO) {
        val shareRoot = File(context.cacheDir, RERUN_SHARE_DIR)
        // Only the latest shared file is kept: the share sheet has read it by the next share.
        shareRoot.deleteRecursively()
        val dir = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, ModelCopy.fileName(title)).apply { writeBytes(glb) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = RerunMeshGlb.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** The surface's words. */
internal object ModelCopy {
    const val POINTS = "Points"
    const val SURFACE = "Surface"
    const val BUILDING = "Building the surface of your room…"
    const val FAILED = "No surface yet: scan slowly, 1–3 m from the walls."
    const val SHARE = "Share the 3D model"
    const val ABOUT = "Surface fuses the room's depth into one solid, coloured model. " +
        "Share it as a .glb for Blender, three.js or any 3D viewer."

    fun stats(model: RerunModelBuild): String {
        val size = "%.1f × %.1f m".format(model.bounds[3] - model.bounds[0], model.bounds[5] - model.bounds[2])
        val triangles = if (model.triangles >= THOUSAND) "${model.triangles / THOUSAND}k" else "${model.triangles}"
        val time = "%.1f s".format(model.buildMs / 1000f)
        val full = if (model.budgetReached) " · memory full, part left out" else ""
        return "$size · $triangles triangles · built in $time$full"
    }

    fun fileName(title: String): String {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "Room" }
        return "$safe model.glb"
    }

    private const val THOUSAND = 1000
}

internal const val RERUN_SURFACE_SWITCH_TAG = "rerun_surface_switch"
internal const val RERUN_POINTS_TAG = "rerun_points"
internal const val RERUN_BUILD_MODEL_TAG = "rerun_build_model"
internal const val RERUN_SHARE_MODEL_TAG = "rerun_share_model"

private const val TAG = "RerunModel"
private const val LOG_EVERY_FRAMES = 30
private const val MB = 1024L * 1024L
private const val KB = 1024
private const val NS_PER_MS = 1_000_000L

/** A live TSDF voxel meshes once two medium-confidence views (or one sure one) agree on it. */
private const val LIVE_MIN_WEIGHT = 1f
private const val FUSE_SHARE = 0.5f
private const val MESH_SHARE = 0.95f
