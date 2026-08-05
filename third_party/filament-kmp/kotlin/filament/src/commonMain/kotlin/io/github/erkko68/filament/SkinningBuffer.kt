package io.github.erkko68.filament

/**
 * SkinningBuffer is used to hold skinning data (bones) for skeletal animation.
 *
 * SkinningBuffer is a simple wrapper around a structured Uniform Buffer Object (UBO) that stores
 * bone transformations. It is used by RenderableManager to deform vertices according to bone
 * transformations and weights during skeletal animation (rigged animations).
 *
 * **Important constraint:** Due to GLSL limitations, the SkinningBuffer size must always be a
 * multiple of 256 bones. The builder automatically adjusts the requested bone count to meet this
 * requirement, which may cause some memory overhead. This overhead can be mitigated by using the
 * same SkinningBuffer to store bone information for multiple RenderPrimitives.
 *
 * @see RenderableManager.setSkinningBuffer
 */
@PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "unbound in filament.js — Builder.build throws and setBonesAt is a silent no-op; glTF skinning still works through gltfio.")
expect class SkinningBuffer {
    /**
     * Builder for creating SkinningBuffer instances.
     *
     * Configure the number of bones and optional pre-initialization, then call build() to create
     * the SkinningBuffer.
     */
    class Builder() {
        /**
         * Sets the number of bones in this buffer.
         *
         * The buffer size will be automatically adjusted to the nearest multiple of 256 bones
         * due to GLSL constraints.
         *
         * @param boneCount Number of bones to allocate
         * @return This Builder, for chaining calls
         */
        fun boneCount(boneCount: Int): Builder

        /**
         * Sets whether to initialize the buffer with identity bones.
         *
         * When true, the new buffer is created with all bones set to identity transforms.
         * When false (default), the buffer is created uninitialized.
         *
         * @param initialize true to initialize with identity bones, false to leave uninitialized
         * @return This Builder, for chaining calls
         */
        fun initialize(initialize: Boolean): Builder

        /**
         * Creates the SkinningBuffer object.
         *
         * @param engine Engine to associate this SkinningBuffer with
         * @return The newly created SkinningBuffer
         * @throws UnsupportedOperationException on JS — SkinningBuffer is unbound in the web wrapper.
         */
        fun build(engine: Engine): SkinningBuffer
    }

    /**
     * Gets the number of bones in this buffer.
     *
     * @return The bone count (adjusted to nearest multiple of 256)
     */
    val boneCount: Int

    /**
     * Updates bone transforms in the range [offset, offset + boneCount).
     *
     * Each bone is specified as a 4x4 transformation matrix in row-major order.
     *
     * @param engine The engine
     * @param matrices Array of 4x4 matrices (16 floats per matrix)
     * @param boneCount Number of bones to set
     * @param offset Offset in elements (not bytes) in the SkinningBuffer (default: 0)
     */
    fun setBonesAsMatrices(engine: Engine, matrices: FloatArray, boneCount: Int, offset: Int)

    /**
     * Updates bone transforms in the range [offset, offset + boneCount) using quaternion+translation format.
     *
     * Each bone is specified as a quaternion (4 floats: x, y, z, w) followed by a translation (3 floats: x, y, z).
     * This format is more compact than 4x4 matrices (7 floats per bone vs 16).
     *
     * @param engine The engine
     * @param bones Array of quaternions and translations (7 floats per bone: qx, qy, qz, qw, tx, ty, tz)
     * @param boneCount Number of bones to set
     * @param offset Offset in elements (not bytes) in the SkinningBuffer (default: 0)
     */
    fun setBonesAsQuaternions(engine: Engine, bones: FloatArray, boneCount: Int, offset: Int)
}
