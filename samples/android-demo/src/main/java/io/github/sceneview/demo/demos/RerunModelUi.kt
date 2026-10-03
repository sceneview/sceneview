package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
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
import androidx.compose.material3.Text
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunMeshSimplifier
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.ConnectedChoiceRow
import io.github.sceneview.loaders.ModelLoader
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The scan's surface: the room as one meshed, coloured 3D model — its raw depth fused into a TSDF
 * ([RerunTsdf]) while it recorded, meshed by marching cubes ([RerunMarchingCubes]) on demand, drawn
 * by the replay's own 3D view (Points | Surface) and shared as a lighter `.glb` ([RerunMeshGlb],
 * [RerunMeshSimplifier]) that any viewer opens.
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

/** A built model: its mesh, the `.glb` the 3D view draws, its size, and what building it took. */
internal class RerunModelBuild(
    val mesh: RerunMesh,
    val glb: ByteArray,
    val bounds: FloatArray,
    val triangles: Int,
    val vertices: Int,
    val buildMs: Long,
    val voxelBytes: Long,
    val budgetReached: Boolean,
) {
    /** The light `.glb` a share sends ([sharedRerunModel]), kept from the first share that made it. */
    @Volatile
    var shared: ByteArray? = null

    /** The triangles in [shared], so a cached copy that stayed heavy ([stillHeavy]) says so on every share. */
    @Volatile
    var sharedTriangles: Int = 0

    /** What the last share has to say, in place of the model's figures; `null` when the light copy went out. */
    var shareNote: RerunShareNote? by mutableStateOf(null)
}

/**
 * The `.glb` a share sends, and whether it is the [whole] mesh instead of the light copy. A light
 * copy the simplifier could not bring near its target is [heavy].
 */
internal class RerunSharedModel(val glb: ByteArray, val whole: Boolean, val heavy: Boolean = false)

/** What a share says when the light copy is not what went out. */
internal enum class RerunShareNote(@StringRes val message: Int) {
    /** Too little memory to simplify the mesh: the whole one was shared. */
    SentWhole(R.string.demo_ar_rerun_share_whole),

    /** Too little memory to write even the whole mesh: nothing was shared. */
    OutOfMemory(R.string.demo_ar_rerun_share_out_of_memory),

    /** The file could not be written, or handed to the share sheet. */
    Failed(R.string.demo_ar_rerun_share_failed),

    /** The mesh was lightened, but the simplifier stopped well above its target. */
    StillHeavy(R.string.demo_ar_rerun_share_still_heavy);

    companion object {
        /** The note for a share [error] stopped. */
        fun of(error: Throwable): RerunShareNote = if (error is OutOfMemoryError) OutOfMemory else Failed
    }
}

