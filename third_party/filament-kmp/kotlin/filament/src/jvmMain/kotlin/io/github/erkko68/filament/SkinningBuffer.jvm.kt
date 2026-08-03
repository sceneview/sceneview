package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.MemorySegment

@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "unbound in filament.js — Builder.build throws and setBonesAt is a silent no-op; glTF skinning still works through gltfio.")
actual class SkinningBuffer internal constructor(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaSkinningBufferBuilder_create()

        actual fun boneCount(boneCount: Int): Builder {
            FilamentC.FilaSkinningBufferBuilder_boneCount(nativeBuilder, boneCount)
            return this
        }

        actual fun initialize(initialize: Boolean): Builder {
            FilamentC.FilaSkinningBufferBuilder_initialize(nativeBuilder, initialize)
            return this
        }

        actual fun build(engine: Engine): SkinningBuffer {
            val handle = FilamentC.FilaSkinningBufferBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaSkinningBufferBuilder_destroy(nativeBuilder)
            return SkinningBuffer(handle)
        }
    }

    actual val boneCount: Int get() = FilamentC.FilaSkinningBuffer_getBoneCount(nativeHandle).toInt()

    actual fun setBonesAsMatrices(engine: Engine, matrices: FloatArray, boneCount: Int, offset: Int) {
        confined { arena ->
            FilamentC.FilaSkinningBuffer_setBonesMat4f(
                nativeHandle, engine.nativeHandle,
                arena.floats(matrices),
                boneCount.toLong(), offset.toLong()
            )
        }
    }

    actual fun setBonesAsQuaternions(engine: Engine, bones: FloatArray, boneCount: Int, offset: Int) {
        // Each bone is 8 floats: [qx,qy,qz,qw, tx,ty,tz,1] — matches FilaBone memory layout.
        confined { arena ->
            FilamentC.FilaSkinningBuffer_setBonesQuaternions(
                nativeHandle, engine.nativeHandle,
                arena.floats(bones),
                boneCount.toLong(), offset.toLong()
            )
        }
    }
}
