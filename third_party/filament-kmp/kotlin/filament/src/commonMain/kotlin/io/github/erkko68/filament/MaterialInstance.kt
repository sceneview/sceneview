package io.github.erkko68.filament

/**
 * MaterialInstance customizes the parameters of a Material for per-object rendering.
 *
 * Each Material can spawn multiple MaterialInstances with different parameter values
 * (colors, textures, numeric uniforms, etc.). Changes to a MaterialInstance only affect
 * renderables using that specific instance, not the Material or other instances.
 *
 * **Creating and destroying:**
 * Create instances via Material.createInstance() and destroy with Engine.destroy(instance).
 * You can duplicate an existing instance using the companion object's duplicate() method.
 *
 * **Setting parameters:**
 * Use setParameter() overloads to set uniforms (booleans, floats, vectors, matrices, textures).
 * Parameter names and types must match those defined in the material. Array parameters are
 * also supported via setParameter(name, type, array, offset, count) variants.
 *
 * **Rendering state customization:**
 * Each MaterialInstance can override per-instance rendering behavior:
 * - Scissor rectangle for pixel-perfect clipping
 * - Polygon offset for depth artifacts
 * - Culling mode (override material's cull setting)
 * - Depth test configuration
 * - Stencil test and operations
 * - Color and depth write masks
 *
 * These settings override the Material's defaults.
 *
 * @see Material
 * @see Material.createInstance
 */
