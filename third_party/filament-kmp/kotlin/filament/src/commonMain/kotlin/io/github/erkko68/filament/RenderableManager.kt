package io.github.erkko68.filament

/**
 * Factory and manager for Renderables, which are entities that can be drawn.
 *
 * Renderables are bundles of primitives, each with its own geometry and material. All
 * primitives in a renderable share rendering attributes such as shadow casting, skinning,
 * morphing, layer masks, and priority.
 *
 * **Creating renderables:**
 *
 * ```
 * val entity = entityManager.create()
 * RenderableManager.Builder(1)          // 1 primitive
 *     .boundingBox(Box(-1f, -1f, -1f, 1f, 1f, 1f))
 *     .material(0, materialInstance)
 *     .geometry(0, PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 6)
 *     .receiveShadows(true)
 *     .castShadows(true)
 *     .build(engine, entity)
 *
 * scene.addEntity(entity)
 * ```
 *
 * **Modifying renderables:**
 * To modify an existing renderable, use RenderableManager to get a temporary handle called an
 * Instance. The instance is used to get/set rendering state. Instances are ephemeral; store
 * Entity IDs, not instances.
 *
 * **Related components:**
 * - Use TransformManager to associate a 4x4 transform with an entity
 * - Use LightManager to add lights
 * - Use RenderableManager to add geometry and materials
 *
 * @see RenderableManager.Builder
 * @see TransformManager
 * @see LightManager
 */
