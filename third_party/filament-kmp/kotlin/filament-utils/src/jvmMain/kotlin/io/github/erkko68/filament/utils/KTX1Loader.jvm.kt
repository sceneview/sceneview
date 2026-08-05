package io.github.erkko68.filament.utils

import io.github.erkko68.filament.Engine
import io.github.erkko68.filament.IndirectLight
import io.github.erkko68.filament.Skybox
import io.github.erkko68.filament.Texture
import io.github.erkko68.filament.bytes
import io.github.erkko68.filament.confined
import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.floats
import io.github.erkko68.filament.toFloats
import java.lang.foreign.ValueLayout

actual object KTX1Loader {
    actual class Options actual constructor() {
        actual var srgb: Boolean = false
    }

    actual class IndirectLightBundle actual constructor(
        actual val indirectLight: IndirectLight?,
        actual val cubemap: Texture?
    )

    actual class SkyboxBundle actual constructor(
        actual val skybox: Skybox?,
        actual val cubemap: Texture?
    )

    actual fun createTexture(engine: Engine, buffer: ByteArray, options: Options): Texture? = confined { a ->
        val handle = FilamentC.FilaKTX1Loader_createTexture(
            engine.nativeHandle, a.bytes(buffer), buffer.size.toLong(), options.srgb,
        )
        handle?.let { Texture(it) }
    }

    actual fun createIndirectLight(engine: Engine, buffer: ByteArray, options: Options): IndirectLightBundle {
        // Mirrors KTX1Loader.native.kt: unpack SH, create the cubemap, then build the IBL from both.
        val sh = getSphericalHarmonics(buffer) ?: return IndirectLightBundle(null, null)
        val tex = createTexture(engine, buffer, options) ?: return IndirectLightBundle(null, null)
        val il = confined { a ->
            FilamentC.FilaKTX1Loader_createIndirectLight(engine.nativeHandle, tex.nativeHandle, a.floats(sh))
        }
        return IndirectLightBundle(il?.let { IndirectLight(it) }, tex)
    }

    actual fun createSkybox(engine: Engine, buffer: ByteArray, options: Options): SkyboxBundle {
        val tex = createTexture(engine, buffer, options) ?: return SkyboxBundle(null, null)
        val skybox = FilamentC.FilaKTX1Loader_createSkybox(engine.nativeHandle, tex.nativeHandle)
        return SkyboxBundle(skybox?.let { Skybox(it) }, tex)
    }

    actual fun getSphericalHarmonics(buffer: ByteArray): FloatArray? = confined { a ->
        val outSh = a.allocate(ValueLayout.JAVA_FLOAT, (9 * 3).toLong())
        val ok = FilamentC.FilaKTX1Loader_getSphericalHarmonics(a.bytes(buffer), buffer.size.toLong(), outSh)
        if (ok) outSh.toFloats() else null
    }
}
