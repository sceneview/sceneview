package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual class ColorGrading internal constructor(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        internal val nativeHandle: MemorySegment = FilamentC.FilaColorGradingBuilder_create()

        actual fun quality(qualityLevel: QualityLevel): Builder {
            FilamentC.FilaColorGradingBuilder_quality(nativeHandle, qualityLevel.ordinal)
            return this
        }

        actual fun format(format: LutFormat): Builder {
            FilamentC.FilaColorGradingBuilder_format(nativeHandle, format.ordinal)
            return this
        }

        actual fun dimensions(dim: Int): Builder {
            FilamentC.FilaColorGradingBuilder_dimensions(nativeHandle, dim.toByte())
            return this
        }

        actual fun toneMapper(toneMapper: ToneMapper): Builder {
            FilamentC.FilaColorGradingBuilder_toneMapper(nativeHandle, toneMapper.nativeHandle)
            return this
        }

        actual fun luminanceScaling(luminanceScaling: Boolean): Builder {
            FilamentC.FilaColorGradingBuilder_luminanceScaling(nativeHandle, luminanceScaling)
            return this
        }

        actual fun gamutMapping(gamutMapping: Boolean): Builder {
            FilamentC.FilaColorGradingBuilder_gamutMapping(nativeHandle, gamutMapping)
            return this
        }

        actual fun exposure(exposure: Float): Builder {
            FilamentC.FilaColorGradingBuilder_exposure(nativeHandle, exposure)
            return this
        }

        actual fun nightAdaptation(adaptation: Float): Builder {
            FilamentC.FilaColorGradingBuilder_nightAdaptation(nativeHandle, adaptation)
            return this
        }

        actual fun whiteBalance(temperature: Float, tint: Float): Builder {
            FilamentC.FilaColorGradingBuilder_whiteBalance(nativeHandle, temperature, tint)
            return this
        }

        actual fun channelMixer(outRed: FloatArray, outGreen: FloatArray, outBlue: FloatArray): Builder {
            confined { arena ->
                FilamentC.FilaColorGradingBuilder_channelMixer(nativeHandle, arena.floats(outRed), arena.floats(outGreen), arena.floats(outBlue))
            }
            return this
        }

        actual fun shadowsMidtonesHighlights(shadows: FloatArray, midtones: FloatArray, highlights: FloatArray, ranges: FloatArray): Builder {
            confined { arena ->
                FilamentC.FilaColorGradingBuilder_shadowsMidtonesHighlights(nativeHandle, arena.floats(shadows), arena.floats(midtones), arena.floats(highlights), arena.floats(ranges))
            }
            return this
        }

        actual fun slopeOffsetPower(slope: FloatArray, offset: FloatArray, power: FloatArray): Builder {
            confined { arena ->
                FilamentC.FilaColorGradingBuilder_slopeOffsetPower(nativeHandle, arena.floats(slope), arena.floats(offset), arena.floats(power))
            }
            return this
        }

        actual fun contrast(contrast: Float): Builder {
            FilamentC.FilaColorGradingBuilder_contrast(nativeHandle, contrast)
            return this
        }

        actual fun vibrance(vibrance: Float): Builder {
            FilamentC.FilaColorGradingBuilder_vibrance(nativeHandle, vibrance)
            return this
        }

        actual fun saturation(saturation: Float): Builder {
            FilamentC.FilaColorGradingBuilder_saturation(nativeHandle, saturation)
            return this
        }

        actual fun curves(shadowGamma: FloatArray, midPoint: FloatArray, highlightScale: FloatArray): Builder {
            confined { arena ->
                FilamentC.FilaColorGradingBuilder_curves(nativeHandle, arena.floats(shadowGamma), arena.floats(midPoint), arena.floats(highlightScale))
            }
            return this
        }

        actual fun customLut(data: FloatArray, dimension: Int): Builder {
            confined { arena -> FilamentC.FilaColorGradingBuilder_customLut(nativeHandle, arena.floats(data), dimension.toByte()) }
            return this
        }

        actual fun fastMath(fastMath: Boolean): Builder {
            FilamentC.FilaColorGradingBuilder_fastMath(nativeHandle, fastMath)
            return this
        }

        actual fun build(engine: Engine): ColorGrading {
            return ColorGrading(FilamentC.FilaColorGradingBuilder_build(nativeHandle, engine.nativeHandle))
        }
    }

    actual enum class QualityLevel { LOW, MEDIUM, HIGH, ULTRA }
    actual enum class LutFormat { INTEGER, FLOAT }
}
