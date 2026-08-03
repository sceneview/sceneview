package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class Texture public constructor(public var nativeHandle: MemorySegment?) {

    actual enum class Sampler {
        SAMPLER_2D, SAMPLER_2D_ARRAY, SAMPLER_CUBEMAP, SAMPLER_EXTERNAL, SAMPLER_3D, SAMPLER_CUBEMAP_ARRAY;
        internal fun toNative(): Int = ordinal
        companion object {
            internal fun fromNative(native: Int): Sampler = entries.getOrElse(native) { SAMPLER_2D }
        }
    }

    actual enum class InternalFormat {
        R8, R8_SNORM, R8UI, R8I, STENCIL8,
        R16F, R16UI, R16I,
        RG8, RG8_SNORM, RG8UI, RG8I,
        RGB565, RGB9_E5, RGB5_A1,
        RGBA4,
        DEPTH16,
        RGB8, SRGB8, RGB8_SNORM, RGB8UI, RGB8I,
        DEPTH24,
        R32F, R32UI, R32I,
        RG16F, RG16UI, RG16I,
        R11F_G11F_B10F,
        RGBA8, SRGB8_A8, RGBA8_SNORM,
        UNUSED,
        RGB10_A2, RGBA8UI, RGBA8I,
        DEPTH32F, DEPTH24_STENCIL8, DEPTH32F_STENCIL8,
        RGB16F, RGB16UI, RGB16I,
        RG32F, RG32UI, RG32I,
        RGBA16F, RGBA16UI, RGBA16I,
        RGB32F, RGB32UI, RGB32I,
        RGBA32F, RGBA32UI, RGBA32I,
        EAC_R11, EAC_R11_SIGNED, EAC_RG11, EAC_RG11_SIGNED,
        ETC2_RGB8, ETC2_SRGB8,
        ETC2_RGB8_A1, ETC2_SRGB8_A1,
        ETC2_EAC_RGBA8, ETC2_EAC_SRGBA8,
        DXT1_RGB, DXT1_RGBA, DXT3_RGBA, DXT5_RGBA,
        DXT1_SRGB, DXT1_SRGBA, DXT3_SRGBA, DXT5_SRGBA,
        RGBA_ASTC_4x4, RGBA_ASTC_5x4, RGBA_ASTC_5x5, RGBA_ASTC_6x5, RGBA_ASTC_6x6,
        RGBA_ASTC_8x5, RGBA_ASTC_8x6, RGBA_ASTC_8x8,
        RGBA_ASTC_10x5, RGBA_ASTC_10x6, RGBA_ASTC_10x8, RGBA_ASTC_10x10,
        RGBA_ASTC_12x10, RGBA_ASTC_12x12,
        SRGB8_ALPHA8_ASTC_4x4, SRGB8_ALPHA8_ASTC_5x4, SRGB8_ALPHA8_ASTC_5x5,
        SRGB8_ALPHA8_ASTC_6x5, SRGB8_ALPHA8_ASTC_6x6,
        SRGB8_ALPHA8_ASTC_8x5, SRGB8_ALPHA8_ASTC_8x6, SRGB8_ALPHA8_ASTC_8x8,
        SRGB8_ALPHA8_ASTC_10x5, SRGB8_ALPHA8_ASTC_10x6, SRGB8_ALPHA8_ASTC_10x8,
        SRGB8_ALPHA8_ASTC_10x10, SRGB8_ALPHA8_ASTC_12x10, SRGB8_ALPHA8_ASTC_12x12,
        RED_RGTC1, SIGNED_RED_RGTC1, RED_GREEN_RGTC2, SIGNED_RED_GREEN_RGTC2,
        RGB_BPTC_SIGNED_FLOAT, RGB_BPTC_UNSIGNED_FLOAT, RGBA_BPTC_UNORM, SRGB_ALPHA_BPTC_UNORM;

        // The C wrapper exposes a subset of Filament's formats, in a different numbering than
        // Kotlin's ordinal, so map explicitly to the generated constants (unsupported -> RGBA8).
        internal fun toNative(): Int = when (this) {
            R8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R8()
            R8_SNORM -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R8_SNORM()
            R8UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R8UI()
            R8I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R8I()
            STENCIL8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_STENCIL8()
            R16F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R16F()
            R16UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R16UI()
            R16I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R16I()
            RG8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG8()
            RG8_SNORM -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG8_SNORM()
            RG8UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG8UI()
            RG8I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG8I()
            RGB565 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB565()
            RGB9_E5 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB9_E5()
            RGB5_A1 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB5_A1()
            RGBA4 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA4()
            DEPTH16 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_DEPTH16()
            RGB8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB8()
            SRGB8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_SRGB8()
            RGB8_SNORM -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB8_SNORM()
            RGB8UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB8UI()
            RGB8I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB8I()
            DEPTH24 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_DEPTH24()
            R32F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R32F()
            R32UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R32UI()
            R32I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R32I()
            RG16F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG16F()
            RG16UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG16UI()
            RG16I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG16I()
            R11F_G11F_B10F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_R11F_G11F_B10F()
            RGBA8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA8()
            SRGB8_A8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_SRGB8_A8()
            RGBA8_SNORM -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA8_SNORM()
            RGB10_A2 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB10_A2()
            RGBA8UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA8UI()
            RGBA8I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA8I()
            DEPTH32F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_DEPTH32F()
            DEPTH24_STENCIL8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_DEPTH24_STENCIL8()
            DEPTH32F_STENCIL8 -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_DEPTH32F_STENCIL8()
            RGB16F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB16F()
            RGB16UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB16UI()
            RGB16I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB16I()
            RG32F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG32F()
            RG32UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG32UI()
            RG32I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RG32I()
            RGBA16F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA16F()
            RGBA16UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA16UI()
            RGBA16I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA16I()
            RGB32F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB32F()
            RGB32UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB32UI()
            RGB32I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGB32I()
            RGBA32F -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA32F()
            RGBA32UI -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA32UI()
            RGBA32I -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA32I()
            else -> FilamentC.FILA_TEXTURE_INTERNAL_FORMAT_RGBA8()
        }

        companion object {
            internal fun fromNative(native: Int): InternalFormat =
                InternalFormat.entries.find { it.toNative() == native } ?: RGBA8
        }
    }

    actual enum class CubemapFace {
        POSITIVE_X, NEGATIVE_X, POSITIVE_Y, NEGATIVE_Y, POSITIVE_Z, NEGATIVE_Z;
        internal fun toNative(): Int = ordinal
    }

    actual enum class Format {
        R, R_INTEGER, RG, RG_INTEGER, RGB, RGB_INTEGER, RGBA, RGBA_INTEGER, UNUSED, DEPTH_COMPONENT, DEPTH_STENCIL, ALPHA;
        internal fun toNative(): Int = ordinal
    }

    actual enum class Type {
        UBYTE, BYTE, USHORT, SHORT, UINT, INT, HALF, FLOAT, COMPRESSED, UINT_10F_11F_11F_REV, USHORT_565;
        internal fun toNative(): Int = ordinal
    }

    actual enum class Swizzle {
        SUBSTITUTE_ZERO, SUBSTITUTE_ONE, CHANNEL_0, CHANNEL_1, CHANNEL_2, CHANNEL_3;
        internal fun toNative(): Int = ordinal
    }

    actual class Usage {
        actual companion object {
            actual val COLOR_ATTACHMENT: Int = FilamentC.FILA_TEXTURE_USAGE_COLOR_ATTACHMENT()
            actual val DEPTH_ATTACHMENT: Int = FilamentC.FILA_TEXTURE_USAGE_DEPTH_ATTACHMENT()
            actual val STENCIL_ATTACHMENT: Int = FilamentC.FILA_TEXTURE_USAGE_STENCIL_ATTACHMENT()
            actual val UPLOADABLE: Int = FilamentC.FILA_TEXTURE_USAGE_UPLOADABLE()
            actual val SAMPLEABLE: Int = FilamentC.FILA_TEXTURE_USAGE_SAMPLEABLE()
            actual val SUBPASS_INPUT: Int = FilamentC.FILA_TEXTURE_USAGE_SUBPASS_INPUT()
            actual val BLIT_SRC: Int = FilamentC.FILA_TEXTURE_USAGE_BLIT_SRC()
            actual val BLIT_DST: Int = FilamentC.FILA_TEXTURE_USAGE_BLIT_DST()
            actual val PROTECTED: Int = FilamentC.FILA_TEXTURE_USAGE_PROTECTED()
            actual val GEN_MIPMAPPABLE: Int = FilamentC.FILA_TEXTURE_USAGE_GEN_MIPMAPPABLE()
            actual val DEFAULT: Int = FilamentC.FILA_TEXTURE_USAGE_DEFAULT()
        }
    }

    actual class PixelBufferDescriptor actual constructor(
        actual val storage: ByteArray,
        actual val sizeInBytes: Int,
        actual val format: Format,
        actual val type: Type,
        actual val alignment: Int,
        actual val left: Int,
        actual val top: Int,
        actual val stride: Int,
        actual val callback: (() -> Unit)?
    )

    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaTextureBuilder_create()

        actual fun width(width: Int): Builder = apply { FilamentC.FilaTextureBuilder_width(nativeBuilder, width) }
        actual fun height(height: Int): Builder = apply { FilamentC.FilaTextureBuilder_height(nativeBuilder, height) }
        actual fun depth(depth: Int): Builder = apply { FilamentC.FilaTextureBuilder_depth(nativeBuilder, depth) }
        actual fun levels(levels: Int): Builder = apply { FilamentC.FilaTextureBuilder_levels(nativeBuilder, levels.toByte()) }
        actual fun samples(samples: Int): Builder = apply { FilamentC.FilaTextureBuilder_samples(nativeBuilder, samples.toByte()) }
        actual fun sampler(target: Sampler): Builder = apply { FilamentC.FilaTextureBuilder_sampler(nativeBuilder, target.toNative()) }
        actual fun format(format: InternalFormat): Builder = apply { FilamentC.FilaTextureBuilder_format(nativeBuilder, format.toNative()) }
        actual fun usage(usage: Int): Builder = apply { FilamentC.FilaTextureBuilder_usage(nativeBuilder, usage) }
        actual fun swizzle(r: Swizzle, g: Swizzle, b: Swizzle, a: Swizzle): Builder = apply {
            FilamentC.FilaTextureBuilder_swizzle(nativeBuilder, r.toNative(), g.toNative(), b.toNative(), a.toNative())
        }
        actual fun importTexture(id: Long): Builder = apply { FilamentC.FilaTextureBuilder_importTexture(nativeBuilder, id) }
        actual fun external(): Builder = apply { FilamentC.FilaTextureBuilder_external(nativeBuilder) }
        actual fun build(engine: Engine): Texture {
            val handle = FilamentC.FilaTextureBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaTextureBuilder_destroy(nativeBuilder)
            return Texture(handle)
        }
    }

    actual fun getWidth(level: Int): Int = FilamentC.FilaTexture_getWidth(nativeHandle, level.toLong()).toInt()
    actual fun getHeight(level: Int): Int = FilamentC.FilaTexture_getHeight(nativeHandle, level.toLong()).toInt()
    actual fun getDepth(level: Int): Int = FilamentC.FilaTexture_getDepth(nativeHandle, level.toLong()).toInt()
    actual fun getLevels(): Int = FilamentC.FilaTexture_getLevels(nativeHandle).toInt()
    actual fun getTarget(): Sampler = Sampler.fromNative(FilamentC.FilaTexture_getTarget(nativeHandle))
    actual fun getFormat(): InternalFormat = InternalFormat.fromNative(FilamentC.FilaTexture_getFormat(nativeHandle))

    actual fun setImage(engine: Engine, level: Int, descriptor: PixelBufferDescriptor) {
        setImage(engine, level, 0, 0, 0, getWidth(level), getHeight(level), getDepth(level), descriptor)
    }

    actual fun setImage(engine: Engine, level: Int, xoffset: Int, yoffset: Int, width: Int, height: Int, descriptor: PixelBufferDescriptor) {
        setImage(engine, level, xoffset, yoffset, 0, width, height, 1, descriptor)
    }

    actual fun setImage(engine: Engine, level: Int, xoffset: Int, yoffset: Int, zoffset: Int, width: Int, height: Int, depth: Int, descriptor: PixelBufferDescriptor) {
        val dataArena = Arena.ofShared()
        val seg = dataArena.bytes(descriptor.storage)
        val userData = Completions.register { try { descriptor.callback?.invoke() } finally { dataArena.close() } }
        FilamentC.FilaTexture_setImage(
            nativeHandle, engine.nativeHandle, level.toLong(),
            xoffset, yoffset, zoffset,
            width, height, depth,
            seg, descriptor.sizeInBytes.toLong(),
            descriptor.format.toNative(), descriptor.type.toNative(),
            descriptor.alignment.toByte(), descriptor.left, descriptor.top, descriptor.stride,
            NULL, Completions.bufferStub, userData
        )
    }

    actual fun generateMipmaps(engine: Engine) {
        FilamentC.FilaTexture_generateMipmaps(nativeHandle, engine.nativeHandle)
    }

    actual fun setExternalStream(engine: Engine, stream: Stream) {
        FilamentC.FilaTexture_setExternalStream(nativeHandle, engine.nativeHandle, stream.nativeHandle)
    }

    actual companion object {
        actual fun isTextureFormatSupported(engine: Engine, format: InternalFormat): Boolean =
            FilamentC.FilaTexture_isTextureFormatSupported(engine.nativeHandle, format.toNative())

        actual fun isTextureFormatMipmappable(engine: Engine, format: InternalFormat): Boolean =
            FilamentC.FilaTexture_isTextureFormatMipmappable(engine.nativeHandle, format.toNative())

        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "always returns false — not bound in filament.js.")
        actual fun isTextureSwizzleSupported(engine: Engine): Boolean =
            FilamentC.FilaTexture_isTextureSwizzleSupported(engine.nativeHandle)

        actual fun validatePixelFormatAndType(internalFormat: InternalFormat, pixelDataFormat: Format, pixelDataType: Type): Boolean =
            FilamentC.FilaTexture_validatePixelFormatAndType(internalFormat.toNative(), pixelDataFormat.toNative(), pixelDataType.toNative())

        actual fun getMaxTextureSize(engine: Engine, type: Sampler): Int =
            FilamentC.FilaTexture_getMaxTextureSize(engine.nativeHandle, type.toNative()).toInt()

        actual fun getMaxArrayTextureLayers(engine: Engine): Int =
            FilamentC.FilaTexture_getMaxArrayTextureLayers(engine.nativeHandle).toInt()

        actual fun computeDataSize(format: Format, type: Type, stride: Int, height: Int, alignment: Int): Int =
            FilamentC.FilaTexture_computeDataSize(format.toNative(), type.toNative(), stride.toLong(), height.toLong(), alignment.toLong()).toInt()
    }
}
