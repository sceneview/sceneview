package io.github.sceneview.node

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer

/**
 * Mesh are bundles of primitives, each of which has its own geometry and material.
 *
 * All primitives in a particular renderable share a set of rendering attributes, such as whether
 * they cast shadows or use vertex skinning. Kotlin usage example:
 *
 * ```
 * // Prefer letting MeshNode allocate and own its Filament entity — the node then manages
 * // that entity's full lifecycle (and, once it owns it, frees it on destroy()). Passing a
 * // manually-created entity makes the node *borrow* it instead.
 * val mesh = MeshNode(
 *     engine = engine,
 *     primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
 *     vertexBuffer = vertexBuffer,
 *     indexBuffer = indexBuffer,
 *     materialInstance = material,
 * )
 * // Add it to the scene declaratively inside a SceneView { } content block, or
 * // imperatively with parentNode.addChildNode(mesh).
 * ```
 *
 * To modify the state of an existing renderable, clients should first use RenderableManager to get
 * a temporary handle called an <em>instance</em>. The instance can then be used to get or set the
 * renderable's state. Please note that instances are ephemeral; clients should store entities,
 * not instances.
 *
 * @see Geometry
 */
open class MeshNode(
    engine: Engine,
    primitiveType: PrimitiveType,
    val vertexBuffer: VertexBuffer,
    val indexBuffer: IndexBuffer,
    /**
     * The mesh's axis-aligned bounding box, in local space. Pass one whenever the vertices are
     * known: it enables frustum culling and shadows.
     *
     * Filament refuses to build a renderable without a box unless it is neither culled nor a
     * shadow caster/receiver ("AABB can't be empty" abort). So when this is `null` the node
     * disables culling, shadow casting **and shadow receiving** before [builder] runs; a
     * [builder] that turns shadows back on must also set a non-empty `boundingBox`.
     */
    val boundingBox: Box? = null,
    /**
     * Binds a material instance.
     *
     * If no material is specified, Filament will fall back to a basic default material.
     */
    materialInstance: MaterialInstance? = null,
    /**
     * If `true`, [destroy] also frees [vertexBuffer] and [indexBuffer] via
     * [Engine.safeDestroyVertexBuffer] / [Engine.safeDestroyIndexBuffer].
     *
     * Use `true` when this node exclusively owns the buffers — typically when you build a
     * one-off `VertexBuffer`/`IndexBuffer` just for this node and let the node go out of
     * scope (e.g. [io.github.sceneview.ar.node.StreetscapeGeometryNode]). Without this
     * flag the buffers outlive the renderable and accumulate in Filament's native heap
     * until engine teardown — a steady-state leak (#2037).
     *
     * Use `false` (the default, for backward compatibility) when the buffers are shared or
     * owned by a longer-lived holder responsible for destroying them externally.
     */
    private val destroyBuffersOnDispose: Boolean = false,
    builder: RenderableManager.Builder.() -> Unit = {}
) : RenderableNode(engine) {

    init {
        RenderableManager.Builder(1)
            .geometry(
                0,
                primitiveType,
                vertexBuffer,
                indexBuffer
            )
            .apply {
                val box = this@MeshNode.boundingBox
                if (box != null) {
                    boundingBox(box)
                    culling(true)
                } else {
                    // No box: Filament aborts in build() unless the renderable is neither culled
                    // nor a shadow caster/receiver — and receiveShadows defaults to true.
                    culling(false)
                    castShadows(false)
                    receiveShadows(false)
                }
                materialInstance?.let { materialInstance ->
                    material(0, materialInstance)
                }
            }.apply(builder)
            .build(engine, entity)
        updateCollisionShape()
    }

    override fun destroy() {
        // Once only (#4259): a second call would free handles, material instances and an
        // entity id that may already belong to another node.
        if (isDestroyed) return
        // RenderableNode.destroy() tears down the renderable component first, then the
        // entity. Free the raw geometry buffers after that, only when this node owns them.
        super.destroy()
        if (destroyBuffersOnDispose) {
            engine.safeDestroyVertexBuffer(vertexBuffer)
            engine.safeDestroyIndexBuffer(indexBuffer)
        }
    }
}