package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC

actual class TextureSampler {
    // FilaTextureSampler is a uint64 value type (packed sampler params), not a pointer.
    internal var nativeHandle: Long = 0L

    actual enum class WrapMode {
        CLAMP_TO_EDGE,
        REPEAT,
        MIRRORED_REPEAT
    }

    actual enum class MinFilter {
        NEAREST,
        LINEAR,
        NEAREST_MIPMAP_NEAREST,
        LINEAR_MIPMAP_NEAREST,
        NEAREST_MIPMAP_LINEAR,
        LINEAR_MIPMAP_LINEAR,
    }

    actual enum class MagFilter {
        NEAREST,
        LINEAR
    }

    actual enum class CompareMode {
        NONE,
        COMPARE_TO_TEXTURE
    }

    actual enum class CompareFunction {
        LESS_EQUAL,
        GREATER_EQUAL,
        LESS,
        GREATER,
        EQUAL,
        NOT_EQUAL,
        ALWAYS,
        NEVER
    }

    actual constructor() {
        nativeHandle = FilamentC.FilaTextureSampler_create(
            MinFilter.LINEAR_MIPMAP_LINEAR.toFila(),
            MagFilter.LINEAR.toFila(),
            WrapMode.REPEAT.toFila(),
            WrapMode.REPEAT.toFila(),
            WrapMode.REPEAT.toFila()
        )
    }

    actual constructor(minMag: MagFilter) {
        val min = if (minMag == MagFilter.NEAREST) MinFilter.NEAREST else MinFilter.LINEAR
        nativeHandle = FilamentC.FilaTextureSampler_create(
            min.toFila(),
            minMag.toFila(),
            WrapMode.CLAMP_TO_EDGE.toFila(),
            WrapMode.CLAMP_TO_EDGE.toFila(),
            WrapMode.CLAMP_TO_EDGE.toFila()
        )
    }

    actual constructor(minMag: MagFilter, wrap: WrapMode) {
        val min = if (minMag == MagFilter.NEAREST) MinFilter.NEAREST else MinFilter.LINEAR
        nativeHandle = FilamentC.FilaTextureSampler_create(
            min.toFila(),
            minMag.toFila(),
            wrap.toFila(),
            wrap.toFila(),
            wrap.toFila()
        )
    }

    actual constructor(min: MinFilter, mag: MagFilter, wrap: WrapMode) {
        nativeHandle = FilamentC.FilaTextureSampler_create(
            min.toFila(),
            mag.toFila(),
            wrap.toFila(),
            wrap.toFila(),
            wrap.toFila()
        )
    }

    actual constructor(min: MinFilter, mag: MagFilter, s: WrapMode, t: WrapMode, r: WrapMode) {
        nativeHandle = FilamentC.FilaTextureSampler_create(
            min.toFila(),
            mag.toFila(),
            s.toFila(),
            t.toFila(),
            r.toFila()
        )
    }

    actual constructor(mode: CompareMode) {
        nativeHandle = FilamentC.FilaTextureSampler_createCompare(mode.toFila(), CompareFunction.LESS_EQUAL.toFila())
    }

    actual constructor(mode: CompareMode, function: CompareFunction) {
        nativeHandle = FilamentC.FilaTextureSampler_createCompare(mode.toFila(), function.toFila())
    }

    internal constructor(sampler: Long) {
        nativeHandle = sampler
    }

    actual var minFilter: MinFilter
        get() = MinFilter.entries[FilamentC.FilaTextureSampler_getMinFilter(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setMinFilter(nativeHandle, value.toFila()) }

    actual var magFilter: MagFilter
        get() = MagFilter.entries[FilamentC.FilaTextureSampler_getMagFilter(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setMagFilter(nativeHandle, value.toFila()) }

    actual var wrapModeS: WrapMode
        get() = WrapMode.entries[FilamentC.FilaTextureSampler_getWrapModeS(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setWrapModeS(nativeHandle, value.toFila()) }

    actual var wrapModeT: WrapMode
        get() = WrapMode.entries[FilamentC.FilaTextureSampler_getWrapModeT(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setWrapModeT(nativeHandle, value.toFila()) }

    actual var wrapModeR: WrapMode
        get() = WrapMode.entries[FilamentC.FilaTextureSampler_getWrapModeR(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setWrapModeR(nativeHandle, value.toFila()) }

    actual var anisotropy: Float
        get() = FilamentC.FilaTextureSampler_getAnisotropy(nativeHandle)
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setAnisotropy(nativeHandle, value) }

    actual var compareMode: CompareMode
        get() = CompareMode.entries[FilamentC.FilaTextureSampler_getCompareMode(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setCompareMode(nativeHandle, value.toFila()) }

    actual var compareFunction: CompareFunction
        get() = CompareFunction.entries[FilamentC.FilaTextureSampler_getCompareFunction(nativeHandle)]
        set(value) { nativeHandle = FilamentC.FilaTextureSampler_setCompareFunction(nativeHandle, value.toFila()) }

    private fun WrapMode.toFila(): Int = this.ordinal
    private fun MinFilter.toFila(): Int = this.ordinal
    private fun MagFilter.toFila(): Int = this.ordinal
    private fun CompareMode.toFila(): Int = this.ordinal
    private fun CompareFunction.toFila(): Int = this.ordinal
}
