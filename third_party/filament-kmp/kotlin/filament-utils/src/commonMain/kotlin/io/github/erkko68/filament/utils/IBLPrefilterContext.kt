package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap
import io.github.erkko68.filament.Texture

/**
 * Creates and initializes GPU state common to all environment map filters supported.
 *
 * Typically, only one instance per [Engine] needs to exist.
 *
 * Usage example:
 * ```kotlin
 * val context = IBLPrefilterContext(engine)
 * val filter = SpecularFilter(context)
 * val texture = filter.run(environmentCubemap)
 * val indirectLight = IndirectLight.Builder()
 *     .reflections(texture)
 *     .build(engine)
 * ```
 *
 * @param engine the [Engine] to use for all GPU operations
 */
expect class IBLPrefilterContext(engine: Engine) {
    /**
     * Destroys all GPU resources created during initialization.
     */
    fun destroy()
}

/**
 * Converts an equirectangular image to a cubemap.
 *
 * The input equirectangular texture must:
 * - Be a 2D texture (width == 2 * height)
 * - Have all mip levels allocated
 * - Have SAMPLEABLE usage
 *
 * @param context the [IBLPrefilterContext] to use
 */
expect class EquirectangularToCubemap(context: IBLPrefilterContext) {
    /**
     * Destroys all GPU resources created during initialization.
     */
    fun destroy()

    /**
     * Converts the given equirectangular [Texture] to a cubemap.
     *
     * The output cubemap is automatically created with default parameters (256 size, 9 levels)
     * if not provided via a platform-specific overload.
     *
     * @param equirect the equirectangular texture to convert; must be SAMPLEABLE, 2D, width == 2*height, all mips allocated
     * @return the resulting cubemap [Texture]
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — returns the input texture unchanged; filament.js does not expose IBLPrefilterContext.")
    fun run(equirect: Texture): Texture
}

/**
 * GPU-based implementation of the specular probe pre-integration filter.
 *
 * An instance is needed per filter configuration. The filter uses D_GGX kernel with 1024 samples
 * and 5 roughness levels by default.
 *
 * @param context the [IBLPrefilterContext] to use
 */
expect class SpecularFilter(context: IBLPrefilterContext) {
    /**
     * Destroys all GPU resources created during initialization.
     */
    fun destroy()

    /**
     * Generates a prefiltered specular cubemap from the given environment cubemap.
     *
     * The environment cubemap must be SAMPLEABLE and have all mip levels allocated.
     * The output is automatically created with default parameters if not provided via
     * a platform-specific overload.
     *
     * @param skybox the environment cubemap to prefilter; must be SAMPLEABLE with all levels allocated
     * @return the prefiltered specular [Texture]
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — returns the input texture unchanged; filament.js does not expose IBLPrefilterContext.")
    fun run(skybox: Texture): Texture
}
