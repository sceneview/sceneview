package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.DEFAULT_IBL_INTENSITY
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.createEnvironment
import io.github.sceneview.demo.BuildConfig
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoSceneRestingCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demoContentPadding
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.CameraRig
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.DollhouseOpening
import io.github.sceneview.demo.demos.internal.FramingInsets
import io.github.sceneview.demo.demos.internal.ReconstructionMethod
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMarchingCubes
import io.github.sceneview.demo.demos.internal.RerunMeshGlb
import io.github.sceneview.demo.demos.internal.RerunPointsMesh
import io.github.sceneview.demo.demos.internal.RerunResultView
import io.github.sceneview.demo.demos.internal.RerunScanModel
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RerunSyntheticRoom
import io.github.sceneview.demo.demos.internal.RerunTsdf
import io.github.sceneview.demo.demos.internal.ResultFloor
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanResultCinematics
import io.github.sceneview.demo.demos.internal.ScanResultFrame
import io.github.sceneview.demo.demos.internal.ScanResultFraming
import io.github.sceneview.demo.demos.internal.ScanResultCut
import io.github.sceneview.demo.demos.internal.ScanResultTurntable
import io.github.sceneview.demo.demos.internal.ResultShading
import io.github.sceneview.demo.common.qaStateOverridesAllowed
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.groundSource
import io.github.sceneview.demo.demos.internal.model.AtlasEncoder
import io.github.sceneview.demo.demos.internal.model.CleanModel
import io.github.sceneview.demo.demos.internal.model.CleanModelResult
import io.github.sceneview.demo.demos.internal.model.ModelPhoto
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.RawSurface
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import io.github.sceneview.demo.isDemoCompactHeight
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.SceneViewTokens.Space
import io.github.sceneview.demo.theme.motionFade
import io.github.sceneview.demo.ui.NarrationText
import io.github.sceneview.demo.ui.narrationBase
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.colorOf
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.model.model
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The 3D Scan's result: at the end of a scan, the space as one meshed, coloured 3D model — its raw
 * depth fused into a TSDF ([RerunTsdf]) while it recorded, meshed by marching cubes
 * ([RerunMarchingCubes]) when the scan stops, kept next to the scan ([RerunSessionStore.saveModel])
 * and shown here, still, on the themed stage. The marching cubes' raw surface is finished by
 * [CleanModel] — cleaned, flattened, simplified and textured from the scan's key photos — and
 * exported as that same `.glb`.
 */

/** What a scan's model is built from. */
internal sealed interface RerunModelSource {
    /**
     * The TSDF the recording fused from ARCore's raw depth, frame by frame: the full model.
     * [cloud], the recording's coloured points, is the fallback when meshing finds no surface;
     * [photos], its key photos, texture the model.
     */
    class Live(tsdf: RerunTsdf, val cloud: DenseCloud?, val photos: ModelPhotos? = null) : RerunModelSource {
        @Volatile
        private var held: RerunTsdf? = tsdf

        /** The fused depth; `null` once [release]d. */
        val tsdf: RerunTsdf? get() = held

        /**
         * Lets the TSDF go (up to [RerunTsdf.MAX_BYTES]) once the model built from it is kept with
         * its scan: reopened, the scan reads that model instead of meshing again.
         */
        fun release() {
            held?.let(RerunLiveModels::forget)
            held = null
        }
    }

    /** A kept scan's surfel cloud, when its raw depth is gone: the scan reopened later. */
    class Surfels(val cloud: DenseCloud, val photos: ModelPhotos? = null) : RerunModelSource

    /**
     * QA only: the ray-cast room, the emulator's stand-in for a camera (it cannot run AR). With
     * [meshFails], meshing is skipped, as if it had found no surface: the points fallback.
     */
    data class Synthetic(val meshFails: Boolean = false) : RerunModelSource

