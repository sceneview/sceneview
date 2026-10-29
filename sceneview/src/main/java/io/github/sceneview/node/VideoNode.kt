package io.github.sceneview.node

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.drainFramePipeline
import io.github.sceneview.geometries.Plane
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.matchToneMapInverse
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.safeDestroyStream
import io.github.sceneview.safeDestroyTexture
import io.github.sceneview.whenBackendIdle

/**
 * A [Node] that renders video from an Android [MediaPlayer] onto a flat plane in 3D space.
 *
 * Creates a Filament [Stream] backed by a [SurfaceTexture], routes the [MediaPlayer]'s output
 * to that surface, and maps it to a [PlaneNode] whose aspect ratio matches the video dimensions.
 *
 * Optionally supports chroma-key (green-screen) compositing via [chromaKeyColor].
 *
 * In a `SceneView { }` block, declare it with the `VideoNode` composables: `VideoNode(videoPath = …)`
 * prepares and owns the player, `VideoNode(player = …)` takes one from `rememberMediaPlayer`.
 *
 * ```kotlin
 * SceneView {
 *     VideoNode(videoPath = "videos/promo.mp4", position = Position(z = -2f))
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
            value.matchToneMapInverse(collisionSystem?.view)
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
     * Bridges "a video frame landed on our `SurfaceTexture`" to the render gate. Delivered on the
     * main thread (see the [Handler] below) because the gate it ends up marking is the render
     * loop's own state.
     */
    private val frameSignal = SurfaceFrameSignal(::requestRender)

    /**
     * A playing video pushes new frames into the node's `SurfaceTexture` from outside the library,
     * so nothing else would invalidate — a render-on-demand scene would show a frozen first frame.
     * `isPlaying` throws on a released player; a released player is not playing.
     *
     * `isPlaying` is not the whole answer, though, and that was a real "frozen picture" case: a
     * **seek** or a **frame-step** on a paused player produces exactly one new frame and leaves
     * `isPlaying` false throughout, so a parked scene kept showing the frame from before the seek.
     * [SurfaceFrameSignal] answers for those — every frame the surface receives, playing or not.
     *
     * A player starts, seeks and stops without telling this node, so this is registered as a
     * provider and the scene asks on every tick (#3724).
     */
    private fun isVideoFrameActive(): Boolean = frameSignal.isActive(
        forcedActive = runCatching { player.isPlaying }.getOrDefault(false)
    )

    init {
        addFrameActivityProvider(::isVideoFrameActive)
    }

    private val onVideoSizeChanged = MediaPlayer.OnVideoSizeChangedListener { _, width, height ->
        if (size == null && width > 0 && height > 0) {
            updateGeometry(size = normalize(Size(width.toFloat(), height.toFloat())))
        }
    }

    init {
        // Route the player's output to our surface and register the size listener.
        this.player = player
        // Every frame the producer queues — a playing video, but also the single frame a seek or a
        // frame-step produces on a paused player — wakes the render loop. Dispatched on the main
        // looper so `requestRender()` reaches the gate from the thread that owns it.
        surfaceTexture.setOnFrameAvailableListener(
            { frameSignal.onFrameAvailable() },
            Handler(Looper.getMainLooper())
        )
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
     * 2. waits for the backend to execute that destroy, so no in-flight frame still holds the
     *    MaterialInstance — without blocking the main thread: polled once per frame through
     *    `Engine.whenBackendIdle`, where an unbounded [Engine.drainFramePipeline] used to park it on
     *    the whole backend backlog (field ANRs in `Fence.wait` at teardown),
     * 3. only then destroys the external [Texture] and [Stream], and releases the [SurfaceTexture].
     *
     * On a thread without a [Looper] there is nothing to poll from: step 2 is the blocking drain.
     *
     * Must run on the main thread — all Filament JNI calls do.
     */
    override fun destroy() {
        // Once only (#4259): a second call would free handles, material instances and an
        // entity id that may already belong to another node.
        if (isDestroyed) return
        val mi = materialInstance
        super.destroy()
        player.setOnVideoSizeChangedListener(null)
        surfaceTexture.setOnFrameAvailableListener(null)
        surface.release()
        materialLoader.destroyMaterialInstance(mi)
        // The just-destroyed MaterialInstance must be reclaimed by the backend before its
        // external texture is freed — otherwise Filament SIGABRTs ("Invalid texture still bound
        // to MaterialInstance"). See KDoc above.
        val engine = materialLoader.engine
        val releaseExternal = {
            engine.safeDestroyTexture(texture)
            engine.safeDestroyStream(stream)
            surfaceTexture.release()
        }
        if (Looper.myLooper() == null) {
            engine.drainFramePipeline()
            releaseExternal()
            return
        }
        // Engine already gone: it freed the texture and stream with it.
        engine.whenBackendIdle(onEngineGone = { surfaceTexture.release() }) { releaseExternal() }
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