/** Builds the model of [source] — fuse (unless done), mesh, write — reporting its share done to [progress]. */
internal fun buildRerunModel(source: RerunModelSource, progress: (Float) -> Unit): RerunModelBuild {
    val started = System.nanoTime()
    Log.i(TAG, "model (${source::class.simpleName}): building")
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
        mesh = mesh,
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
 * The `.glb` a share sends: [build]'s mesh simplified to [RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES]
 * so a phone's viewer opens it, resting on its floor and centred. It is made on the first share —
 * seconds of blocking CPU work, reported to [progress], which stops it by throwing — then kept.
 *
 * A mesh whose simplification does not fit in the [free] heap ([lightShareFits]), or runs out of
 * it on the way, is sent whole instead — and not kept: the next share tries the light copy again,
 * and a phone short of memory does not hold a second full model. Writing the whole one can still
 * run out of memory: that error is the caller's to catch.
 */
internal fun sharedRerunModel(
    build: RerunModelBuild,
    free: Long = freeHeapBytes(),
    progress: (Float) -> Unit,
): RerunSharedModel {
    build.shared?.let { return RerunSharedModel(it, whole = false, heavy = stillHeavy(build.sharedTriangles)) }
    val started = System.nanoTime()
    var reduced = build.triangles
    val light = if (lightShareFits(build.vertices, build.triangles, free)) {
        try {
            RerunMeshGlb.writeShared(build.mesh, onReduced = { reduced = it }, progress = progress)
        } catch (ignored: OutOfMemoryError) {
            // The check is an estimate, and other threads allocate too.
            null
        }
    } else {
        null
    }
    val glb = light ?: RerunMeshGlb.writeShared(build.mesh, fullResolution = true)
    Log.i(
        TAG,
        "shared model: ${build.triangles} triangles, ${build.glb.size / KB} KB -> ${glb.size / KB} KB " +
            "in ${(System.nanoTime() - started) / NS_PER_MS} ms (${free / MB} MB free, sent whole ${light == null}); " +
            "triangles ${build.triangles} in, $reduced out, target ${RerunMeshSimplifier.DEFAULT_TARGET_TRIANGLES}",
    )
    build.sharedTriangles = reduced
    build.shared = light
    return RerunSharedModel(glb, whole = light == null, heavy = light != null && stillHeavy(reduced))
}

/**
 * Whether a light copy of [triangles] is still far above the simplifier's target: it stops when a
 * sweep gains under 1 %, so a mesh whose collapses are mostly illegal comes out heavy. Beyond
 * [HEAVY_OVERSHOOT] times the target the share says so.
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
    /** The model on screen; `null` while it builds and when there is none to show. */
    val build: RerunModelBuild?,
    /** Why there is no surface, in the words shown: the points stay on screen. `null` while there may be one. */
    val failure: String?,
    /** The mesh, once built; on screen while [ReplaySurface.shown]. */
    val surface: ReplaySurface?,
    /** What the last share has to say, in the words shown ([RerunShareNote]); `null` when it sent the light copy. */
    val shareNote: String? = null,
) {
    /** There is no surface to show, and [failure] says why. */
    val failed: Boolean get() = failure != null

    val building: Boolean get() = wanted && build == null && !failed

    /** What the timeline card says instead of its gesture hint, while Surface is asked for. */
    val caption: String?
        get() = when {
            !wanted -> null
            failure != null -> failure
            build == null -> ModelCopy.BUILDING
            else -> shareNote ?: ModelCopy.stats(build)
        }
}

/** Why a replay has no surface to show. */
internal enum class RerunSurfaceFailure(@StringRes val message: Int) {
    /** The scan left nothing to mesh. */
    PoorScan(R.string.demo_ar_rerun_surface_poor_scan),

    /** Building the mesh, or loading it into the 3D view, ran out of memory. */
    OutOfMemory(R.string.demo_ar_rerun_surface_out_of_memory),

    /** The build failed, or the 3D view could not load what it built. */
    Unavailable(R.string.demo_ar_rerun_surface_unavailable);

    companion object {
        /**
         * The failure [error] is — `null` when nothing was thrown and the mesh simply came out
         * empty. Only that one is the scan's doing: telling someone to scan more slowly when the
         * phone ran out of memory sends them to redo a scan that was fine.
         */
        fun of(error: Throwable?): RerunSurfaceFailure = when (error) {
            null -> PoorScan
            is OutOfMemoryError -> OutOfMemory
            else -> Unavailable
        }
    }
}

/**
 * The surface of [source], built the first time it is [wanted] and once only: going back to Points
 * while it runs and asking again waits for the same build instead of starting a second one beside
 * it. Leaving the replay stops the build at its next progress step.
 *
 * What it keeps is the parsed model ([ReplaySurfaceModel]), loaded through [modelLoader] once the
 * build is done and freed with the replay: a 3D view that left the screen — Camera mode — comes
 * back with an instance of it instead of parsing the `.glb` again. A model instance cannot be kept
 * that way: the node that drew it takes it along.
 */
