package io.github.sceneview.web.nodes

import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager
import io.github.sceneview.web.bindings.FilamentAsset

/**
 * A [Node] whose subtree is a loaded glTF/GLB model — the web mirror of
 * Android `io.github.sceneview.node.ModelNode` (issue #2024, slice 2 of
 * `.claude/plans/v5-web-node-graph.md`).
 *
 * Created via `SceneView.addModelNode(url)`, which runs today's full
 * `loadModel` pipeline (render-gate #2332, supersede guard #1597,
 * auto-center) and then re-parents the asset's root entity under this node,
 * so moving/rotating/scaling the node moves the whole model — Android's
 * `ModelNode` adopting `modelInstance.root`, verbatim.
 *
 * Loading is asynchronous: [asset] is `null` until the model's glTF parse
 * completes (the web analog of `rememberModelInstance`'s null-while-loading
 * contract). The node itself is usable immediately — transforms set while
 * loading apply to the pivot and are inherited by the model when it lands.
 *
 * **Ownership (slice-2 semantics, revisited in P4):** the [FilamentAsset] is
 * owned by the `SceneView` asset tracker, not by this node — [destroy] frees
 * the node's own pivot entity but NOT the asset (use `SceneView.destroy()`
 * or a same-URL reload, #1597, for that). Two `addModelNode` calls with the
 * same URL share the pipeline's per-URL replacement identity: the second
 * load supersedes and frees the first node's asset content.
 */
class ModelNode internal constructor(
    backend: NodeBackend,
) : Node(backend) {

    /** See [Node]'s secondary constructor. */
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))

    /**
     * The loaded gltfio asset, `null` while the async load is in flight (or
     * after it failed — check the console for the pipeline's error log).
     */
    var asset: FilamentAsset? = null
        internal set
}
