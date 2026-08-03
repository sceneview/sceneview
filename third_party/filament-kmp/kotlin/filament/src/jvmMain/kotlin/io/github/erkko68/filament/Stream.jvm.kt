package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException on construction — Stream is not bound in filament.js; external/native video streams have no web equivalent.")
actual class Stream internal constructor(internal var nativeHandle: MemorySegment?) {
    actual enum class StreamType {
        NATIVE,
        ACQUIRED
    }

    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaStreamBuilder_create()

        actual fun width(width: Int): Builder {
            FilamentC.FilaStreamBuilder_width(nativeBuilder, width)
            return this
        }

        actual fun height(height: Int): Builder {
            FilamentC.FilaStreamBuilder_height(nativeBuilder, height)
            return this
        }

        actual fun build(engine: Engine): Stream {
            val handle = FilamentC.FilaStreamBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaStreamBuilder_destroy(nativeBuilder)
            return Stream(handle)
        }
    }

    actual val streamType: StreamType get() = StreamType.values()[FilamentC.FilaStream_getStreamType(nativeHandle)]

    actual fun setDimensions(width: Int, height: Int) {
        FilamentC.FilaStream_setDimensions(nativeHandle, width, height)
    }

    actual val timestamp: Long get() = FilamentC.FilaStream_getTimestamp(nativeHandle)
}