@Composable
internal fun rememberRerunSurface(
    source: RerunModelSource?,
    wanted: Boolean,
    modelLoader: ModelLoader,
): RerunSurfaceState {
    var progress by remember(source, modelLoader) { mutableFloatStateOf(0f) }
    var result by remember(source, modelLoader) { mutableStateOf<Result<RerunModelBuild>?>(null) }
    var loaded by remember(source, modelLoader) { mutableStateOf<Result<ReplaySurfaceModel>?>(null) }
    val asked by rememberUpdatedState(wanted)
    LaunchedEffect(source, modelLoader) {
        val from = source ?: return@LaunchedEffect
        snapshotFlow { asked }.first { it }
        val built = withContext(Dispatchers.Default) {
            val job = coroutineContext.job
            // The build is one blocking call. Once this screen is gone it is stopped at its next
            // progress step, so leaving mid-build and coming back does not run two builds at once.
            runCatching {
                buildRerunModel(from) {
                    job.ensureActive()
                    progress = it
                }
            }.onFailure {
                if (it is CancellationException) {
                    Log.i(TAG, "model: build stopped at ${(progress * PERCENT).toInt()} %, its screen left")
                    throw it
                }
            }
        }
        // Parsed here, once, on the main thread: not in the composition of the view that draws it,
        // where every Camera → 3D return paid for it again.
        val parsed = built.getOrNull()?.takeIf { it.triangles > 0 }?.let { build ->
            runCatching { ReplaySurfaceModel(modelLoader, build.glb) }
        }
        (built.exceptionOrNull() ?: parsed?.exceptionOrNull())?.let { Log.w(TAG, "no surface", it) }
        loaded = parsed
        result = built
    }
    // Keyed like the state above: a new source, or leaving the replay, frees the model it kept.
    DisposableEffect(source, modelLoader) { onDispose { loaded?.getOrNull()?.destroy() } }

    val error = result?.exceptionOrNull() ?: loaded?.exceptionOrNull()
    val model = loaded?.getOrNull()?.takeUnless { it.lost }
    val failure = when {
        result == null || model != null -> null
        error != null -> RerunSurfaceFailure.of(error)
        // Built and parsed, yet the view could draw no instance of it.
        loaded != null -> RerunSurfaceFailure.Unavailable
        else -> RerunSurfaceFailure.PoorScan
    }
    val build = result?.getOrNull()?.takeIf { model != null }
    val shown = wanted && source != null
    val surface = remember(build, model, shown) {
        if (build == null || model == null) return@remember null
        // The synthetic room is not the sample's room: it stands alone, framed by its own box.
        ReplaySurface(model, build.bounds, aligned = source !is RerunModelSource.Synthetic, shown = shown)
    }
    return RerunSurfaceState(
        wanted = shown,
        progress = progress,
        build = build,
        failure = failure?.let { stringResource(it.message) },
        surface = surface,
        shareNote = build?.shareNote?.let { stringResource(it.message) },
    )
}

/**
 * Points | Surface, the head of the replay's timeline card: what the 3D view above draws. The
 * surface builds on the first tap (its progress runs under the switch) and a lighter `.glb` of it
 * is shared from the button beside it: the first share takes a few seconds to simplify the mesh,
 * under the same progress line. A share that could not send that lighter copy says so in the
 * surface's caption ([RerunShareNote]).
 *
 * With [captioned] the switch says what the surface is doing — building, failed, or its size —
 * on a line of its own: the card of a phone on its side has no caption to say it in.
 */
