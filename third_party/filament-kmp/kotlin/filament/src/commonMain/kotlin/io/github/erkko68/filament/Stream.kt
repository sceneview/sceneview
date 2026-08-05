package io.github.erkko68.filament

/**
 * Stream is used to attach a video stream to a Filament Texture.
 *
 * The Stream class is fairly platform-centric. It supports two different configurations:
 *
 * - **NATIVE**: Connects to a native OS stream (e.g., Android SurfaceTexture)
 * - **ACQUIRED**: Connects to an external source (e.g., Android AHardwareBuffer)
 *
 * Before explaining these different configurations, let's review the high-level structure of an AR
 * or video application that uses Filament:
 *
 * ```
 * while (true) {
 *     // Application work: write frame data, move camera, etc.
 *
 *     if (renderer.beginFrame(swapChain)) {
 *         renderer.render(view)
 *         renderer.endFrame()
 *     }
 * }
 * ```
 *
 * Let's say that the video image data at the time of a particular invocation of `beginFrame()`
 * becomes visible to users at time A. The 3D scene state (including camera) at that same
 * invocation becomes apparent to users at time B.
 *
 * - If time A matches time B, the stream is **synchronized**.
 * - Filament invokes low-level graphics commands on the **driver thread**.
 * - The thread that calls `beginFrame()` is called the **main thread**.
 *
 * For ACQUIRED streams, Filament explicitly acquires the stream, then releases it later via a
 * callback function. This configuration is especially useful when the Vulkan backend is enabled.
 *
 * NATIVE streams are deprecated because they are backend-specific and do not make any
 * synchronization guarantee.
 *
 * @see Texture
 * @see TextureSampler
 */
@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException on construction — Stream is not bound in filament.js; external/native video streams have no web equivalent.")
expect class Stream {
    /**
     * Indicates the type of stream source.
     */
    enum class StreamType {
        /** Native OS stream (e.g., Android SurfaceTexture) */
        NATIVE,
        /** Stream acquired from external source (e.g., AHardwareBuffer) */
        ACQUIRED
    }

    /**
     * Builder for creating Stream instances.
     *
     * By default, Stream objects are ACQUIRED and must have external images pushed to them.
     * To create a NATIVE stream, call the deprecated stream() method on the builder.
     */
    class Builder() {
        /**
         * Sets the initial width of the incoming stream in pixels.
         *
         * Whether this value is used is stream-dependent. On Android, it must be set when using
         * a native stream.
         *
         * @param width Stream width in pixels.
         * @return This Builder, for chaining calls.
         */
        fun width(width: Int): Builder

        /**
         * Sets the initial height of the incoming stream in pixels.
         *
         * Whether this value is used is stream-dependent. On Android, it must be set when using
         * a native stream.
         *
         * @param height Stream height in pixels.
         * @return This Builder, for chaining calls.
         */
        fun height(height: Int): Builder

        /**
         * Creates the Stream object and associates it with the given Engine.
         *
         * @param engine Engine to associate this Stream with.
         * @return The newly created Stream.
         * @throws UnsupportedOperationException on JS — Stream is unbound in the web wrapper.
         */
        fun build(engine: Engine): Stream
    }

    /**
     * Indicates whether this stream is a NATIVE stream or ACQUIRED stream.
     *
     * @return The StreamType of this stream.
     */
    val streamType: StreamType

    /**
     * Returns the presentation timestamp of the currently displayed frame in nanoseconds.
     *
     * This value can change at any time and represents the time when the current frame
     * was acquired or presented.
     *
     * @return Timestamp in nanoseconds (monotonically increasing).
     */
    val timestamp: Long

    /**
     * Updates the size of the incoming stream.
     *
     * Whether this value is used is stream-dependent. On Android, it must be set when using
     * a native stream. The dimensions should match the associated Texture's dimensions.
     *
     * @param width New width in pixels.
     * @param height New height in pixels.
     */
    fun setDimensions(width: Int, height: Int)
}
