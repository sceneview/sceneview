package io.github.erkko68.filament

/**
 * An offscreen render target that can be associated with a View and contains weak references
 * to a set of attached Texture objects.
 *
 * RenderTarget is intended to be used with the View's post-processing disabled for the most part,
 * especially when a DEPTH attachment is also used. Custom RenderTargets are ultimately intended
 * to render into textures that might be used during the main render pass.
 *
 * Clients are responsible for the lifetime of all associated Texture attachments.
 *
 * @see View
 */
expect class RenderTarget {
    /**
     * Attachment point identifiers for texture attachments in a render target.
     *
     * - COLOR: Identifies the 1st color attachment (default)
     * - COLOR1-COLOR7: Identifies the 2nd through 8th color attachments
     * - DEPTH: Identifies the depth attachment
     *
     * The maximum number of color attachments supported is platform-dependent.
     */
    enum class AttachmentPoint {
        COLOR, COLOR1, COLOR2, COLOR3, COLOR4, COLOR5, COLOR6, COLOR7, DEPTH
    }

    /**
     * Builder for creating RenderTarget instances.
     *
     * Use this to configure texture attachments, mipmap levels, cubemap faces, and layers,
     * then call build() to create the RenderTarget.
     */
    class Builder() {
        /**
         * Sets a texture to a given attachment point.
         *
         * When using a DEPTH attachment, it is important to always disable post-processing
         * in the View. Failing to do so will cause the DEPTH attachment to be ignored in most
         * cases.
         *
         * When the intention is to keep the content of the DEPTH attachment after rendering,
         * the DEPTH attachment must be SAMPLEABLE, otherwise the content of the DEPTH buffer
         * may be discarded.
         *
         * @param attachment The attachment point of the texture
         * @param texture The associated texture object (null to clear)
         * @return This Builder, for chaining calls
         */
        fun texture(attachment: AttachmentPoint, texture: Texture?): Builder

        /**
         * Sets the mipmap level for a given attachment point.
         *
         * @param attachment The attachment point of the texture
         * @param level The associated mipmap level (default: 0)
         * @return This Builder, for chaining calls
         */
        fun mipLevel(attachment: AttachmentPoint, level: Int): Builder

        /**
         * Sets the face for cubemap textures at the given attachment point.
         *
         * @param attachment The attachment point
         * @param face The associated cubemap face
         * @return This Builder, for chaining calls
         */
        fun face(attachment: AttachmentPoint, face: Texture.CubemapFace): Builder

        /**
         * Sets an index of a single layer for 2D array, cubemap array, and 3D textures.
         *
         * For cubemap array textures, layer is translated into an array index and face according to:
         * - index: layer / 6
         * - face: layer % 6
         *
         * @param attachment The attachment point
         * @param layer The associated layer index
         * @return This Builder, for chaining calls
         */
        fun layer(attachment: AttachmentPoint, layer: Int): Builder

        /**
         * Creates the RenderTarget object.
         *
         * @param engine Engine to associate this RenderTarget with
         * @return The newly created RenderTarget
         */
        fun build(engine: Engine): RenderTarget
    }

    /**
     * Gets the texture attached to a given attachment point.
     *
     * @param attachment The attachment point to query
     * @return The attached texture, or null if no texture is attached
     */
    fun getTexture(attachment: AttachmentPoint): Texture?

    /**
     * Gets the mipmap level for a given attachment point.
     *
     * @param attachment The attachment point to query
     * @return The mipmap level (default: 0)
     */
    fun getMipLevel(attachment: AttachmentPoint): Int

    /**
     * Gets the cubemap face for a given attachment point.
     *
     * @param attachment The attachment point to query
     * @return The cubemap face
     */
    fun getFace(attachment: AttachmentPoint): Texture.CubemapFace

    /**
     * Gets the layer index for a given attachment point.
     *
     * @param attachment The attachment point to query
     * @return The layer index
     */
    fun getLayer(attachment: AttachmentPoint): Int
}
