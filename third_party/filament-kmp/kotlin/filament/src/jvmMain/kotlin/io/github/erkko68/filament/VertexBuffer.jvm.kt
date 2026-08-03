package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import java.lang.foreign.Arena

actual class VertexBuffer internal constructor(internal var nativeHandle: java.lang.foreign.MemorySegment?) {
    actual enum class VertexAttribute {
        POSITION, TANGENTS, COLOR, UV0, UV1, BONE_INDICES, BONE_WEIGHTS, UNUSED,
        CUSTOM0, CUSTOM1, CUSTOM2, CUSTOM3, CUSTOM4, CUSTOM5, CUSTOM6, CUSTOM7
    }

    actual enum class AttributeType {
        BYTE, BYTE2, BYTE3, BYTE4,
        UBYTE, UBYTE2, UBYTE3, UBYTE4,
        SHORT, SHORT2, SHORT3, SHORT4,
        USHORT, USHORT2, USHORT3, USHORT4,
        INT, UINT,
        FLOAT, FLOAT2, FLOAT3, FLOAT4,
        HALF, HALF2, HALF3, HALF4
    }

    actual class Builder actual constructor() {
        private val nativeBuilder = FilamentC.FilaVertexBufferBuilder_create()

        actual fun vertexCount(vertexCount: Int): Builder {
            FilamentC.FilaVertexBufferBuilder_vertexCount(nativeBuilder, vertexCount)
            return this
        }
        actual fun bufferCount(bufferCount: Int): Builder {
            FilamentC.FilaVertexBufferBuilder_bufferCount(nativeBuilder, bufferCount.toByte())
            return this
        }
        actual fun enableBufferObjects(enabled: Boolean): Builder {
            FilamentC.FilaVertexBufferBuilder_enableBufferObjects(nativeBuilder, enabled)
            return this
        }
        actual fun attribute(attribute: VertexAttribute, bufferIndex: Int, attributeType: AttributeType, byteOffset: Int, byteStride: Int): Builder {
            FilamentC.FilaVertexBufferBuilder_attribute(
                nativeBuilder,
                attribute.ordinal,
                bufferIndex.toByte(),
                attributeType.ordinal,
                byteOffset,
                byteStride.toByte()
            )
            return this
        }
        actual fun normalized(attribute: VertexAttribute, enabled: Boolean): Builder {
            FilamentC.FilaVertexBufferBuilder_normalized(nativeBuilder, attribute.ordinal, enabled)
            return this
        }
        actual fun build(engine: Engine): VertexBuffer {
            val handle = FilamentC.FilaVertexBufferBuilder_build(nativeBuilder, engine.nativeHandle)
            FilamentC.FilaVertexBufferBuilder_destroy(nativeBuilder)
            return VertexBuffer(handle)
        }
    }

    actual val vertexCount: Int get() = FilamentC.FilaVertexBuffer_getVertexCount(nativeHandle).toInt()

    actual fun setBufferAt(engine: Engine, bufferIndex: Int, data: ByteArray) {
        setBufferAt(engine, bufferIndex, data, 0, 0, null)
    }

    actual fun setBufferAt(engine: Engine, bufferIndex: Int, data: ByteArray, destOffsetInBytes: Int, count: Int) {
        setBufferAt(engine, bufferIndex, data, destOffsetInBytes, count, null)
    }

    actual fun setBufferAt(engine: Engine, bufferIndex: Int, data: ByteArray, destOffsetInBytes: Int, count: Int, callback: (() -> Unit)?) {
        val dataArena = Arena.ofShared()
        val seg = dataArena.bytes(data)
        val size = (if (count > 0) count else data.size).toLong()
        val userData = Completions.register { try { callback?.invoke() } finally { dataArena.close() } }
        FilamentC.FilaVertexBuffer_setBufferAt(nativeHandle, engine.nativeHandle, bufferIndex.toByte(), seg, size, destOffsetInBytes, NULL, Completions.bufferStub, userData)
    }

    actual fun setBufferObjectAt(engine: Engine, bufferIndex: Int, bufferObject: BufferObject) {
        FilamentC.FilaVertexBuffer_setBufferObjectAt(nativeHandle, engine.nativeHandle, bufferIndex.toByte(), bufferObject.nativeHandle)
    }
}
