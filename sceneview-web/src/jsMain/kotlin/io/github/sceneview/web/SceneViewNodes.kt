package io.github.sceneview.web

import io.github.sceneview.web.bindings.FilamentAsset
import io.github.sceneview.web.nodes.CameraNode
import io.github.sceneview.web.nodes.CubeNode
import io.github.sceneview.web.nodes.CylinderNode
import io.github.sceneview.web.nodes.FilamentCameraController
import io.github.sceneview.web.nodes.FilamentLightController
import io.github.sceneview.web.nodes.GeometryConfig
import io.github.sceneview.web.nodes.GeometryNode
import io.github.sceneview.web.nodes.LightConfig
import io.github.sceneview.web.nodes.LightNode
import io.github.sceneview.web.nodes.ModelNode
import io.github.sceneview.web.nodes.Node
import io.github.sceneview.web.nodes.PlaneNode
import io.github.sceneview.web.nodes.SphereNode
import io.github.sceneview.web.nodes.SplatNode

/**
 * Node-returning content factories for [SceneView] — #2024 slice 2.
 *
 * Each factory runs the exact legacy pipeline ([SceneView.loadModel] /
 * [SceneView.addGeometry] — render-gate #2332, per-URL supersede #1597,
 * auto-center all intact) and re-parents the created asset's root entity
 * under a [Node] pivot, so the content is addressable and transformable
 * through the retained node tree. Same-package extensions (not `SceneView`
 * members) purely to keep the class under the detekt function budget.
 */

/**
 * Loads a glTF/GLB model and wraps it in a [ModelNode] added to the retained
 * node tree — the web mirror of Android's `ModelNode(modelInstance)`.
 *
 * The returned node is usable immediately; the model content attaches to it
 * asynchronously (`node.asset` is `null` until the glTF parses — transforms
 * set in the meantime apply to the pivot and are inherited by the model when
 * it lands, before the first frame can paint it).
 *
 * If the node is destroyed before the async load lands, the content is NOT
 * adopted and stays flat legacy content owned by the asset tracker (freed on
 * `destroy()` or a same-URL reload, #1597) — node-owned asset lifecycle
 * arrives with P4's root-node centering.
 */
fun SceneView.addModelNode(
    url: String,
    parent: Node? = null,
    autoAnimate: Boolean = true,
    scale: Float = 1f,
    onLoaded: ((FilamentAsset) -> Unit)? = null,
): ModelNode {
    val node = ModelNode(engine, newEntity())
    addNode(node, parent)
    loadModelInternal(
        url,
        onLoaded = onLoaded,
        autoAnimate = autoAnimate,
        scale = scale,
        // Node-owned: this content is framed via its ModelNode pivot, so the
        // flat-content auto-centre pass (P4) must not also re-parent/centre it.
        nodeOwned = true,
        onAssetCreated = { asset ->
            if (node.isDestroyed) {
                console.warn(
                    "SceneView: ModelNode for $url was destroyed before its " +
                        "load finished — content stays unparented (tracker-owned)",
                )
            } else {
                node.adoptChildEntity(asset.getRoot())
                node.asset = asset
            }
        },
    )
    return node
}

/**
 * Adds a procedural geometry primitive wrapped in a [GeometryNode]. Same
 * pipeline as [SceneView.addGeometry] (KMP core generators → in-memory GLB →
 * gltfio, so primitives get real PBR materials), with the generated asset's
 * root re-parented under the returned node.
 *
 * Prefer the typed factories ([addCubeNode] / [addSphereNode] /
 * [addCylinderNode] / [addPlaneNode]) when the shape is static — they mirror
 * the Android node names an AI already knows.
 */
fun SceneView.addGeometryNode(config: GeometryConfig, parent: Node? = null): GeometryNode =
    GeometryNode(engine, newEntity()).also { attachGeometry(it, config, parent) }

/** Adds a box primitive as a [CubeNode] — web mirror of Android `CubeNode`. */
fun SceneView.addCubeNode(
    size: Double = 1.0,
    parent: Node? = null,
    config: GeometryConfig.() -> Unit = {},
): CubeNode = CubeNode(engine, newEntity()).also {
    attachGeometry(it, GeometryConfig().apply { cube(); size(size) }.apply(config), parent)
}

