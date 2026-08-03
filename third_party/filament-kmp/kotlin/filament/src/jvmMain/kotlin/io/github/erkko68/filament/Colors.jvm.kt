package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC

actual object Colors {
    actual enum class RgbType {
        SRGB, LINEAR;
        internal fun toNative(): Int = ordinal
    }

    actual enum class RgbaType {
        SRGB, LINEAR, PREMULTIPLIED_SRGB, PREMULTIPLIED_LINEAR;
        internal fun toNative(): Int = ordinal
    }

    actual enum class Conversion {
        ACCURATE, FAST;
        internal fun toNative(): Int = ordinal
    }

    actual fun toLinear(type: RgbType, r: Float, g: Float, b: Float): FloatArray {
        return toLinear(type, floatArrayOf(r, g, b))
    }

    actual fun toLinear(type: RgbType, rgb: FloatArray): FloatArray {
        confined { arena ->
            val out = arena.floatArr(3)
            FilamentC.FilaColors_toLinearRgb(type.toNative(), arena.floats(rgb), out)
            System.arraycopy(out.toFloats(), 0, rgb, 0, 3)
        }
        return rgb
    }

    actual fun toLinear(type: RgbaType, r: Float, g: Float, b: Float, a: Float): FloatArray {
        return toLinear(type, floatArrayOf(r, g, b, a))
    }

    actual fun toLinear(type: RgbaType, rgba: FloatArray): FloatArray {
        confined { arena ->
            val out = arena.floatArr(4)
            FilamentC.FilaColors_toLinearRgba(type.toNative(), arena.floats(rgba), out)
            System.arraycopy(out.toFloats(), 0, rgba, 0, 4)
        }
        return rgba
    }

    actual fun toLinear(conversion: Conversion, rgb: FloatArray): FloatArray {
        confined { arena ->
            val out = arena.floatArr(3)
            FilamentC.FilaColors_toLinearConvert(conversion.toNative(), arena.floats(rgb), out)
            System.arraycopy(out.toFloats(), 0, rgb, 0, 3)
        }
        return rgb
    }

    actual fun cct(temperature: Float): FloatArray {
        return confined { arena ->
            val out = arena.floatArr(3)
            FilamentC.FilaColors_cct(temperature, out)
            out.toFloats()
        }
    }

    actual fun illuminantD(temperature: Float): FloatArray {
        return confined { arena ->
            val out = arena.floatArr(3)
            FilamentC.FilaColors_illuminantD(temperature, out)
            out.toFloats()
        }
    }
}
