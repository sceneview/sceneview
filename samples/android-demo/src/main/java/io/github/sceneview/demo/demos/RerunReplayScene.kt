package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import io.github.sceneview.demo.demos.internal.ArDebugFrame
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DebugPlaneKind
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.PointColorAtlas
import io.github.sceneview.demo.demos.internal.ReplayGeometry
import io.github.sceneview.demo.demos.internal.ReplayManifest
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setTexture
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.texture.ImageTexture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
) {
    /** The thumbnail of the camera image in force at [time], `null` before the first one. */
    fun thumbnailAt(time: Float): Bitmap? {
        val index = trace.imageIndexAt(time)
        return if (index < 0) null else thumbnails[trace.imagePath(index)]
    }

    /** Photo [path] from the archive, `null` when the manifest does not index it. */
    fun decode(path: String, options: BitmapFactory.Options? = null): Bitmap? {
        val span = manifest.media[path] ?: return null
        if (span.offset + span.length > archive.size) return null
        return BitmapFactory.decodeByteArray(archive, span.offset, span.length, options)
    }
}

/** Loads the bundled replay from the assets. Call off the main thread's back: it is IO. */
internal suspend fun loadRerunReplay(context: Context): RerunReplayMedia = withContext(Dispatchers.IO) {
    val assets = context.assets
    val manifest = assets.open(RerunReplayAssets.MANIFEST).bufferedReader().use { it.readText() }
        .let(ReplayManifest::parse)
        ?: error("Unreadable replay manifest")
    val events = assets.open(RerunReplayAssets.LOG).bufferedReader().useLines { parseArDebugLog(it) }
    val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
    val archive = assets.open(RerunReplayAssets.MEDIA).use { it.readBytes() }
    val shell = RerunReplayMedia(trace, manifest, emptyMap(), emptyMap(), archive)
    // Decoded side by side: the cover stays up until they are, so their time is the wait.
    val planes = manifest.textures.map { texture ->
        async(Dispatchers.Default) { shell.decode(texture.path)?.let { texture.planeId to it } }
    }
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
    )
}

/** A camera frame at full size, for the camera view. Off the main thread. */
internal suspend fun decodeFrame(media: RerunReplayMedia, path: String): Bitmap? = withContext(Dispatchers.Default) {
    runCatching { media.decode(path) }.getOrNull()
}

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
) {
    private val textures = ArrayList<Texture>()
    private val materials = ArrayList<MaterialInstance>()
    private val clamp = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )
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

    private val frameTextures = HashMap<String, Texture>()

    /** One photo quad per keyframe the whole session reaches, plus the live camera's. */
    private val photoSlots: List<PhotoSlot> = run {
        // Keyframes only accumulate along the path, so the last frame has the most — plus a spare.
        val count = media.trace.frameAt(media.trace.duration).keyframes.size + 2
        val placeholder = planeTextures.values.firstOrNull() ?: atlas
        List(count) {
            val material = material(placeholder)
            PhotoSlot(DebugLayerNode(engine, material, PHOTO_FRUSTUM_PRIORITY, textured = true), material)
        }
    }

    val nodes: List<DebugLayerNode> = planeNodes.values + pointsNode + shadowNode + photoSlots.map { it.node }

    private val mesh = DebugMesh()
    private val keys = HashMap<Any, Any?>()

    /** The recording camera's lens, which the frustums take so their photos fit them. */
    val lens get() = media.manifest.lens

    /** Whether plane [id] is drawn as a photo here, so the flat layers draw its outline only. */
    fun isTextured(id: Int): Boolean = id in planeNodes

    fun sync(frame: ArDebugFrame, pointStyle: ArDebugStyle, floorY: Float, show: ReplayVisibility) {
        syncPlanes(frame, floorY, show.planes)
        syncPoints(frame, pointStyle, show.points)
        syncShadows(frame, show.anchors)
        syncPhotos(frame, show.trail)
    }

    private fun syncPlanes(frame: ArDebugFrame, floorY: Float, shown: Boolean) {
        for ((id, node) in planeNodes) {
            val plane = frame.planes.firstOrNull { it.id == id }?.takeIf { shown }
            node.isVisible = plane != null
            if (plane == null || !changed(node, listOf(System.identityHashCode(plane), floorY))) continue
            mesh.clear()
            val flatten = if (plane.kind == DebugPlaneKind.Floor) floorY - ReplayGeometry.FLOOR_UNDER_GRID_M else null
            ReplayGeometry.addTexturedPlane(mesh, plane.polygon, media.manifest.textureFor(id)!!, flatten)
            node.upload(mesh)
        }
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
    }

    private fun frameTexture(path: String): Texture? = frameTextures[path]
        ?: media.thumbnails[path]?.let { texture(it) }?.also { frameTextures[path] = it }

    private fun changed(owner: Any, key: Any?): Boolean {
        if (keys.containsKey(owner) && keys[owner] == key) return false
        keys[owner] = key
        return true
    }

    /** Material instances and textures, once the nodes are gone. */
    fun destroy() {
        materials.forEach { materialLoader.destroyMaterialInstance(it) }
        textures.forEach { engine.safeDestroyTexture(it) }
        materials.clear()
        textures.clear()
    }

    private class PhotoSlot(val node: DebugLayerNode, val material: MaterialInstance) {
        var texture: Texture? = null
    }

    private companion object {
        /** Under the grid (priority 1), so the grid's lines stay on the photo floor. */
        const val PHOTO_PRIORITY = 0
        const val SHADOW_PRIORITY = 2
        const val POINTS_PRIORITY = 3
        const val PHOTO_FRUSTUM_PRIORITY = 5

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
)
