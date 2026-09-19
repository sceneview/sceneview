package io.github.sceneview.node

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.drainFramePipeline
import io.github.sceneview.geometries.Plane
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.safeDestroyStream
import io.github.sceneview.safeDestroyTexture

/**
 * A [Node] that renders video from an Android [MediaPlayer] onto a flat plane in 3D space.
 *
 * Creates a Filament [Stream] backed by a [SurfaceTexture], routes the [MediaPlayer]'s output
 * to that surface, and maps it to a [PlaneNode] whose aspect ratio matches the video dimensions.
 *
 * Optionally supports chroma-key (green-screen) compositing via [chromaKeyColor].
 *
 * ```kotlin
 * SceneView {
 *     val player = remember {
 *         MediaPlayer().apply {
 *             setDataSource(context, videoUri)
 *             isLooping = true
 *             prepare()
 *             start()
 *         }
 *     }
 *     DisposableEffect(Unit) { onDispose { player.release() } }
 *
 *     VideoNode(
 *         materialLoader = materialLoader,
 *         player = player,
 *         position = Position(z = -2f)
 *     )
 * }
 * ```
 *
 * The plane is auto-sized from the video's aspect ratio (longer edge = 1.0 world unit) unless
 * you provide an explicit [size]. When the video dimensions become known via
 * [MediaPlayer.OnVideoSizeChangedListener] the geometry is updated automatically.
 *
 * @param materialLoader   [MaterialLoader] used to create the video plane material.
 * @param player           [MediaPlayer] whose frames are rendered on this node. The player should
 *                         be prepared before passing it so that its dimensions are available for
 *                         initial sizing. If not yet prepared, the plane starts at 16:9 and is
 *                         resized once [MediaPlayer.OnVideoSizeChangedListener] fires.
 * @param chromaKeyColor   Optional ARGB chroma-key colour for green-screen compositing.
 * @param size             Fixed plane size in world units. `null` (default) = auto-size from the
 *                         video's aspect ratio.
 * @param center           Geometry centre offset relative to the node's origin.
 * @param normal           Plane normal direction.
 * @param builderApply     Extra [RenderableManager.Builder] configuration.
 */
