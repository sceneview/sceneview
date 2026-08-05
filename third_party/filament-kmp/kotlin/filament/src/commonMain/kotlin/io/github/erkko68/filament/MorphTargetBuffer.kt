package io.github.erkko68.filament

/**
 * A container for vertex morphing data that supports both automatic and manual morphing.
 *
 * MorphTargetBuffer operates in a hybrid model depending on the attribute being morphed:
 *
 * **1. Automatic for Built-ins (positions/tangents):**
 * Enable via `withPositions(true)` or `withTangents(true)`. The MorphTargetBuffer will
 * allocate internal storage and hold the data for these attributes, which you upload
 * via `setPositionsAt()` or `setTangentsAt()`. The framework automatically applies
 * the morphing logic in the vertex shader.
 *
 * **2. Manual for Custom Data (e.g., UVs, colors):**
 * The MorphTargetBuffer does NOT hold data for custom targets. The user is responsible
 * for the full data pipeline:
 * - Create and manage a separate Texture to hold the morph target data (offsets).
 * - In the material, declare a `sampler2d_array` parameter.
 * - Bind the Texture to the material instance.
 * - In the vertex shader, manually call `morphData2`, `morphData3`, or `morphData4`
 *   with the custom sampler to apply the morphing.
 *
 * A MorphTargetBuffer object must be associated with a Renderable via
 * RenderableManager.Builder.morphing() to enable the morphing pipeline.
 *
 * @see RenderableManager
 */
@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "Builder.build throws UnsupportedOperationException — the standalone API is unbound in filament.js; gltfio handles glTF morph targets internally.")
expect class MorphTargetBuffer {
    /**
     * Builder for creating MorphTargetBuffer instances.
     */
    class Builder() {
        /**
         * Sets the size of the morph targets in vertex counts.
         *
         * @param vertexCount Number of vertices the morph targets can hold.
         * @return This Builder, for chaining calls.
         */
        fun vertexCount(vertexCount: Int): Builder

        /**
         * Sets the number of morph targets to allocate.
         *
         * @param count Number of morph targets (e.g., blend shapes) to allocate.
         * @return This Builder, for chaining calls.
         */
        fun count(count: Int): Builder

        /**
         * Enable automatic morphing for positions.
         *
         * When enabled, the MorphTargetBuffer allocates internal storage for position
         * morph targets. Upload data via setPositionsAt().
         *
         * @param enabled true to enable position morphing, false to disable.
         * @return This Builder, for chaining calls.
         */
        fun withPositions(enabled: Boolean = true): Builder

        /**
         * Enable automatic morphing for tangents.
         *
         * When enabled, the MorphTargetBuffer allocates internal storage for tangent
         * morph targets. Upload data via setTangentsAt().
         *
         * @param enabled true to enable tangent morphing, false to disable.
         * @return This Builder, for chaining calls.
         */
        fun withTangents(enabled: Boolean = true): Builder

        /**
         * Enable the custom morphing pipeline for user-defined attributes.
         *
         * When enabled, you can supply custom morph target data via a Texture and
         * apply it manually in the vertex shader.
         *
         * @param enabled true to enable custom morphing, false to disable.
         * @return This Builder, for chaining calls.
         */
        fun enableCustomMorphing(enabled: Boolean = true): Builder

        /**
         * Creates the MorphTargetBuffer and associates it with the given Engine.
         *
         * @param engine Engine to associate this MorphTargetBuffer with.
         * @return The newly created MorphTargetBuffer.
         * @throws UnsupportedOperationException on JS — MorphTargetBuffer is unbound in the web wrapper.
         */
        fun build(engine: Engine): MorphTargetBuffer
    }

    /**
     * Gets the number of vertices this MorphTargetBuffer can hold.
     *
     * @return Vertex count capacity.
     */
    val vertexCount: Int

    /**
     * Gets the number of morph targets (blend shapes) allocated.
     *
     * @return Number of morph targets.
     */
    val count: Int

    /**
     * Indicates whether this buffer supports automatic position morphing.
     *
     * @return true if position morphing is enabled, false otherwise.
     */
    val hasPositions: Boolean

    /**
     * Indicates whether this buffer supports automatic tangent morphing.
     *
     * @return true if tangent morphing is enabled, false otherwise.
     */
    val hasTangents: Boolean

    /**
     * Indicates whether custom morphing is enabled for user-defined attributes.
     *
     * @return true if custom morphing is enabled, false otherwise.
     */
    val isCustomMorphingEnabled: Boolean

    /**
     * Upload position data for a specific morph target.
     *
     * The positions array must contain `count` vertices, with 3 floats per vertex (x, y, z).
     *
     * @param engine The Engine instance.
     * @param targetIndex Zero-based index of the morph target to update (< count).
     * @param positions Array of position offsets (3 floats per vertex).
     * @param count Number of vertices being updated.
     */
    fun setPositionsAt(engine: Engine, targetIndex: Int, positions: FloatArray, count: Int)

    /**
     * Upload tangent data for a specific morph target.
     *
     * The tangents array must contain `count` vertices, with 2 shorts per vertex (quaternion encoded).
     *
     * @param engine The Engine instance.
     * @param targetIndex Zero-based index of the morph target to update (< count).
     * @param tangents Array of tangent offsets (encoded as quaternions).
     * @param count Number of vertices being updated.
     */
    fun setTangentsAt(engine: Engine, targetIndex: Int, tangents: ShortArray, count: Int)
}
