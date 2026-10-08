package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.annotation.ColorInt
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChainFlags
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.ToneMapper
import com.google.android.filament.Viewport
import io.github.sceneview.EngineDestroyQueue
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPlane
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.MeasureDrawing
import io.github.sceneview.demo.demos.internal.PlaneLayering
import io.github.sceneview.demo.demos.internal.PointColorAtlas
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.SteadyRoomMeasure
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.demos.internal.Vec3
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setTexture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/*
 * The Rerun demo's bundled replay, on Android: loading it from the assets (IO), and drawing its
 * textured half — the planes' photos, the camera's photos in their frustums, the coloured point
 * cloud and the models' contact shadows — next to the flat-colour layers ArDebugView already
 * draws. The data and the geometry are pure Kotlin in demos/internal (RerunReplay.kt).
 */

/**
 * Everything the bundled replay needs, decoded off the main thread: the session as a trace, its
 * manifest, the planes' photos, and every camera frame as a small thumbnail — for the filmstrip
 * and the frustums. The full-size frame is decoded on demand ([decodeFrame]).
 */
internal class RerunReplayMedia(
    val trace: ArDebugTrace,
    val manifest: ReplayManifest,
    val planeBitmaps: Map<Int, Bitmap>,
    val thumbnails: Map<String, Bitmap>,
    /** The media archive's bytes, which [decodeFrame] cuts full-size frames from. */
    private val archive: ByteArray,
    /**
     * A room scan still recording (Record mode): the trace and the thumbnails keep growing, so
     * the view frames what is there now and keeps a photo slot for every keyframe it may reach.
     */
    val growing: Boolean = false,
    /** A `.svscan` v2's dense cloud, ready to draw; `null` for a v1 or sparse-tier scan. */
    val dense: ReplayDenseLayer? = null,
    /**
     * A scan still recording with raw depth: its dense map's latest snapshot, `null` until the
     * first ([ScanCapture.liveDense]). Read on the main thread each frame; a new instance is a
     * new snapshot to upload.
     */
    val liveDense: (() -> ReplayDenseLayer?)? = null,
) {
    /** The thumbnail of the camera image in force at [time], `null` before the first one. */
    fun thumbnailAt(time: Float): Bitmap? {
        val index = trace.imageIndexAt(time)
        return if (index < 0) null else thumbnails[trace.imagePath(index)]
    }

    /**
     * The points drawn at [time]: the dense cloud's surfels found by then, which stand in for
     * the sparse map, or the feature points without a dense cloud ([ArDebugTrace.pointCountAt]).
     */
    fun pointCountAt(time: Float): Int = trace.pointCountAt(time, dense?.cloud?.count ?: 0)

    /** Photo [path] from the archive, `null` when the manifest does not index it. */
    fun decode(path: String, options: BitmapFactory.Options? = null): Bitmap? {
        val span = manifest.media[path] ?: return null
        if (span.offset + span.length > archive.size) return null
        return BitmapFactory.decodeByteArray(archive, span.offset, span.length, options)
    }

    /** Photo [path]'s encoded bytes, as the archive holds them; `null` when it does not. */
    fun bytesOf(path: String): ByteArray? {
        val span = manifest.media[path] ?: return null
        if (span.offset + span.length > archive.size) return null
        return archive.copyOfRange(span.offset, span.offset + span.length)
    }
}

/**
 * A `.svscan` v2's dense cloud as the replay draws it — the design's zero-shader parity fallback:
 * [mesh] one square surfel per point in the plane of its normal, [atlas] its colours
 * ([DenseSurfels.ATLAS_SIZE]² RGBA), both built off the main thread.
 */