open class VideoNode(
    val materialLoader: MaterialLoader,
    player: MediaPlayer,
    chromaKeyColor: Int? = null,
    /**
     * Fixed plane size in world units.
     * `null` (default) = auto-size from video dimensions (longer edge = 1.0 unit).
     */
    val size: Size? = null,
    center: Position = Plane.DEFAULT_CENTER,
    normal: Direction = Plane.DEFAULT_NORMAL,
    builderApply: RenderableManager.Builder.() -> Unit = {}
) : PlaneNode(
    engine = materialLoader.engine,
    size = size ?: videoNaturalSize(player),
    center = center,
    normal = normal,
    builderApply = builderApply
) {
    private val surfaceTexture: SurfaceTexture = SurfaceTexture(0).also {
        it.detachFromGLContext()
    }

    private val surface: Surface = Surface(surfaceTexture)

    /** The Filament [Stream] backed by the internal [SurfaceTexture]. */
    val stream: Stream = Stream.Builder()
        .stream(surfaceTexture)
        .build(materialLoader.engine)

    /** The Filament external [Texture] sampling frames from [stream]. */
    val texture: Texture = Texture.Builder()
        .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
        .format(Texture.InternalFormat.RGB8)
        .build(materialLoader.engine)
        .apply { setExternalStream(materialLoader.engine, stream) }

    override var materialInstance: MaterialInstance =
        materialLoader.createVideoInstance(texture, chromaKeyColor)
            .also { setMaterialInstanceAt(0, it) }
        set(value) {
            if (value === field) return
            val old = field
            field = value
            setMaterialInstanceAt(0, value)
            // The external [texture] stays bound to [old] until the next frame: destroying [old]
            // immediately would free a MaterialInstance still GPU-referenced by [texture], the
            // exact `Invalid texture still bound to MaterialInstance` SIGABRT #1497 fixed for
            // [destroy]. Drain the frame pipeline first so [old] is fully reclaimed before it is
            // freed. Runs on the main thread — all Filament JNI calls do.
            materialLoader.engine.drainFramePipeline()
            materialLoader.destroyMaterialInstance(old)  // also removes from MaterialLoader tracking
        }

    /**
     * The [MediaPlayer] currently feeding this node.
     *
     * Assigning a new player automatically routes its output to the node's internal surface and
     * registers the video-size listener. The old player is NOT stopped or released.
     */
    var player: MediaPlayer = player
        set(value) {
            field.setOnVideoSizeChangedListener(null)
            field = value
            value.setSurface(surface)
            if (size == null) {
                val w = value.videoWidth
                val h = value.videoHeight
                if (w > 0 && h > 0) {
                    updateGeometry(size = normalize(Size(w.toFloat(), h.toFloat())))
                }
            }
            value.setOnVideoSizeChangedListener(onVideoSizeChanged)
        }

    /**
     * A playing video pushes new frames into the node's `SurfaceTexture` from outside the library,
     * so nothing else would invalidate — a render-on-demand scene would show a frozen first frame.
     * `isPlaying` throws on a released player; a released player is not playing.
     */
    override val isFrameActive: Boolean
        get() = runCatching { player.isPlaying }.getOrDefault(false) || super.isFrameActive

    private val onVideoSizeChanged = MediaPlayer.OnVideoSizeChangedListener { _, width, height ->
        if (size == null && width > 0 && height > 0) {
            updateGeometry(size = normalize(Size(width.toFloat(), height.toFloat())))
        }
    }

    init {
        // Route the player's output to our surface and register the size listener.
        this.player = player
    }

    /**
     * Tears the node down in an order that is safe for Filament's external-stream pipeline.
     *
     * The [texture] is an external [Texture] bound to [materialInstance]; destroying it while
     * the MaterialInstance is still GPU-bound triggers a native SIGABRT
     * (`Invalid texture still bound to MaterialInstance`) — MaterialInstance reclamation is
     * coupled to the render loop, not to this call (see the sibling [ImageNode] for the same
     * hazard). Unlike [ImageNode], a [VideoNode] also owns native [Stream]/[SurfaceTexture]
     * resources that must be released, so the texture cannot simply be leaked.
     *
     * The sequence below therefore:
     * 1. destroys the [MaterialInstance] first (un-references the texture),
     * 2. calls [Engine.drainFramePipeline] so any in-flight frame still holding the
     *    MaterialInstance is flushed and the MI is actually reclaimed,
     * 3. only then destroys the external [Texture] and [Stream].
     *
     * Must run on the main thread — all Filament JNI calls do.
     */
    override fun destroy() {
        val mi = materialInstance
        super.destroy()
        player.setOnVideoSizeChangedListener(null)
        surface.release()
        materialLoader.destroyMaterialInstance(mi)
        // Flush the render pipeline so the just-destroyed MaterialInstance is reclaimed before
        // its external texture is freed — otherwise Filament SIGABRTs ("Invalid texture still
        // bound to MaterialInstance"). See KDoc above.
        materialLoader.engine.drainFramePipeline()
        materialLoader.engine.safeDestroyTexture(texture)
        materialLoader.engine.safeDestroyStream(stream)
        surfaceTexture.release()
    }
}

/**
 * Returns a normalised [Size] derived from [player]'s video dimensions, or a default 16:9 ratio
 * when the dimensions are not yet known (player not prepared, or no video track).
 */
private fun videoNaturalSize(player: MediaPlayer): Size {
    val w = player.videoWidth
    val h = player.videoHeight
    return if (w > 0 && h > 0) {
        normalize(Size(w.toFloat(), h.toFloat()))
    } else {
        // 16:9 default — will be corrected by OnVideoSizeChangedListener once the player is ready
        Size(1.0f, 9.0f / 16.0f)
    }
}
