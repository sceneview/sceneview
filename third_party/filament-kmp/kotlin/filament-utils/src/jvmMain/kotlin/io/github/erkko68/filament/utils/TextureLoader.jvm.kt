package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.bytes
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ffm.FilamentC

actual object TextureLoader {
    actual enum class TextureType {
        COLOR, NORMAL, DATA
    }

    actual fun loadTexture(engine: Engine, buffer: ByteArray, type: TextureType): Texture? = confined { a ->
        // The C wrapper takes an sRGB flag, sRGB only for COLOR (mirrors TextureLoader.native.kt).
        val handle = FilamentC.FilaTextureLoader_loadTexture(
            engine.nativeHandle, a.bytes(buffer), buffer.size.toLong(), type == TextureType.COLOR,
        )
        handle?.let { Texture(it) }
    }
}
