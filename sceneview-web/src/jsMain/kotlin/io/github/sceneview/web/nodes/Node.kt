package io.github.sceneview.web.nodes

import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.lookTowards
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.computeWorldToLocal
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.toQuaternion
import io.github.sceneview.math.toRotation
import io.github.sceneview.math.worldToLocalScale
import io.github.sceneview.rendering.NodeTransform
import io.github.sceneview.rendering.SceneNode
import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager

/**
 * Base scene-graph node for SceneView Web — the first platform implementation
 * of the cross-platform [SceneNode] contract from `sceneview-core`
 * (issue #2024, slice 1 of `.claude/plans/v5-web-node-graph.md`).
 *
 * Mirrors Android `io.github.sceneview.node.Node`:
 * - owns one Filament [Entity] with a `TransformManager` component;
 * - keeps pristine Kotlin-side TRS state ([position] / [quaternion] / [scale])
 *   and pushes the composed matrix to Filament, never reading it back
 *   (the Android #2335 compose-only invariant, so per-frame component writes
 *   never drift through matrix re-decomposition);
 * - hierarchy ([parent] / [childNodes]) delegates transform inheritance to
 *   Filament's `TransformManager.setParent`, exactly like Android
 *   `Node.parentInstance`;
 * - world-space getters/setters compose through the Kotlin parent chain with
 *   the shared `sceneview-core` math ([NodeTransform]) — the same types and
 *   functions Android uses, so cross-platform transform semantics match by
 *   construction (and stay unit-testable without the Filament WASM module).
 *
 * **Not yet part of the published JS API.** The `@JsExport` `NodeHandle`
 * surface, the concrete `ModelNode`/`GeometryNode`/`LightNode` subtypes, the
 * `isVisible` render cascade, collision, and gestures land in later slices —
 * see the phasing table in the design doc. Until then this type is consumed
 * only inside the `sceneview-web` module.
 */
