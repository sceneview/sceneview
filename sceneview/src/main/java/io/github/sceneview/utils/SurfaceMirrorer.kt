package io.github.sceneview.utils

import android.view.Surface
import com.google.android.filament.Engine
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.View
import com.google.android.filament.Viewport

/**
 * Mirrors the rendered Filament frame to additional [Surface]s — the primitive behind clean
 * **in-app video recording** of a `SceneView` / `ARSceneView`.
 *
 * Every rendered frame is copied (GPU-side, letterboxed to preserve aspect ratio) onto each
 * mirrored surface. Point it at a [android.media.MediaRecorder]'s input surface and you get an
 * MP4 of exactly what the scene renders — camera feed and virtual content composited, **without
 * MediaProjection**: no system consent dialog, no `mediaProjection` foreground service, and no
 * overlay UI captured in the frame (only the 3D/AR scene is mirrored, never your Compose UI).
 *
 * ### Record the scene to MP4 (5 lines of recording logic)
 * ```kotlin
 * val surfaceMirrorer = rememberSurfaceMirrorer()
 * SceneView(surfaceMirrorer = surfaceMirrorer, ...) // or ARSceneView(surfaceMirrorer = ...)
 *
 * // Start recording:
 * val recorder = MediaRecorder(context).apply {
 *     setVideoSource(MediaRecorder.VideoSource.SURFACE)
 *     setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
 *     setVideoEncoder(MediaRecorder.VideoEncoder.H264)
 *     setVideoSize(1280, 720)
 *     setOutputFile(outputFile.absolutePath)
 *     prepare()
 * }
 * surfaceMirrorer.startMirroring(recorder.surface, width = 1280, height = 720)
 * recorder.start()
 *
 * // Stop recording:
 * surfaceMirrorer.stopMirroring(recorder.surface)
 * recorder.stop()
 * recorder.release()
 * ```
 *
 * Multiple surfaces can be mirrored simultaneously — each [startMirroring] call adds one target.
 *
 * ### Threading
 * [startMirroring] performs no Filament call and is safe from any thread — the swap chain is
 * created lazily on the render (main) thread at the next frame. [stopMirroring] destroys the
 * swap chain immediately and **must be called from the main thread** (the standard Filament JNI
 * rule). Both are idempotent: starting an already-mirrored surface or stopping a non-mirrored
 * one is a no-op.
 *
 * @see io.github.sceneview.rememberSurfaceMirrorer
 * @see io.github.sceneview.SceneRenderer.surfaceMirrorer
 */
class SurfaceMirrorer {

    private data class SurfaceMirror(
        val surface: Surface,
        var swapChain: SwapChain?,
        /** Destination rectangle on [surface]; `null` = match the source view size at copy time. */
        val viewport: Viewport?
    )

    private val surfaceMirrors = mutableListOf<SurfaceMirror>()

    /** Engine the swap chains were created on — bound at the first mirrored frame. */
    private var engine: Engine? = null

    /**
     * The [Surface]s currently being mirrored to (snapshot copy).
     */
    val mirroredSurfaces: List<Surface>
        get() = synchronized(surfaceMirrors) { surfaceMirrors.map { it.surface } }

    /**
     * Whether [surface] is currently being mirrored to.
     */
    fun isMirroring(surface: Surface): Boolean =
        synchronized(surfaceMirrors) { surfaceMirrors.any { it.surface == surface } }

    /**
     * Starts mirroring the rendered scene to [surface].
     *
     * Use [android.media.MediaRecorder.getSurface], [android.media.MediaCodec.createInputSurface]
     * or [android.media.MediaCodec.createPersistentInputSurface] to obtain a recording input
     * surface. Mirroring incurs a per-frame GPU copy — only mirror while capturing, and call
     * [stopMirroring] when done.
     *
     * The frame is letterboxed into the destination rectangle so the scene's aspect ratio is
     * preserved. No-op if [surface] is already being mirrored (double-start safe). Safe to call
     * from any thread, and before the scene's first frame — copying begins with the next
     * rendered frame.
     *
     * @param surface the [Surface] onto which the rendered scene is mirrored.
     * @param left    the left edge of the destination rectangle on [surface]. Default `0`.
     * @param bottom  the bottom edge of the destination rectangle on [surface]. Default `0`.
     * @param width   the width of the destination rectangle on [surface]. Pass the surface's
     *                width (e.g. the `MediaRecorder` video width). Default `null` = the source
     *                view width — correct when [surface] has the same size as the scene view.
     * @param height  the height of the destination rectangle on [surface]. Pass the surface's
     *                height (e.g. the `MediaRecorder` video height). Default `null` = the source
     *                view height.
     */
    @JvmOverloads
    fun startMirroring(
        surface: Surface,
        left: Int = 0,
        bottom: Int = 0,
        width: Int? = null,
        height: Int? = null
    ) {
        synchronized(surfaceMirrors) {
            if (surfaceMirrors.any { it.surface == surface }) return
            surfaceMirrors.add(
                SurfaceMirror(
                    surface = surface,
                    // Created lazily on the render thread at the next frame — keeps this call
                    // JNI-free and thread-safe.
                    swapChain = null,
                    viewport = if (width != null && height != null) {
                        Viewport(left, bottom, width, height)
                    } else {
                        null
                    }
                )
            )
        }
    }

