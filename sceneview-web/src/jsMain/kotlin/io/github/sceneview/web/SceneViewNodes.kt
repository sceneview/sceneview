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
    addGeometry(config)?.let { asset ->
        node.adoptChildEntity(asset.getRoot())
        node.asset = asset
    }
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
