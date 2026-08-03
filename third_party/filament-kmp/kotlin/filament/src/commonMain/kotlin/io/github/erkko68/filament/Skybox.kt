package io.github.erkko68.filament

/**
 * Skybox renders a background environment cube around the camera.
 *
 * When added to a Scene, the Skybox fills all untouched pixels. The Skybox is rendered as
 * though the camera is inside an infinitely large cube with the cubemap mapped to its exterior.
 * This allows rendering a background environment that follows the camera's orientation.
 *
 * **Creation and destruction:**
 * Create a Skybox with the Builder and destroy with Engine.destroy(skybox).
 *
 * ```
 * val skybox = Skybox.Builder()
 *     .environment(cubemap)
 *     .build(engine)
 * // later:
 * engine.destroy(skybox)
 * ```
 *
 * **Note:** The Skybox and IndirectLight both render backgrounds. Currently, only Texture-based
 * skyboxes are supported. The Skybox typically uses lower-resolution cubemaps for visual appearance,
 * while IndirectLight provides the high-quality irradiance for lighting calculations.
 *
 * @see Scene.setSkybox
 * @see IndirectLight
 */
expect class Skybox {
    /**
     * Builder for creating Skybox instances.
     *
     * The Skybox can be configured with a cubemap environment, color, intensity, and
     * priority, along with optional sun disk rendering.
     */
    class Builder() {
        /**
         * Set the environment map (the skybox content).
         *
         * The Skybox is rendered as though it were an infinitely large cube with the camera
         * inside it. The cubemap is mapped onto the cube's exterior, so the cubemap appears
         * mirrored following OpenGL conventions.
         *
         * The **cmgen** tool generates reflection maps by default, which are ideal for use as skyboxes.
         *
         * @param cubemap A cube map Texture.
         * @return This Builder, for chaining calls.
         *
         * @see Texture
         */
        fun environment(cubemap: Texture): Builder

        /**
         * Indicates whether the sun disk should be rendered.
         *
         * The sun can only be rendered if there is at least one light of type SUN in the scene.
         * Default: false.
         *
         * @param show true to render the sun disk, false to disable.
         * @return This Builder, for chaining calls.
         */
        fun showSun(show: Boolean): Builder

        /**
         * Set the Skybox intensity when no IndirectLight is set on the Scene.
         *
         * This call is ignored when an IndirectLight is set on the Scene, and the intensity
         * of the IndirectLight is used instead.
         *
         * @param envIntensity Scale factor applied to the skybox texel values such that
         *                     the result is in lux, or lumen/m² (default = 30000).
         * @return This Builder, for chaining calls.
         *
         * @see IndirectLight.Builder.intensity
         */
        fun intensity(envIntensity: Float): Builder

        /**
         * Set the Skybox to a constant color.
         *
         * This is ignored if an environment cubemap is set. Default: opaque black.
         *
         * @param r Red channel [0, 1].
         * @param g Green channel [0, 1].
         * @param b Blue channel [0, 1].
         * @param a Alpha channel [0, 1].
         * @return This Builder, for chaining calls.
         */
        fun color(r: Float, g: Float, b: Float, a: Float): Builder

        /**
         * Set the rendering priority of the Skybox.
         *
         * By default, it is set to the lowest priority (7) such that the Skybox is always rendered
         * after opaque objects, to reduce overdraw when depth culling is enabled.
         *
         * @param priority Clamped to the range [0..7], defaults to 7. 7 is the lowest priority (rendered last).
         * @return This Builder, for chaining calls.
         *
         * @see RenderableManager.Builder.priority
         */
        fun priority(priority: Int): Builder

        /**
         * Creates the Skybox object and associates it with the given Engine.
         *
         * @param engine Engine to associate this Skybox with.
         * @return The newly created Skybox.
         */
        fun build(engine: Engine): Skybox
    }

    /**
     * Dynamically update the Skybox's constant color.
     *
     * @param r Red channel [0, 1].
     * @param g Green channel [0, 1].
     * @param b Blue channel [0, 1].
     * @param a Alpha channel [0, 1].
     */
    fun setColor(r: Float, g: Float, b: Float, a: Float)

    /**
     * Returns the Skybox's intensity in lux, or lumen/m².
     *
     * @return Intensity multiplier.
     */
    val intensity: Float

    /**
     * Returns the visibility mask bits (layer mask).
     *
     * Sets bits in a visibility mask. By default, this is 0x1. This provides a simple mechanism
     * for hiding or showing this Skybox in a Scene.
     *
     * @return Bitmask of visible layers.
     *
     * @see View.setVisibleLayers
     */
    val layerMask: Int

    /**
     * Returns the associated environment cubemap Texture.
     *
     * @return The cubemap Texture, or null if using a constant color instead.
     */
    val texture: Texture?

    /**
     * Set bits in the visibility mask.
     *
     * This provides a simple mechanism for hiding or showing this Skybox in a Scene.
     *
     * For example, to set bit 1 and reset bits 0 and 2 while leaving all other bits unaffected,
     * call: `setLayerMask(7, 2)`.
     *
     * @param select The set of bits to affect.
     * @param value The replacement values for the affected bits.
     *
     * @see View.setVisibleLayers
     */
    fun setLayerMask(select: Int, value: Int)
}
