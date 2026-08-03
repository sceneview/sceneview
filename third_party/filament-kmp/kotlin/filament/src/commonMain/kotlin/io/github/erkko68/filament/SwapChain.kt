package io.github.erkko68.filament

/**
 * A SwapChain represents an Operating System's native renderable surface.
 *
 * Typically, it's a native window or a view. A SwapChain is initialized from a native object,
 * which must be of the proper type for each platform Filament is running on:
 *
 * Platform        | Native Type
 * :---------------|:----------------------------:
 * Android         | ANativeWindow*
 * macOS - OpenGL  | NSView*
 * macOS - Metal   | CAMetalLayer*
 * iOS - OpenGL    | CAEAGLLayer*
 * iOS - Metal     | CAMetalLayer*
 * X11             | Window
 * Windows         | HWND
 *
 * @see Engine.createSwapChain
 */
expect class SwapChain {
    /**
     * Frame rate compatibility strategy for [setFrameRate].
     */
    enum class FrameRateCompatibility {
        /** Default compatibility: the platform decides how to honor the requested rate. */
        DEFAULT,
        /** The content has a fixed source frame rate (e.g. video playback). */
        FIXED_SOURCE
    }

    /**
     * Strategy for applying a frame rate change that is not seamless.
     */
    enum class ChangeFrameRateStrategy {
        /** Only change the frame rate if the transition is seamless (no visual interruption). */
        ONLY_IF_SEAMLESS,
        /** Change the frame rate even if it requires a non-seamless transition. */
        ALWAYS
    }

    companion object {
        /**
         * Checks if protected content (DRM) rendering is supported on this platform.
         *
         * Protected content rendering is required for secure video playback in some scenarios.
         *
         * @param engine The engine
         * @return true if protected content is supported, false otherwise
         */
        fun isProtectedContentSupported(engine: Engine): Boolean

        /**
         * Checks if sRGB swap chain is supported on this platform.
         *
         * When supported, a SwapChain can be configured to automatically perform linear to sRGB
         * encoding in the hardware, which is more efficient than doing it in a shader.
         *
         * @param engine The engine
         * @return true if sRGB swap chain is supported, false otherwise
         */
        fun isSRGBSwapChainSupported(engine: Engine): Boolean

        /**
         * Checks if Multi-Sample Anti-Aliasing (MSAA) swap chain is supported.
         *
         * MSAA can be configured when creating a SwapChain on supported platforms (EGL on Android,
         * Metal). Other GL platforms (GLX, WGL, etc.) don't support swap chain MSAA because the
         * settings must be configured before window creation.
         *
         * @param engine The engine
         * @param samples Number of samples (e.g., 2, 4, 8)
         * @return true if MSAA with the specified sample count is supported, false otherwise
         */
        fun isMSAASwapChainSupported(engine: Engine, samples: Int): Boolean
    }

    /**
     * Gets the native window/surface associated with this SwapChain.
     *
     * The returned object type depends on the platform (e.g., ANativeWindow* on Android,
     * HWND on Windows, NSView* on macOS, etc.).
     *
     * @return The native window object, or null if not available
     */
    val nativeWindow: Any?

    /**
     * Gets the native SwapChain object handle.
     *
     * This is an opaque handle for platform-specific access to the underlying SwapChain object.
     *
     * @return The native object handle as a long value
     */
    val nativeObject: Long

    /**
     * Checks if a frame scheduled callback is currently set on this SwapChain.
     *
     * @return true if a frame scheduled callback is set, false otherwise
     */
    val isFrameScheduledCallbackSet: Boolean

    /**
     * Sets a callback to be invoked when a frame's rendering has completed on the GPU.
     *
     * The callback is called after the frame has been fully rendered on the GPU. On the default
     * handler, this is guaranteed to be called on the main Filament thread.
     *
     * Use this callback to be notified when GPU rendering is finished, useful for synchronization
     * or performance monitoring.
     *
     * @param callback The callback function to invoke when frame GPU rendering completes.
     *                 Pass null or a no-op function to unset the callback.
     */
    fun setFrameCompletedCallback(callback: () -> Unit)

    /**
     * Sets a callback to be invoked when a frame is scheduled for presentation.
     *
     * The exact timing and behavior of this callback depends on the graphics backend:
     *
     * **Metal Backend:** Signifies that Filament has completed all CPU-side processing for the
     * frame and it is ready to be scheduled for presentation. The callback allows the application
     * to take control of presentation scheduling.
     *
     * **Other Backends (OpenGL, Vulkan, WebGPU):** Serves as a notification that CPU-side
     * processing is complete. Filament proceeds with normal presentation automatically.
     *
     * Only one callback per frame can be set. If called multiple times before Renderer.endFrame(),
     * the most recent call overwrites the previous one.
     *
     * @param callback The callback function to invoke when the frame is scheduled.
     *                 Pass null or a no-op function to unset the callback.
     */
    fun setFrameScheduledCallback(callback: () -> Unit)

    /**
     * Returns whether this SwapChain supports the [setFrameRate] API.
     *
     * When a SwapChain is newly created, the actual surface capability may not yet be determined
     * by the underlying OS, in which case this returns false. Once the platform completes surface
     * connection, this method authoritatively returns true or false.
     *
     * @return true if [setFrameRate] is definitively supported, false otherwise
     */
    fun isFrameRateChangeSupported(): Boolean

    /**
     * Sets the intended frame rate for this SwapChain.
     *
     * Uses [FrameRateCompatibility.DEFAULT] and [ChangeFrameRateStrategy.ONLY_IF_SEAMLESS].
     *
     * @param frameRate The intended frame rate in frames per second. 0.0f clears/resets the rate.
     */
    fun setFrameRate(frameRate: Float)

    /**
     * Sets the intended frame rate for this SwapChain.
     *
     * @param frameRate     The intended frame rate in frames per second. 0.0f clears/resets the rate.
     * @param compatibility Frame rate compatibility mode.
     * @param strategy      Change strategy for non-seamless transitions.
     */
    fun setFrameRate(frameRate: Float, compatibility: FrameRateCompatibility, strategy: ChangeFrameRateStrategy)
}