    companion object {
        /** The model [pack] can build: its live TSDF when this process recorded it, else [media]'s surfels. */
        fun of(pack: RerunCapturePack?, media: RerunReplayMedia?): RerunModelSource? {
            val cloud = media?.dense?.cloud?.takeIf { it.count > 0 }
            val photos = media?.let(::photosOf)
            return RerunLiveModels.find(pack)?.let { Live(it, cloud, photos) }
                ?: cloud?.takeIf { it.normals != null }?.let { Surfels(it, photos) }
        }

        /** [media]'s key photos, decoded only when the texture picks them, each bitmap let go once read. */
        private fun photosOf(media: RerunReplayMedia): ModelPhotos? =
            ModelPhotos.of(media.trace, media.manifest.lens) { path ->
                media.decode(path)?.let { bitmap ->
                    try {
                        val argb = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        BakePhoto(UNPOSED, bitmap.width, bitmap.height, argb)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }

        /** [ModelPhotos.of] puts each photo at its trace pose; the decoder's pose is not read. */
        private val UNPOSED = DebugPose(0f, 0f, 0f)
    }
}

/**
 * The recording's TSDF, fed the same raw-depth copies as the surfel map — the depth image's own
 * lens, fresh depth only — on its own worker: a frame that arrives while one integrates is left
 * to the surfel map alone, so neither slows the other.
 */
internal class RerunLiveModel(private val preset: ScanPreset = ScanPreset.ROOM) {
    /** The TSDF while it records; `null` once [keepFor] has handed it on, so only its slot holds it. */
    @Volatile
    private var tsdf: RerunTsdf? = RerunTsdf(voxelM = preset.modelVoxelM)
    private val busy = AtomicBoolean(false)

    @Volatile
    private var job: Job? = null

    /** Integrates [frame] on [scope]'s Default dispatcher, unless an integration is under way. */
    fun offer(frame: DepthFrame, scope: CoroutineScope) {
        val tsdf = tsdf ?: return
        if (!busy.compareAndSet(false, true)) return
        job = scope.launch(Dispatchers.Default) {
            try {
                val step = tsdf.integrate(frame, farM = preset.farM)
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

    /**
     * Keeps this TSDF for [pack], the scan it recorded, so its result can build the model, and
     * lets go of it here: from now on the slot alone holds it, and the model's build can free it.
     */
    fun keepFor(pack: RerunCapturePack?) {
        val tsdf = tsdf ?: return
        this.tsdf = null
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

    /** Empties the slot when it holds [tsdf]. */
    fun forget(tsdf: RerunTsdf) {
        if (kept?.second === tsdf) kept = null
    }
}

/**
 * A built mesh: its `.glb`, its size, and what building it took. [finished] when [CleanModel]
 * finished it; `false` for the raw surface, shown first or left when the finish failed — with
 * [outOfMemoryInHeap] when it ran out of memory ([RerunScanModel.outOfMemoryInHeap]).
 */
internal class RerunModelBuild(
    val glb: ByteArray,
    val bounds: FloatArray,
    val triangles: Int,
    val vertices: Int,
    val buildMs: Long,
    val voxelBytes: Long,
    val budgetReached: Boolean,
    val finished: Boolean = true,
    val outOfMemoryInHeap: Long? = null,
)

/**
 * Meshes [source] — fuse (unless done), mesh, then finish ([CleanModel]): clean, simplify, texture
 * from its photos and write — reporting its share done to [progress]. A finish that fails, out
 * of memory included, leaves the raw surface: the one already shown, or written then (the
 * finish's garbage gone). Out of memory, it carries the heap it ran out of, to be kept as it is.
 *
 * A surface of [RAW_FIRST_TRIANGLES] or more takes a phone longer to finish than a result should
 * keep the user waiting: when [onRaw] is given, that raw surface goes to it first (not
 * [RerunModelBuild.finished]), and the finished model, in the same place, replaces it.
 *
 * Finishing again a raw surface already on screen, [shown] is its `.glb`: the finish works in
 * the surface's arrays as with one written first, and [onRaw] hears nothing — written a second
 * time, a big scan's `.glb` would hold twice its size in the heap the finish needs.
 *
 * The surface is held in its [RawSurface] only, never in a local: a finish that works in its
 * arrays lets them go once prepared, before the cleaning's peak.
 */
internal fun buildRerunModel(
    source: RerunModelSource,
    onRaw: ((RerunModelBuild) -> Unit)? = null,
    shown: ByteArray? = null,
    reconstruction: ReconstructionMethod = ReconstructionMethod.Fused,
    progress: (Float) -> Unit,
): RerunModelBuild {
    val started = System.nanoTime()
    val meshed = mesh(source, progress, reconstruction)
    val raw = meshed.surface
    val meshedAt = System.nanoTime()
    val photos = when (source) {
        is RerunModelSource.Live -> source.photos
        is RerunModelSource.Surfels -> source.photos
        is RerunModelSource.Synthetic -> syntheticPhotos()
    }
    val rawTriangles = raw.triangleCount
    val rawVertices = raw.vertexCount
    val rawBounds = RerunResultView.framingBounds(raw.mesh)
    val rawGlb = if (shown != null) {
        shown
    } else if (onRaw != null && rawTriangles >= RAW_FIRST_TRIANGLES) {
        RerunMeshGlb.write(raw.mesh).also { glb ->
            onRaw(
                RerunModelBuild(
                    glb = glb,
                    bounds = rawBounds,
                    triangles = rawTriangles,
                    vertices = rawVertices,
                    buildMs = (meshedAt - started) / NS_PER_MS,
                    voxelBytes = meshed.bytes,
                    budgetReached = meshed.droppedBlocks > 0,
                    finished = false,
                ),
            )
        }
    } else {
        null
    }
    // Once its .glb is written, the raw surface's own arrays are what the finish works in: a
    // copy of a big scan is the heap a mid-range phone does not have (a Pixel 4a's 192 MB).
    val finish = if (rawTriangles > 0) {
        finish(raw, photos, rawGlb) { progress(MESH_SHARE + (1f - MESH_SHARE) * it) }
    } else {
        null
    }
    val finished = finish?.getOrNull()
    // Out of memory, the finish gives the raw surface back itself; one that throws it is left the same.
    val outOfMemory = finished?.stats?.rawKept == true || finish?.exceptionOrNull() is OutOfMemoryError
    val outOfMemoryInHeap = Runtime.getRuntime().maxMemory().takeIf { outOfMemory }
    // Without a .glb written first, the finish copied the surface: it is still held.
    val glb = when {
        finished != null -> finished.glb
        rawTriangles > 0 -> rawGlb ?: RerunMeshGlb.write(raw.mesh)
        else -> ByteArray(0)
    }
    progress(1f)
    val done = System.nanoTime()
    // A finish that ran out of memory has no model of its own: the raw surface's figures are its.
    val finishedMesh = finished?.model?.asRerunMesh()
    Log.i(
        TAG,
        "model (${source::class.simpleName}): $rawTriangles triangles, $rawVertices vertices " +
            "(${meshed.extractedTriangles} raw, ${meshed.droppedComponents} pieces dropped, " +
            "capped ${meshed.capped} at ${meshed.maxTriangles}); " +
            "fused ${meshed.fuseMs} ms, meshed ${(meshedAt - started) / NS_PER_MS - meshed.fuseMs} ms, " +
            "finished ${(done - meshedAt) / NS_PER_MS} ms (${finished?.stats ?: "raw surface"}" +
            "${outOfMemoryInHeap?.let { ", out of a ${it / MB} MB heap" } ?: ""}); " +
            "tsdf ${meshed.frames} frames, ${meshed.blocks} blocks, ${meshed.bytes / MB} MB, " +
            "dropped ${meshed.droppedBlocks}, ${if (meshed.released) "released" else "kept"}; " +
            "glb ${glb.size / KB} KB",
    )
    return RerunModelBuild(
        glb = glb,
        bounds = finishedMesh?.let(RerunResultView::framingBounds) ?: rawBounds,
        triangles = finishedMesh?.triangleCount ?: rawTriangles,
        vertices = finishedMesh?.vertexCount ?: rawVertices,
        buildMs = (done - started) / NS_PER_MS,
        voxelBytes = meshed.bytes,
        budgetReached = meshed.droppedBlocks > 0,
        finished = finishedMesh != null,
        outOfMemoryInHeap = outOfMemoryInHeap,
    )
}

/**
 * [source]'s surface, the extraction's figures, and what its TSDF held — the TSDF itself is not
 * kept past meshing, nor the extraction, which would hold the surface past its holder.
 */
private class Meshed(
    val surface: RawSurface,
    /** Triangles marching cubes emitted, before the small pieces went, and the pieces dropped. */
    val extractedTriangles: Int,
    val droppedComponents: Int,
    /** The most triangles the heap's finish takes ([CleanModel.maxRawTriangles]), and whether it cut the extraction. */
    val maxTriangles: Int,
    val capped: Boolean,
    val fuseMs: Long,
    val frames: Int,
    val blocks: Int,
    val bytes: Long,
    val droppedBlocks: Int,
    val released: Boolean,
)

/**
 * Fuses [source] (unless done) and meshes it. Nothing past this function holds the TSDF, so the
 * finish's peak — the cleaning and the photos — never sits on top of its up to
 * [RerunTsdf.MAX_BYTES]. A recording's TSDF that big is also let go from its slot: reopened
 * without a model kept, the scan meshes its surfels instead.
 *
 * The surface is meshed within what the heap's finish takes ([CleanModel.maxRawTriangles]): on a
 * Pixel 4a's 192 MB, a scan past it meshes with cells twice as coarse, and finishes.
 */
private fun mesh(
    source: RerunModelSource,
    progress: (Float) -> Unit,
    reconstruction: ReconstructionMethod = ReconstructionMethod.Fused,
): Meshed {
    val started = System.nanoTime()
    val reconstructed = reconstructedTsdf(source, reconstruction) { progress(it * FUSE_SHARE) }
    val tsdf = reconstructed ?: when (source) {
        is RerunModelSource.Live -> checkNotNull(source.tsdf) { "TSDF released" }
        is RerunModelSource.Surfels -> RerunTsdf().also { t ->
            t.integrateSurfels(source.cloud) { progress(it * FUSE_SHARE) }
        }
        is RerunModelSource.Synthetic -> RerunTsdf().also { t ->
            val poses = RerunSyntheticRoom.poses()
            poses.forEachIndexed { i, pose ->
                t.integrate(TexturedSyntheticRoom.scene.render(pose))
                progress(FUSE_SHARE * (i + 1) / poses.size)
            }
        }
    }
    val fuseMs = (System.nanoTime() - started) / NS_PER_MS
    // A surfel's weight counts surfels, each already seen in DenseFusion.MIN_VIEWS frames or more:
    // the default keeps the lateral fade between two surfel columns. Depth frames count views.
    val minWeight = if (reconstructed != null || source is RerunModelSource.Surfels) RerunMarchingCubes.DEFAULT_MIN_WEIGHT else FRAMES_MIN_WEIGHT
    val base = if (source is RerunModelSource.Live && reconstructed == null) 0f else FUSE_SHARE
    val maxTriangles = CleanModel.maxRawTriangles(Runtime.getRuntime().maxMemory())
    val extraction = RerunMarchingCubes.extractWhole(tsdf, minWeight, maxTriangles) { progress(base + (MESH_SHARE - base) * it) }
    val release = source is RerunModelSource.Live && reconstructed == null && tsdf.bytes >= tsdf.maxBytes / 2
    if (release && source is RerunModelSource.Live) source.release()
    return Meshed(
        surface = RawSurface(extraction.mesh),
        extractedTriangles = extraction.rawTriangles,
        droppedComponents = extraction.droppedComponents,
        maxTriangles = maxTriangles,
        capped = extraction.capped,
        fuseMs = fuseMs,
        frames = tsdf.frames,
        blocks = tsdf.blockCount,
        bytes = tsdf.bytes,
        droppedBlocks = tsdf.droppedBlocks,
        released = release,
    )
}

/**
 * [raw] finished by [CleanModel] — the raw surface's `.glb` when it ran out of memory
 * ([CleanModelStats.rawKept]) — or why it failed (logged), out of memory again included: the
 * finish's garbage is gone with it, and the raw surface is left. Cancellation goes through.
 * With [rawGlb], [raw]'s arrays are worked in and let go, not copied: its `.glb` is already
 * written, and [raw] is not read again. Without, [raw] keeps its surface.
 */
private fun finish(raw: RawSurface, photos: ModelPhotos?, rawGlb: ByteArray?, progress: (Float) -> Unit): Result<CleanModelResult> =
    runCatching {
        if (rawGlb != null) {
            CleanModel.build(raw, rawGlb, photos, JpegAtlasEncoder, progress = progress)
        } else {
            CleanModel.build(raw.mesh, photos, JpegAtlasEncoder, progress = progress)
        }
    }
        .onFailure { if (it is CancellationException) throw it }
        .onFailure { Log.w(TAG, "finishing the model failed: the raw surface is left", it) }

/** QA only: the ray-cast room's key photos, as a recording keeps them. */
private fun syntheticPhotos(): ModelPhotos =
    ModelPhotos(TexturedSyntheticRoom.PHOTO_LENS, TexturedSyntheticRoom.photos().map { shot -> ModelPhoto(shot.pose) { shot } })

/** The texture atlas as the `.glb` embeds it. */
private object JpegAtlasEncoder : AtlasEncoder {
    override fun jpeg(argb: IntArray, width: Int, height: Int): ByteArray? {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, ATLAS_JPEG_QUALITY, it) }
                .toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * The scan's model: the meshed surface, or — when meshing finds none — the scan's coloured points
 * as small squares ([RerunPointsMesh]). `null` when there is neither. Off the main thread.
 * Only the finished model is [RerunScanModel.finished]: the raw surface a failed finish leaves,
 * or the points, are shown but not kept, and the next open tries again — except the raw surface
 * a finish ran out of memory on, kept as it is ([RerunScanModel.outOfMemoryInHeap]). [onRaw]
 * hears a big scan's raw surface while it finishes; [shown] is the `.glb` of one already on
 * screen, finished again ([buildRerunModel]).
 *
 * [progress] is also where a cancelled build stops: it may throw [CancellationException], which
 * goes through untouched — a screen left mid-build stops meshing instead of racing the next one.
 */
internal fun buildScanModel(
    source: RerunModelSource,
    onRaw: ((RerunScanModel) -> Unit)? = null,
    shown: ByteArray? = null,
    reconstruction: ReconstructionMethod = ReconstructionMethod.Fused,
    progress: (Float) -> Unit,
): RerunScanModel? {
    val skipMesh = source is RerunModelSource.Synthetic && source.meshFails
    val mesh = if (skipMesh) {
        null
    } else {
        val raw = onRaw?.let { show -> { build: RerunModelBuild -> show(build.scanModel()) } }
        runCatching { buildRerunModel(source, raw, shown, reconstruction) { progress(it * MESH_PART) } }
            .onFailure { if (it is CancellationException) throw it }
            .onFailure { Log.w(TAG, "meshing failed", it) }
            .getOrNull()
            ?.takeIf { it.triangles > 0 }
    }
    if (mesh != null) {
        progress(1f)
        return mesh.scanModel()
    }
    val cloud = when (source) {
        is RerunModelSource.Live -> source.cloud
        is RerunModelSource.Surfels -> source.cloud
        is RerunModelSource.Synthetic -> syntheticCloud()
    }?.takeIf { it.count > 0 }
    val points = cloud?.let { RerunPointsMesh.of(it) }?.takeIf { it.triangleCount > 0 }
    progress(1f)
    points ?: return null
    Log.i(TAG, "model: points fallback, ${cloud.count} points, ${points.triangleCount} triangles")
    return RerunScanModel(
        kind = RerunScanModel.Kind.Points,
        glb = RerunMeshGlb.write(points),
        bounds = RerunPointsMesh.framingBounds(points),
        triangles = points.triangleCount,
        vertices = points.vertexCount,
        finished = false,
    )
}

private fun RerunModelBuild.scanModel() = RerunScanModel(
    kind = RerunScanModel.Kind.Mesh,
    glb = glb,
    bounds = bounds,
    triangles = triangles,
    vertices = vertices,
    budgetReached = budgetReached,
    finished = finished,
    outOfMemoryInHeap = outOfMemoryInHeap,
)

/** QA only: the ray-cast room's depth, fused into coloured points as a recording fuses them. */
internal fun syntheticCloud(): DenseCloud {
    val fusion = DenseFusion()
    RerunSyntheticRoom.frames().forEach { fusion.add(DepthBackProjection.project(it)) }
    return fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
}

/** Where the result screen is: opening the model, showing it, or with none to show. */
private sealed interface ModelState {
    data object Loading : ModelState

    /**
     * [model] shown; not [final] while it is a big scan's raw surface, its finished model on the
     * way. Framed by [framedBy]: the first model shown, so the finished one replacing the raw
     * surface keeps the camera, the reveal and the toggles where they are.
     */
    class Ready(val model: RerunScanModel, val final: Boolean = true, val framedBy: RerunScanModel = model) : ModelState

    data object Failed : ModelState
}

/** The model is known for good: the finished one, the fallback, or none. */
private val ModelState.settled: Boolean get() = this == ModelState.Failed || (this as? ModelState.Ready)?.final == true

/**
 * A scan's result: its model, full screen and still on the themed stage, framed as what it is —
 * an object on its own box, a room square to its walls, three quarters on its front, from
 * outside ([ScanResultFraming], by [scope]) — and left there: the camera only moves when the user
 * moves it (Scan redesign, PR 1). Kept with the scan ([store], [id]) — reopened, it shows the
 * same model without building it again; never kept, it is built from [source] with the default
 * finish, without a question first, and saved.
 *
 * One bar under it, three actions: Share, View in AR, and More (Plan, Measure, Watch capture).
 * Nothing is drawn over the model by default: no grid, slab, dimensions, zones nor pill.
 *
 * [onModel] hears the model once it is known (`null` when there is none), for the export sheet.
 * [opened] is the kept model when the caller has already read it, so it is not read twice.
 */
@Composable
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
internal fun RerunModelScreen(
    source: RerunModelSource?,
    title: String,
    store: RerunSessionStore?,
    id: String?,
    onModel: (RerunScanModel?) -> Unit,
    onBack: () -> Unit,
    onRename: ((String) -> Unit)?,
    onReplay: (() -> Unit)?,
    onViewInAr: (() -> Unit)?,
    onExport: () -> Unit,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    hintStays: Boolean = false,
    opened: RerunScanModel? = null,
    scope: ScanScope = ScanScope.Room,
) {
    remember(engine) { io.github.sceneview.demo.demos.internal.model.TextureBudget.readGpuLimit(engine) }
    val state by produceState<ModelState>(ModelState.Loading, source, id) {
        val stored = opened ?: if (store != null && id != null) {
            withContext(Dispatchers.IO) { runCatching { store.model(id) }.getOrNull() }
        } else {
            null
        }
        // A raw surface kept for running out of memory, with attempts left, is finished again from
        // [source], shown meanwhile as a big scan's raw surface is shown while it finishes.
        val retry = stored?.takeIf { it.retriesFinish && source != null }
        // A model an earlier finish built is shown at once and built again from [source] with
        // this one's (RerunScanModel.stalePipeline); it stays when there is nothing to build from.
        val stale = stored?.takeIf { it.stalePipeline && source != null }
        val kept = stored?.takeIf { retry == null && stale == null }
        var shownFirst: RerunScanModel? = retry ?: stale
        shownFirst?.let { value = ModelState.Ready(it, final = false) }
        val built = kept ?: source?.let { from ->
            withContext(Dispatchers.Default) {
                val job = coroutineContext.job
                // The line narrates the step without a share (one indicator, `DESIGN.md`): the
                // share is only where a screen closed meanwhile stops the build.
                val report = { _: Float -> job.ensureActive() }
                // A big scan's raw surface is shown while it finishes; the model then takes its place.
                // Finishing again, the kept one is already shown, its .glb the finish's own: a
                // second, written again, would hold as much heap once more.
                val raw = { surface: RerunScanModel ->
                    if (shownFirst == null) {
                        shownFirst = surface
                        value = ModelState.Ready(surface, final = false)
                    }
                }
                // The default finish, never asked first: the result opens on the model.
                runCatching { buildScanModel(from, raw, retry?.glb, ReconstructionMethod.Fused, report) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "model build failed", it) }
                    .getOrNull()
            }
        }
        val model = retry?.finishedAgain(built) ?: built ?: stale
        // A model finished now is kept with its scan, so the next open reads it instead of meshing
        // again; so is the raw surface a finish ran out of memory on, shown at once on the next
        // opens and finished again behind it while attempts are left, their count kept with it
        // ([RerunScanModel.retriesFinish]). Another fallback is not: the next open tries again —
        // from the TSDF while its slot holds it, from the scan's surfels once a big one was let go
        // at meshing to give the finish its heap (mesh()). Only a model kept here lets go a TSDF
        // still held.
        val keepAs = id?.takeIf { kept == null && model !== stale && store != null && model?.keptWithScan == true }
        if (keepAs != null && model != null) {
            val saved = withContext(Dispatchers.IO) {
                runCatching { checkNotNull(store).saveModel(keepAs, model) }
                    .onFailure { Log.w(TAG, "model not saved", it) }
                    .isSuccess
            }
            if (saved) (source as? RerunModelSource.Live)?.release()
        }
        value = if (model != null) ModelState.Ready(model, framedBy = shownFirst ?: model) else ModelState.Failed
    }
    val model = (state as? ModelState.Ready)?.model
    val settled = state.settled
    LaunchedEffect(state) { if (state.settled) onModel(model) }
    val framedBy = (state as? ModelState.Ready)?.framedBy
    // The ground it stands on (#4719), read off the model's own vertices once the model is settled,
    // never beside a big scan's finish ([groundSource]). Until the next settles, the last one stays.
    val groundModel = groundSource(model, settled)
    val ground by produceState<GroundBuild?>(null, groundModel) {
        groundModel?.let { built -> value = withContext(Dispatchers.Default) { groundOf(built) } }
    }
    // The room's dimensions, read off its walls (#4735) with the parts it missed (#4736): worked
    // out, never drawn but for More › Measure. The coverage is the capture's to show now.
    val rescan by produceState<RescanBuild?>(null, source, ground) {
        value = ground?.let { g -> withContext(Dispatchers.Default) { rescanOf(source, g.ground) } }
    }
    // Its frame: square to the walls it saw, on its own box (ScanResultFraming), read once off the
    // model the result opens on — the finished model that takes a raw surface's place keeps it.
    val frame by produceState<ScanResultFrame?>(null, framedBy) {
        value = null
        val by = framedBy ?: return@produceState
        value = withContext(Dispatchers.Default) { ScanResultFraming.read(by.glb) } ?: ScanResultFrame.unturned(by.bounds)
    }
    // A room is a dollhouse: seen into from outside and above, its walls cut at a fixed height
    // over its floor (ScanResultCut) — none comes and goes as the camera turns. It waits for its
    // frame, which knows the floor; an object is drawn whole at once.
    val cuts = scope.hasFloorPlan
    val cutY = frame?.takeIf { cuts }?.let { it.local[1] + ScanResultCut.HEIGHT_M }
    val drawn = model?.takeIf { !cuts || frame != null }
    // More's QA A/B: every material lit, or unlit, against the model as built (the default).
    val offersShading = BuildConfig.DEBUG && DemoSettings.qaMode
    var shading by remember {
        mutableStateOf(ResultShading.of(DemoSettings.qaDemoState?.takeIf { qaStateOverridesAllowed() }))
    }
    // Created before the SceneView, so released after its node (Compose forgets in reverse).
    // On the main thread, as every Filament call (CLAUDE.md); the cut writes the renderer's copy
    // only, the model kept and shared as it is.
    // Left unshown, not thrown, when the heap has no room for the renderer's copy.
    val instance = remember(drawn, cutY, shading) {
        drawn?.let { shading.buffer(it) }?.let { buffer ->
            cutY?.let { ScanResultCut.cut(buffer, it) }
            runCatching { modelLoader.createModelInstance(buffer) }.getOrNull()
        }
    }
    DisposableEffect(instance) { onDispose { instance?.let { modelLoader.destroyModel(it.model) } } }
    // The slow turn the result opens on (ScanResultTurntable), until the first touch; never
    // under reduced motion, nor in QA mode's still pictures unless their motion is asked for.
    val spins = (LocalMotionEnabled.current && !DemoSettings.qaMode) || DemoSettings.qaMotion
    val turntable = remember(framedBy, spins) { ScanResultTurntable().also { if (!spins) it.stop() } }
    // Which sides of the room its dimensions are written on: the two the camera faces.
    val facing = remember(id ?: source) { FacingSides() }
    // No drift and no reveal: the camera opens on the frame and holds still there until the user
    // moves it, its pitch between a look across and the plan's look down. Kept across a source
    // that arrives for a scan already shown, whose finish is run again.
    val orbit = remember(id ?: source) { ArDebugOrbitCamera(drift = false, elevationRange = ScanResultFraming.PITCHES) }
    // 3D ↔ Plan, the one camera move, and only when the user asks for it; a touch cuts it short.
    val cinematics = remember(orbit) { ScanResultCinematics(orbit, reveals = false) }
    // Read live by the framing below, so a toggle and the frame that flies to it agree.
    var plan by remember(framedBy) { mutableStateOf(false) }
    // More › Measure: the grid, the slab and the room's dimensions, off until asked for.
    var measuring by remember(framedBy) { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var shown by remember(framedBy) { mutableStateOf(false) }
    // Photo | Model | Plan, QA preview (ResultLayers.kt): the scan's surfels as splats.
    val layers = rememberResultLayers(source, framedBy, settled)
    val animateReveal = LocalMotionEnabled.current || DemoSettings.qaMotion
    var revealOver by remember(framedBy, animateReveal) { mutableStateOf(!animateReveal) }
    // The reveal's wire (ModelReveal): of a finished model the result opens on only, and only for
    // as long as the reveal plays. A raw surface or a retry, whose finish needs the heap, gets
    // none (ModelReveal.buildsWire), nor does the finished model that later takes its place.
    val revealOf = model?.takeIf { ModelReveal.buildsWire(settled, opensOn = it === framedBy, animate = animateReveal) }
    val wireBuild by rememberRevealWire(
        glb = revealOf?.takeIf { drawn != null }?.glb,
        triangles = revealOf?.triangles ?: 0,
        needed = !revealOver,
        cutY = cutY,
    )
    val wirePending = wireBuild is RevealWireBuild.Pending
    // The reveal starts once its wire is read, so it never opens on the whole model, then cuts.
    val revealClock = rememberRevealClock(key = framedBy, started = shown && !wirePending, animate = animateReveal)
    LaunchedEffect(revealClock) {
        snapshotFlow { revealClock.value >= ModelReveal.TOTAL_MS }.first { it }
        revealOver = true
    }
    var hintVisible by remember(framedBy) { mutableStateOf(true) }
    LaunchedEffect(shown, hintStays) {
        if (shown && !hintStays) {
            delay(HINT_MS)
            hintVisible = false
        }
    }
    // The finished model in a big scan's raw surface's place: framed by the raw one. The line
    // says so once, heard as it changes, then fades as the hint does.
    val rawShownFirst = (state as? ModelState.Ready)?.let { it.framedBy !== it.model } == true
    var finishedSaid by remember(framedBy) { mutableStateOf(false) }
    LaunchedEffect(framedBy, rawShownFirst && settled) {
        if (rawShownFirst && settled) {
            delay(HINT_MS)
            finishedSaid = true
        }
    }
    var renaming by remember { mutableStateOf(false) }
    val rename = onRename?.let { { renaming = true } }
    val measure = rescan?.measure

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        themedStage = true,
        // Reset and feedback in the identity row's ⋮: the bar holds the scan's three actions only.
        actionsMenu = true,
        topOverlay = { ScanNameCard(title = title, saved = id != null && settled, onRename = rename) },
        bottomOverlay = {
            ModelStatus.slot(model, settled, rawShownFirst, finishedSaid)?.let { slot -> ModelStatusLine(slot) }
            // Laid out from the start and only faded: the model is framed above this slot, and a
            // hint that came and went would move the model when it goes.
            val hintShown = shown && hintVisible
            val hintAlpha by animateFloatAsState(if (hintShown) 1f else 0f, label = "hint")
            ModelHint(
                text = stringResource(R.string.scan_result_hint),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .alpha(hintAlpha)
                    .then(if (hintShown) Modifier else Modifier.clearAndSetSemantics { }),
            )
            ResultLayerPicker(
                layers = layers,
                plan = plan,
                ready = shown && instance != null,
                onPlan = {
                    plan = it
                    cinematics.request(ScanResultCinematics.Request.Recenter)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            // More's menu, hung at the bar's end over its last item; it adds no height.
            Box(Modifier.align(Alignment.End).padding(end = Space.md)) {
                ResultMoreMenu(
                    expanded = more,
                    onDismiss = { more = false },
                    plan = plan.takeIf { scope.hasFloorPlan },
                    onPlan = {
                        more = false
                        plan = !plan
                        cinematics.request(ScanResultCinematics.Request.Recenter)
                    },
                    measuring = measuring.takeIf { scope.hasFloorPlan },
                    canMeasure = ground != null,
                    onMeasure = {
                        more = false
                        measuring = !measuring
                    },
                    onWatch = onReplay?.let { watch ->
                        {
                            more = false
                            watch()
                        }
                    },
                    shading = shading.takeIf { offersShading },
                    onShading = { shading = shading.next },
                )
            }
        },
        // Greyed while the model is built: neither is worth anything before it is known.
        dock = listOf(
            DockItem(
                icon = Icons.Rounded.IosShare,
                label = stringResource(R.string.scan_result_share_label),
                caption = stringResource(R.string.scan_result_share),
                onClick = onExport,
                enabled = settled,
            ),
            DockItem(
                icon = Icons.Rounded.ViewInAr,
                label = stringResource(R.string.scan_result_view_ar_label),
                caption = stringResource(R.string.scan_result_view_ar),
                onClick = { onViewInAr?.invoke() },
                enabled = settled && onViewInAr != null,
            ),
            DockItem(
                icon = Icons.Rounded.MoreHoriz,
                label = stringResource(R.string.scan_result_more_label),
                caption = stringResource(R.string.scan_result_more),
                onClick = { more = true },
                // An object has no plan to show: with no capture to watch either, More is empty.
                enabled = instance != null && (scope.hasFloorPlan || onReplay != null || offersShading),
                selected = more,
            ),
        ),
    ) {
        val chrome = LocalStageChrome.current
        val environment = rememberModelEnvironment(engine, chrome.ground)
        val view = rememberView(engine)
        val colorGrading = remember(engine) { ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine) }
        DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
        // Before the first frame, not on it: the tone mapping is part of the first picture.
        DisposableEffect(view, colorGrading) {
            view.configureForDebug(colorGrading)
            onDispose { }
        }
        val opening = remember(framedBy, orbit) { DollhouseOpening() }
        BoxWithConstraints(Modifier.fillMaxSize().background(chrome.ground)) {
            // What the chrome leaves of the view at rest — below the name, above the bar, between
            // the window's safe sides — less a gutter: the model opens centred on it, and whole in
            // it when it fits there at its size (#4492, RerunResultView.area). Held
            // sideways there is no such band: the model takes the safe area, the glass over it.
            val direction = LocalLayoutDirection.current
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val cover = if (isDemoCompactHeight()) {
                safe
            } else {
                demoContentPadding(
                    cover = LocalDemoSceneRestingCover.current,
                    sceneHeight = maxHeight,
                    left = safe.calculateLeftPadding(direction),
                    right = safe.calculateRightPadding(direction),
                )
            }
            val gutter = Space.md
            val insets = FramingInsets(
                left = (cover.calculateLeftPadding(direction) + gutter) / maxWidth,
                top = (cover.calculateTopPadding() + gutter) / maxHeight,
                right = (cover.calculateRightPadding(direction) + gutter) / maxWidth,
                bottom = (cover.calculateBottomPadding() + gutter) / maxHeight,
            )
            // The view's shape from its constraints: the surface reports its size after composing.
            val aspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
            // Four fifths of the zone between the name and the bar, on its fuller axis.
            val area = RerunResultView.area(insets)
            // Three quarters on its front, from outside and above; the plan straight down on the
            // same walls, its front at the bottom of the screen.
            val fov = orbit.verticalFovDegrees
            // A room framed on what is drawn of it: its box cut where its walls are.
            val shownFrame = remember(frame, cuts) { if (cuts) frame?.cappedAt(ScanResultCut.HEIGHT_M) else frame }
            val elevation = ScanResultFraming.elevation(scope)
            val view3d = remember(shownFrame, elevation, aspect, area, fov) {
                shownFrame?.pose(elevation, ScanResultFraming.THREE_QUARTER, fov, aspect, area)
            }
            val viewPlan = remember(shownFrame, aspect, area, fov) {
                shownFrame?.pose(ArDebugFraming.MAP_ELEVATION, 0f, fov, aspect, area)
            }
            // The turntable's pose about the opening, at its distance, whole in the zone the chrome frees.
            val zone = remember(insets) { insets.bounded() }
            val home = rememberUpdatedState {
                val f = shownFrame
                when {
                    plan -> viewPlan
                    f == null || view3d == null -> view3d
                    else -> turntable.pose(f, view3d, elevation, ScanResultFraming.THREE_QUARTER, fov, aspect, zone)
                }
            }
            SideEffect {
                // The gestures keep the eye outside the box and above its floor, never past a
                // pinch beyond the framing: the scan cannot be lost, nor entered.
                val f = shownFrame
                if (f != null && view3d != null) orbit.limits = f.limits(*listOfNotNull(view3d, viewPlan).toTypedArray())
                // A double tap comes back to the opening, not to where the turntable had got to.
                (if (plan) viewPlan else view3d)?.let { orbit.frontAzimuth = it.azimuthDegrees }
                home.value()?.let { opening.aim(orbit, it) }
            }
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
                cameraManipulator = cinematics.manipulator,
                onFrame = { frameTimeNanos ->
                    val framed = home.value() ?: return@SceneView
                    // The view is let to be seen once the model is drawn on its framing, not before.
                    // The wire is read first, so the model's reveal starts with it, not a frame after.
                    val ready = instance != null && (revealOf == null || wireBuild !is RevealWireBuild.Pending)
                    if (cinematics.frameDrawn(opening, framed, ready = ready)) shown = true
                    // The turntable, once the model is revealed: the first touch, or the plan, stops it for good.
                    if (!orbit.following || plan) {
                        turntable.stop()
                    } else if (turntable.running && shown && revealOver && !cinematics.animator.isPlaying) {
                        turntable.advanceTo(frameTimeNanos)
                        home.value()?.let { orbit.followTo(ArDebugFraming.clamp(it)) }
                    }
                    CameraRig.eye(orbit.pose).let { eye ->
                        facing.look(measure, eye.x, eye.z)
                        layers.look(eye)
                    }
                },
            ) {
                // The scan in its own world space, metres, Y up: framed by its bounds, not moved.
                // Revealed (ModelReveal): its wire sweeps up, then it rises into it, in place.
                instance?.let {
                    RevealedModel(
                        instance = it,
                        build = (wireBuild as? RevealWireBuild.Done)?.takeIf { model === revealOf },
                        // Read here, in the scene, so only the scene recomposes as the reveal plays.
                        elapsedMs = revealClock.value,
                        color = chrome.accent,
                        engine = engine,
                        materialLoader = materialLoader,
                    )
                }
                // An object stands on a soft contact shadow under its footprint, once it is revealed.
                shownFrame?.takeIf { scope == ScanScope.Object && instance != null && revealOver }?.let { f ->
                    val (x, z) = f.worldCentre
                    ContactShadow(
                        size = Size(
                            x = (f.local[3] - f.local[0]) * SHADOW_SPREAD,
                            y = 0f,
                            z = (f.local[5] - f.local[2]) * SHADOW_SPREAD,
                        ),
                        context = ContactShadowContext.Floor,
                        position = Position(x = x, y = f.local[1] + SHADOW_LIFT_M, z = z),
                        rotation = Rotation(y = -f.yawDegrees),
                    )
                }
                ground?.takeIf { measuring }?.let {
                    // The replay's grid round the slab (QA 2026-10-22), its lines sized for the
                    // camera the result opens on, bucketed so a reframe rarely rebuilds them.
                    val style = ArDebugStyle.forOrbit(
                        home.value()?.distance ?: 1f,
                        orbit.verticalFovDegrees,
                        constraints.maxHeight,
                    )
                    ScanGridLayer(it.ground, style, engine, materialLoader)
                    ScanGroundLayer(it.meshes, engine, materialLoader, ScanGroundColors.current())
                    // The room's dimensions on that grid, on the two sides facing the camera (#4735).
                    measure?.let { m ->
                        val y = it.ground.floorY - ScanGroundMeshes.SLAB_DROP_M + ResultFloor.MEASURE_LIFT_M
                        ResultMeasureLayer(m, y, style, facing.sides, engine, materialLoader)
                    }
                }
                ResultPhotoLayer(layers, instance)
            }
            // The ground until the model is drawn on its framing: no pose but that one is seen.
            // Then it fades (`motion-entrance`), never a cut from the save's "Fusing" to the
            // model (QA 2026-10-22). Opacity only: kept under reduced motion.
            val veil by animateFloatAsState(
                targetValue = if (shown) 0f else 1f,
                animationSpec = if (shown) {
                    tween(SceneViewTokens.Duration.longMillis, easing = SceneViewTokens.Ease.expressive)
                } else {
                    snap()
                },
                label = "veil",
            )
            if (veil > 0f) {
                Box(
                    Modifier
                        .matchParentSize()
                        .alpha(veil)
                        .background(chrome.ground)
                        .then(if (shown) Modifier else Modifier.testTag(RERUN_MODEL_VEIL_TAG)),
                )
            }
            ResultLayerVeil(layers, chrome.ground, Modifier.matchParentSize())
        }
    }
    if (renaming && onRename != null) {
        RenameDialog(
            title = title,
            onDismiss = { renaming = false },
            onRename = {
                renaming = false
                onRename(it)
            },
        )
    }
}

/**
 * More, under the result's bar: for a room, the floor plan and Measure — the grid, the slab and
 * the room's dimensions where it has walls to read them off — each ticked while on (`null` leaves
 * it out); and the capture played back ([onWatch], `null`: none to play). [canMeasure] is whether
 * the ground is read yet: until then Measure waits, greyed.
 */
@Composable
@Suppress("LongParameterList")
private fun ResultMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    plan: Boolean?,
    onPlan: () -> Unit,
    measuring: Boolean?,
    canMeasure: Boolean,
    onMeasure: () -> Unit,
    onWatch: (() -> Unit)?,
    shading: ResultShading? = null,
    onShading: () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.testTag(RERUN_MODEL_MORE_TAG)) {
        if (plan != null) {
            ResultMoreItem(Icons.Rounded.Map, stringResource(R.string.scan_result_plan), on = plan, onClick = onPlan)
        }
        if (measuring != null) {
            ResultMoreItem(
                Icons.Rounded.Straighten,
                stringResource(R.string.scan_result_measure),
                on = measuring,
                onClick = onMeasure,
                enabled = canMeasure,
            )
        }
        if (onWatch != null) {
            ResultMoreItem(Icons.Rounded.PlayCircle, stringResource(R.string.scan_result_watch), on = false, onClick = onWatch)
        }
        if (shading != null) {
            // QA only: a tap goes as built → lit → unlit, the menu left open on the result.
            val label = when (shading) {
                ResultShading.AsBuilt -> R.string.scan_result_qa_shading_as_built
                ResultShading.Lit -> R.string.scan_result_qa_shading_lit
                ResultShading.Unlit -> R.string.scan_result_qa_shading_unlit
            }
            ResultMoreItem(Icons.Rounded.WbSunny, stringResource(label), on = false, onClick = onShading)
        }
    }
}

@Composable
private fun ResultMoreItem(icon: ImageVector, text: String, on: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = if (on) {
            { Icon(Icons.Rounded.Check, contentDescription = null) }
        } else {
            null
        },
        modifier = Modifier.semantics { selected = on },
    )
}

/** The scan's name, and where it is kept; a tap renames it. */
@Composable
private fun ScanNameCard(title: String, saved: Boolean, onRename: (() -> Unit)?) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.lg)
    val label = stringResource(R.string.scan_result_rename)
    Box(Modifier.fillMaxWidth().padding(horizontal = Space.md), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .widthIn(max = SceneViewTokens.ArOverlay.maxWidth)
                .heightIn(min = SceneViewTokens.Layout.touchTarget)
                .clip(shape)
                .background(chrome.card, shape)
                .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
                .then(
                    if (onRename != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onRename)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Space.md, vertical = Space.sm)
                .testTag(RERUN_MODEL_NAME_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    title,
                    style = SceneViewTokens.Type.card,
                    color = chrome.onCard,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (saved) {
                    Text(
                        stringResource(R.string.scan_result_saved),
                        style = SceneViewTokens.Type.caption,
                        color = chrome.onCardMuted,
                    )
                }
            }
            if (onRename != null) {
                Icon(
                    Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = chrome.onCardMuted,
                    modifier = Modifier.size(Space.lg),
                )
            }
        }
    }
}

