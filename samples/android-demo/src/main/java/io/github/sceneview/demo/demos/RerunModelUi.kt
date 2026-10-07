package io.github.sceneview.demo.demos

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.FileProvider
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.DEFAULT_IBL_INTENSITY
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.model.model
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The Rerun demo's final model: at the end of a scan, the room as one meshed, coloured 3D model
 * — its raw depth fused into a TSDF ([RerunTsdf]) while it recorded, meshed by marching cubes
 * ([RerunMarchingCubes]) on demand, shown here and shared as a `.glb` ([RerunMeshGlb]).
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
 * The recording's TSDF. The scan's final fusion fills it when the scan stops, from every kept
 * raw-depth frame on the pose ARCore gives it by then (`ScanCapture.finish`). [offer] is the feed
 * it had while recording, a frame at a time on the pose of its moment: nothing calls it any more.
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

/** The replay's way in: one button over the filmstrip, for a scan that can build its model. */
@Composable
internal fun RerunBuildModelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    Row(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .clickable(role = Role.Button, onClickLabel = ModelCopy.BUILD, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(RERUN_BUILD_MODEL_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Rounded.Chair, contentDescription = null, tint = chrome.onCard)
        Text(ModelCopy.BUILD, style = SceneViewTokens.Type.card, color = chrome.onCard)
    }
}

/**
 * The room's model: built off the main thread (progress on the card), then shown in 3D — turn it
 * with a finger, the walls you face fall away like a dollhouse's — and shared as a `.glb`.
 */
@Composable
@Suppress("LongMethod", "LongParameterList")
internal fun RerunModelScreen(
    source: RerunModelSource,
    title: String,
    onBack: () -> Unit,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    drift: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember(source) { mutableFloatStateOf(0f) }
    val build by produceState<Result<RerunModelBuild>?>(null, source) {
        value = withContext(Dispatchers.Default) { runCatching { buildRerunModel(source) { progress = it } } }
    }
    val model = build?.getOrNull()?.takeIf { it.triangles > 0 }
    // Created before the SceneView, so released after its node (Compose forgets in reverse).
    val instance = remember(model) {
        model?.let { built ->
            val buffer = ByteBuffer.allocateDirect(built.glb.size).order(ByteOrder.nativeOrder()).put(built.glb)
            buffer.rewind()
            runCatching { modelLoader.createModelInstance(buffer) }.getOrNull()
        }
    }
    DisposableEffect(instance) { onDispose { instance?.let { modelLoader.destroyModel(it.model) } } }
    val orbit = remember(source) { ArDebugOrbitCamera(drift = drift) }
    var framed by remember(source) { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        controls = { Text(ModelCopy.ABOUT, style = SceneViewTokens.Type.body) },
        themedStage = true,
        bottomOverlay = {
            // The themed stage's chrome, which DemoScaffold provides to its slots.
            val chrome = LocalStageChrome.current
            OverlayCard(testTag = RERUN_MODEL_CARD_TAG) {
                Text(title, style = SceneViewTokens.Type.card, color = chrome.onCard)
                val failed = build?.isFailure == true || build?.getOrNull()?.triangles == 0
                when {
                    failed -> Text(ModelCopy.FAILED, style = SceneViewTokens.Type.caption, color = chrome.onCardMuted)
                    model == null -> {
                        Text(ModelCopy.BUILDING, style = SceneViewTokens.Type.caption, color = chrome.onCardMuted)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = chrome.accent,
                            trackColor = chrome.track,
                            drawStopIndicator = {},
                        )
                    }
                    else -> Text(
                        ModelCopy.stats(model),
                        style = SceneViewTokens.Type.caption,
                        color = chrome.onCardMuted,
                    )
                }
            }
        },
        dock = listOf(
            DockItem(
                icon = Icons.Rounded._3dRotation,
                label = ModelCopy.RECENTER,
                caption = ModelCopy.RECENTER_CAPTION,
                onClick = { orbit.recenter() },
                enabled = model != null,
            ),
        ),
        dockAccent = DockItem(
            icon = Icons.Rounded.IosShare,
            label = ModelCopy.SHARE,
            caption = ModelCopy.SHARE_CAPTION,
            onClick = {
                val built = model ?: return@DockItem
                if (sharing) return@DockItem
                sharing = true
                scope.launch {
                    shareModelFile(context, title, built.glb)
                    sharing = false
                }
            },
            enabled = model != null && !sharing,
        ),
    ) {
        val chrome = LocalStageChrome.current
        val environment = rememberModelEnvironment(engine, chrome.ground)
        val view = rememberView(engine)
        val colorGrading = remember(engine) { ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine) }
        DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
        Box(Modifier.fillMaxSize().background(chrome.ground)) {
            SceneView(
                modifier = Modifier.matchParentSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                surfaceType = SurfaceType.TextureSurface,
                view = view,
                renderer = rememberRenderer(engine),
                isOpaque = true,
                frameRatePolicy = FrameRatePolicy.Continuous(),
                autoCenterContent = false,
                environment = environment,
                cameraManipulator = orbit,
                onFrame = {
                    // Linear tone mapping: the stage's skybox is exactly the chrome's ground.
                    if (view.colorGrading !== colorGrading) view.configureForDebug(colorGrading)
                    val built = model ?: return@SceneView
                    val home = ArDebugFraming.home(
                        built.bounds, orbit.home.azimuthDegrees, orbit.verticalFovDegrees, orbit.aspect,
                        elevationDegrees = MODEL_ELEVATION,
                        margin = MODEL_MARGIN,
                    )
                    if (orbit.following) orbit.home = home
                    if (!framed) {
                        framed = true
                        orbit.snapTo(home)
                    }
                },
            ) {
                // The room in its own world space, metres, Y up: framed by its bounds, not moved.
                instance?.let { ModelNode(modelInstance = it, autoAnimate = false) }
            }
        }
    }
}