open class Node internal constructor(
    internal val backend: NodeBackend,
) : SceneNode {

    /**
     * Creates a node backed by a Filament [Entity] with a `TransformManager`
     * component (created on demand).
     *
     * @param engine The Filament engine that owns the entity's components.
     * @param entity The entity this node owns — a fresh one by default.
     */
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))

    /** The Filament entity this node owns (`null` only for test-backed nodes). */
    val entity: Entity? get() = (backend as? FilamentNodeBackend)?.entity

    override var name: String? = null

    /**
     * Whether this node is visible. Stored state only in slice 1 — the
     * render-side cascade (scene add/remove of renderables) is wired in a
     * later slice, once nodes own renderable entities (design doc Phase 4).
     */
    override var isVisible: Boolean = true

    override var isHittable: Boolean = true

    /** `true` once [destroy] has run; every later [destroy] call is a no-op. */
    var isDestroyed: Boolean = false
        private set

    // Pristine TRS state (the Android Node #2335 pattern): the three caches
    // are the source of truth, composed into a matrix and PUSHED to Filament —
    // never read back, so repeated component writes cannot drift through
    // matrix decomposition.
    private var _position: Position = Position()
    private var _quaternion: Quaternion = Quaternion()
    private var _scale: Scale = Scale(1.0f)

    init {
        // Align the Filament-side matrix with the Kotlin state from birth, so
        // the two sides never diverge even for entities that carried a prior
        // transform.
        applyLocalTransform()
    }

    /**
     * Position in the parent's coordinate space.
     *
     * Same axis conventions as Android `Node.position`: +x right, +y up,
     * -z forward.
     */
    override var position: Position
        get() = _position
        set(value) {
            _position = value
            applyLocalTransform()
        }

    /** Rotation as a quaternion in the parent's coordinate space. */
    override var quaternion: Quaternion
        get() = _quaternion
        set(value) {
            _quaternion = value
            applyLocalTransform()
        }

    /** Euler-angle rotation in degrees (ZYX order), in the parent's space. */
    override var rotation: Rotation
        get() = _quaternion.toRotation()
        set(value) {
            quaternion = value.toQuaternion()
        }

    /**
     * Scale in the parent's coordinate space.
     *
     * Prefer `Scale(1.5f)` (fills all three components) over
     * `Scale(x = 1.5f)` for uniform scales — see the `Scale` typealias gotcha
     * in `sceneview-core`.
     */
    override var scale: Scale
        get() = _scale
        set(value) {
            _scale = value
            applyLocalTransform()
        }

    /** Combined local transform. The setter decomposes into TRS state. */
    override var transform: Transform
        get() = Transform(_position, _quaternion, _scale)
        set(value) {
            _position = value.position
            _quaternion = value.quaternion
            _scale = value.scale
            applyLocalTransform()
        }

    // --- World transforms -------------------------------------------------
    //
    // Composed through the Kotlin parent chain with the shared core math —
    // the exact `parentWorld * local` composition Filament performs on its
    // side after `setParent`, so the Kotlin read is authoritative without a
    // per-read WASM marshalling round-trip.

    override var worldTransform: Transform
        get() = parentNode
            ?.let { NodeTransform.getWorldTransform(it.worldTransform, transform) }
            ?: transform
        set(value) {
            transform = parentNode
                ?.let { NodeTransform.getLocalTransform(it.worldTransform, value) }
                ?: value
        }

    override var worldPosition: Position
        get() = worldTransform.position
        set(value) {
            position = parentNode
                ?.let { NodeTransform.getLocalPosition(it.worldTransform, value) }
                ?: value
        }

    override var worldQuaternion: Quaternion
        get() = worldTransform.quaternion
        set(value) {
            quaternion = parentNode
                ?.let { NodeTransform.getLocalQuaternion(it.worldQuaternion, value) }
                ?: value
        }

    override var worldRotation: Rotation
        // Extract Euler straight from the matrix (not via the quaternion) to
        // stay branch-stable near gimbal lock — same rationale as Android's
        // refreshWorldCache (#2264).
        get() = worldTransform.rotation
        set(value) {
            rotation = parentNode
                ?.let { NodeTransform.getLocalRotation(it.worldQuaternion, value) }
                ?: value
        }

    override var worldScale: Scale
        get() = worldTransform.scale
        set(value) {
            scale = parentNode
                ?.let { worldToLocalScale(value, computeWorldToLocal(it.worldTransform)) }
                ?: value
        }

    // --- Hierarchy ----------------------------------------------------------

    /**
     * The parent node, `null` when detached (root).
     *
     * Android `Node.parent` setter semantics: assigning removes this node from
     * the old parent's [childNodes], adds it to the new parent's, and
     * re-parents the Filament transform component (`TransformManager.setParent`)
     * so engine-side world composition follows. The **local** transform is
     * kept — so the world transform generally changes when the parent does.
     *
     * Only web [Node] parents are supported (the only [SceneNode]
     * implementation on this platform).
     */
    final override var parent: SceneNode? = null
        set(value) {
            if (field === value) return
            require(value == null || value is Node) {
                "A web Node can only be parented to another web Node (got ${value!!::class.simpleName})"
            }
            // Reject self/descendant parenting — a cycle would make the
            // world-transform walk (and Filament's own composition) diverge.
            var ancestor: SceneNode? = value
            while (ancestor != null) {
                require(ancestor !== this) {
                    "Cannot set parent: '${(value as Node).name ?: value}' is this node or one of its descendants"
                }
                ancestor = ancestor.parent
            }
            val oldParent = field as Node?
            field = value
            oldParent?.let { it._childNodes = it._childNodes - this }
            (value as Node?)?.let { it._childNodes = it._childNodes + this }
            backend.setParent((value as Node?)?.backend)
        }

    private var _childNodes = setOf<SceneNode>()

    final override val childNodes: Set<SceneNode> get() = _childNodes

    override fun addChildNode(node: SceneNode) {
        require(node is Node) {
            "A web Node can only parent another web Node (got ${node::class.simpleName})"
        }
        node.parent = this
    }

    override fun removeChildNode(node: SceneNode) {
        if (node is Node && node.parent === this) {
            node.parent = null
        }
    }

    // --- Orientation helpers -------------------------------------------------

    override fun lookAt(targetWorldPosition: Position, upDirection: Direction) {
        // Same composition as Android Node.lookAt: build the world-space
        // orientation with kotlin-math, then let the worldQuaternion setter
        // convert into the parent's space.
        worldQuaternion = lookAt(
            eye = worldPosition,
            target = targetWorldPosition,
            up = upDirection
        ).quaternion
    }

    override fun lookTowards(lookDirection: Direction, upDirection: Direction) {
        worldQuaternion = lookTowards(
            eye = worldPosition,
            forward = lookDirection,
            up = upDirection
        ).quaternion
    }

    // --- Lifecycle -------------------------------------------------------------

    /**
     * Destroys this node and its whole subtree, freeing each node's Filament
     * transform component + entity exactly once (idempotent via [isDestroyed]).
     *
     * Children are destroyed first (snapshot iteration — each child's destroy
     * mutates [childNodes] through `parent = null`), then this node detaches
     * from its parent and releases its engine resources — the Android
     * `Node.destroy()` order.
     */
    override fun destroy() {
        if (isDestroyed) return
        isDestroyed = true
        childNodes.toList().forEach { it.destroy() }
        parent = null
        backend.destroy()
    }

    // --- Internals --------------------------------------------------------------

    private val parentNode: Node? get() = parent as Node?

    /**
     * Composes the pristine TRS state into a matrix and pushes it to the
     * engine — the compose-only write path (never decomposes back).
     */
    private fun applyLocalTransform() {
        backend.setLocalTransform(Transform(_position, _quaternion, _scale))
    }
}