/**
 * What the model screen says under the model: that it is building it, finishing the raw surface
 * shown meanwhile (a big scan, [RAW_FIRST_TRIANGLES] or more), or why what it shows is less than
 * a finished model — so the raw surface is never left in its place without a word.
 */
internal enum class ModelStatus(@StringRes val text: Int) {
    Creating(R.string.scan_result_creating),
    Finishing(R.string.scan_result_finishing),

    /** The raw surface kept the last time ([RerunScanModel.retriesFinish]), finishing again behind it. */
    FinishingAgain(R.string.scan_result_finishing_again),
    Failed(R.string.scan_result_failed),
    Partial(R.string.scan_result_partial),

    /**
     * Kept as scanned, its finishes spent: the finish needs more heap than this phone's
     * ([RerunScanModel.outOfMemoryInHeap]).
     */
    RawOutOfMemory(R.string.scan_result_raw_memory),

    /** Kept as scanned after running out of memory, finished again at the next open ([RerunScanModel.retriesFinish]). */
    RawOutOfMemoryRetry(R.string.scan_result_raw_memory_retry),

    /** Shown as scanned, not kept: the next open tries the finish again. */
    RawUnfinished(R.string.scan_result_raw_unfinished),

    /** The finished model in the raw surface's place: said once, then faded ([slot]). */
    Finished(R.string.scan_result_finished),
    ;

