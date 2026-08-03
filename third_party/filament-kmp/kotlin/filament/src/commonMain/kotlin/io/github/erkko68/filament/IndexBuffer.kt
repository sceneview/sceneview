package io.github.erkko68.filament

/**
 * A buffer containing vertex indices into a VertexBuffer.
 *
 * IndexBuffer holds a sequence of indices that reference vertices in an associated VertexBuffer.
 * Indices can be 16-bit (USHORT) or 32-bit (UINT). Typically used in conjunction with
 * RenderableManager to define the geometry of primitives.
 *
 * @see VertexBuffer, RenderableManager
 */
expect class IndexBuffer {
    /**
     * Builder for creating IndexBuffer instances.
     *
     * Configure the index count and type (16-bit or 32-bit), then call build() to create
     * the IndexBuffer.
     */
    class Builder() {
        /**
         * Index data type options.
         *
         * - USHORT: 16-bit unsigned integers (indices 0-65535)
         * - UINT: 32-bit unsigned integers (indices 0-4294967295)
         */
        enum class IndexType {
            USHORT,
            UINT,
        }

        /**
         * Sets the number of indices in this buffer.
         *
         * @param indexCount Number of indices
         * @return This Builder, for chaining calls
         */
        fun indexCount(indexCount: Int): Builder

        /**
         * Sets the index data type (USHORT for 16-bit or UINT for 32-bit).
         *
         * @param indexType The index data type (default: USHORT)
         * @return This Builder, for chaining calls
         */
        fun bufferType(indexType: IndexType): Builder

        /**
         * Creates the IndexBuffer object.
         *
         * @param engine Engine to associate this IndexBuffer with
         * @return The newly created IndexBuffer
         */
        fun build(engine: Engine): IndexBuffer
    }

    /**
     * Gets the number of indices in this buffer.
     *
     * @return The index count
     */
    val indexCount: Int

    /**
     * Sets the index data for this buffer.
     *
     * @param engine The engine
     * @param data The index data as a ByteArray
     */
    fun setBuffer(engine: Engine, data: ByteArray)

    /**
     * Sets the index data with offset and count.
     *
     * @param engine The engine
     * @param data The index data as a ByteArray
     * @param destOffsetInBytes Destination offset in bytes
     * @param count Number of bytes to copy
     */
    fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int)

    /**
     * Sets the index data with offset, count, and optional completion callback.
     *
     * @param engine The engine
     * @param data The index data as a ByteArray
     * @param destOffsetInBytes Destination offset in bytes
     * @param count Number of bytes to copy
     * @param callback Optional callback that executes when the data upload is complete
     */
    fun setBuffer(engine: Engine, data: ByteArray, destOffsetInBytes: Int, count: Int, callback: (() -> Unit)? = null)
}
