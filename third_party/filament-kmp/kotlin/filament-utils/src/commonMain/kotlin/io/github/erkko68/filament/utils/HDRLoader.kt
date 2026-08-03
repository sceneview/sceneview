package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

/**
 * Decodes an HDR image from raw bytes into a Filament [Texture].
 */
expect object HDRLoader {
    /**
     * Decodes HDR bytes into a Filament [Texture].
     *
     * @param engine the [Engine] to create the texture with
     * @param buffer the raw HDR image data
     * @param internalFormat the internal format to use for the resulting texture
     * @return the created [Texture], or null on failure
     * @throws UnsupportedOperationException on Web — `filament.js` exposes no Radiance/RGBE decoder.
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js exposes no Radiance/RGBE decoder.")
    fun createTexture(engine: Engine, buffer: ByteArray, internalFormat: Texture.InternalFormat): Texture?
}