    companion object {
        /**
         * The lines a big scan's raw surface can have under it, from its finish to each way the
         * finish ends: the line keeps the room of the tallest, whichever it ends on.
         */
        val RAW_LINES: List<ModelStatus>
            get() = listOf(Finishing, FinishingAgain, Finished, RawOutOfMemory, RawOutOfMemoryRetry, RawUnfinished)

        /** For [model] on screen, `null` while none is; [settled] once it is known for good. `null`: nothing to say. */
        fun of(model: RerunScanModel?, settled: Boolean): ModelStatus? = when {
            !settled -> when {
                model == null -> Creating
                model.retriesFinish -> FinishingAgain
                else -> Finishing
            }
            model == null -> Failed
            model.kind == RerunScanModel.Kind.Points -> Partial
            model.finished -> null
            model.outOfMemoryInHeap != null -> if (model.retriesFinish) RawOutOfMemoryRetry else RawOutOfMemory
            else -> RawUnfinished
        }

        /**
         * The line's slot under [model]: [of]'s status, shown; or, once a finished model has taken
         * the place of the raw surface shown first ([rawShownFirst]), [Finished] — shown until
         * [finishedSaid], then laid out and faded: the model is framed above this slot, and a line
         * that went would move it just after the swap. `null`: no line was ever under this model.
         */
        fun slot(
            model: RerunScanModel?,
            settled: Boolean,
            rawShownFirst: Boolean,
            finishedSaid: Boolean = false,
        ): ModelStatusSlot? =
            of(model, settled)?.let { ModelStatusSlot(it, visible = true) }
                ?: ModelStatusSlot(Finished, visible = !finishedSaid).takeIf { rawShownFirst }
    }
}

