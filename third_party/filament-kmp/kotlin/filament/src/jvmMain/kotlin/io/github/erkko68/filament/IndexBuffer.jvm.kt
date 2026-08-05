package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class IndexBuffer internal constructor(internal var nativeHandle: MemorySegment?) {
    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaIndexBufferBuilder_create()
        actual enum class IndexType { USHORT, UINT }
        actual fun indexCount(indexCount: Int): Builder = apply { FilamentC.FilaIndexBufferBuilder_indexCount(nativeBuilder, indexCount) }
        actual fun bufferType(indexType: IndexType): Builder = apply {
            FilamentC.FilaIndexBufferBuilder_bufferType(nativeBuilder, indexType.ordinal)
        }
        actual fun build(engine: Engine): IndexBuffer = IndexBuffer(FilamentC.FilaIndexBufferBuilder_build(nativeBuilder, engine.nativeHandle))
    }

    actual val indexCount: Int get() = FilamentC.FilaIndexBuffer_getIndexCount(nativeHandle).toInt()

    actual fun setBuffer(engine: Engine, data: ByteArray) {
        setBuffer(engine, data, 0, 0, null)
    }

    actual fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int) {
        setBuffer(engine, data, destOffsetInBytes, count, null)
    }

    actual fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int, callback: (() -> Unit)?) {
        val dataArena = Arena.ofShared()
        val seg = dataArena.bytes(data)
        val size = (if (count > 0) count else data.size).toLong()
        val userData = Completions.register { try { callback?.invoke() } finally { dataArena.close() } }
        FilamentC.FilaIndexBuffer_setBuffer(nativeHandle, engine.nativeHandle, seg, size, destOffsetInBytes, NULL, Completions.bufferStub, userData)
    }
}