internal class ReplayDenseLayer(val cloud: DenseCloud, val voxelM: Float, val mesh: DebugMesh, val atlas: ByteArray) {
    companion object {
        /** The dense cloud [manifest] indexes in [archive], built for drawing; `null` without one. */
        fun of(manifest: ReplayManifest, archive: ByteArray): ReplayDenseLayer? {
            val dense = manifest.dense ?: return null
            val span = manifest.media[dense.path] ?: return null
            if (span.offset + span.length > archive.size) return null
            val cloud = SvpcCodec.decode(archive, span.offset, span.length)?.takeIf { it.count > 0 } ?: return null
            return of(cloud, dense.voxelM)
        }

        /** [cloud] of [voxelM] surfels, meshed and coloured for drawing. Off the main thread. */
        fun of(cloud: DenseCloud, voxelM: Float) = ReplayDenseLayer(
            cloud = cloud,
            voxelM = voxelM,
            mesh = DenseSurfels.mesh(cloud, voxelM),
            atlas = DenseSurfels.atlas(cloud, fallback = DENSE_FALLBACK_COLOR),
        )

        /** A surfel the camera never coloured: the sparse points' own neutral. */
        const val DENSE_FALLBACK_COLOR = 0xFFB8C2D6.toInt()
    }
}

/** Loads the bundled replay from the assets. Call off the main thread's back: it is IO. */
internal suspend fun loadRerunReplay(context: Context): RerunReplayMedia = withContext(Dispatchers.IO) {
    val assets = context.assets
    val manifest = assets.open(RerunReplayAssets.MANIFEST).bufferedReader().use { it.readText() }
        .let(ReplayManifest::parse)
        ?: error("Unreadable replay manifest")
    val events = assets.open(RerunReplayAssets.LOG).bufferedReader().useLines { parseArDebugLog(it) }
    val archive = assets.open(RerunReplayAssets.MEDIA).use { it.readBytes() }
    openReplay(manifest, events, archive)
}

/**
 * Opens a scan saved on the phone ([RerunSessionStore]) — through the very code the bundled
 * replay opens by, so the two look the same. `null` when its manifest is unreadable.
 */
internal suspend fun loadRerunSession(capture: RerunCapturePack): RerunReplayMedia? = withContext(Dispatchers.Default) {
    val manifest = ReplayManifest.parse(String(capture.manifest)) ?: return@withContext null
    openReplay(manifest, parseArDebugLog(String(capture.log).lineSequence()), capture.media)
}

/** A replay from its three parts: the photos and plane textures decoded side by side. */
private suspend fun openReplay(
    manifest: ReplayManifest,
    events: List<ArDebugEvent>,
    archive: ByteArray,
): RerunReplayMedia = coroutineScope {
    val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
    val shell = RerunReplayMedia(trace, manifest, emptyMap(), emptyMap(), archive)
    // Decoded side by side: the cover stays up until they are, so their time is the wait.
    val planes = manifest.textures.map { texture ->
        async(Dispatchers.Default) { shell.decode(texture.path)?.let { texture.planeId to it } }
    }
    val dense = async(Dispatchers.Default) { runCatching { ReplayDenseLayer.of(manifest, archive) }.getOrNull() }
    val thumbnails = (0 until trace.imageCount).map { i ->
        async(Dispatchers.Default) {
            val path = trace.imagePath(i)
            val options = BitmapFactory.Options().apply { inSampleSize = THUMBNAIL_SAMPLE_SIZE }
            shell.decode(path, options)?.let { path to it }
        }
    }
    RerunReplayMedia(
        trace = trace,
        manifest = manifest,
        planeBitmaps = planes.awaitAll().filterNotNull().toMap(),
        thumbnails = thumbnails.awaitAll().filterNotNull().toMap(),
        archive = archive,
        dense = dense.await(),
    )
}

/** A camera frame at full size, for the camera view. Off the main thread. */
internal suspend fun decodeFrame(media: RerunReplayMedia, path: String): Bitmap? = withContext(Dispatchers.Default) {
    runCatching { media.decode(path) }.getOrNull()
}

/**
 * Draws the replay's materials once, off screen, while the landing is read: the unlit layers,
 * opaque and translucent, the photos, the stage's skybox, and the view's bloom, 4× MSAA and
 * colour grading. A GL driver without parallel shader compilation — the emulator's, most
 * low-end phones' — compiles a program on its first draw, so the first replay opened in a
 * process waited ~2 s behind its loading cover, where the second one took 0.2 s (#4080).
 * Filament keeps the programs once compiled; everything drawn here is released right after, in
 * command order, so the main thread never waits on the driver. Main thread.
 */