/** [status] laid out under the model, seen while [visible]. */
internal class ModelStatusSlot(val status: ModelStatus, val visible: Boolean) {
    /** The lines whose room [status] holds: a big scan's ([ModelStatus.RAW_LINES]), else none. */
    val reserve: List<ModelStatus> get() = if (status in ModelStatus.RAW_LINES) ModelStatus.RAW_LINES else emptyList()
}

/** [slot] as the model screen lays it out under the model: faded, and not heard, once it is not [ModelStatusSlot.visible]. */
@Composable
internal fun ModelStatusLine(slot: ModelStatusSlot, modifier: Modifier = Modifier) {
    // Faded, not removed, once said: the model is framed above this slot (as the hint).
    val statusAlpha by animateFloatAsState(if (slot.visible) 1f else 0f, motionFade(), label = "status")
    ModelStatusCard(
        text = stringResource(slot.status.text),
        reserve = slot.reserve.map { stringResource(it.text) },
        modifier = modifier
            .alpha(statusAlpha)
            .then(if (slot.visible) Modifier else Modifier.clearAndSetSemantics { }),
    )
}

/**
 * What the model screen is doing, or why it shows less than a surface: one line, the one
 * indicator, its ellipsis breathing while a step is in flight (`DESIGN.md` → `motion-narration`),
 * announced as it changes. Laid out as tall as the tallest of [reserve] — drawn, never seen nor
 * heard — so the card keeps its room whichever of them it ends on.
 */
