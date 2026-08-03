package io.github.erkko68.filament

/**
 * Renderer represents an operating system window and manages frame rendering and pacing.
 *
 * Typically, applications create a Renderer per window. The Renderer generates drawing commands
 * for the render thread and manages frame latency to keep it low.
 *
 * A Renderer generates drawing commands from a View, which itself contains a Scene description.
 *
 * **Typical render loop:**
 * ```
 * while (!quit) {
 *     if (renderer.beginFrame(swapChain)) {
 *         renderer.render(view)
 *         renderer.endFrame()
 *     }
 * }
 * ```
 *
 * **Frame pacing:** beginFrame() manages frame pacing and returns false if the GPU is
 * falling behind (to skip frames and reduce latency). When false is returned, either
 * skip the frame or proceed anyway; if proceeding, you must still call endFrame().
 *
 * @see Engine
 * @see View
 */
expect class Renderer {
    /**
     * Display refresh-rate information for frame pacing and dynamic resolution scaling.
     *
     * This is used to achieve correct frame pacing and dynamic resolution scaling.
     */
    class DisplayInfo() {
        /** Refresh rate of the display in Hz. Set to 0 for offscreen rendering or to disable frame pacing. Default: 60. */
        var refreshRate: Float
    }

    /**
     * Frame rate control and dynamic resolution scaling options.
     *
     * Controls the desired frame rate and how quickly the system reacts to GPU load changes.
     *
     * interval: Desired frame interval in multiples of the refresh period (1 / DisplayInfo.refreshRate).
     *           Set to 1 to render at the display refresh rate.
     *
     * The parameters below are relevant when some Views are using dynamic resolution scaling:
     *
     * headRoomRatio: Additional headroom for the GPU as a ratio of the target frame time.
     *                Useful for taking into account constant costs like post-processing or
     *                GPU drivers on different platforms.
     * scaleRate: Rate at which the GPU load is adjusted to reach the target frame rate.
     *            This value can be computed as 1 / N, where N is the number of frames
     *            needed to reach 64% of the target scale factor. Higher values make the
     *            dynamic resolution react faster.
     * history: History size for filtering GPU load. Higher values tend to filter more.
     *          Clamped to 31.
     */
    class FrameRateOptions() {
        /** Additional headroom for the GPU as a fraction of target frame time. */
        var headRoomRatio: Float
        /** Rate at which GPU load is adjusted; computed as 1/N frames to reach 64% target. */
        var scaleRate: Float
        /** History size for load filtering (clamped to 31). */
        var history: Int
        /** Desired frame interval (1 = render every vsync). */
        var interval: Float
    }

    /**
     * ClearOptions control how the SwapChain is cleared or discarded at the beginning of a frame.
     *
     * The RenderTarget is cleared using the clearColor. The RenderTarget is cleared using this color,
     * which won't be tone-mapped since tone-mapping is part of View rendering (this is not).
     */
    class ClearOptions() {
        /** RGBA clear color. Values are stored as doubles. The backend converts them as-is based on format. */
        var clearColor: DoubleArray
        /** Whether the SwapChain should be cleared using clearColor. Default: false. */
        var clear: Boolean
        /** Whether the SwapChain content should be discarded. Default: true. Set false to preserve existing content. */
        var discard: Boolean
    }

    companion object {
        /** Indicates dstSwapChain should be committed after copyFrame. */
        val MIRROR_FRAME_FLAG_COMMIT: Int
        /** Indicates presentation time should be set on dstSwapChain in copyFrame. */
        val MIRROR_FRAME_FLAG_SET_PRESENTATION_TIME: Int
        /** Indicates dstSwapChain should be cleared to black before copyFrame. */
        val MIRROR_FRAME_FLAG_CLEAR: Int
    }

    /** Get the Engine that created this Renderer. */
    val engine: Engine

    /** Get/set display information for frame pacing and dynamic resolution. */
    var displayInfo: DisplayInfo

    /** Get/set frame rate control and dynamic resolution options. */
    var frameRateOptions: FrameRateOptions

    /** Get/set clear behavior for the SwapChain. */
    var clearOptions: ClearOptions

    /**
     * Set the time at which the frame must be presented.
     *
     * This is set as the duration in nanoseconds since epoch of std::chrono::steady_clock.
     * Must be called between beginFrame() and endFrame().
     *
     * @param monotonicClockNanos Time in nanoseconds.
     */
    fun setPresentationTime(monotonicClockNanos: Long)

    /**
     * Set the real desired presentation time targeted for this frame.
     *
     * Unlike setPresentationTime(), which configures hardware headroom, this is the exact target
     * presentation time and is used for frame history reporting.
     * Must be called before endFrame().
     *
     * @param monotonicClockNanos Desired presentation timestamp in steady_clock nanoseconds.
     */
    fun setDesiredPresentationTime(monotonicClockNanos: Long)

    /**
     * Set the deadline by which CPU and GPU rendering must complete for the buffer to meet its
     * target display latching window.
     * Must be called before endFrame().
     *
     * @param monotonicClockNanos Deadline timestamp in steady_clock nanoseconds.
     */
    fun setRenderingDeadline(monotonicClockNanos: Long)

    /**
     * Set the VSYNC time expressed as the duration in nanoseconds since epoch of std::chrono::steady_clock.
     *
     * If called, passing 0 to frameTimeNanos in beginFrame() will use this time instead.
     *
     * @param steadyClockTimeNano Duration in nanoseconds since epoch.
     */
    fun setVsyncTime(steadyClockTimeNano: Long)

    /**
     * Skip the current frame for frame pacing.
     *
     * Call this when momentarily skipping frames, for instance if the content of the scene doesn't change.
     *
     * @param vsyncSteadyClockTimeNano VSYNC time in steady_clock nanoseconds.
     */
    fun skipFrame(vsyncSteadyClockTimeNano: Long)

    /**
     * Check if the current frame should be rendered.
     *
     * Returns the same result as the last call to beginFrame().
     *
     * @return true if frame should be rendered, false if frame should be skipped.
     */
    fun shouldRenderFrame(): Boolean

    /**
     * Prepare a frame for rendering and manage frame pacing.
     *
     * Manages frame pacing and returns whether the frame should be rendered:
     * - true: frame should be rendered; must call render() and endFrame().
     * - false: frame is behind; can skip the frame (don't call endFrame()), or proceed anyway.
     *
     * When false is returned, the application can either skip rendering the frame entirely,
     * or proceed at the cost of increased latency.
     *
     * @param swapChain SwapChain to render into.
     * @param frameTimeNanos VSYNC time in steady_clock nanoseconds, or 0 to use setVsyncTime().
     * @return true if frame should be rendered, false if behind schedule.
     */
    fun beginFrame(swapChain: SwapChain, frameTimeNanos: Long): Boolean

    /**
     * Finish the current frame and schedule it for display.
     *
     * Must be called after beginFrame(true) returned true. This commits the frame and
     * schedules it for presentation.
     */
    fun endFrame()

    /**
     * Render a View into this Renderer's target.
     *
     * Performs shadow passes, depth pre-pass, color pass, and post-processing.
     *
     * Must be called between beginFrame(true) and endFrame().
     * Can be called multiple times per frame, but typically once.
     *
     * @param view The View to render.
     */
    fun render(view: View)

    /**
     * Render a View without frame pacing.
     *
     * Useful for off-screen rendering or when frame pacing is not desired.
     *
     * @param view The View to render.
     */
    fun renderStandaloneView(view: View)

    /**
     * Copy the rendered frame to another SwapChain at a specified viewport.
     *
     * Call after render() but before endFrame(). This is useful for rendering to multiple
     * displays or for implementing picture-in-picture functionality.
     *
     * @param dstSwapChain Destination SwapChain.
     * @param dstViewport Destination viewport rectangle.
     * @param srcViewport Source viewport rectangle.
     * @param flags Behavior flags (COMMIT, SET_PRESENTATION_TIME, CLEAR).
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.copyFrame is not bound in filament.js.")
    fun copyFrame(dstSwapChain: SwapChain, dstViewport: Viewport, srcViewport: Viewport, flags: Int)

    /**
     * Read back SwapChain pixels asynchronously.
     *
     * The buffer's callback is invoked on the main thread when complete (usually after
     * several endFrame() calls). Formats RGBA/RGBA_INTEGER and types UBYTE/UINT/INT/FLOAT
     * are always supported.
     *
     * Call between beginFrame() and endFrame(), typically after render().
     * Impacts performance significantly, especially on some mobile platforms.
     *
     * @param xoffset Left offset in pixels.
     * @param yoffset Bottom offset in pixels.
     * @param width Width in pixels.
     * @param height Height in pixels.
     * @param buffer Pixel buffer descriptor for the result.
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.readPixels is not bound in filament.js.")
    fun readPixels(xoffset: Int, yoffset: Int, width: Int, height: Int, buffer: Texture.PixelBufferDescriptor)

    /**
     * Read back RenderTarget pixels asynchronously.
     *
     * Similar to readPixels(SwapChain), but reads from a RenderTarget instead.
     *
     * @param renderTarget RenderTarget to read from.
     * @param xoffset Left offset in pixels.
     * @param yoffset Bottom offset in pixels.
     * @param width Width in pixels.
     * @param height Height in pixels.
     * @param buffer Pixel buffer descriptor for the result.
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — Renderer.readPixels is not bound in filament.js.")
    fun readPixels(renderTarget: RenderTarget, xoffset: Int, yoffset: Int, width: Int, height: Int, buffer: Texture.PixelBufferDescriptor)

    /**
     * Get elapsed time since resetUserTime() in seconds.
     *
     * @return Elapsed time in seconds.
     */
    val userTime: Double

    /** Reset the user time clock to zero. */
    fun resetUserTime()

    /**
     * Skip the next N frames for frame pacing.
     *
     * @param frameCount Number of frames to skip.
     */
    fun skipNextFrames(frameCount: Int)

    /**
     * Get the number of frames left to skip.
     *
     * @return Number of frames remaining to skip.
     */
    val frameToSkipCount: Int
}
