package io.github.sceneview.web.nodes

import io.github.sceneview.math.Position
import io.github.sceneview.math.centerOriginTranslation
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

/**
 * Aligns the AABB point selected by a **normalized** [origin] with the node origin — the web
 * mirror of Android `ModelNode.centerOrigin(Position)` (issue #2763).
 *
 * `origin` is a point of the model's bounding box in normalized AABB coordinates —
 * `0` = bounding-box center, `±1` = bounding-box faces, per axis. Translates the node by
 * `-(center + origin * halfExtent) * scale` (the shared `sceneview-core`
 * [centerOriginTranslation] — identical formula to Android, pinned by the
 * `centerOriginGoldenVectors` table) so the selected AABB point lands exactly on the node's
 * local origin, whatever the asset's authored pivot. Composes **additively** with the current
 * [Node.position], mirroring Android's contract: applying it more than once keeps shifting the
 * node, apply it once (typically right after load).
 *
 * - `Position(0f, 0f, 0f)` (default) — the bounding-box center lands on the node origin (true
 *   centering, even for models whose authored pivot is off-center).
 * - `Position(0f, -1f, 0f)` — center horizontal, bottom aligned: the model sits on the node
 *   origin.
 * - `Position(-1f, 1f, 0f)` — left | top aligned.
 *
 * A no-op while [ModelNode.asset] hasn't finished loading yet — `getBoundingBox()` is not
 * readable until then (#1597), so there is nothing to align against.
 */
fun ModelNode.centerOrigin(origin: Position = Position(x = 0f, y = 0f, z = 0f)) {
    val box = asset?.getBoundingBox() ?: return
    val min: dynamic = box.min
    val max: dynamic = box.max
    fun component(v: dynamic, i: Int) = (v[i] as Number).toFloat()
    val center = Position(
        (component(min, 0) + component(max, 0)) / 2f,
        (component(min, 1) + component(max, 1)) / 2f,
        (component(min, 2) + component(max, 2)) / 2f,
    )
    val halfExtent = Position(
        (component(max, 0) - component(min, 0)) / 2f,
        (component(max, 1) - component(min, 1)) / 2f,
        (component(max, 2) - component(min, 2)) / 2f,
    )
    position += centerOriginTranslation(center, halfExtent, scale, origin)
}
