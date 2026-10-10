package io.github.sceneview.environment

import com.google.android.filament.Engine
import com.google.android.filament.Texture
import com.google.android.filament.utils.IBLPrefilterContext

/**
 * IBLPrefilter creates and initializes GPU state common to all environment map filters.
 * Typically, only one instance per filament Engine of this object needs to exist.
 *
 * @see [IBLPrefilterContext]
 */
class IBLPrefilter(engine: Engine) {

    // The three are created on first use. Each Lazy is kept so that destroy() can tell what was
    // actually created.
    private val contextLazy = lazy { IBLPrefilterContext(engine) }
    private val equirectangularToCubemapLazy = lazy {
        IBLPrefilterContext.EquirectangularToCubemap(context)
    }
    private val specularFilterLazy = lazy { IBLPrefilterContext.SpecularFilter(context) }

    /**
     * Created IBLPrefilterContext, keeping it around if several cubemap will be processed.
     */
    val context by contextLazy

    /**
     * EquirectangularToCubemap is use to convert an equirectangluar image to a cubemap.
     *
     * Creates a EquirectangularToCubemap processor.
     */
    private val equirectangularToCubemap by equirectangularToCubemapLazy

    /**
     * Converts an equirectangular image to a cubemap.
     *
     * @param equirect Texture to convert to a cubemap.
     * - Can't be null.
     * - Must be a 2d texture
     * - Must have equirectangular geometry, that is width == 2*height.
     * - Must be allocated with all mip levels.
     * - Must be SAMPLEABLE
     *
     * @return the cubemap texture
     *
     * @see [EquirectangularToCubemap]
     */
    fun equirectangularToCubemap(equirect: Texture): Texture =
        equirectangularToCubemap.run(equirect)

    /**
     * Created specular (reflections) filter. This operation generates the kernel, so it's
     * important to keep it around if it will be reused for several cubemaps.
     * An instance of SpecularFilter is needed per filter configuration. A filter configuration
     * contains the filter's kernel and sample count.
     */
    private val specularFilter by specularFilterLazy

    /**
     * Generates a prefiltered cubemap.
     *
     * SpecularFilter is a GPU based implementation of the specular probe pre-integration filter.
     *
     * ** Launch the heaver computation. Expect 100-100ms on the GPU.**
     *
     * @param skybox Environment cubemap.
     * This cubemap is SAMPLED and have all its levels allocated.
     *
     * @return the reflections texture
     */
    fun specularFilter(skybox: Texture) = specularFilter.run(skybox)

    /**
     * Destroys what was created, and only that: a prefilter that was never used has nothing on the
     * GPU. Reading the lazy properties here would build the context, its materials and the
     * specular kernel — shader programs included — only to wait for them and destroy them.
     */
    fun destroy() {
        if (specularFilterLazy.isInitialized()) runCatching { specularFilter.destroy() }
        if (equirectangularToCubemapLazy.isInitialized()) {
            runCatching { equirectangularToCubemap.destroy() }
        }
        if (contextLazy.isInitialized()) runCatching { context.destroy() }
    }
}