@Composable
internal fun ModelStatusCard(text: String, modifier: Modifier = Modifier, reserve: List<String> = emptyList()) {
    val chrome = LocalStageChrome.current
    OverlayCard(testTag = RERUN_MODEL_CARD_TAG, modifier = modifier) {
        Box {
            // First in the box, so the line keeps its node as it changes, and is heard changing.
            NarrationText(
                text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = chrome.onCard,
                style = SceneViewTokens.Type.card,
            )
            // A narrated line in both its forms: as written, and drawn with its three dots.
            reserve.flatMap { line -> listOfNotNull(line, narrationBase(line)?.let { "$it..." }) }.forEach { line ->
                Text(line, style = SceneViewTokens.Type.card, modifier = Modifier.alpha(0f).clearAndSetSemantics { })
            }
        }
    }
}

/** The ground of a model (#4719): where it stands and which of its floor it saw. */
private class GroundBuild(val ground: ScanGround, val meshes: ScanGroundMeshes)

/** [model]'s ground, `null` when its vertices cannot be read or stand on nothing. Off the main thread. */
private fun groundOf(model: RerunScanModel): GroundBuild? = runCatching {
    val started = System.nanoTime()
    val mesh = RerunGlbMesh.read(model.glb) ?: return@runCatching null
    val ground = ScanGround.of(mesh.positions, mesh.normals, mesh.vertexCount) ?: return@runCatching null
    GroundBuild(ground, ScanGroundMeshes.of(ground)).also {
        Log.i(
            TAG,
            "ground: floor y ${"%.2f".format(ground.floorY)} m, ${ground.columns}x${ground.rows} cells of " +
                "${(ground.cellM * CM_PER_M).toInt()} cm, ${ground.seen} seen, ${ground.missing} missing, " +
                "${it.meshes.triangles} triangles in ${(System.nanoTime() - started) / NS_PER_MS} ms",
        )
    }
}.onFailure { Log.w(TAG, "ground failed", it) }.getOrNull()