expect class RenderableManager {
    /**
     * Primitive topology types.
     */
    enum class PrimitiveType { POINTS, LINES, LINE_STRIP, TRIANGLES, TRIANGLE_STRIP }

    /**
     * Type of geometry for a Renderable.
     */
    enum class GeometryType {
        /**
         * Dynamic geometry has no restriction
         */
        DYNAMIC,
        /**
         * Bounds and world space transform are immutable
         */
        STATIC_BOUNDS,
        /**
         * Skinning/morphing not allowed and Vertex/IndexBuffer immutables
         */
        STATIC
    }

    /**
     * Adds renderable components to entities using a builder pattern.
     */
    class Builder(count: Int) {
        /**
         * Specifies the geometry data for a primitive.
         *
         * Associates a vertex buffer and an index buffer with a primitive. Typically, each
         * primitive is specified with a pair of daisy-chained calls: geometry(...) and
         * material(...).
         *
         * @param index zero-based index of the primitive, must be less than the count passed to Builder constructor
         * @param type specifies the topology of the primitive (e.g., PrimitiveType.TRIANGLES)
         * @param vb specifies the vertex buffer, which in turn specifies a set of attributes
         * @param ib specifies the index buffer (either u16 or u32)
         * @return Builder reference for chaining calls.
         */
        fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer): Builder

        /**
         * Specifies the geometry data for a primitive with offset and count.
         *
         * @param index zero-based index of the primitive, must be less than the count passed to Builder constructor
         * @param type specifies the topology of the primitive (e.g., PrimitiveType.TRIANGLES)
         * @param vb specifies the vertex buffer, which in turn specifies a set of attributes
         * @param ib specifies the index buffer (either u16 or u32)
         * @param offset specifies where in the index buffer to start reading (expressed as a number of indices)
         * @param count number of indices to read (for triangles, this should be a multiple of 3)
         * @return Builder reference for chaining calls.
         */
        fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, count: Int): Builder

        /**
         * Specifies the geometry data for a primitive with explicit min/max indices.
         *
         * @param index zero-based index of the primitive, must be less than the count passed to Builder constructor
         * @param type specifies the topology of the primitive (e.g., PrimitiveType.TRIANGLES)
         * @param vb specifies the vertex buffer, which in turn specifies a set of attributes
         * @param ib specifies the index buffer (either u16 or u32)
         * @param offset specifies where in the index buffer to start reading (expressed as a number of indices)
         * @param minIndex specifies the minimum index contained in the index buffer
         * @param maxIndex specifies the maximum index contained in the index buffer
         * @param count number of indices to read (for triangles, this should be a multiple of 3)
         * @return Builder reference for chaining calls.
         */
        fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, minIndex: Int, maxIndex: Int, count: Int): Builder

        /**
         * Specifies the geometry data for a primitive (non-indexed version).
         *
         * Filament primitives normally have an associated vertex buffer and index buffer. Typically,
         * each primitive is specified with a pair of daisy-chained calls: geometry(...) and
         * material(...).
         *
         * Non-indexed rendering: when indices are not provided, the primitive is treated as a
         * non-indexed draw and offset / count refer to vertex offset and vertex count
         * respectively.
         *
         * Attribute-less rendering: This can be used for procedural rendering, where the vertex
         * shader generates positions, UVs, etc procedurally, typically from gl_VertexIndex /
         * gl_VertexID. The associated VertexBuffer may have bufferCount == 0 with
         * no declared attributes. Attribute-less rendering requires FEATURE_LEVEL_1 or higher
         * as GLES2 has no gl_VertexID and is incompatible with skinning and morphing.
         *
         * @param index zero-based index of the primitive, must be less than the count passed to Builder constructor
         * @param type specifies the topology of the primitive (e.g., PrimitiveType.TRIANGLES)
         * @param vb specifies the vertex buffer, which in turn specifies a set of attributes
         * @param offset specifies where in the vertex buffer to start reading (expressed as a number of vertices)
         * @param count number of vertices to read (for triangles, this should be a multiple of 3)
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed geometry overloads.")
        fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer, offset: Int, count: Int): Builder

        /**
         * Specifies the geometry data for a primitive using all vertices (non-indexed version).
         *
         * @param index zero-based index of the primitive, must be less than the count passed to Builder constructor
         * @param type specifies the topology of the primitive (e.g., PrimitiveType.TRIANGLES)
         * @param vb specifies the vertex buffer, which in turn specifies a set of attributes
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed geometry overloads.")
        fun geometry(index: Int, type: PrimitiveType, vb: VertexBuffer): Builder

        /**
         * Specify the type of geometry for this renderable.
         *
         * DYNAMIC geometry has no restriction, STATIC_BOUNDS geometry means that both the bounds
         * and the world-space transform of the renderable are immutable.
         * STATIC geometry has the same restrictions as STATIC_BOUNDS, but in addition disallows
         * skinning, morphing and changing the VertexBuffer or IndexBuffer in any way.
         *
         * @param type type of geometry.
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws an embind \"unbound types\" Error — filament.js does not register Builder.geometryType.")
        fun geometryType(type: GeometryType): Builder

        /**
         * Binds a material instance to the specified primitive.
         *
         * If no material is specified for a given primitive, Filament will fall back to a basic
         * default material.
         *
         * The MaterialInstance's material must have a feature level equal or lower to the engine's
         * selected feature level.
         *
         * @param index zero-based index of the primitive, must be less than the count passed to
         * Builder constructor
         * @param materialInstance the material to bind
         * @return Builder reference for chaining calls.
         */
        fun material(index: Int, materialInstance: MaterialInstance): Builder

        /**
         * The axis-aligned bounding box of the renderable.
         *
         * This is an object-space AABB used for frustum culling. For skinning and morphing, this
         * should encompass all possible vertex positions. It is mandatory unless culling is
         * disabled for the renderable.
         *
         * @param box axis-aligned bounding box
         * @return Builder reference for chaining calls.
         */
        fun boundingBox(box: Box): Builder

        /**
         * Sets bits in a visibility mask. By default, this is 0x1.
         *
         * This feature provides a simple mechanism for hiding and showing groups of renderables
         * in a Scene. See View.setVisibleLayers().
         *
         * For example, to set bit 1 and reset bits 0 and 2 while leaving all other bits unaffected,
         * do: builder.layerMask(7, 2).
         *
         * To change this at run time, see RenderableManager.setLayerMask.
         *
         * @param select the set of bits to affect
         * @param value the replacement values for the affected bits
         * @return Builder reference for chaining calls.
         */
        fun layerMask(select: Int, value: Int): Builder

        /**
         * Provides coarse-grained control over draw order.
         *
         * In general Filament reserves the right to re-order renderables to allow for efficient
         * rendering. However clients can control ordering at a coarse level using priority.
         * The priority is applied separately for opaque and translucent objects, that is, opaque
         * objects are always drawn before translucent objects regardless of the priority.
         *
         * For example, this could be used to draw a semitransparent HUD on top of everything,
         * without using a separate View. Note that priority is completely orthogonal to
         * Builder.layerMask, which merely controls visibility.
         *
         * The Skybox always using the lowest priority, so it's drawn last, which may improve
         * performance.
         *
         * @param priority clamped to the range [0..7], defaults to 4; 7 is lowest priority
         *                 (rendered last).
         * @return Builder reference for chaining calls.
         */
        fun priority(priority: Int): Builder

        /**
         * Set the channel this renderable is associated to. There can be 8 channels.
         * All renderables in a given channel are rendered together, regardless of anything else.
         * They are sorted as usual within a channel.
         * Channels work similarly to priorities, except that they enforce the strongest ordering.
         *
         * Channels 0 and 1 may not have render primitives using a material with refractionType
         * set to screenspace.
         *
         * @param channel clamped to the range [0..7], defaults to 2.
         * @return Builder reference for chaining calls.
         */
        fun channel(channel: Int): Builder

        /**
         * Controls frustum culling, true by default.
         *
         * Note: Do not confuse frustum culling with backface culling. The latter is controlled via
         * the material.
         *
         * @param enabled whether frustum culling is enabled
         * @return Builder reference for chaining calls.
         */
        fun culling(enabled: Boolean): Builder

        /**
         * Controls if this renderable casts shadows, false by default.
         *
         * If the View's shadow type is set to ShadowType.VSM, castShadows should only be disabled
         * if either is true:
         *   - receiveShadows is also disabled
         *   - the object is guaranteed to not cast shadows on itself or other objects (for example,
         *     a ground plane)
         *
         * @param enabled whether shadow casting is enabled
         * @return Builder reference for chaining calls.
         */
        fun castShadows(enabled: Boolean): Builder

        /**
         * Controls if this renderable receives shadows, true by default.
         *
         * @param enabled whether shadow receiving is enabled
         * @return Builder reference for chaining calls.
         */
        fun receiveShadows(enabled: Boolean): Builder

        /**
         * Controls if this renderable uses screen-space contact shadows. This is more
         * expensive but can improve the quality of shadows, especially in large scenes.
         * (off by default).
         *
         * @param enabled whether screen-space contact shadows are enabled
         * @return Builder reference for chaining calls.
         */
        fun screenSpaceContactShadows(enabled: Boolean): Builder

        /**
         * Enables GPU vertex skinning for up to 255 bones, 0 by default.
         *
         * Skinning Buffer mode must be disabled.
         *
         * Each vertex can be affected by up to 4 bones simultaneously. The attached
         * VertexBuffer must provide data in the BONE_INDICES slot (uvec4) and the
         * BONE_WEIGHTS slot (float4).
         *
         * See also RenderableManager.setBonesAsMatrices() or RenderableManager.setBonesAsQuaternions(),
         * which can be called on a per-frame basis to advance the animation.
         *
         * @param boneCount 0 to disable, otherwise the number of bone transforms (up to 255)
         * @return Builder reference for chaining calls.
         */
        fun skinning(boneCount: Int): Builder

        /**
         * Enables GPU vertex skinning for up to 255 bones with initial bone transforms, 0 by default.
         *
         * Skinning Buffer mode must be disabled.
         *
         * Each vertex can be affected by up to 4 bones simultaneously. The attached
         * VertexBuffer must provide data in the BONE_INDICES slot (uvec4) and the
         * BONE_WEIGHTS slot (float4).
         *
         * See also RenderableManager.setBonesAsMatrices() or RenderableManager.setBonesAsQuaternions(),
         * which can be called on a per-frame basis to advance the animation.
         *
         * @param boneCount the number of bone transforms (up to 255)
         * @param bones the initial set of transforms (one for each bone)
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — filament.js only binds the bone-count skinning overload; glTF skinning works through gltfio.")
        fun skinning(boneCount: Int, bones: FloatArray): Builder

        /**
         * Allows bones to be swapped out and shared using SkinningBuffer.
         *
         * If skinning buffer mode is enabled, clients must call setSkinningBuffer() rather than
         * setBonesAsMatrices() or setBonesAsQuaternions(). This allows sharing of data between renderables.
         *
         * @param skinningBuffer the SkinningBuffer to use
         * @param boneCount the number of bone transforms (up to 255)
         * @param offset offset in the SkinningBuffer
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — SkinningBuffer itself is unbound in filament.js; glTF skinning works through gltfio.")
        fun skinning(skinningBuffer: SkinningBuffer, boneCount: Int, offset: Int): Builder

        /**
         * Allows bones to be swapped out and shared using SkinningBuffer. This method must be
         * called before skinning() for buffer-mode skinning.
         *
         * If skinning buffer mode is enabled, clients must call setSkinningBuffer() rather than
         * setBonesAsMatrices() or setBonesAsQuaternions(). This allows sharing of data between renderables.
         *
         * @param enabled If true, enables buffer object mode.  False by default.
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — SkinningBuffer itself is unbound in filament.js.")
        fun enableSkinningBuffers(enabled: Boolean): Builder

        /**
         * Controls if the renderable has legacy vertex morphing targets, zero by default. This is
         * required to enable GPU morphing.
         *
         * For legacy morphing, the attached VertexBuffer must provide data in the
         * appropriate VertexAttribute slots (MORPH_POSITION_0 etc). Legacy morphing only
         * supports up to 4 morph targets and will be deprecated in the future. Legacy morphing must
         * be enabled on the material definition: either via the legacyMorphing material attribute
         * or by calling filamat.MaterialBuilder.useLegacyMorphing().
         *
         * See also RenderableManager.setMorphWeights(), which can be called on a per-frame basis
         * to advance the animation.
         *
         * @param targetCount the number of morph targets
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "degraded — filament.js only binds a boolean enable, so the target count is reduced to targetCount > 0.")
        fun morphing(targetCount: Int): Builder

        /**
         * Controls if the renderable has vertex morphing targets, zero by default. This is
         * required to enable GPU morphing.
         *
         * Filament supports two morphing modes: standard (default) and legacy.
         *
         * For standard morphing, A MorphTargetBuffer must be provided.
         * Standard morphing supports up to CONFIG_MAX_MORPH_TARGET_COUNT morph targets.
         *
         * See also RenderableManager.setMorphWeights(), which can be called on a per-frame basis
         * to advance the animation.
         *
         * @param morphTargetBuffer the morph target buffer
         * @return Builder reference for chaining calls.
         */
        @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "silent no-op — MorphTargetBuffer itself is unbound in filament.js; glTF morphing works through gltfio.")
        fun morphing(morphTargetBuffer: MorphTargetBuffer): Builder

        /**
         * Controls if this renderable is affected by the large-scale fog.
         *
         * @param enabled If true, enables large-scale fog on this object. Disables it otherwise.
         *                True by default.
         * @return Builder reference for chaining calls.
         */
        fun fog(enabled: Boolean): Builder

        /**
         * Enables or disables a light channel. Light channel 0 is enabled by default.
         *
         * @param channel Light channel to enable or disable, between 0 and 7.
         * @param enable Whether to enable or disable the light channel.
         * @return Builder reference for chaining calls.
         */
        fun lightChannel(channel: Int, enable: Boolean): Builder

        /**
         * Sets the drawing order for blended primitives. The drawing order is either global or
         * local (default) to this Renderable. In either case, the Renderable priority takes
         * precedence.
         *
         * @param primitiveIndex the primitive of interest
         * @param blendOrder draw order number (0 by default). Only the lowest 15 bits are used.
         * @return Builder reference for chaining calls.
         */
        fun blendOrder(index: Int, blendOrder: Int): Builder

        /**
         * Sets whether the blend order is global or local to this Renderable (by default).
         *
         * @param primitiveIndex the primitive of interest
         * @param enabled true for global, false for local blend ordering.
         * @return Builder reference for chaining calls.
         */
        fun globalBlendOrderEnabled(index: Int, enabled: Boolean): Builder

        /**
         * Specifies the number of draw instances of this renderable. The default is 1 instance and
         * the maximum number of instances allowed is 32767. 0 is invalid.
         *
         * All instances are culled using the same bounding box, so care must be taken to make
         * sure all instances render inside the specified bounding box.
         *
         * The material must set its instanced parameter to true in order to use
         * getInstanceIndex() in the vertex or fragment shader to get the instance index and
         * possibly adjust the position or transform.
         *
         * @param instanceCount the number of instances silently clamped between 1 and 32767.
         * @return Builder reference for chaining calls.
         */
        fun instances(instanceCount: Int): Builder

        /**
         * Adds the Renderable component to an entity.
         *
         * @param engine Reference to the filament Engine to associate this Renderable with.
         * @param entity Entity to add the Renderable component to.
         *
         * If this component already exists on the given entity and the construction is successful,
         * it is first destroyed as if destroy(entity) was called. In case of error,
         * the existing component is unmodified.
         */
        fun build(engine: Engine, entity: Entity)
    }

    /**
     * Checks if the given entity already has a renderable component.
     *
     * @param entity the entity to check
     * @return true if the entity has a renderable component
     */
    fun hasComponent(entity: Entity): Boolean

    /**
     * Gets a temporary handle that can be used to access the renderable state.
     *
     * @param entity the entity to get the instance for
     * @return Non-zero handle if the entity has a renderable component, otherwise null
     */
    fun getInstance(entity: Entity): EntityInstance

    /**
     * Destroys the renderable component in the given entity.
     *
     * @param entity the entity whose renderable component is to be destroyed
     */
    fun destroy(entity: Entity)

    /**
     * Changes the bounding box used for frustum culling.
     * The renderable must not have staticGeometry enabled.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param box the new axis-aligned bounding box
     * @see Builder.boundingBox()
     * @see RenderableManager.getAxisAlignedBoundingBox()
     */
    fun setAxisAlignedBoundingBox(instance: EntityInstance, box: Box)

    /**
     * Gets the bounding box used for frustum culling.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param outBox optional output Box; if null, a new Box is created
     * @return the axis-aligned bounding box
     * @see Builder.boundingBox()
     * @see RenderableManager.setAxisAlignedBoundingBox()
     */
    fun getAxisAlignedBoundingBox(instance: EntityInstance, outBox: Box?): Box

    /**
     * Changes the visibility bits.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param select the set of bits to affect
     * @param value the replacement values for the affected bits
     * @see Builder.layerMask()
     * @see View.setVisibleLayers()
     */
    fun setLayerMask(instance: EntityInstance, select: Int, value: Int)

    /**
     * Changes the coarse-level draw ordering.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param priority the new priority clamped to [0..7]
     * @see Builder.priority()
     */
    fun setPriority(instance: EntityInstance, priority: Int)

    /**
     * Get the coarse-level draw ordering.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return the priority value
     * @see Builder.priority()
     */
    fun getPriority(instance: EntityInstance): Int

    /**
     * Changes the channel a renderable is associated to.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param channel the new channel value clamped to [0..7]
     * @see Builder.channel()
     */
    fun setChannel(instance: EntityInstance, channel: Int)

    /**
     * Get the channel a renderable is associated to.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return the channel value
     * @see Builder.channel()
     */
    fun getChannel(instance: EntityInstance): Int

    /**
     * Changes whether or not frustum culling is on.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param enabled whether frustum culling is enabled
     * @see Builder.culling()
     */
    fun setCulling(instance: EntityInstance, enabled: Boolean)

    /**
     * Get whether or not frustum culling is on.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return whether frustum culling is enabled
     * @see Builder.culling()
     */
    fun isCullingEnabled(instance: EntityInstance): Boolean

    /**
     * Changes whether or not the large-scale fog is applied to this renderable
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param enabled whether fog is enabled
     * @see Builder.fog()
     */
    fun setFogEnabled(instance: EntityInstance, enabled: Boolean)

    /**
     * Returns whether large-scale fog is enabled for this renderable.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return whether fog is enabled
     * @see Builder.fog()
     */
    fun getFogEnabled(instance: EntityInstance): Boolean

    /**
     * Changes whether or not the renderable casts shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param enabled whether shadow casting is enabled
     * @see Builder.castShadows()
     */
    fun setCastShadows(instance: EntityInstance, enabled: Boolean)

    /**
     * Changes whether or not the renderable can receive shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param enabled whether shadow receiving is enabled
     * @see Builder.receiveShadows()
     */
    fun setReceiveShadows(instance: EntityInstance, enabled: Boolean)

    /**
     * Changes whether or not the renderable can use screen-space contact shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param enabled whether screen-space contact shadows are enabled
     * @see Builder.screenSpaceContactShadows()
     */
    fun setScreenSpaceContactShadows(instance: EntityInstance, enabled: Boolean)

    /**
     * Checks if the renderable can cast shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return whether the renderable casts shadows
     * @see Builder.castShadows()
     */
    fun isShadowCaster(instance: EntityInstance): Boolean

    /**
     * Checks if the renderable can receive shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return whether the renderable receives shadows
     * @see Builder.receiveShadows()
     */
    fun isShadowReceiver(instance: EntityInstance): Boolean

    /**
     * Checks if the renderable can use screen-space contact shadows.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return whether screen-space contact shadows are enabled
     * @see Builder.screenSpaceContactShadows()
     */
    fun isScreenSpaceContactShadowsEnabled(instance: EntityInstance): Boolean

    /**
     * Get the number of primitives in the given entity.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return the number of primitives
     */
    fun getPrimitiveCount(instance: EntityInstance): Int

    /**
     * Get the number of instances in the given entity.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return the number of instances
     */
    fun getInstanceCount(instance: EntityInstance): Int

    /**
     * Changes a material instance on a primitive.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @param materialInstance the material instance to bind
     */
    fun setMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int, materialInstance: MaterialInstance)

    /**
     * Gets a material instance on a primitive.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @return the material instance, or null for default
     */
    fun getMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int): MaterialInstance?

    /**
     * Retrieves the set of enabled attribute slots in the given primitive's VertexBuffer.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     */
    fun getEnabledAttributesAt(instance: EntityInstance, primitiveIndex: Int): Set<VertexBuffer.VertexAttribute>

    /**
     * Changes the geometry for a primitive.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @param type primitive type
     * @param vb vertex buffer for this primitive
     * @param ib index buffer for this primitive
     * @param offset index offset in the index buffer
     * @param count number of indices to render
     */
    fun setGeometryAt(instance: EntityInstance, primitiveIndex: Int, type: PrimitiveType, vb: VertexBuffer, ib: IndexBuffer, offset: Int, count: Int)

    /**
     * Changes the geometry for a primitive (non-indexed).
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @param type primitive type
     * @param vb vertex buffer for this primitive
     * @param offset vertex offset in the vertex buffer
     * @param count number of vertices to render
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — filament.js only binds the indexed setGeometryAt overload.")
    fun setGeometryAt(instance: EntityInstance, primitiveIndex: Int, type: PrimitiveType, vb: VertexBuffer, offset: Int, count: Int)

    /**
     * Sets the drawing order for blended primitives.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @param blendOrder draw order number. Only the lowest 15 bits are used.
     * @see Builder.blendOrder()
     */
    fun setBlendOrderAt(instance: EntityInstance, primitiveIndex: Int, blendOrder: Int)

    /**
     * Gets the drawing order for blended primitives.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @return the draw order
     * @see Builder.blendOrder()
     */
    fun getBlendOrderAt(instance: EntityInstance, primitiveIndex: Int): Int

    /**
     * Sets whether the blend order is global or local to this Renderable.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @param enabled true for global, false for local blend ordering
     * @see Builder.globalBlendOrderEnabled()
     */
    fun setGlobalBlendOrderEnabledAt(instance: EntityInstance, primitiveIndex: Int, enabled: Boolean)

    /**
     * Gets whether the blend order is global or local to this Renderable.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     * @return true for global, false for local blend ordering
     * @see Builder.globalBlendOrderEnabled()
     */
    fun isGlobalBlendOrderEnabledAt(instance: EntityInstance, primitiveIndex: Int): Boolean

    /**
     * Enables or disables a light channel for this renderable.
     * Light channel 0 is enabled by default.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param channel light channel to enable or disable, between 0 and 7
     * @param enable whether to enable the light channel
     * @see Builder.lightChannel()
     */
    fun setLightChannel(instance: EntityInstance, channel: Int, enable: Boolean)

    /**
     * Returns whether a light channel is enabled on this renderable.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param channel Light channel to query
     * @return true if the light channel is enabled, false otherwise
     */
    fun getLightChannel(instance: EntityInstance, channel: Int): Boolean

    /**
     * Get the number of morph targets in the given entity.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @return the number of morph targets
     */
    fun getMorphTargetCount(instance: EntityInstance): Int

    /**
     * Associates a region of a SkinningBuffer to a renderable instance.
     *
     * Note: due to hardware limitations offset + 256 must be smaller or equal to
     * skinningBuffer.getBoneCount()
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param skinningBuffer skinning buffer to associate to the instance
     * @param count Size of the region in bones, must be smaller or equal to 256.
     * @param offset Start offset of the region in bones
     */
    fun setSkinningBuffer(instance: EntityInstance, skinningBuffer: SkinningBuffer, count: Int, offset: Int)

    /**
     * Updates the vertex morphing weights on a renderable, all zeroes by default.
     *
     * The renderable must be built with morphing enabled, see Builder.morphing(). In legacy
     * morphing mode, only the first 4 weights are considered.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param weights Pointer to morph target weights to be updated.
     * @param offset Index of the first morph target weight to set at instance.
     */
    fun setMorphWeights(instance: EntityInstance, weights: FloatArray, offset: Int = 0)

    /**
     * Associates a MorphTargetBuffer to the given primitive.
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param level the level of detail (lod), only 0 can be specified
     * @param primitiveIndex the primitive of interest
     * @param offset specifies where in the morph target buffer to start reading (expressed as a number of vertices)
     */
    fun setMorphTargetBufferOffsetAt(instance: EntityInstance, level: Int, primitiveIndex: Int, offset: Int)

    /**
     * Updates the bone transforms in the range [offset, offset + boneCount).
     * The bones must be pre-allocated using Builder.skinning().
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param matrices bone transform matrices
     * @param boneCount the number of bones to set
     * @param offset the offset of the first bone to set
     */
    fun setBonesAsMatrices(instance: EntityInstance, matrices: FloatArray, boneCount: Int, offset: Int)

    /**
     * Updates the bone transforms in the range [offset, offset + boneCount).
     * The bones must be pre-allocated using Builder.skinning().
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param quaternions bone transforms as quaternions (4 float per bone)
     * @param boneCount the number of bones to set
     * @param offset the offset of the first bone to set
     */
    fun setBonesAsQuaternions(instance: EntityInstance, quaternions: FloatArray, boneCount: Int, offset: Int)

    /**
     * Clears the material instance for a primitive (revert to default).
     *
     * @param instance Instance of the component obtained from getInstance()
     * @param primitiveIndex the primitive of interest
     */
    fun clearMaterialInstanceAt(instance: EntityInstance, primitiveIndex: Int)
}

/** Converts a native attribute bitset into the corresponding set of [VertexBuffer.VertexAttribute]. */
internal fun attributeBitsetToSet(bits: Int): Set<VertexBuffer.VertexAttribute> =
    VertexBuffer.VertexAttribute.entries.filterTo(mutableSetOf()) { (bits shr it.ordinal) and 1 == 1 }