@Composable
internal fun RerunSurfaceSwitch(
    state: RerunSurfaceState,
    onWanted: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    captioned: Boolean = false,
) {
    val chrome = LocalStageChrome.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The share under way and its share done; null when there is none.
    var sharing by remember { mutableStateOf<Float?>(null) }
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
                        if (sharing != null) return@IconButton
                        sharing = 0f
                        scope.launch {
                            built.shareNote = null
                            try {
                                built.shareNote = shareModelFile(context, title, built) { sharing = it }
                            } finally {
                                sharing = null
                            }
                        }
                    },
                    enabled = sharing == null,
                    modifier = Modifier.size(SceneViewTokens.Layout.touchTarget).testTag(RERUN_SHARE_MODEL_TAG),
                ) {
                    Icon(Icons.Rounded.IosShare, contentDescription = ModelCopy.SHARE, tint = chrome.onCard)
                }
            }
        }
        val shareDone = sharing
        if (state.building || shareDone != null) {
            LinearProgressIndicator(
                progress = { shareDone ?: state.progress },
                modifier = Modifier.fillMaxWidth(),
                color = chrome.accent,
                trackColor = chrome.track,
                drawStopIndicator = {},
            )
        }
        state.caption?.takeIf { captioned }?.let { caption ->
            Text(
                caption,
                style = OnScrimCaption,
                // Two lines: on its side the card is narrow, and a share note runs to ~48 characters.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(RERUN_SURFACE_CAPTION_TAG),
            )
        }
    }
}

/**
 * [build]'s light model ([sharedRerunModel]) as `<title> model.glb` in a fresh cache directory,
 * handed to the share sheet. Leaving the screen stops the simplification at its next [progress] step.
 *
 * Returns what there is to say about it: the whole mesh went out, the light copy stayed heavy, or — out of memory, a file that
 * could not be written — nothing did. `null` when the light copy was shared.
 */
private suspend fun shareModelFile(
    context: Context,
    title: String,
    build: RerunModelBuild,
    progress: (Float) -> Unit,
): RerunShareNote? = runCatching {
    val shared = withContext(Dispatchers.Default) {
        val job = coroutineContext.job
        sharedRerunModel(build) {
            job.ensureActive()
            progress(it)
        }
    }
    val file = withContext(Dispatchers.IO) {
        val shareRoot = File(context.cacheDir, RERUN_SHARE_DIR)
        // Only the latest shared file is kept: the share sheet has read it by the next share.
        shareRoot.deleteRecursively()
        val dir = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        File(dir, ModelCopy.fileName(title)).apply { writeBytes(shared.glb) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = RerunMeshGlb.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    when {
        shared.whole -> RerunShareNote.SentWhole
        shared.heavy -> RerunShareNote.StillHeavy
        else -> null
    }
}.getOrElse { error ->
    if (error is CancellationException) throw error
    Log.w(TAG, "model not shared", error)
    RerunShareNote.of(error)
}

/** The surface's words. */
internal object ModelCopy {
    const val POINTS = "Points"
    const val SURFACE = "Surface"
    const val BUILDING = "Building the surface of your room…"
    const val SHARE = "Share"

    /**
     * What was built, on one line. It is named a preview — a short scan gives a coarse, patchy
     * mesh — and it carries no size: the figures above measure the room squared to its walls, and
     * the mesh's world-axis box beside them read as a second, different room.
     */
    fun stats(model: RerunModelBuild): String {
        val triangles = if (model.triangles >= THOUSAND) "${model.triangles / THOUSAND}k" else "${model.triangles}"
        return if (model.budgetReached) "$PREVIEW · partial, memory was full" else "$PREVIEW · $triangles triangles"
    }

    private const val PREVIEW = "Surface preview"

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
internal const val RERUN_SURFACE_CAPTION_TAG = "rerun_surface_caption"

private const val TAG = "RerunModel"
private const val LOG_EVERY_FRAMES = 30
private const val MB = 1024L * 1024L
private const val KB = 1024
private const val NS_PER_MS = 1_000_000L
private const val PERCENT = 100

/** A live TSDF voxel meshes once two medium-confidence views (or one sure one) agree on it. */
private const val LIVE_MIN_WEIGHT = 1f
private const val FUSE_SHARE = 0.5f
private const val MESH_SHARE = 0.95f

/** A light copy more than this many times the triangle target is called heavy in the share's caption. */
private const val HEAVY_OVERSHOOT = 1.5

/** The simplifier's working arrays may take half of the heap left, no more. */
private const val SHARE_MEMORY_DIVISOR = 2
