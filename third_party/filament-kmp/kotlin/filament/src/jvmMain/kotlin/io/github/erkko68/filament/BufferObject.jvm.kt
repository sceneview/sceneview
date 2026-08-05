package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class BufferObject internal constructor(internal var nativeHandle: MemorySegment?) {
    actual enum class BindingType {
        VERTEX,
        UNIFORM,
        SHADER_STORAGE;
        internal fun toNative(): Int = ordinal
    }

    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaBufferObjectBuilder_create()

        actual fun size(byteCount: Int): Builder {
            FilamentC.FilaBufferObjectBuilder_size(nativeBuilder, byteCount)
            return this
        }

        actual fun bindingType(bindingType: BindingType): Builder {
            FilamentC.FilaBufferObjectBuilder_bindingType(nativeBuilder, bindingType.toNative())
            return this
        }

        actual fun build(engine: Engine): BufferObject {
            val handle = FilamentC.FilaBufferObjectBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaBufferObjectBuilder_destroy(nativeBuilder)
            return BufferObject(handle)
        }
    }

    actual val byteCount: Int get() = FilamentC.FilaBufferObject_getByteCount(nativeHandle).toInt()

    actual fun setBuffer(engine: Engine, data: ByteArray) {
        setBuffer(engine, data, 0, 0, null)
    }

    actual fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int) {
        setBuffer(engine, data, destOffsetInBytes, count, null)
    }

    actual fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int, callback: (() -> Unit)?) {
        // Data is consumed asynchronously; keep it (and free it) until the completion callback fires.
        val dataArena = Arena.ofShared()
        val seg = dataArena.bytes(data)
        val size = (if (count > 0) count else data.size).toLong()
        val userData = Completions.register { try { callback?.invoke() } finally { dataArena.close() } }
        FilamentC.FilaBufferObject_setBuffer(nativeHandle, engine.nativeHandle, seg, size, destOffsetInBytes, NULL, Completions.bufferStub, userData)
    }
}
