package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.bytes
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.FilamentPlatform
import io.github.erkko68.filament.PlatformGap

actual object HDRLoader {
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js exposes no Radiance/RGBE decoder.")
    actual fun createTexture(engine: Engine, buffer: ByteArray, internalFormat: Texture.InternalFormat): Texture? = confined { a ->
        val handle = FilamentC.FilaHDRLoader_createTexture(
            engine.nativeHandle, a.bytes(buffer), buffer.size.toLong(), internalFormat.ordinal,
        )
        if (handle == null || handle.address() == 0L) null else Texture(handle)
    }
}