    /**
     * Stops mirroring to [surface].
     *
     * Call when capture is complete — otherwise the per-frame GPU copy cost remains. The caller
     * stays responsible for releasing the [Surface] itself (e.g. `MediaRecorder.stop()`).
     *
     * Idempotent: no-op if [surface] is not currently mirrored. Must be called from the main
     * thread (destroys the mirror's Filament swap chain).
     *
     * Mirrors are identified by [Surface] **instance** — pass the same object you passed to
     * [startMirroring]. In particular, capture `MediaRecorder.getSurface()` once and reuse it:
     * each call may return a new Java object wrapping the same native surface.
     */
    fun stopMirroring(surface: Surface) {
        synchronized(surfaceMirrors) {
            surfaceMirrors.filter { it.surface == surface }.onEach { mirror ->
                mirror.swapChain?.let { swapChain ->
                    engine?.let { runCatching { it.destroySwapChain(swapChain) } }
                }
                mirror.swapChain = null
            }.also { surfaceMirrors.removeAll(it) }
        }
    }

    /**
     * Copies the frame just rendered by [renderer] to every mirrored surface.
     *
     * Called by [io.github.sceneview.SceneRenderer] on the render thread, between
     * `Renderer.render(view)` and `Renderer.endFrame()`.
     */
    internal fun onFrame(engine: Engine, renderer: Renderer, view: View) {
        this.engine = engine
        synchronized(surfaceMirrors) {
            surfaceMirrors.forEach { mirror ->
                // Lazy swap-chain creation — startMirroring is JNI-free.
                val swapChain = mirror.swapChain
                    ?: runCatching { engine.createSwapChain(mirror.surface) }.getOrNull()
                        ?.also { mirror.swapChain = it }
                    ?: return@forEach
                val destViewport = mirror.viewport ?: view.viewport
                renderer.copyFrame(
                    swapChain,
                    getLetterboxViewport(view.viewport, destViewport),
                    view.viewport,
                    Renderer.MIRROR_FRAME_FLAG_COMMIT
                            or Renderer.MIRROR_FRAME_FLAG_SET_PRESENTATION_TIME
                            or Renderer.MIRROR_FRAME_FLAG_CLEAR
                )
            }
        }
    }

    /**
     * Destroys every mirror's swap chain and clears the mirror list.
     *
     * Called by [io.github.sceneview.SceneRenderer.destroy] when the scene is disposed — a
     * mirrored recording ends with the view that feeds it.
     */
    internal fun destroy() {
        synchronized(surfaceMirrors) {
            surfaceMirrors.forEach { mirror ->
                mirror.swapChain?.let { swapChain ->
                    engine?.let { runCatching { it.destroySwapChain(swapChain) } }
                }
                mirror.swapChain = null
            }
            surfaceMirrors.clear()
        }
        this.engine = null
    }

    private fun getLetterboxViewport(srcViewport: Viewport, destViewport: Viewport): Viewport {
        val scale =
            if (destViewport.width.toFloat() / destViewport.height.toFloat() > srcViewport.width.toFloat() / srcViewport.height.toFloat()) {
                destViewport.height.toFloat() / srcViewport.height.toFloat()
            } else {
                destViewport.width / srcViewport.width.toFloat()
            }
        val width = (srcViewport.width * scale).toInt()
        val height = (srcViewport.height * scale).toInt()
        return Viewport(
            destViewport.left + (destViewport.width - width) / 2,
            destViewport.bottom + (destViewport.height - height) / 2,
            width,
            height
        )
    }
}