@Composable
private fun rememberModelEnvironment(engine: Engine, ground: androidx.compose.ui.graphics.Color): Environment {
    val context = LocalContext.current
    return rememberEnvironment(engine, key = ground) {
        val stage = colorOf(ground).toLinearSpace()
        Environment(
            indirectLight = KTX1Loader.createIndirectLight(
                engine,
                context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
            ).indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
            skybox = Skybox.Builder().color(stage.x, stage.y, stage.z, 1f).build(engine),
        )
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

/** The model screen's words. */
internal object ModelCopy {
    const val BUILD = "Build 3D model"
    const val BUILDING = "Building the 3D model of your room…"
    const val FAILED = "No model: the scan saw too few surfaces up close. Scan slowly, 1–3 m from the walls."
    const val RECENTER = "Recenter the model"
    const val RECENTER_CAPTION = "Recenter"
    const val SHARE = "Share the 3D model"
    const val SHARE_CAPTION = "Share"
    const val ABOUT = "The room's depth, fused into a solid surface and coloured by the camera. " +
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

internal const val RERUN_BUILD_MODEL_TAG = "rerun_build_model"
internal const val RERUN_MODEL_CARD_TAG = "rerun_model_card"

private const val TAG = "RerunModel"
private const val LOG_EVERY_FRAMES = 30
private const val MB = 1024L * 1024L
private const val KB = 1024
private const val NS_PER_MS = 1_000_000L

/** A live TSDF voxel meshes once two medium-confidence views (or one sure one) agree on it. */
private const val LIVE_MIN_WEIGHT = 1f
private const val FUSE_SHARE = 0.5f
private const val MESH_SHARE = 0.95f

/** A three-quarter view from higher than the replay's: into the room over its near walls. */
private const val MODEL_ELEVATION = 50f
private const val MODEL_MARGIN = 1.05f