internal fun warmUpReplay(engine: Engine, materialLoader: MaterialLoader) {
    val texture = Texture.Builder()
        .width(1)
        .height(1)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .build(engine)
    val instances = listOf(
        materialLoader.createUnlitColorInstance(Color.WHITE),
        materialLoader.createUnlitColorInstance(Color.TRANSPARENT),
        materialLoader.createImageInstance(texture),
    )
    // Each draws one degenerate triangle: enough to bind, and so compile, its program.
    val nodes = instances.mapIndexed { i, instance -> DebugLayerNode(engine, instance, i, textured = true) }
    val skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
    val colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    val scene = engine.createScene().also { scene ->
        scene.skybox = skybox
        nodes.forEach { scene.addEntity(it.entity) }
    }
    val cameraEntity = EntityManager.get().create()
    val view = engine.createView().apply {
        this.scene = scene
        camera = engine.createCamera(cameraEntity)
        viewport = Viewport(0, 0, WARM_UP_SIZE, WARM_UP_SIZE)
        configureForDebug(colorGrading)
    }
    val swapChain = engine.createSwapChain(WARM_UP_SIZE, WARM_UP_SIZE, SwapChainFlags.CONFIG_DEFAULT)
    val renderer = engine.createRenderer()
    if (renderer.beginFrame(swapChain, 0L)) {
        renderer.render(view)
        renderer.endFrame()
    }
    engine.destroyRenderer(renderer)
    engine.destroySwapChain(swapChain)
    engine.destroyView(view)
    engine.destroyCameraComponent(cameraEntity)
    EntityManager.get().destroy(cameraEntity)
    nodes.forEach { scene.removeEntity(it.entity) }
    engine.destroyScene(scene)
    nodes.forEach { it.destroy() }
    engine.destroySkybox(skybox)
    engine.destroyColorGrading(colorGrading)
    instances.forEach(materialLoader::destroyMaterialInstance)
    EngineDestroyQueue.of(engine).enqueueTexture(texture)
}

/** The off-screen warm-up's size: past bloom's seven halvings, nothing more. */
private const val WARM_UP_SIZE = 128

/** 240×320 frames at a half: 120×160, ~77 KB each — the 184 of them fit in 14 MB. */
private const val THUMBNAIL_SAMPLE_SIZE = 2

/**
 * The replay's textured layers, kept in step with an [ArDebugFrame] like `ArDebugLayers`: each
 * rebuilt only when its inputs change. Owns its textures and material instances; [destroy] on
 * the main thread after the nodes left the scene.
 */
