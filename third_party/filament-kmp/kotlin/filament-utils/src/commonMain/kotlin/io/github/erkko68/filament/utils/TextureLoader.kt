package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Texture

/**
 * Loads common image formats (PNG, JPG, etc.) into a Filament [Texture].
 */
expect object TextureLoader {
    /**
     * Specifies the intended use of the texture data, which influences the internal format selected.
     */
    enum class TextureType {
        /** sRGB color data. */
        COLOR,
        /** Linear normal map data. */
        NORMAL,
        /** Linear non-color data. */
        DATA
    }

    /**
     * Decodes image bytes (PNG, JPG, etc.) into a Filament [Texture].
     *
     * @param engine the [Engine] to create the texture with
     * @param buffer the raw encoded image data
     * @param type the intended use of the texture, which influences the internal format selected
     * @return the created [Texture], or null on failure
     */
    fun loadTexture(engine: Engine, buffer: ByteArray, type: TextureType): Texture?
}
