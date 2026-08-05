package io.github.erkko68.filament

/**
 * A generic GPU buffer for storing data.
 *
 * BufferObject usage is optional and mainly useful when you need to share data between multiple
 * VertexBuffer instances. It also allows you to efficiently swap-out buffers in VertexBuffer.
 *
 * For simple use cases where you don't need to share data, it is not necessary to use BufferObject.
 * The buffer is created uninitialized; use setBuffer() to initialize it.
 *
 * @see VertexBuffer, IndexBuffer
 */
expect class BufferObject {
    /**
     * Binding type options for BufferObject.
     *
     * Distinguishes between different GPU buffer binding points:
     * - VERTEX: Vertex buffer (used with VertexBuffer)
     * - UNIFORM: Uniform/constant buffer
     * - SHADER_STORAGE: Shader storage buffer (for compute)
     */
    enum class BindingType {
        VERTEX,
        UNIFORM,
        SHADER_STORAGE,
    }

    /**
     * Builder for creating BufferObject instances.
     *
     * Configure the buffer size and binding type, then call build() to create the BufferObject.
     */
    class Builder() {
        /**
         * Sets the size of this buffer in bytes.
         *
         * @param byteCount Maximum number of bytes the BufferObject can hold
         * @return This Builder, for chaining calls
         */
        fun size(byteCount: Int): Builder

        /**
         * Sets the binding type for this buffer object.
         *
         * Distinguishes between different buffer types (VERTEX, UNIFORM, SHADER_STORAGE).
         * Default: VERTEX.
         *
         * @param bindingType The binding type for this buffer
         * @return This Builder, for chaining calls
         */
        fun bindingType(bindingType: BindingType): Builder

        /**
         * Creates the BufferObject.
         *
         * After creation, the buffer object is uninitialized. Use setBuffer() to initialize it.
         *
         * @param engine Engine to associate this BufferObject with
         * @return The newly created BufferObject
         */
        fun build(engine: Engine): BufferObject
    }

    /**
     * Gets the size of this BufferObject in bytes.
     *
     * @return The maximum capacity of the BufferObject in bytes
     */
    val byteCount: Int

    /**
     * Asynchronously copies data to initialize a region of this BufferObject.
     *
     * @param engine The engine
     * @param data The data to copy
     */
    fun setBuffer(engine: Engine, data: ByteArray)

    /**
     * Asynchronously copies data to a region of this BufferObject with offset and count.
     *
     * @param engine The engine
     * @param data The data to copy
     * @param destOffsetInBytes Destination offset in bytes (must be a multiple of 4)
     * @param count Number of bytes to copy
     */
    fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int)

    /**
     * Asynchronously copies data to a region of this BufferObject with callback.
     *
     * @param engine The engine
     * @param data The data to copy
     * @param destOffsetInBytes Destination offset in bytes (must be a multiple of 4)
     * @param count Number of bytes to copy
     * @param callback Optional callback that executes when the data upload is complete
     */
    fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int, callback: (() -> Unit)? = null)
}
