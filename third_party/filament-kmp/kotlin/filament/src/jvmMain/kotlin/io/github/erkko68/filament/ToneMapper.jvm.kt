package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

actual open class ToneMapper(internal val nativeHandle: MemorySegment?) {
    actual class Linear actual constructor() : ToneMapper(FilamentC.FilaToneMapper_Linear())
    actual class ACES actual constructor() : ToneMapper(FilamentC.FilaToneMapper_ACES())
    actual class ACESLegacy actual constructor() : ToneMapper(FilamentC.FilaToneMapper_ACESLegacy())
    actual class Filmic actual constructor() : ToneMapper(FilamentC.FilaToneMapper_Filmic())
    actual class PBRNeutralToneMapper actual constructor() : ToneMapper(FilamentC.FilaToneMapper_PBRNeutral())
    actual class GT7ToneMapper actual constructor() : ToneMapper(FilamentC.FilaToneMapper_GT7())

    actual class Agx actual constructor(look: AgxLook) : ToneMapper(
        FilamentC.FilaToneMapper_Agx(look.ordinal)
    ) {
        actual enum class AgxLook { NONE, PUNCHY, GOLDEN }
    }

    actual class Generic actual constructor(
        contrast: Float,
        midGrayIn: Float,
        midGrayOut: Float,
        hdrMax: Float
    ) : ToneMapper(FilamentC.FilaToneMapper_Generic(contrast, midGrayIn, midGrayOut, hdrMax)) {
        actual var contrast: Float
            get() = FilamentC.FilaToneMapper_Generic_getContrast(nativeHandle)
            set(value) { FilamentC.FilaToneMapper_Generic_setContrast(nativeHandle, value) }
        actual var midGrayIn: Float
            get() = FilamentC.FilaToneMapper_Generic_getMidGrayIn(nativeHandle)
            set(value) { FilamentC.FilaToneMapper_Generic_setMidGrayIn(nativeHandle, value) }
        actual var midGrayOut: Float
            get() = FilamentC.FilaToneMapper_Generic_getMidGrayOut(nativeHandle)
            set(value) { FilamentC.FilaToneMapper_Generic_setMidGrayOut(nativeHandle, value) }
        actual var hdrMax: Float
            get() = FilamentC.FilaToneMapper_Generic_getHdrMax(nativeHandle)
            set(value) { FilamentC.FilaToneMapper_Generic_setHdrMax(nativeHandle, value) }
    }

    actual class DisplayRange actual constructor() : ToneMapper(FilamentC.FilaToneMapper_DisplayRange())
}