/** The one-line gesture hint, a pill that leaves after a few seconds. */
@Composable
private fun ModelHint(text: String, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    Text(
        text = text,
        style = SceneViewTokens.Type.caption,
        color = chrome.onCard,
        modifier = modifier
            .padding(top = Space.sm)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(RERUN_MODEL_HINT_TAG),
    )
}

@Composable
private fun RenameDialog(title: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var draft by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_result_rename_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(NAME_MAX_LENGTH) },
                singleLine = true,
                label = { Text(stringResource(R.string.scan_result_rename_field)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(draft.trim()) }, enabled = draft.isNotBlank()) {
                Text(stringResource(R.string.scan_result_rename_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.scan_result_cancel)) } },
    )
}

@Composable
private fun rememberModelEnvironment(engine: Engine, ground: androidx.compose.ui.graphics.Color): Environment {
    val context = androidx.compose.ui.platform.LocalContext.current
    return rememberEnvironment(engine, key = ground) {
        val stage = colorOf(ground).toLinearSpace()
        val bundle = KTX1Loader.createIndirectLight(
            engine,
            context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
        )
        createEnvironment(
            engine = engine,
            indirectLight = bundle.indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
            skybox = Skybox.Builder().color(stage.x, stage.y, stage.z, 1f).build(engine),
            // The bundle's cubemap leaves with the environment, after the light (#4358).
            textures = listOfNotNull(bundle.cubemap),
        )
    }
}

