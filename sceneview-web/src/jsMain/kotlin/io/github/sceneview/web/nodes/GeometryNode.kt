package io.github.sceneview.web.nodes

import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager
import io.github.sceneview.web.bindings.FilamentAsset

/**
 * A [Node] whose subtree is a procedural geometry primitive — the web mirror
 * of Android `io.github.sceneview.node.GeometryNode` (issue #2024, slice 2).
 *
 * Created via `SceneView.addGeometryNode(config)` (or the typed
 * `addCubeNode` / `addSphereNode` / `addCylinderNode` / `addPlaneNode`
 * factories), which runs today's `addGeometry` pipeline — the KMP core
 * generators emit an in-memory GLB loaded through gltfio, so primitives get
 * the same PBR material system as models — and then re-parents the
 * generated asset's root entity under this node.
 *
 * Ownership matches [ModelNode]: the generated [FilamentAsset] belongs to
 * the `SceneView` asset tracker; [destroy] frees only the node's own pivot
 * entity.
 */
open class GeometryNode internal constructor(
    backend: NodeBackend,
) : Node(backend) {

    /** See [Node]'s secondary constructor. */
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))

    /** The generated gltfio asset, `null` only if the geometry build failed. */
    var asset: FilamentAsset? = null
        internal set
}

/**
 * A box primitive node — web mirror of Android `CubeNode`.
 * Create via `SceneView.addCubeNode(...)`.
 */
class CubeNode internal constructor(backend: NodeBackend) : GeometryNode(backend) {
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))
}

/**
 * A sphere primitive node — web mirror of Android `SphereNode`.
 * Create via `SceneView.addSphereNode(...)`.
 */
class SphereNode internal constructor(backend: NodeBackend) : GeometryNode(backend) {
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))
}

/**
 * A cylinder primitive node — web mirror of Android `CylinderNode`.
 * Create via `SceneView.addCylinderNode(...)`.
 */
class CylinderNode internal constructor(backend: NodeBackend) : GeometryNode(backend) {
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))
}

/**
 * A plane (quad) primitive node — web mirror of Android `PlaneNode`.
 * Create via `SceneView.addPlaneNode(...)`.
 */
class PlaneNode internal constructor(backend: NodeBackend) : GeometryNode(backend) {
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))
}
