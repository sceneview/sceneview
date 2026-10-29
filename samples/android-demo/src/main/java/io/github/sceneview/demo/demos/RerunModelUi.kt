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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded._360
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoSceneRestingCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demoContentPadding
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.BakePhoto
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthBackProjection
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.DollhouseFraming
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
import io.github.sceneview.demo.demos.internal.scanFloorArea
import io.github.sceneview.demo.demos.internal.ScanGround
import io.github.sceneview.demo.demos.internal.ScanGroundMeshes
import io.github.sceneview.demo.demos.internal.ScanPreset
import io.github.sceneview.demo.demos.internal.ScanResultCinematics
import io.github.sceneview.demo.demos.internal.groundSource
import io.github.sceneview.demo.demos.internal.model.AtlasEncoder
import io.github.sceneview.demo.demos.internal.model.CleanModel
import io.github.sceneview.demo.demos.internal.model.CleanModelResult
import io.github.sceneview.demo.demos.internal.model.ModelPhoto
import io.github.sceneview.demo.demos.internal.model.ModelPhotos
import io.github.sceneview.demo.demos.internal.model.RawSurface
import io.github.sceneview.demo.demos.internal.model.TexturedSyntheticRoom
import io.github.sceneview.demo.isDemoCompactHeight
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
import io.github.sceneview.math.colorOf
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
 * A scan's result: its model, full screen and still on the themed stage, opened at a three-quarter
 * angle that fits the geometry whole. Kept with the scan ([store], [id]) — reopened, it shows the
 * same model without building it again; never kept, it is built from [source] and saved.
 *
 * [onModel] hears the model once it is known (`null` when there is none), for the export sheet.
 * [opened] is the kept model when the caller has already read it, so it is not read twice.
 * [floorCovered] is the scan's floor covered in m², its replay's figure (#4734); `null` when its
 * recording cannot be read, and no legend is shown.
 * [onRescan] starts a re-scan of one of its parts to scan again (#4736); `null`: not offered.
 * With [asks], a model built here first asks how to finish it ([ReconstructionSheet]): as
 * scanned or filled, with what filling can make of this scan ([finishMethod]); the QA states,
 * whose hint stays, build it as scanned without asking.
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
    floorCovered: Float? = null,
    onRescan: ((RescanPick) -> Unit)? = null,
    asks: Boolean = !hintStays,
) {
    // The question of how to finish the model, while it waits for an answer.
    var question by remember(source, id) { mutableStateOf<FinishQuestion?>(null) }
    val state by produceState<ModelState>(ModelState.Loading, source, id) {
        val stored = opened ?: if (store != null && id != null) {
            withContext(Dispatchers.IO) { runCatching { store.model(id) }.getOrNull() }
        } else {
            null
        }
        // A raw surface kept for running out of memory, with attempts left, is finished again from
        // [source], shown meanwhile as a big scan's raw surface is shown while it finishes.
        val retry = stored?.takeIf { it.retriesFinish && source != null }
        val kept = stored?.takeIf { retry == null }
        var shownFirst: RerunScanModel? = retry
        if (retry != null) value = ModelState.Ready(retry, final = false)
        val built = kept ?: source?.let { from ->
            // A raw surface finished again is not asked again: the method it was built by is not
            // kept, and it finishes as scanned.
            val method = if (retry == null) finishMethod(from, asks) { question = it } else ReconstructionMethod.Fused
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
                runCatching { buildScanModel(from, raw, retry?.glb, method, report) }
                    .onFailure { if (it is CancellationException) throw it }
                    .onFailure { Log.w(TAG, "model build failed", it) }
                    .getOrNull()
            }
        }
        val model = retry?.finishedAgain(built) ?: built
        // A model finished now is kept with its scan, so the next open reads it instead of meshing
        // again; so is the raw surface a finish ran out of memory on, shown at once on the next
        // opens and finished again behind it while attempts are left, their count kept with it
        // ([RerunScanModel.retriesFinish]). Another fallback is not: the next open tries again —
        // from the TSDF while its slot holds it, from the scan's surfels once a big one was let go
        // at meshing to give the finish its heap (mesh()). Only a model kept here lets go a TSDF
        // still held.
        val keepAs = id?.takeIf { kept == null && store != null && model?.keptWithScan == true }
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
    // Created before the SceneView, so released after its node (Compose forgets in reverse).
    // On the main thread, as every Filament call (CLAUDE.md).
    // Left unshown, not thrown, when the heap has no room for the renderer's copy.
    val instance = remember(model) {
        model?.glbBuffer()?.let { buffer -> runCatching { modelLoader.createModelInstance(buffer) }.getOrNull() }
    }
    DisposableEffect(instance) { onDispose { instance?.let { modelLoader.destroyModel(it.model) } } }
    // The ground it stands on and the floor it missed (#4719), read off the model's own vertices
    // once the model is settled, never beside a big scan's finish ([groundSource]). Until the next
    // settles, the last one stays.
    val groundModel = groundSource(model, settled)
    val ground by produceState<GroundBuild?>(null, groundModel) {
        groundModel?.let { built -> value = withContext(Dispatchers.Default) { groundOf(built) } }
    }
    // The parts worth scanning again (#4736), once the floor is read: framed on the model, listed on a tap.
    val rescan by produceState<RescanBuild?>(null, source, ground) {
        value = ground?.let { g -> withContext(Dispatchers.Default) { rescanOf(source, g.ground) } }
    }
    var rescanOpen by remember(id ?: source) { mutableStateOf(false) }
    // No drift: the camera holds still once the reveal has landed, until the user moves it. Kept
    // across a source that arrives for a scan already shown, whose finish is run again.
    val orbit = remember(id ?: source) { ArDebugOrbitCamera(drift = false) }
    // The reveal, the 3D ↔ Plan crane and the tour (#4631), every one cut short by a touch.
    val cinematics = remember(orbit) { ScanResultCinematics(orbit) }
    SideEffect {
        framedBy?.bounds?.let { b -> cinematics.extent = maxOf(b[3] - b[0], b[5] - b[2]).coerceAtLeast(MIN_EXTENT_M) }
    }
    // Read live by the framing below, so a toggle and the frame that flies to it agree.
    var plan by remember(framedBy) { mutableStateOf(false) }
    var touring by remember(framedBy) { mutableStateOf(false) }
    var shown by remember(framedBy) { mutableStateOf(false) }
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

    DemoScaffold(
        title = stringResource(R.string.demo_ar_rerun_title),
        onBack = onBack,
        controls = {
            Text(stringResource(R.string.scan_result_about), style = SceneViewTokens.Type.body)
            if (rename != null) {
                TextButton(onClick = rename) { Text(stringResource(R.string.scan_result_rename)) }
            }
        },
        themedStage = true,
        topOverlay = { ScanNameCard(title = title, saved = id != null, onRename = rename) },
        bottomOverlay = {
            ModelStatus.slot(model, settled, rawShownFirst, finishedSaid)?.let { slot -> ModelStatusLine(slot) }
            // Laid out from the start and only faded: the model is framed above this slot, and a
            // hint that came and went would move the model when it goes.
            val hintShown = shown && hintVisible
            val hintAlpha by animateFloatAsState(if (hintShown) 1f else 0f, label = "hint")
            // Laid out from the start too, for the same reason; usable once the model is seen.
            val tourReady = shown && instance != null
            // Side by side, so the legend adds no height over the model.
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val floor = ground?.ground?.takeIf { tourReady && it.seen + it.missing > 0 }
                val locale = LocalLocale.current.platformLocale
                // Without a readable recording there is no figure, and nothing to key.
                val covered = floorCovered?.takeIf { it > 0f }?.let { scanFloorArea(it, locale) }
                if (floor != null && covered != null) FloorLegend(covered)
                ScanTourPill(
                    touring = touring,
                    enabled = tourReady,
                    onClick = { cinematics.request(ScanResultCinematics.Request.ToggleTour) },
                    modifier = Modifier
                        .alpha(if (tourReady) 1f else 0f)
                        .then(if (tourReady) Modifier else Modifier.clearAndSetSemantics { }),
                )
                rescan?.takeIf { tourReady }?.let { RescanZonesPill(it.zones.size, onClick = { rescanOpen = true }) }
            }
            ModelHint(
                text = stringResource(R.string.scan_result_hint),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .alpha(hintAlpha)
                    .then(if (hintShown) Modifier else Modifier.clearAndSetSemantics { }),
            )
        },
        dock = listOfNotNull(
            // 3D and Plan are one camera move apart (Matterport's dollhouse ↔ floor plan); the
            // selected one recenters, as the replay's 3D view does.
            DockItem(
                icon = Icons.Rounded._3dRotation,
                label = stringResource(R.string.scan_result_3d_label),
                caption = stringResource(R.string.scan_result_3d),
                onClick = {
                    plan = false
                    cinematics.request(ScanResultCinematics.Request.Recenter)
                },
                enabled = instance != null,
                selected = !plan,
            ),
            DockItem(
                icon = Icons.Rounded.Map,
                label = stringResource(R.string.scan_result_plan_label),
                caption = stringResource(R.string.scan_result_plan),
                onClick = {
                    plan = true
                    cinematics.request(ScanResultCinematics.Request.Recenter)
                },
                enabled = instance != null,
                selected = plan,
            ),
            onReplay?.let {
                DockItem(
                    icon = Icons.Rounded.Replay,
                    label = stringResource(R.string.scan_result_replay_label),
                    caption = stringResource(R.string.scan_result_replay),
                    onClick = it,
                )
            },
            onViewInAr?.let {
                DockItem(
                    icon = Icons.Rounded.ViewInAr,
                    label = stringResource(R.string.scan_place),
                    onClick = it,
                )
            },
        ),
        dockAccent = DockItem(
            icon = Icons.Rounded.IosShare,
            label = stringResource(R.string.scan_result_export_label),
            caption = stringResource(R.string.scan_result_export),
            onClick = onExport,
            enabled = settled,
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
        val framing = remember(framedBy, orbit) {
            framedBy?.let { DollhouseFraming(it.bounds, RerunResultView.ELEVATION, orbit.verticalFovDegrees) }
        }
        // The floor plan: the same room, the same turn, straight down from the map's elevation.
        val planFraming = remember(framedBy, orbit) {
            framedBy?.let { DollhouseFraming(it.bounds, ArDebugFraming.MAP_ELEVATION, orbit.verticalFovDegrees) }
        }
        val opening = remember(framedBy, orbit) { DollhouseOpening() }
        BoxWithConstraints(Modifier.fillMaxSize().background(chrome.ground)) {
            // What the chrome leaves of the view at rest — below the name, above the dock, between
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
            // Most of the narrower side, between the chrome when the model fits there at that size.
            val area = RerunResultView.area(aspect, insets)
            val home = rememberUpdatedState {
                (if (plan) planFraming else framing)?.home(RerunResultView.AZIMUTH, aspect, area)
            }
            SideEffect { home.value()?.let { opening.aim(orbit, it) } }
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
                onFrame = {
                    val framed = home.value() ?: return@SceneView
                    // The reveal starts on the frame the model is drawn on its framing, and the
                    // view is let to be seen on its first frame, not the still one before it.
                    if (cinematics.frameDrawn(opening, framed, ready = instance != null)) shown = true
                    if (touring != cinematics.touring) touring = cinematics.touring
                },
            ) {
                // The scan in its own world space, metres, Y up: framed by its bounds, not moved.
                instance?.let { ModelNode(modelInstance = it, autoAnimate = false) }
                ground?.let {
                    // The replay's grid round the slab (QA 2026-10-22), its lines sized for the
                    // camera the result opens on, bucketed so a reframe rarely rebuilds them.
                    val style = ArDebugStyle.forOrbit(
                        home.value()?.distance ?: 1f,
                        orbit.verticalFovDegrees,
                        constraints.maxHeight,
                    )
                    ScanGridLayer(it.ground, style, engine, materialLoader)
                    ScanGroundLayer(it.meshes, engine, materialLoader, ScanGroundColors.current())
                }
                rescan?.let { RescanZoneLayer(it.mesh, engine, materialLoader) }
            }
            // The ground until the model is drawn on its framing: no pose but that one is seen.
            // Then it fades as the camera reveal flies in (`motion-entrance`), never a cut from the
            // save's "Fusing" to the model (QA 2026-10-22). Opacity only: kept under reduced motion.
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
    rescan?.takeIf { rescanOpen }?.let { RescanZonesDialog(it.zones, onDismiss = { rescanOpen = false }, onRescan = it.picker(onRescan)) }
    question?.let { asked ->
        ReconstructionSheet(asked.fill, ReconstructionMethod.Fused, onFinish = asked::finish, onDismiss = asked::dismiss)
    }
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

/**
 * How much of the floor the scan covers, in the live map's own words. The floor it did not see is
 * not hatched on the result any more: its holes read as the slab, and the parts worth scanning
 * again have their own pill (#4736).
 */
@Composable
private fun FloorLegend(area: String) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val text = stringResource(R.string.scan_coverage_floor, area)
    Text(
        text = text,
        style = SceneViewTokens.Type.caption,
        color = chrome.onCard,
        modifier = Modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .clearAndSetSemantics { contentDescription = text }
            .testTag(RERUN_MODEL_FLOOR_TAG),
    )
}

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

/**
 * Tour, and Stop while it plays (#4631): a pill in the stage's chrome over the model, beside
 * which the hint is read — the one camera move the user asks for, never one that starts alone.
 */
@Composable
private fun ScanTourPill(touring: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val chrome = LocalStageChrome.current
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val label = stringResource(R.string.scan_result_tour_label)
    Row(
        modifier = modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .clip(shape)
            .background(chrome.card, shape)
            .overMediaEdge(shape, chrome.edgeRing, chrome.edgeHalo)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(RERUN_MODEL_TOUR_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = if (touring) Icons.Rounded.Stop else Icons.Rounded._360,
            contentDescription = null,
            tint = chrome.onCard,
            modifier = Modifier.size(SceneViewTokens.Layout.dockIconSize),
        )
        Text(
            text = stringResource(if (touring) R.string.scan_result_tour_stop else R.string.scan_result_tour),
            style = SceneViewTokens.Type.caption,
            color = chrome.onCard,
        )
    }
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
internal const val RERUN_MODEL_TOUR_TAG = "rerun_model_tour"
internal const val RERUN_MODEL_VEIL_TAG = "rerun_model_veil"
internal const val RERUN_MODEL_FLOOR_TAG = "rerun_model_floor"

private const val CM_PER_M = 100f

/** The smallest scan the tour pushes into, metres: an object on a table. */
private const val MIN_EXTENT_M = 0.2f

private const val TAG = "RerunModel"
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