internal const val RERUN_MODEL_CARD_TAG = "rerun_model_card"
internal const val RERUN_MODEL_NAME_TAG = "rerun_model_name"
internal const val RERUN_MODEL_HINT_TAG = "rerun_model_hint"
internal const val RERUN_MODEL_MORE_TAG = "rerun_model_more"
internal const val RERUN_MODEL_VEIL_TAG = "rerun_model_veil"

private const val CM_PER_M = 100f

private const val TAG = "RerunModel"

/** How much wider than an object's footprint its contact shadow spreads. */
private const val SHADOW_SPREAD = 1.3f

/** The contact shadow's height over the object's floor, against z-fighting with a scanned table. */
private const val SHADOW_LIFT_M = 0.003f
private const val LOG_EVERY_FRAMES = 30
private const val MB = 1024L * 1024L
private const val KB = 1024
private const val NS_PER_MS = 1_000_000L

/**
 * A depth-fused voxel meshes once two medium-confidence views (or one sure one) agree on it:
 * twice the marching cubes' default, so the voxels seen once at the edge of the view or through
 * a window leave no veil on the finished model.
 */
private const val FRAMES_MIN_WEIGHT = 1f
private const val FUSE_SHARE = 0.3f

/** Where meshing ends on the progress bar: finishing the model ([CleanModel]) takes the rest. */
private const val MESH_SHARE = 0.45f

/**
 * A raw surface this big is shown while it finishes: a Pixel 4a took 23.5 s to finish 192k
 * triangles, so from about this size the finish passes the 20 s a result may keep the user waiting.
 */
internal const val RAW_FIRST_TRIANGLES = 120_000
private const val ATLAS_JPEG_QUALITY = 85

/** The mesh's share of the progress bar; the points fallback, when needed, takes the rest. */
private const val MESH_PART = 0.9f

/** How long the gesture hint stays once the model is on screen. */
private const val HINT_MS = 4_000L
private const val NAME_MAX_LENGTH = 80
