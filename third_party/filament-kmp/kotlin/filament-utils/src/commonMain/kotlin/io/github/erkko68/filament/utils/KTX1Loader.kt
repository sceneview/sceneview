package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndirectLight
import io.github.erkko68.filament.Skybox
import io.github.erkko68.filament.Texture

/**
 * Decodes KTX1 data into Filament textures, indirect lights, and skyboxes.
 */
expect object KTX1Loader {
    /**
     * Options for KTX1 decoding.
     */
    class Options() {
        /** If true, the resulting texture uses an sRGB internal format. */
        var srgb: Boolean
    }

    /**
     * Holds the result of creating an [IndirectLight] from KTX1 data.
     *
     * @property indirectLight the created [IndirectLight], or null on failure
     * @property cubemap the underlying cubemap [Texture], or null on failure
     */
    class IndirectLightBundle(
        indirectLight: IndirectLight?,
        cubemap: Texture?
    ) {
        val indirectLight: IndirectLight?
        val cubemap: Texture?
    }

    /**
     * Holds the result of creating a [Skybox] from KTX1 data.
     *
     * @property skybox the created [Skybox], or null on failure
     * @property cubemap the underlying cubemap [Texture], or null on failure
     */
    class SkyboxBundle(
        skybox: Skybox?,
        cubemap: Texture?
    ) {
        val skybox: Skybox?
        val cubemap: Texture?
    }

    /**
     * Decodes KTX1 bytes into a Filament [Texture].
     *
     * @param engine the [Engine] to use
     * @param buffer the raw KTX1 image data
     * @param options decoding options, including sRGB toggle
     * @return the created [Texture], or null on failure
     */
    fun createTexture(engine: Engine, buffer: ByteArray, options: Options): Texture?

    /**
     * Creates a Filament [IndirectLight] from KTX1 bytes containing a cubemap and spherical harmonics.
     *
     * @param engine the [Engine] to use
     * @param buffer the raw KTX1 data
     * @param options decoding options, including sRGB toggle
     * @return an [IndirectLightBundle] containing the [IndirectLight] and its cubemap [Texture]
     */
    fun createIndirectLight(engine: Engine, buffer: ByteArray, options: Options): IndirectLightBundle

    /**
     * Creates a Filament [Skybox] from KTX1 bytes containing a cubemap.
     *
     * @param engine the [Engine] to use
     * @param buffer the raw KTX1 data
     * @param options decoding options, including sRGB toggle
     * @return a [SkyboxBundle] containing the [Skybox] and its cubemap [Texture]
     */
    fun createSkybox(engine: Engine, buffer: ByteArray, options: Options): SkyboxBundle

    /**
     * Extracts spherical harmonics coefficients from KTX1 bytes.
     *
     * The returned array contains 9 float3 values (27 floats) representing the L0, L1, and L2
     * spherical harmonics bands stored in the KTX1 metadata.
     *
     * @param buffer the raw KTX1 data containing spherical harmonics metadata
     * @return a FloatArray of 27 coefficients, or null if extraction fails
     */
    fun getSphericalHarmonics(buffer: ByteArray): FloatArray?
}
