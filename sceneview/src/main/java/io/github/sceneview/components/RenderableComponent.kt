package io.github.sceneview.components

import androidx.annotation.IntRange
import com.google.android.filament.Box
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.SkinningBuffer
import com.google.android.filament.VertexBuffer
import io.github.sceneview.geometries.Cube
import io.github.sceneview.geometries.Cylinder
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.Plane
import io.github.sceneview.geometries.Sphere
import io.github.sceneview.geometries.setGeometry
import java.nio.Buffer

typealias RenderableInstance = Int

const val PRIORITY_DEFAULT = 4
const val PRIORITY_FIRST = 0
const val PRIORITY_LAST = 7

/**
 * ECS component for Filament renderables (meshes, geometries, models).
 *
 * Provides typed accessors to [RenderableManager] properties such as geometry,
 * materials, visibility, shadows, skinning, and morph targets.
 */
interface RenderableComponent : Component {

    /** The Filament [RenderableManager] that owns all renderable instances. */
    val renderableManager get() = engine.renderableManager

    /**
     * The renderable-manager instance handle for this entity.
     *
     * **Caching contract (#2328 / #2402 MED-1).** This interface default resolves the handle with a
     * `getInstance` JNI thunk on *every* read, and the renderable getters/setters below all funnel
     * through it — a hot read path. A Kotlin interface property cannot hold a backing field, so the
     * cache must live in the implementer: [io.github.sceneview.node.RenderableNode] and
     * [io.github.sceneview.ar.camera.ARCameraStream] override this with a lazy-once cached handle
     * (the handle is stable for the entity's lifetime — rebuilding the renderable in place keeps
     * it, so a non-zero handle is frozen and a `0` "not built yet" result is re-looked-up).
     * **Any implementer that reads renderable properties frequently MUST cache this the same way;**
     * the uncached default is only safe for one-shot or test implementers.
     */
    val renderableInstance: RenderableInstance get() = renderableManager.getInstance(entity)

    /**
     * Associates a [SkinningBuffer] to a renderable instance.
     *
     * @param skinningBuffer [SkinningBuffer] to use
     * @param count Numbers of bones to set
     * @param offset Offset in the [SkinningBuffer]
     *
     * @see RenderableManager.setSkinningBuffer
     */
    fun setSkinningBuffer(skinningBuffer: SkinningBuffer, count: Int, offset: Int) =
        renderableManager.setSkinningBuffer(renderableInstance, skinningBuffer, count, offset)

    /**
     * Sets the transforms associated with each bone of a Renderable.
     *
     * @param matrices A FloatBuffer containing boneCount 4x4 packed matrices
     * (i.e. 16 floats each matrix and no gap between matrices)
     * @param boneCount Number of bones to set
     * @param offset Index of the first bone to set
     *
     * @see RenderableManager.setBonesAsMatrices
     */
    fun setBonesAsMatrices(
        matrices: Buffer,
        @IntRange(from = 0, to = 255) boneCount: Int,
        @IntRange(from = 0) offset: Int
    ) = renderableManager.setBonesAsMatrices(renderableInstance, matrices, boneCount, offset)

    /**
     * Sets the transforms associated with each bone of a Renderable
     *
     * @param quaternions A FloatBuffer containing boneCount transforms. Each transform consists of
     * 8 float.
     * float 0 to 3 encode a unit quaternion w+ix+jy+kz stored as x,y,z,w.
     * float 4 to 7 encode a translation stored as x,y,z,1
     * @param boneCount Number of bones to set
     * @param offset Index of the first bone to set
     *
     * @see RenderableManager.setBonesAsQuaternions
     */
    fun setBonesAsQuaternions(
        quaternions: Buffer,
        @IntRange(from = 0, to = 255) boneCount: Int,
        @IntRange(from = 0) offset: Int
    ) = renderableManager.setBonesAsQuaternions(renderableInstance, quaternions, boneCount, offset)

    /**
     * Updates the vertex morphing weights on a renderable, all zeroes by default.
     *
     * The renderable must be built with morphing enabled. In legacy morphing mode, only the
     * first 4 weights are considered.
     *
     * @see RenderableManager.setMorphWeights
     */
    fun setMorphWeights(weights: FloatArray, @IntRange(from = 0) offset: Int = 0) =
        renderableManager.setMorphWeights(renderableInstance, weights, offset)

    /**
     * Gets the morph target count on a renderable.
     *
     * @see RenderableManager.getMorphTargetCount
     */
    val morphTargetCount: Int get() = renderableManager.getMorphTargetCount(renderableInstance)