/** Adds a sphere primitive as a [SphereNode] — web mirror of Android `SphereNode`. */
fun SceneView.addSphereNode(
    radius: Double = 1.0,
    parent: Node? = null,
    config: GeometryConfig.() -> Unit = {},
): SphereNode = SphereNode(engine, newEntity()).also {
    attachGeometry(it, GeometryConfig().apply { sphere(); radius(radius) }.apply(config), parent)
}

/** Adds a cylinder primitive as a [CylinderNode] — web mirror of Android `CylinderNode`. */
fun SceneView.addCylinderNode(
    radius: Double = 1.0,
    height: Double = 2.0,
    parent: Node? = null,
    config: GeometryConfig.() -> Unit = {},
): CylinderNode = CylinderNode(engine, newEntity()).also {
    attachGeometry(
        it,
        GeometryConfig().apply { cylinder(); radius(radius); height(height) }.apply(config),
        parent,
    )
}

/** Adds a plane (quad) primitive as a [PlaneNode] — web mirror of Android `PlaneNode`. */
fun SceneView.addPlaneNode(
    sizeX: Double = 1.0,
    sizeZ: Double = 1.0,
    parent: Node? = null,
    config: GeometryConfig.() -> Unit = {},
): PlaneNode = PlaneNode(engine, newEntity()).also {
    attachGeometry(
        it,
        GeometryConfig().apply { plane(); size(sizeX, 1.0, sizeZ) }.apply(config),
        parent,
    )
}

/**
 * Shared tail of the geometry-node factories: run the [SceneView.addGeometry]
 * pipeline and, if the (synchronous) asset build succeeded, adopt its root
 * under [node]. On build failure the node stays an empty pivot in the graph
 * with `asset == null` — same contract as a failed model load.
 */
private fun SceneView.attachGeometry(node: GeometryNode, config: GeometryConfig, parent: Node?) {
    addNode(node, parent)
    // Node-owned geometry: framed via this GeometryNode pivot, so it is excluded
    // from the flat-content auto-centre pass (#2024 P4).
    addGeometry(config, nodeOwned = true)?.let { asset ->
        node.adoptChildEntity(asset.getRoot())
        node.asset = asset
    }
}

/**
 * Adds a Gaussian Splatting cloud wrapped in a [SplatNode] added to the retained node
 * tree — the web mirror of Android's `SplatNode(splatCloud)` (#2646 P2).
 *
 * Synchronous overload for callers that already hold a parsed
 * [io.github.sceneview.core.splat.SplatCloud] (the shared KMP parsers —
 * `SplatParser.parse(bytes)`). Registers the node's batch renderables with the scene,
 * wires the painter's-sort camera feed to this view's camera, and hooks repaint +
 * scene-detach so `node.destroy()` cleans up completely.
 */
fun SceneView.addSplatNode(
    splatCloud: io.github.sceneview.core.splat.SplatCloud,
    parent: Node? = null,
): SplatNode {
    val node = SplatNode(engine, newEntity(), splatCloud)
    // Repaint is wired by addNode below (Node.propagateInvalidate, #2024 P5b).
    node.onDetach = { entities -> scene.removeEntities(entities) }
    node.cameraPositionProvider = {
        // Camera world position straight from Filament (a JS number[] float3).
        val p: dynamic = camera.getPosition()
        io.github.sceneview.math.Position(
            (p[0].unsafeCast<Double>()).toFloat(),
            (p[1].unsafeCast<Double>()).toFloat(),
            (p[2].unsafeCast<Double>()).toFloat(),
        )
    }
    addNode(node, parent)
    scene.addEntities(node.batchEntities.toTypedArray())
    requestRender()
    return node
}

/**
 * Fetches a `.ply` / `.spz` splat file, parses it through the shared KMP parsers, and
 * adds the resulting [SplatNode] — the async URL twin of the [SplatCloud] overload,
 * following [addModelNode]'s contract: the returned pivot [Node] is usable immediately
 * (transform it while the fetch is in flight), and the [SplatNode] attaches under it
 * when the load lands ([onLoaded] fires then; [onError] on fetch/parse failure).
 *
 * If the pivot is destroyed before the load lands, the cloud is dropped — nothing was
 * registered yet, so there is nothing to leak.
 */
