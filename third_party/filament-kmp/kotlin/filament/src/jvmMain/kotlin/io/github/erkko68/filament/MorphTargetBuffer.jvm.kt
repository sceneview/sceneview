package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "Builder.build throws UnsupportedOperationException — the standalone API is unbound in filament.js; gltfio handles glTF morph targets internally.")
actual class MorphTargetBuffer internal constructor(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaMorphTargetBufferBuilder_create()

        actual fun vertexCount(vertexCount: Int): Builder {
            FilamentC.FilaMorphTargetBufferBuilder_vertexCount(nativeBuilder, vertexCount.toLong())
            return this
        }

        actual fun count(count: Int): Builder {
            FilamentC.FilaMorphTargetBufferBuilder_count(nativeBuilder, count.toLong())
            return this
        }

        actual fun withPositions(enabled: Boolean): Builder {
            FilamentC.FilaMorphTargetBufferBuilder_withPositions(nativeBuilder, enabled)
            return this
        }

        actual fun withTangents(enabled: Boolean): Builder {
            FilamentC.FilaMorphTargetBufferBuilder_withTangents(nativeBuilder, enabled)
            return this
        }

        actual fun enableCustomMorphing(enabled: Boolean): Builder {
            FilamentC.FilaMorphTargetBufferBuilder_enableCustomMorphing(nativeBuilder, enabled)
            return this
        }

        actual fun build(engine: Engine): MorphTargetBuffer {
            val handle = FilamentC.FilaMorphTargetBufferBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaMorphTargetBufferBuilder_destroy(nativeBuilder)
            return MorphTargetBuffer(handle)
        }
    }

    actual val vertexCount: Int get() = FilamentC.FilaMorphTargetBuffer_getVertexCount(nativeHandle).toInt()
    actual val count: Int get() = FilamentC.FilaMorphTargetBuffer_getCount(nativeHandle).toInt()
    actual val hasPositions: Boolean get() = FilamentC.FilaMorphTargetBuffer_hasPositions(nativeHandle)
    actual val hasTangents: Boolean get() = FilamentC.FilaMorphTargetBuffer_hasTangents(nativeHandle)
    actual val isCustomMorphingEnabled: Boolean get() = FilamentC.FilaMorphTargetBuffer_isCustomMorphingEnabled(nativeHandle)

    actual fun setPositionsAt(engine: Engine, targetIndex: Int, positions: FloatArray, count: Int) {
        confined { arena ->
            FilamentC.FilaMorphTargetBuffer_setPositionsAt(
                nativeHandle,
                engine.nativeHandle,
                targetIndex.toLong(),
                arena.floats(positions),
                count.toLong()
            )
        }
    }

    actual fun setTangentsAt(engine: Engine, targetIndex: Int, tangents: ShortArray, count: Int) {
        confined { arena ->
            FilamentC.FilaMorphTargetBuffer_setTangentsAt(
                nativeHandle,
                engine.nativeHandle,
                targetIndex.toLong(),
                arena.shorts(tangents),
                count.toLong()
            )
        }
    }
}