expect class MaterialInstance {
    /**
     * Element types for boolean parameter arrays.
     *
     * - BOOL: Single boolean (true/false)
     * - BOOL2/BOOL3/BOOL4: 2, 3, or 4 component boolean vectors
     */
    enum class BooleanElement { BOOL, BOOL2, BOOL3, BOOL4 }

    /**
     * Element types for integer parameter arrays.
     *
     * - INT: Single 32-bit signed integer
     * - INT2/INT3/INT4: 2, 3, or 4 component integer vectors
     */
    enum class IntElement { INT, INT2, INT3, INT4 }

    /**
     * Element types for floating-point parameter arrays.
     *
     * - FLOAT: Single 32-bit float
     * - FLOAT2/FLOAT3/FLOAT4: 2, 3, or 4 component float vectors
     * - MAT3/MAT4: 3x3 or 4x4 floating-point matrices
     */
    enum class FloatElement { FLOAT, FLOAT2, FLOAT3, FLOAT4, MAT3, MAT4 }

    /**
     * Stencil test operation determines how the stencil buffer is modified.
     *
     * - KEEP: Keep the existing stencil value
     * - ZERO: Clear stencil to 0
     * - REPLACE: Replace with reference value
     * - INCR_CLAMP: Increment and clamp to max
     * - INCR_WRAP: Increment and wrap to 0
     * - DECR_CLAMP: Decrement and clamp to 0
     * - DECR_WRAP: Decrement and wrap to max
     * - INVERT: Bitwise invert stencil value
     */
    enum class StencilOperation { KEEP, ZERO, REPLACE, INCR_CLAMP, INCR_WRAP, DECR_CLAMP, DECR_WRAP, INVERT }

    /**
     * Which face(s) the stencil operation applies to.
     *
     * - FRONT: Front-facing primitives only
     * - BACK: Back-facing primitives only
     * - FRONT_AND_BACK: Both front and back faces
     */
    enum class StencilFace { FRONT, BACK, FRONT_AND_BACK }

    companion object {
        /**
         * Create a new MaterialInstance by duplicating an existing one.
         *
         * This is useful for creating instances with the same initial parameters as an
         * existing instance without having to re-set all parameters individually.
         *
         * @param other A MaterialInstance to copy parameter values from.
         * @param name Optional debug name for the new instance (null to use other's name).
         * @return A new MaterialInstance with all parameters copied from other.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — returns the source instance unchanged; filament.js does not expose MaterialInstance duplication.")
        fun duplicate(other: MaterialInstance, name: String? = null): MaterialInstance
    }

    /**
     * Get the Material this instance is created from.
     *
     * @return The parent Material. The Material owns all instances created from it.
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "getter throws UnsupportedOperationException — filament.js does not expose MaterialInstance.getMaterial.")
    val material: Material

    /**
     * Get the name of this MaterialInstance.
     *
     * @return Instance name string (useful for debugging and profiling).
     */
    val name: String

    /**
     * Sets a boolean parameter.
     * @param name Parameter name as defined in the material
     * @param x Boolean value
     */
    fun setParameter(name: String, x: Boolean)

    /**
     * Sets a float parameter.
     * @param name Parameter name as defined in the material
     * @param x Float value
     */
    fun setParameter(name: String, x: Float)

    /**
     * Sets an integer parameter.
     * @param name Parameter name as defined in the material
     * @param x Integer value
     */
    fun setParameter(name: String, x: Int)

    /**
     * Sets a 2-component boolean vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     */
    fun setParameter(name: String, x: Boolean, y: Boolean)

    /**
     * Sets a 2-component float vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     */
    fun setParameter(name: String, x: Float, y: Float)

    /**
     * Sets a 2-component integer vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     */
    fun setParameter(name: String, x: Int, y: Int)

    /**
     * Sets a 3-component boolean vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     */
    fun setParameter(name: String, x: Boolean, y: Boolean, z: Boolean)

    /**
     * Sets a 3-component float vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     */
    fun setParameter(name: String, x: Float, y: Float, z: Float)

    /**
     * Sets a 3-component integer vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     */
    fun setParameter(name: String, x: Int, y: Int, z: Int)

    /**
     * Sets a 4-component boolean vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     * @param w Fourth component
     */
    fun setParameter(name: String, x: Boolean, y: Boolean, z: Boolean, w: Boolean)

    /**
     * Sets a 4-component float vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     * @param w Fourth component
     */
    fun setParameter(name: String, x: Float, y: Float, z: Float, w: Float)

    /**
     * Sets a 4-component integer vector parameter.
     * @param name Parameter name as defined in the material
     * @param x First component
     * @param y Second component
     * @param z Third component
     * @param w Fourth component
     */
    fun setParameter(name: String, x: Int, y: Int, z: Int, w: Int)

    /**
     * Sets a texture parameter with sampler configuration.
     *
     * Note: Depth textures cannot be sampled with linear filtering unless comparison
     * mode is set to COMPARE_TO_TEXTURE.
     *
     * @param name Parameter name as defined in the material
     * @param texture Texture to bind (can be null to unbind)
     * @param sampler Sampler configuration (filtering, wrapping, comparison function)
     */
    fun setParameter(name: String, texture: Texture, sampler: TextureSampler)

    /**
     * Sets a parameter from a boolean array.
     *
     * @param name Parameter name as defined in the material
     * @param type Array element type (BOOL, BOOL2, BOOL3, or BOOL4)
     * @param v Source array
     * @param offset Index into v to start copying from
     * @param count Number of elements to copy
     */
    fun setParameter(name: String, type: BooleanElement, v: BooleanArray, offset: Int, count: Int)

    /**
     * Sets a parameter from an integer array.
     *
     * @param name Parameter name as defined in the material
     * @param type Array element type (INT, INT2, INT3, or INT4)
     * @param v Source array
     * @param offset Index into v to start copying from
     * @param count Number of elements to copy
     */
    fun setParameter(name: String, type: IntElement, v: IntArray, offset: Int, count: Int)

    /**
     * Sets a parameter from a float array.
     *
     * @param name Parameter name as defined in the material
     * @param type Array element type (FLOAT, FLOAT2, FLOAT3, FLOAT4, MAT3, or MAT4)
     * @param v Source array
     * @param offset Index into v to start copying from
     * @param count Number of elements to copy
     */
    fun setParameter(name: String, type: FloatElement, v: FloatArray, offset: Int, count: Int)

    /**
     * Sets an RGB color parameter.
     *
     * The color is converted based on the specified type (Linear or sRGB).
     *
     * @param name Parameter name as defined in the material
     * @param type Whether color is in Linear or sRGB space
     * @param r Red channel [0, 1]
     * @param g Green channel [0, 1]
     * @param b Blue channel [0, 1]
     */
    fun setParameter(name: String, type: Colors.RgbType, r: Float, g: Float, b: Float)

    /**
     * Sets an RGBA color parameter.
     *
     * The color is converted based on the specified type (Linear or sRGB).
     *
     * @param name Parameter name as defined in the material
     * @param type Whether color is in Linear or sRGB space
     * @param r Red channel [0, 1]
     * @param g Green channel [0, 1]
     * @param b Blue channel [0, 1]
     * @param a Alpha channel [0, 1]
     */
    fun setParameter(name: String, type: Colors.RgbaType, r: Float, g: Float, b: Float, a: Float)

    /**
     * Set-up a custom scissor rectangle; by default it is disabled.
     *
     * The scissor rectangle gets clipped by the View's viewport, in other words, the scissor
     * cannot affect fragments outside of the View's Viewport.
     *
     * Currently the scissor is not compatible with dynamic resolution and should always be
     * disabled when dynamic resolution is used.
     *
     * @param left left coordinate of the scissor box relative to the viewport
     * @param bottom bottom coordinate of the scissor box relative to the viewport
     * @param width width of the scissor box
     * @param height height of the scissor box
     *
     * @see unsetScissor
     * @see View.setViewport
     * @see View.setDynamicResolutionOptions
     */
    fun setScissor(left: Int, bottom: Int, width: Int, height: Int)

    /**
     * Disables the scissor box test; rendering is not restricted to any region.
     */
    fun unsetScissor()

    /**
     * Returns the boolean value of a material specialization constant.
     * @param name Constant name as defined in the material
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — getConstant is not bound in filament.js.")
    fun getConstantBoolean(name: String): Boolean

    /**
     * Returns the float value of a material specialization constant.
     * @param name Constant name as defined in the material
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — getConstant is not bound in filament.js.")
    fun getConstantFloat(name: String): Float

    /**
     * Returns the integer value of a material specialization constant.
     * @param name Constant name as defined in the material
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — getConstant is not bound in filament.js.")
    fun getConstantInt(name: String): Int

    /**
     * Sets a polygon offset that will be applied to all renderables drawn with this material instance.
     *
     * The value of the offset is scale * dz + r * constant, where dz is the change in depth
     * relative to the screen area of the triangle, and r is the smallest value that is guaranteed
     * to produce a resolvable offset for a given implementation. This offset is added before the
     * depth test.
     *
     * @warning Using a polygon offset other than zero has a significant negative performance
     * impact, as most implementations have to disable early depth culling. DO NOT USE unless
     * absolutely necessary.
     *
     * @param scale Scale factor used to create a variable depth offset for each triangle
     * @param constant Scale factor used to create a constant depth offset for each triangle
     */
    fun setPolygonOffset(scale: Float, constant: Float)

    /**
     * Gets/sets the alpha mask threshold for masked blending mode.
     *
     * Overrides the minimum alpha value a fragment must have to not be discarded when the blend
     * mode is MASKED. Defaults to 0.4 if it has not been set in the parent Material. The specified
     * value should be between 0 and 1 and will be clamped if necessary.
     *
     * @see Material.BlendingMode.MASKED
     */
    var maskThreshold: Float

    /**
     * Gets/sets the screen space variance of the filter kernel used when applying specular
     * anti-aliasing.
     *
     * The default value is set to 0.15. The specified value should be between 0 and 1
     * and will be clamped if necessary.
     */
    var specularAntiAliasingVariance: Float

    /**
     * Gets/sets the clamping threshold used to suppress estimation errors when applying specular
     * anti-aliasing.
     *
     * The default value is set to 0.2. The specified value should be between 0 and 1
     * and will be clamped if necessary.
     */
    var specularAntiAliasingThreshold: Float

    /**
     * Gets/sets whether double-sided lighting is enabled.
     *
     * Enables or disables double-sided lighting if the parent Material has double-sided capability,
     * otherwise prints a warning. If double-sided lighting is enabled, backface culling is
     * automatically disabled.
     */
    var isDoubleSided: Boolean

    /**
     * Gets/sets the transparency rendering mode.
     *
     * Specifies how transparent objects should be rendered (default is DEFAULT).
     *
     * @see Material.TransparencyMode
     */
    var transparencyMode: Material.TransparencyMode

    /**
     * Gets/sets the face culling mode.
     *
     * Overrides the default triangle culling state that was set on the material.
     *
     * @see Material.CullingMode
     */
    var cullingMode: Material.CullingMode

    /**
     * Sets different culling modes for color and shadow passes.
     *
     * Overrides the default triangle culling state that was set on the material separately for the
     * color and shadow passes.
     *
     * @param colorPassCullingMode Culling mode for color rendering
     * @param shadowPassCullingMode Culling mode for shadow pass rendering
     */
    fun setCullingMode(colorPassCullingMode: Material.CullingMode, shadowPassCullingMode: Material.CullingMode)

    /**
     * Returns the face culling mode for the shadow passes.
     *
     * @return Culling mode used when rendering shadow maps
     */
    val shadowCullingMode: Material.CullingMode

    /**
     * Gets/sets whether color write is enabled.
     *
     * Overrides the default color-buffer write state that was set on the material.
     */
    var isColorWriteEnabled: Boolean

    /**
     * Gets/sets whether depth write is enabled.
     *
     * Overrides the default depth-buffer write state that was set on the material.
     */
    var isDepthWriteEnabled: Boolean

    /**
     * Gets/sets whether stencil write is enabled.
     *
     * Overrides the default stencil-buffer write state that was set on the material.
     */
    var isStencilWriteEnabled: Boolean

    /**
     * Gets/sets whether depth culling (depth testing) is enabled.
     *
     * Overrides the default depth testing state that was set on the material.
     */
    var isDepthCullingEnabled: Boolean

    /**
     * Gets/sets the depth function.
     *
     * Overrides the default depth function state that was set on the material.
     */
    var depthFunc: TextureSampler.CompareFunction

    /**
     * Sets the stencil comparison function (default is ALWAYS).
     *
     * It's possible to set separate stencil comparison functions; one for front-facing polygons,
     * and one for back-facing polygons. The face parameter determines the comparison function(s)
     * updated by this call.
     *
     * @param func Comparison function
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilCompareFunction(func: TextureSampler.CompareFunction, face: StencilFace)

    /**
     * Sets the stencil comparison function for both front and back faces (default is ALWAYS).
     *
     * @param func Comparison function
     */
    fun setStencilCompareFunction(func: TextureSampler.CompareFunction)

    /**
     * Sets the stencil fail operation (default is KEEP).
     *
     * The stencil fail operation is performed to update values in the stencil buffer when the
     * stencil test fails.
     *
     * It's possible to set separate stencil fail operations; one for front-facing polygons, and one
     * for back-facing polygons. The face parameter determines the stencil fail operation(s) updated
     * by this call.
     *
     * @param op Operation to apply
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilOpStencilFail(op: StencilOperation, face: StencilFace)

    /**
     * Sets the stencil fail operation for both front and back faces (default is KEEP).
     *
     * @param op Operation to apply
     */
    fun setStencilOpStencilFail(op: StencilOperation)

    /**
     * Sets the depth fail operation (default is KEEP).
     *
     * The depth fail operation is performed to update values in the stencil buffer when the depth
     * test fails.
     *
     * It's possible to set separate depth fail operations; one for front-facing polygons, and one
     * for back-facing polygons. The face parameter determines the depth fail operation(s) updated
     * by this call.
     *
     * @param op Operation to apply
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilOpDepthFail(op: StencilOperation, face: StencilFace)

    /**
     * Sets the depth fail operation for both front and back faces (default is KEEP).
     *
     * @param op Operation to apply
     */
    fun setStencilOpDepthFail(op: StencilOperation)

    /**
     * Sets the depth-stencil pass operation (default is KEEP).
     *
     * The depth-stencil pass operation is performed to update values in the stencil buffer when
     * both the stencil test and depth test pass.
     *
     * It's possible to set separate depth-stencil pass operations; one for front-facing polygons,
     * and one for back-facing polygons. The face parameter determines the depth-stencil pass
     * operation(s) updated by this call.
     *
     * @param op Operation to apply
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilOpDepthStencilPass(op: StencilOperation, face: StencilFace)

    /**
     * Sets the depth-stencil pass operation for both front and back faces (default is KEEP).
     *
     * @param op Operation to apply
     */
    fun setStencilOpDepthStencilPass(op: StencilOperation)

    /**
     * Sets the stencil reference value (default is 0).
     *
     * It's possible to set separate stencil reference values; one for front-facing polygons, and one
     * for back-facing polygons. The face parameter determines the reference value(s) updated
     * by this call.
     *
     * @param value Reference value [0, 255]
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilReferenceValue(value: Int, face: StencilFace)

    /**
     * Sets the stencil reference value for both front and back faces (default is 0).
     *
     * @param value Reference value [0, 255]
     */
    fun setStencilReferenceValue(value: Int)

    /**
     * Sets the stencil read mask (default is 0xFF / 255 / all bits).
     *
     * It's possible to set separate stencil read masks; one for front-facing polygons, and one
     * for back-facing polygons. The face parameter determines the read mask(s) updated by this call.
     *
     * @param readMask Bitmask [0, 255]; only masked bits participate in comparison
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilReadMask(readMask: Int, face: StencilFace)

    /**
     * Sets the stencil read mask for both front and back faces (default is 0xFF / 255 / all bits).
     *
     * @param readMask Bitmask [0, 255]; only masked bits participate in comparison
     */
    fun setStencilReadMask(readMask: Int)

    /**
     * Sets the stencil write mask (default is 0xFF / 255 / all bits).
     *
     * It's possible to set separate stencil write masks; one for front-facing polygons, and one
     * for back-facing polygons. The face parameter determines the write mask(s) updated by this call.
     *
     * @param writeMask Bitmask [0, 255]; only masked bits can be modified
     * @param face Which face(s) this applies to (FRONT, BACK, or FRONT_AND_BACK)
     */
    fun setStencilWriteMask(writeMask: Int, face: StencilFace)

    /**
     * Sets the stencil write mask for both front and back faces (default is 0xFF / 255 / all bits).
     *
     * @param writeMask Bitmask [0, 255]; only masked bits can be modified
     */
    fun setStencilWriteMask(writeMask: Int)
}