fun SceneView.addSplatNode(
    url: String,
    parent: Node? = null,
    onLoaded: ((SplatNode) -> Unit)? = null,
    onError: ((Throwable) -> Unit)? = null,
): Node {
    val pivot = Node(engine, newEntity())
    addNode(pivot, parent)
    loadSplatCloud(url, onError = onError) { cloud ->
        if (cloud == null) return@loadSplatCloud
        if (pivot.isDestroyed) {
            console.warn(
                "SceneView: splat pivot for $url was destroyed before its load finished — cloud dropped",
            )
            return@loadSplatCloud
        }
        val node = addSplatNode(cloud, parent = pivot)
        onLoaded?.invoke(node)
    }
    return pivot
}

/**
 * Adds a Filament light wrapped in a [LightNode] added to the retained node
 * tree — the web mirror of Android's `LightNode()` (#2024 slice 2b).
 *
 * Runs the exact [SceneView.addLight] pipeline (same `LightManager.Builder`,
 * `scene.addEntity`, `lightEntities` tracking for #1700 teardown), then
 * re-parents the built light entity under the returned node's pivot and wires a
 * [FilamentLightController] so `node.intensity`/`color(...)`/`direction(...)`/
 * `position(...)` push straight through the `LightManager` instance bindings —
 * proven in-browser by the `kotlin-bundle.spec.ts` #2024-P1 slice-2b probe.
 *
 * Because the light entity is built from [config], the node's initial light
 * looks byte-identical to today's flat `light { }` path — the pivot is an
 * identity transform over the same light entity, so the visual result is
 * unchanged. Runtime mutation of the returned node is the additive part.
 */
fun SceneView.addLightNode(config: LightConfig, parent: Node? = null): LightNode {
    // newEntity() (not the external EntityManager.get() default) — this runs
    // during create() via the light{} DSL, before the external companion
    // resolves. See SceneView.newEntity.
    val node = LightNode(engine, config.type, newEntity())
    addNode(node, parent)
    val lightEntity = buildLightEntity(config)
    node.adoptChildEntity(lightEntity)

    // Seed the node's cached light state from `config` BEFORE wiring the
    // controller: the controller's flush-on-wire pushes the node's cached
    // fields, so without this seed it would clobber the `config` values
    // buildLightEntity just applied and render a custom light as the default
    // 100k white (#2024 slice-2b review). See LightNode.applyConfig.
    node.applyConfig(config)

    node.controller = FilamentLightController(engine.getLightManager(), lightEntity)
    return node
}

/**
 * Adds a [CameraNode] that drives the [SceneView] camera from its scene-graph
 * transform — the web mirror of Android's `CameraNode()` (#2024 slice 2b).
 *
 * The node pushes its `worldTransform` to `Camera.setModelMatrix` every frame
 * (via [CameraNode.onFrame]) — proven in-browser by the `kotlin-bundle.spec.ts`
 * #2024-P1 slice-2b probe. **Use with `cameraControls(false)`**: the default
 * orbit controller writes `camera.lookAt(...)` every frame it moves, so a
 * `CameraNode` and the orbit controller both driving the same camera would
 * fight. See [CameraNode]'s doc for the deferred full-DSL-rewire rationale.
 */
fun SceneView.addCameraNode(parent: Node? = null): CameraNode {
    val node = CameraNode(engine, newEntity())
    addNode(node, parent)
    node.controller = FilamentCameraController(camera)
    return node
}

/**
 * Render-side visibility cascade for a content node (#2024 slice 3 / P4).
 *
 * Adds or removes the node's asset entities from the Filament `Scene` so a
 * hidden [ModelNode] / [GeometryNode] actually disappears and a re-shown one
 * comes back — the design-doc Phase 4 cascade. Uses the already-bound
 * `Scene.addEntities` / `Scene.removeEntities` (proven in the kotlin-bundle
 * smoke test's rendering path), so **no new embind binding** is introduced. A
 * no-op for pivot nodes (no owned asset) and for light nodes (a [LightNode]'s
 * `asset` is `null`; light visibility is a later slice). The transform-graph
 * `isVisible` flag is owned by [Node]; this only performs the scene-membership
 * side-effect. A same-package extension (not a `SceneView` member) to keep the
 * class under the detekt function budget — same reason as the factories above.
 */
fun SceneView.applyNodeVisibility(node: Node) {
    val asset = when (node) {
        is ModelNode -> node.asset
        is GeometryNode -> node.asset
        else -> null
    } ?: return
    val entities = asset.getEntities()
    if (node.isVisible) scene.addEntities(entities) else scene.removeEntities(entities)
    requestRender()
}