internal class ReplayLayers(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    private val media: RerunReplayMedia,
    /** The dimensions' ink, ARGB: the floor outline's colour, so they read as part of the plan. */
    @ColorInt private var measureInk: Int = FALLBACK_POINT_COLOR,
    /** The halo around the dimensions' figures, ARGB: the stage's ground, so they stay legible. */
    @ColorInt private var measureHalo: Int = android.graphics.Color.TRANSPARENT,
    /**
     * The dense cloud drawn here: the scan's own, or a cut of it — the dollhouse's, without its
     * ceiling ([io.github.sceneview.demo.demos.internal.RoomDollhouse.cropDense]) — drawn whole.
     */
    private val dense: ReplayDenseLayer? = media.dense,
) {
    private val textures = ArrayList<Texture>()
    private val materials = ArrayList<MaterialInstance>()
    // Trilinear and anisotropic: the floor is mostly seen at a grazing angle, where trilinear
    // alone drops to a mip level blurred along the view (#4080).
    private val clamp = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    ).apply { anisotropy = ANISOTROPY }
    private val nearest = TextureSampler(
        TextureSampler.MinFilter.NEAREST,
        TextureSampler.MagFilter.NEAREST,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    private fun texture(bitmap: Bitmap): Texture =
        ImageTexture.Builder().bitmap(bitmap).build(engine).also(textures::add)

    /** The image material, writing depth when [solid], so photos and points occlude each other. */
    private fun material(texture: Texture, sampler: TextureSampler = clamp, solid: Boolean = true) =
        materialLoader.createImageInstance(texture, sampler).also {
            it.setDepthWrite(solid)
            materials += it
        }

    private val planeTextures: Map<Int, Texture> = media.planeBitmaps.mapValues { texture(it.value) }
    private val planeNodes: Map<Int, DebugLayerNode> = planeTextures.mapValues { (_, texture) ->
        DebugLayerNode(engine, material(texture), PHOTO_PRIORITY, textured = true)
    }

    private val atlas: Texture = Texture.Builder()
        .width(PointColorAtlas.SIZE)
        .height(PointColorAtlas.SIZE)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(Texture.InternalFormat.SRGB8_A8)
        .build(engine)
        .also(textures::add)
    private val pointsNode = DebugLayerNode(engine, material(atlas, nearest), POINTS_PRIORITY, textured = true)

    private val shadowNode = DebugLayerNode(
        engine, material(texture(shadowBitmap()), solid = false), SHADOW_PRIORITY, textured = true,
    )

    /**
     * A v2 scan's dense cloud: surfels coloured from their own texel of a 1024² atlas, uploaded
     * once here and revealed as the timeline reaches them ([syncDense]). Writes depth, so the
     * surfels hide what is behind them, like a surface.
     */
    private val denseAtlas: Texture? = if (dense == null && media.liveDense == null) null else {
        Texture.Builder()
            .width(DenseSurfels.ATLAS_SIZE)
            .height(DenseSurfels.ATLAS_SIZE)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
            .also(textures::add)
    }
    private val denseNode: DebugLayerNode? = denseAtlas?.let { atlas ->
        DebugLayerNode(engine, material(atlas, nearest), POINTS_PRIORITY, textured = true)
            .also { node -> dense?.let { uploadDense(atlas, node, it) } }
    }

    /** The live snapshot [denseNode] holds; a newer one from [RerunReplayMedia.liveDense] replaces it. */
    private var liveDenseShown: ReplayDenseLayer? = null

    /** The photos in the frustums, at the archive's full size; bounded, see [evictFrames]. */
    private val frameTextures = HashMap<String, Texture>()

    /** One photo quad per keyframe the whole session reaches, plus the live camera's. */
    private val photoSlots: List<PhotoSlot> = run {
        // Keyframes only accumulate along the path, so the last frame has the most — plus a spare.
        // A scan still recording can reach the cap.
        val trace = media.trace
        val keyframes = if (media.growing) ArDebugTrace.MAX_KEYFRAMES else trace.frameAt(trace.duration).keyframes.size
        val count = keyframes + 2
        val placeholder = planeTextures.values.firstOrNull() ?: atlas
        List(count) {
            val material = material(placeholder)
            PhotoSlot(DebugLayerNode(engine, material, PHOTO_FRUSTUM_PRIORITY, textured = true), material)
        }
    }

    /** The room's dimensions: lines and figures in one mesh over one small atlas ([MeasureDrawing]). */
    private var measureTexture: Texture = atlas
    private val measureMaterial = material(atlas, solid = false)
    private val measureNode = DebugLayerNode(engine, measureMaterial, MEASURE_PRIORITY, textured = true)
    private var measurePlanes: List<DebugPlane>? = null
    private var foundMeasure: RoomMeasure? = null

    /** A scan still recording steadies its figure; a recording played back is exact at every frame. */
    private val steadyMeasure = if (media.growing) SteadyRoomMeasure() else null

    /** The room at the last [sync]: the one the dimensions draw, and the one the HUD names. */
    var measure: RoomMeasure? = null
        private set
    private var measureLabels: Pair<String, String>? = null
    private var measureLabelWidths = FloatArray(2)

    val nodes: List<DebugLayerNode> =
        planeNodes.values + pointsNode + listOfNotNull(denseNode) + shadowNode + photoSlots.map { it.node } +
            measureNode

    private val mesh = DebugMesh()
    private val keys = HashMap<Any, Any?>()

    /** The recording camera's lens, which the frustums take so their photos fit them. */
    val lens get() = media.manifest.lens

    /** Whether plane [id] is drawn as a photo here, so the flat layers draw its outline only. */
    fun isTextured(id: Int): Boolean = id in planeNodes

    /**
     * Brings the layers to [frame]. [eye] is where the view looks from, which picks the two sides
     * of the room its dimensions are drawn on; `null` draws them on the first two.
     */
    fun sync(
        frame: ArDebugFrame,
        pointStyle: ArDebugStyle,
        floorY: Float,
        show: ReplayVisibility,
        eye: Vec3? = null,
    ) {
        syncPlanes(frame, floorY, show.planes)
        syncMeasure(frame, pointStyle, floorY, show.measure && show.planes, eye)
        // A dense cloud stands in for the sparse one, under the same toggle — a scan still
        // recording shows its feature points until the first dense snapshot.
        syncDense(frame, show.points)
        val denseDrawn = denseNode != null && (media.liveDense == null || liveDenseShown != null)
        syncPoints(frame, pointStyle, show.points && !denseDrawn)
        syncShadows(frame, show.anchors)
        syncPhotos(frame, show.trail)
    }

    /**
     * Each photo at its own depth ([PlaneLayering]): a floor patch laid flat under the grid, a
     * table top at its own height, overlapping patches a step apart — they z-fought, and the
     * photos shimmered as the camera orbited.
     */
    private fun syncPlanes(frame: ArDebugFrame, floorY: Float, shown: Boolean) {
        val layering = if (shown) PlaneLayering.of(frame, floorY) else null
        for ((id, node) in planeNodes) {
            val plane = frame.planes.firstOrNull { it.id == id }?.takeIf { shown }
            node.isVisible = plane != null
            if (plane == null || layering == null) continue
            val placed = layering.fill(plane)
            if (changed(node, listOf(System.identityHashCode(plane), placed.contentHashCode()))) {
                mesh.clear()
                ReplayGeometry.addTexturedPlane(mesh, plane.polygon, media.manifest.textureFor(id)!!, placed)
                node.upload(mesh)
            }
        }
    }

    /**
     * The room's width and depth drawn on the floor as a plan draws them, on the two sides facing
     * [eye], sized in screen pixels like the other lines. The room is the one [frame] has found so
     * far, so the dimensions grow as the scan plays.
     */
    private fun syncMeasure(frame: ArDebugFrame, style: ArDebugStyle, floorY: Float, shown: Boolean, eye: Vec3?) {
        if (frame.planes !== measurePlanes) {
            measurePlanes = frame.planes
            foundMeasure = RoomMeasure.of(frame.planes, floorY)
        }
        val room = if (steadyMeasure != null) steadyMeasure.update(foundMeasure, frame.time) else foundMeasure
        measure = room
        measureNode.isVisible = shown && room != null
        if (!measureNode.isVisible || room == null) return
        val labels = RoomMeasure.metres(room.width) to RoomMeasure.metres(room.depth)
        if (labels != measureLabels) {
            measureLabels = labels
            drawMeasureLabels(labels)
        }
        val sides = if (eye == null) intArrayOf(0, 1) else MeasureDrawing.sidesFacing(room, eye.x, eye.z)
        // Sized in pixels, and rebuilt only past a 5 % zoom step, not on every frame of a pinch.
        val step = kotlin.math.round(kotlin.math.ln(style.metresPerPixel.coerceAtLeast(1e-6f)) / MEASURE_ZOOM_STEP)
        if (!changed(measureNode, listOf(room.summary, room.yaw, floorY, sides.toList(), step, labels))) return
        val mpp = kotlin.math.exp(step * MEASURE_ZOOM_STEP)
        val textHeight = (MEASURE_TEXT_PX * mpp).coerceIn(MEASURE_TEXT_MIN_M, MEASURE_TEXT_MAX_M)
        val offset = (MEASURE_OFFSET_PX * mpp).coerceIn(MEASURE_OFFSET_MIN_M, MEASURE_OFFSET_MAX_M)
        mesh.clear()
        for (side in sides) {
            val row = side % 2
            val textWidth = textHeight * measureLabelWidths[row] / MeasureDrawing.ROW_HEIGHT
            MeasureDrawing.addDimension(
                mesh, room, side, floorY + MEASURE_LIFT_M, offset, style.outlineHalfWidth, textHeight, textWidth,
            )
        }
        measureNode.upload(mesh)
    }

    /**
     * The dimensions take another [ink] and [halo] — the theme changed. Their atlas is redrawn at
     * the next [sync], by the swap that already follows a change of figures: the layers, their
     * nodes and every other texture stay as they are (#4330).
     */
    fun setMeasureColors(@ColorInt ink: Int, @ColorInt halo: Int) {
        if (ink == measureInk && halo == measureHalo) return
        measureInk = ink
        measureHalo = halo
        measureLabels = null
    }

    /**
     * The dimensions' atlas: [labels]' width on row 0 and depth on row 1, in the ink with a halo
     * of the ground, and the solid strip the lines sample.
     */
    private fun drawMeasureLabels(labels: Pair<String, String>) {
        val width = MeasureDrawing.ATLAS_WIDTH
        val height = MeasureDrawing.ATLAS_HEIGHT
        val row = MeasureDrawing.ROW_HEIGHT
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = measureInk
            textSize = MEASURE_FONT_PX
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val halo = Paint(ink).apply {
            color = measureHalo
            style = Paint.Style.STROKE
            strokeWidth = MEASURE_HALO_PX
            strokeJoin = Paint.Join.ROUND
        }
        listOf(labels.first, labels.second).forEachIndexed { index, text ->
            val baseline = index * row + (row - ink.ascent() - ink.descent()) / 2f
            canvas.drawText(text, MEASURE_PAD_PX, baseline, halo)
            canvas.drawText(text, MEASURE_PAD_PX, baseline, ink)
            measureLabelWidths[index] = (ink.measureText(text) + 2 * MEASURE_PAD_PX).coerceAtMost(width.toFloat())
        }
        val solid = MeasureDrawing.ATLAS_HEIGHT - MeasureDrawing.SOLID_HEIGHT
        canvas.drawRect(0f, solid.toFloat(), width.toFloat(), height.toFloat(), Paint().apply { color = measureInk })
        val texture = texture(bitmap)
        measureMaterial.setTexture(texture, clamp)
        if (measureTexture !== atlas) {
            textures -= measureTexture
            retire(measureTexture)
        }
        measureTexture = texture
    }

    private fun syncPoints(frame: ArDebugFrame, style: ArDebugStyle, shown: Boolean) {
        pointsNode.isVisible = shown
        if (!shown || !changed(pointsNode, listOf(frame.mapPointCount, style))) return
        val colors = frame.mapPointColors ?: IntArray(frame.mapPointCount)
        val pixels = PointColorAtlas.pixels(colors, fallback = FALLBACK_POINT_COLOR)
        atlas.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(ByteBuffer.wrap(pixels), Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        mesh.clear()
        ReplayGeometry.addColoredPoints(mesh, frame.mapPoints, style.mapPointRadius * POINT_SCALE)
        pointsNode.upload(mesh)
    }

    /**
     * The dense cloud as it stood at [frame]'s time: its surfels keep the order the scan found
     * them in, so the trace's `depth_stats` count at that time is how many to draw. A cloud with
     * no stats on its timeline is drawn whole.
     */
    private fun syncDense(frame: ArDebugFrame, shown: Boolean) {
        val node = denseNode ?: return
        val atlas = denseAtlas ?: return
        val live = media.liveDense
        if (live != null) {
            // Recording: the latest snapshot, drawn whole. One upload a snapshot, about a second.
            val snapshot = live()
            if (snapshot != null && snapshot !== liveDenseShown) {
                liveDenseShown = snapshot
                uploadDense(atlas, node, snapshot)
            }
            node.isVisible = shown && liveDenseShown != null
            return
        }
        val count = if (dense === media.dense) media.pointCountAt(frame.time) else dense?.cloud?.count ?: 0
        node.isVisible = shown && count > 0
        if (node.isVisible) node.showIndices(minOf(count, DenseSurfels.MAX_SURFELS) * INDICES_PER_SURFEL)
    }

    private fun uploadDense(atlas: Texture, node: DebugLayerNode, dense: ReplayDenseLayer) {
        atlas.setImage(
            engine, 0,
            Texture.PixelBufferDescriptor(ByteBuffer.wrap(dense.atlas), Texture.Format.RGBA, Texture.Type.UBYTE),
        )
        node.upload(dense.mesh)
    }

    private fun syncShadows(frame: ArDebugFrame, shown: Boolean) {
        shadowNode.isVisible = shown && frame.anchors.isNotEmpty()
        if (!shadowNode.isVisible || !changed(shadowNode, frame.anchors)) return
        mesh.clear()
        for (anchor in frame.anchors) {
            val p = anchor.pose
            ReplayGeometry.addShadow(mesh, p.x, p.y + SHADOW_LIFT_M, p.z, SHADOW_RADIUS_M)
        }
        shadowNode.upload(mesh)
    }

    private fun syncPhotos(frame: ArDebugFrame, shown: Boolean) {
        val lens = media.manifest.lens
        val shots = ArrayList<Triple<DebugPose, String?, Float>>()
        frame.keyframes.forEachIndexed { i, pose ->
            shots += Triple(pose, frame.keyframeImages.getOrNull(i), ReplayGeometry.KEYFRAME_DEPTH)
        }
        frame.camera?.let { shots += Triple(it, frame.image, ReplayGeometry.FRUSTUM_DEPTH) }
        photoSlots.forEachIndexed { i, slot ->
            val shot = shots.getOrNull(i)
            val path = shot?.second
            val texture = path?.let(::frameTexture)
            slot.node.isVisible = shown && texture != null
            if (!slot.node.isVisible || shot == null || texture == null) return@forEachIndexed
            if (slot.texture !== texture) {
                slot.material.setTexture(texture, clamp)
                slot.texture = texture
            }
            if (changed(slot, listOf(shot.first, shot.third))) {
                mesh.clear()
                ReplayGeometry.addImageQuad(mesh, shot.first, shot.third, lens)
                slot.node.upload(mesh)
            }
        }
        evictFrames()
    }

    /**
     * Photo [path] as a texture: the full-size frame (240 × 320 in the bundled replay), not the
     * filmstrip's half-size thumbnail — a frustum's photo is drawn at up to a third of the screen
     * (#4080). Decoded here, on the main thread, once per path the playhead reaches.
     */
    private fun frameTexture(path: String): Texture? = frameTextures[path]
        ?: (media.decode(path) ?: media.thumbnails[path])
            ?.let { ImageTexture.Builder().bitmap(it).build(engine) }
            ?.also { frameTextures[path] = it }

    /**
     * Drops the frame textures no slot shows once there are more than [FRAME_TEXTURE_CACHE], so a
     * whole session played through does not keep every frame on the GPU.
     */
    private fun evictFrames() {
        if (frameTextures.size <= FRAME_TEXTURE_CACHE) return
        val bound = photoSlots.mapNotNullTo(HashSet()) { it.texture }
        val iterator = frameTextures.values.iterator()
        while (iterator.hasNext() && frameTextures.size > FRAME_TEXTURE_CACHE) {
            val texture = iterator.next()
            if (texture in bound) continue
            retire(texture)
            iterator.remove()
        }
    }

    private fun changed(owner: Any, key: Any?): Boolean {
        if (keys.containsKey(owner) && keys[owner] == key) return false
        keys[owner] = key
        return true
    }

    /** Material instances and textures, once the nodes are gone. */
    fun destroy() {
        materials.forEach { materialLoader.destroyMaterialInstance(it) }
        textures.forEach(::retire)
        frameTextures.values.forEach(::retire)
        materials.clear()
        textures.clear()
        frameTextures.clear()
    }

    /**
     * Frees [texture] a few rendered frames from now, through the library's [EngineDestroyQueue],
     * as `ImageNode` frees its own: these layers destroy no texture in the call that rebinds or
     * destroys the material instance that read it.
     */
    private fun retire(texture: Texture) = EngineDestroyQueue.of(engine).enqueueTexture(texture)

    private class PhotoSlot(val node: DebugLayerNode, val material: MaterialInstance) {
        var texture: Texture? = null
    }

    private companion object {
        /** Under the grid (priority 1), so the grid's lines stay on the photo floor. */
        const val PHOTO_PRIORITY = 0
        const val SHADOW_PRIORITY = 2
        const val POINTS_PRIORITY = 3
        const val PHOTO_FRUSTUM_PRIORITY = 5

        /** Over the grid and the outlines, under the frustums' photos. */
        const val MEASURE_PRIORITY = 4

        /** A millimetre over the grid: the dimensions are drawn on the floor, not in it. */
        const val MEASURE_LIFT_M = 0.003f
        /**
         * The label's box, in pixels: its figures' capitals are ~40 % of it, and the floor seen
         * at a slant shortens it further.
         */
        const val MEASURE_TEXT_PX = 72f
        const val MEASURE_TEXT_MIN_M = 0.04f
        const val MEASURE_TEXT_MAX_M = 0.9f
        const val MEASURE_OFFSET_PX = 28f
        const val MEASURE_OFFSET_MIN_M = 0.06f
        const val MEASURE_OFFSET_MAX_M = 0.9f

        /** The dimensions are rebuilt at every 5 % of zoom. */
        const val MEASURE_ZOOM_STEP = 0.05f

        /** The atlas's figures: 76 px bold in a 128 px row, a 14 px halo, 10 px of margin. */
        const val MEASURE_FONT_PX = 76f
        const val MEASURE_HALO_PX = 14f
        const val MEASURE_PAD_PX = 10f

        /** Two triangles per surfel ([DenseSurfels.mesh]). */
        const val INDICES_PER_SURFEL = 6

        /** Anisotropic filtering on the photos: 8 taps, a mid-range GPU's cheap maximum. */
        const val ANISOTROPY = 8f

        /** The keyframes' photos ([ArDebugTrace.MAX_KEYFRAMES]), the live one, and slack. */
        const val FRAME_TEXTURE_CACHE = ArDebugTrace.MAX_KEYFRAMES + 16

        /** Coloured points are read as the room's texture, not as markers: a touch larger. */
        const val POINT_SCALE = 1.25f
        const val FALLBACK_POINT_COLOR = 0xFFB8C2D6.toInt()

        const val SHADOW_RADIUS_M = 0.22f
        const val SHADOW_LIFT_M = 0.003f
        const val SHADOW_SIZE_PX = 64
        const val SHADOW_ALPHA = 150

        /** A soft black disc fading to nothing: a contact shadow without a shadow pass. */
        fun shadowBitmap(): Bitmap {
            val size = SHADOW_SIZE_PX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    size / 2f, size / 2f, size / 2f,
                    intArrayOf(android.graphics.Color.argb(SHADOW_ALPHA, 0, 0, 0), android.graphics.Color.TRANSPARENT),
                    floatArrayOf(0.15f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            Canvas(bitmap).drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            return bitmap
        }
    }
}

/** Which of the replay's textured layers the legend has on. */
internal data class ReplayVisibility(
    val planes: Boolean,
    val points: Boolean,
    val anchors: Boolean,
    val trail: Boolean,
    /** The room's floor-plan dimensions ([RoomMeasure]), drawn with the planes. */
    val measure: Boolean = false,
)