    /**
     * Changes the bounding box used for frustum culling.
     *
     * @see RenderableManager.getAxisAlignedBoundingBox
     * @see RenderableManager.setAxisAlignedBoundingBox
     */
    var axisAlignedBoundingBox: Box
        get() = Box().apply {
            renderableManager.getAxisAlignedBoundingBox(renderableInstance, this)
        }
        set(value) = renderableManager.setAxisAlignedBoundingBox(renderableInstance, value)

    /**
     * Changes the visibility.
     *
     * @see RenderableManager.setLayerMask
     */
    fun setLayerVisible(visible: Boolean) = setLayerMask(0xff, if (visible) 0xff else 0x00)

    /**
     * Changes the visibility bits.
     *
     * @see RenderableManager.setLayerMask
     */
    fun setLayerMask(
        @IntRange(from = 0, to = 255) select: Int,
        @IntRange(from = 0, to = 255) value: Int
    ) = renderableManager.setLayerMask(renderableInstance, select, value)

    /**
     * Changes the coarse-level draw ordering.
     *
     * @see RenderableManager.setPriority
     */
    fun setPriority(@IntRange(from = 0, to = 7) priority: Int) =
        renderableManager.setPriority(renderableInstance, priority)

    /**
     * Changes whether or not frustum culling is on.
     *
     * @see RenderableManager.setCulling
     */
    fun setCulling(enabled: Boolean) = renderableManager.setCulling(renderableInstance, enabled)

    /**
     * Returns whether a light channel is enabled on a specified renderable
     *
     * @param channel Light channel to query
     * @return true if the light channel is enabled, false otherwise
     *
     * @see RenderableManager.getLightChannel
     */
    fun getLightChannel(@IntRange(from = 0, to = 7) channel: Int) =
        renderableManager.getLightChannel(renderableInstance, channel)

    /**
     * Enables or disables a light channel.
     *
     * Light channel 0 is enabled by default.
     *
     * @param channel  Light channel to set
     * @param enable   true to enable, false to disable
     *
     * @see RenderableManager.setLightChannel
     */
    fun setLightChannel(@IntRange(from = 0, to = 7) channel: Int, enable: Boolean) =
        renderableManager.setLightChannel(renderableInstance, channel, enable)

    /**
     * Changes whether or not the renderable casts shadows.
     *
     * @see RenderableManager.isShadowCaster
     * @see RenderableManager.setCastShadows
     */
    var isShadowCaster: Boolean
        get() = renderableManager.isShadowCaster(renderableInstance)
        set(value) = renderableManager.setCastShadows(renderableInstance, value)

    /**
     * Changes whether or not the renderable can receive shadows.
     *
     * @see RenderableManager.isShadowReceiver
     * @see RenderableManager.setReceiveShadows
     */
    var isShadowReceiver: Boolean
        get() = renderableManager.isShadowReceiver(renderableInstance)
        set(value) = renderableManager.setReceiveShadows(renderableInstance, value)

    /**
     * Changes whether or not the renderable can use screen-space contact shadows.
     *
     * @see RenderableManager.setScreenSpaceContactShadows
     */
    fun setScreenSpaceContactShadows(enabled: Boolean) =
        renderableManager.setScreenSpaceContactShadows(renderableInstance, enabled)

    /**
     * Gets the immutable number of primitives in the given renderable.
     *
     * @see RenderableManager.getPrimitiveCount
     */
    val primitiveCount: Int
        @IntRange(from = 0) get() = renderableManager.getPrimitiveCount(renderableInstance)

    /**
     * Changes the material instance binding for the given primitive.
     *
     * @see RenderableManager.getMaterialInstanceAt
     * @see RenderableManager.setMaterialInstanceAt
     */
    var materialInstances: List<MaterialInstance>
        get() = List(primitiveCount) { index ->
            getMaterialInstanceAt(index)
        }
        set(value) = value.forEachIndexed { primitiveIndex, materialInstance ->
            setMaterialInstanceAt(primitiveIndex, materialInstance)
        }

    /**
     * Changes the material instance binding for the first primitive.
     *
     * @see RenderableManager.getMaterialInstanceAt
     * @see RenderableManager.setMaterialInstanceAt
     */
    var materialInstance: MaterialInstance
        get() = getMaterialInstanceAt(0)
        set(value) = setMaterialInstanceAt(0, value)

    /**
     * Changes the material instance binding for all primitives.
     *
     * @see RenderableManager.setMaterialInstanceAt
     */
    fun setMaterialInstances(materialInstance: MaterialInstance) {
        for (primitiveIndex in 0 until primitiveCount) {
            setMaterialInstanceAt(primitiveIndex, materialInstance)
        }
    }

