package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class IndirectLight constructor(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        // SH coefficient / rotation arrays are read at build(); keep them in a builder-scoped arena.
        private val arena = Arena.ofConfined()
        private val nativeBuilder = FilamentC.FilaIndirectLightBuilder_create()

        actual fun reflections(cubemap: Texture): Builder = apply { FilamentC.FilaIndirectLightBuilder_reflections(nativeBuilder, cubemap.nativeHandle) }
        actual fun irradiance(bands: Int, sh: FloatArray): Builder = apply {
            FilamentC.FilaIndirectLightBuilder_irradiance(nativeBuilder, bands.toByte(), arena.floats(sh))
        }
        actual fun radiance(bands: Int, sh: FloatArray): Builder = apply {
            FilamentC.FilaIndirectLightBuilder_radiance(nativeBuilder, bands.toByte(), arena.floats(sh))
        }
        actual fun irradiance(cubemap: Texture): Builder = apply { FilamentC.FilaIndirectLightBuilder_irradianceAsTexture(nativeBuilder, cubemap.nativeHandle) }
        actual fun intensity(envIntensity: Float): Builder = apply { FilamentC.FilaIndirectLightBuilder_intensity(nativeBuilder, envIntensity) }
        actual fun rotation(rotation: FloatArray): Builder = apply {
            FilamentC.FilaIndirectLightBuilder_rotation(nativeBuilder, arena.floats(rotation))
        }
        actual fun build(engine: Engine): IndirectLight {
            val handle = FilamentC.FilaIndirectLightBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaIndirectLightBuilder_destroy(nativeBuilder)
            arena.close()
            return IndirectLight(handle)
        }
    }

    actual var intensity: Float
        get() = FilamentC.FilaIndirectLight_getIntensity(nativeHandle)
        set(value) { FilamentC.FilaIndirectLight_setIntensity(nativeHandle, value) }

    actual var rotation: FloatArray
        get() = confined { arena ->
            val out = arena.floatArr(9)
            FilamentC.FilaIndirectLight_getRotation(nativeHandle, out)
            out.toFloats()
        }
        set(value) { confined { arena -> FilamentC.FilaIndirectLight_setRotation(nativeHandle, arena.floats(value)) } }

    actual val reflectionsTexture: Texture? get() = FilamentC.FilaIndirectLight_getReflectionsTexture(nativeHandle).let { if (it.isNullPtr()) null else Texture(it) }
    actual val irradianceTexture: Texture? get() = FilamentC.FilaIndirectLight_getIrradianceTexture(nativeHandle).let { if (it.isNullPtr()) null else Texture(it) }

    actual companion object {
        actual fun getDirectionEstimate(sh: FloatArray, out: FloatArray?): FloatArray {
            val result = out ?: FloatArray(3)
            confined { arena ->
                val outSeg = arena.floatArr(3)
                FilamentC.FilaIndirectLight_getDirectionEstimateStatic(arena.floats(sh), outSeg)
                System.arraycopy(outSeg.toFloats(), 0, result, 0, 3)
            }
            return result
        }

        actual fun getColorEstimate(sh: FloatArray, x: Double, y: Double, z: Double, out: FloatArray?): FloatArray {
            val result = out ?: FloatArray(4)
            confined { arena ->
                val outSeg = arena.floatArr(4)
                FilamentC.FilaIndirectLight_getColorEstimateStatic(arena.floats(sh), x.toFloat(), y.toFloat(), z.toFloat(), outSeg)
                System.arraycopy(outSeg.toFloats(), 0, result, 0, 4)
            }
            return result
        }
    }
}
