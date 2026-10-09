package io.github.sceneview.environment

import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import io.github.sceneview.loaders.EnvironmentLoader

/**
 *
 * Indirect light and skybox environment for a [Scene]
 *
 * Environments are usually captured as high-resolution HDR equirectangular images and processed by
 * the cmgen tool to generate the data needed by IndirectLight.
 *
 * You can also process an hdr at runtime but this is more consuming.
 *
 * - Currently IndirectLight is intended to be used for "distant probes", that is, to represent
 * global illumination from a distant (i.e. at infinity) environment, such as the sky or distant
 * mountains. Only a single IndirectLight can be used in a Scene. This limitation will be lifted in
 * the future.
 * - When added to a Scene, the Skybox fills all untouched pixels.
 *
 * @see [EnvironmentLoader]
 * @see [IndirectLight.Builder]
 * @see [Skybox.Builder]
 */
data class Environment(
    /**
     * IndirectLight is used to simulate environment lighting.
     *
     *  Environment lighting has a two components:
     *  - irradiance
     *  - reflections (specular component)
     *
     *  `null` to unset the IndirectLight.
     */
    val indirectLight: IndirectLight? = null,
    /**
     * The Skybox is drawn last and covers all pixels not touched by geometry.
     *
     * `null` to unset the Skybox.
     */
    val skybox: Skybox? = null,
    /**
     * Spherical harmonics from the content of a KTX file.
     *
     * Array of 9 * 3 floats, or null on failure.
     */
    val sphericalHarmonics: List<Float>? = null
) {
    /**
     * The cubemaps [indirectLight] and [skybox] sample, when this environment was handed them.
     *
     * Filament frees neither with the object that samples it, so they are released here, by
     * [destroy]. Held by identity and outside the constructor on purpose: a `copy()` shares the
     * Filament handles of the environment it was made from without owning anything, so the
     * environment to destroy is always the original, and destroying a copy never pulls a texture
     * from under the original's indirect light.
     *
     * Not synchronized: like every Filament handle, an environment is built and destroyed on the
     * main thread.
     */
    private val ownedTextures = mutableListOf<Texture>()

    internal fun ownTextures(textures: Iterable<Texture>) {
        textures.forEach { texture ->
            if (ownedTextures.none { it === texture }) ownedTextures += texture
        }
    }

    /**
     * Destroys what this environment holds, in the one order Filament allows: the indirect light
     * and the skybox first, then the textures they were sampling.
     *
     * The single implementation behind `Engine.safeDestroyEnvironment` and
     * `EnvironmentLoader.destroyEnvironment`, so the two cannot drift apart. The textures are
     * handed out once: a second call releases none, whichever of the two paths makes it.
     */
    internal fun destroy(
        destroyIndirectLight: (IndirectLight) -> Unit,
        destroySkybox: (Skybox) -> Unit,
        destroyTexture: (Texture) -> Unit,
    ) {
        indirectLight?.let(destroyIndirectLight)
        skybox?.let(destroySkybox)
        val textures = ownedTextures.toList()
        ownedTextures.clear()
        textures.forEach(destroyTexture)
    }
}
