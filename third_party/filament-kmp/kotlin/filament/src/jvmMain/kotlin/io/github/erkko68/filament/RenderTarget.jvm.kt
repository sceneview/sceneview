package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class RenderTarget internal constructor(internal var nativeHandle: MemorySegment?, private val textures: Array<Texture?>) {
    actual enum class AttachmentPoint {
        COLOR, COLOR1, COLOR2, COLOR3, COLOR4, COLOR5, COLOR6, COLOR7, DEPTH
    }

    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaRenderTargetBuilder_create()
        private val textures = arrayOfNulls<Texture>(AttachmentPoint.values().size)

        actual fun texture(attachment: AttachmentPoint, texture: Texture?): Builder {
            textures[attachment.ordinal] = texture
            FilamentC.FilaRenderTargetBuilder_texture(nativeBuilder, attachment.ordinal, texture?.nativeHandle ?: NULL)
            return this
        }

        actual fun mipLevel(attachment: AttachmentPoint, level: Int): Builder {
            FilamentC.FilaRenderTargetBuilder_mipLevel(nativeBuilder, attachment.ordinal, level.toByte())
            return this
        }

        actual fun face(attachment: AttachmentPoint, face: Texture.CubemapFace): Builder {
            FilamentC.FilaRenderTargetBuilder_face(nativeBuilder, attachment.ordinal, face.ordinal)
            return this
        }

        actual fun layer(attachment: AttachmentPoint, layer: Int): Builder {
            FilamentC.FilaRenderTargetBuilder_layer(nativeBuilder, attachment.ordinal, layer)
            return this
        }

        actual fun build(engine: Engine): RenderTarget {
            val handle = FilamentC.FilaRenderTargetBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaRenderTargetBuilder_destroy(nativeBuilder)
            return RenderTarget(handle, textures.copyOf())
        }
    }

    actual fun getTexture(attachment: AttachmentPoint): Texture? = textures[attachment.ordinal]

    actual fun getMipLevel(attachment: AttachmentPoint): Int =
        FilamentC.FilaRenderTarget_getMipLevel(nativeHandle, attachment.ordinal).toInt()

    actual fun getFace(attachment: AttachmentPoint): Texture.CubemapFace =
        Texture.CubemapFace.values()[FilamentC.FilaRenderTarget_getFace(nativeHandle, attachment.ordinal)]

    actual fun getLayer(attachment: AttachmentPoint): Int =
        FilamentC.FilaRenderTarget_getLayer(nativeHandle, attachment.ordinal)
}