    /**
     * Creates a MaterialInstance Java wrapper object for a particular material instance.
     *
     * @see RenderableManager.getMaterialInstanceAt
     */
    fun getMaterialInstanceAt(@IntRange(from = 0) primitiveIndex: Int) =
        renderableManager.getMaterialInstanceAt(renderableInstance, primitiveIndex)

    /**
     * Changes the material instance binding for the given primitive.
     *
     * @see RenderableManager.setMaterialInstanceAt
     */
    fun setMaterialInstanceAt(
        @IntRange(from = 0) primitiveIndex: Int,
        materialInstance: MaterialInstance
    ) = renderableManager.setMaterialInstanceAt(
        renderableInstance,
        primitiveIndex,
        materialInstance
    )

    operator fun List<MaterialInstance>.set(
        @IntRange(from = 0) primitiveIndex: Int,
        materialInstance: MaterialInstance
    ) = setMaterialInstanceAt(primitiveIndex, materialInstance)

    /**
     * Changes the geometry for the given renderable instance.
     *
     * @see Geometry
     * @see Plane
     * @see Cube
     * @see Sphere
     * @see Cylinder
     * @see RenderableManager.setGeometry
     */
    fun setGeometry(geometry: Geometry) =
        renderableManager.setGeometry(renderableInstance, geometry)

    /**
     * Changes the geometry for the given primitive.
     *
     * @see RenderableManager.setGeometryAt
     */
    fun setGeometryAt(
        @IntRange(from = 0) primitiveIndex: Int,
        type: RenderableManager.PrimitiveType,
        vertices: VertexBuffer,
        indices: IndexBuffer,
        @IntRange(from = 0) offset: Int = 0,
        @IntRange(from = 0) count: Int = indices.indexCount
    ) = renderableManager.setGeometryAt(
        renderableInstance,
        primitiveIndex,
        type,
        vertices,
        indices,
        offset,
        count
    )

    /**
     * Changes the drawing order for blended primitives.
     *
     * The drawing order is either global or local (default) to this Renderable.
     * In either case, the Renderable priority takes precedence.
     *
     * @param blendOrder draw order number (0 by default). Only the lowest 15 bits are used.
     *
     * @see setBlendOrderAt
     */
    fun setBlendOrder(@IntRange(from = 0, to = 65535) blendOrder: Int) {
        for (primitiveIndex in 0 until primitiveCount) {
            setBlendOrderAt(primitiveIndex, blendOrder)
        }
    }

    /**
     * Changes the drawing order for blended primitives. The drawing order is either global or
     * local (default) to this Renderable.
     *
     * In either case, the Renderable priority takes precedence.
     *
     * @param primitiveIndex the primitive of interest
     * @param blendOrder draw order number (0 by default). Only the lowest 15 bits are used.
     *
     * @see RenderableManager.setBlendOrderAt
     */
    fun setBlendOrderAt(
        @IntRange(from = 0) primitiveIndex: Int,
        @IntRange(from = 0, to = 65535) blendOrder: Int
    ) = renderableManager.setBlendOrderAt(renderableInstance, primitiveIndex, blendOrder)

    /**
     * Changes whether the blend order is global or local to this Renderable (by default)
     *
     * @param enabled true for global, false for local blend ordering.
     *
     * @see RenderableManager.setGlobalBlendOrderEnabledAt
     */
    fun setGlobalBlendOrderEnabled(enabled: Boolean) {
        for (primitiveIndex in 0 until primitiveCount) {
            setGlobalBlendOrderEnabledAt(primitiveIndex, enabled)
        }
    }

    /**
     * Changes whether the blend order is global or local to this Renderable (by default)
     *
     * @param primitiveIndex the primitive of interest
     * @param enabled true for global, false for local blend ordering.
     *
     * @see RenderableManager.setGlobalBlendOrderEnabledAt
     */
    fun setGlobalBlendOrderEnabledAt(@IntRange(from = 0) primitiveIndex: Int, enabled: Boolean) =
        renderableManager.setGlobalBlendOrderEnabledAt(renderableInstance, primitiveIndex, enabled)

    /**
     * Retrieves the set of enabled attribute slots in the given primitive's VertexBuffer.
     *
     * @see RenderableManager.getEnabledAttributesAt
     */
    val enabledAttributes
        get() = List(primitiveCount) { primitiveIndex ->
            renderableManager.getEnabledAttributesAt(renderableInstance, primitiveIndex)
        }
}

