package io.github.sceneview.web.nodes

import io.github.sceneview.math.Transform
import io.github.sceneview.math.copyColumnsInto
import io.github.sceneview.web.bindings.Camera
import io.github.sceneview.web.bindings.Engine
import io.github.sceneview.web.bindings.Entity
import io.github.sceneview.web.bindings.EntityManager

/**
 * A [Node] that drives a Filament [Camera] from its scene-graph transform —
 * the web mirror of Android `io.github.sceneview.node.CameraNode` (issue #2024,
 * slice 2b of `.claude/plans/v5-web-node-graph.md`).
 *
 * Every frame ([onFrame], fanned out from `SceneView.renderLoop` through
 * `SceneGraph.dispatchFrame`) the node pushes its [worldTransform] to
 * `Camera.setModelMatrix` — byte-identical to Android `CameraNode`, which
 * synchronises the RealityKit/Filament camera from the node's `worldTransform`.
 * So moving/rotating the node (or a parent) moves the camera, and the node's
 * position/rotation are the single source of truth for the view.
 *
 * `Camera.setModelMatrix` is proven in-browser by the `kotlin-bundle.spec.ts`
 * #2024-P1 slice-2b probe before this node depends on it (the embind
 * probe-first rule — Karma stubs `Camera`, so `jsTest` can never validate it).
 *
 * **Conflict with the orbit controller (honest slice-2b scope):** the default
 * `SceneView` orbit camera writes `camera.lookAt(...)` every frame it moves, so
 * a `CameraNode` and the orbit controller both driving the *same* camera would
 * fight. `addCameraNode` therefore only makes sense with `cameraControls(false)`
 * (manual camera). The DSL `camera { }` block keeps its existing
 * projection/exposure/orbit behaviour unchanged (visual byte-identical); the
 * `CameraNode` is an incubating Kotlin/JS surface for manual-camera callers,
 * mirroring how slice 1 shipped the base `Node` as incubating. Full DSL
 * re-wiring of `camera { }` onto a `CameraNode` is deferred until the orbit
 * controller is expressed as a node-driving controller (post-v5).
 */
class CameraNode internal constructor(
    backend: NodeBackend,
) : Node(backend) {

    /** See [Node]'s secondary constructor. */
    constructor(engine: Engine, entity: Entity = EntityManager.get().create()) :
        this(FilamentNodeBackend(engine, entity))

    /**
     * The engine-side seam pushing the node's world transform to the Filament
     * camera, `null` until `SceneView.addCameraNode` wires it (or for a
     * test-backed node). Kept as an interface so the per-frame sync is
     * `jsTest`-checkable without the Filament WASM module — production uses
     * [FilamentCameraController].
     */
    internal var controller: CameraController? = null

    /** Reusable column-major mat4 scratch — keeps [onFrame] allocation-light. */
    private val matrixScratch = FloatArray(16)

    /**
     * Pushes the current [worldTransform] to the camera. A no-op after
     * [destroy] (the guard mirrors [Node.applyLocalTransform]) and when no
     * controller is wired.
     *
     * @param deltaSeconds Unused — the camera follows the node's transform, not
     *   a time-based animation; kept to satisfy the `SceneNode.onFrame` contract.
     */
    override fun onFrame(deltaSeconds: Float) {
        if (isDestroyed) return
        val controller = controller ?: return
        writeModelMatrix(worldTransform)
        controller.setModelMatrix(matrixScratch)
    }

    private fun writeModelMatrix(transform: Transform) {
        transform.copyColumnsInto(matrixScratch)
    }
}

/**
 * Engine-side seam for [CameraNode]'s per-frame camera sync — the camera analog
 * of [NodeBackend]. Exists as an interface so the [CameraNode.onFrame] sync is
 * unit-testable in `jsTest` without the Filament WASM module (a recording
 * fake); production forwards to `Camera.setModelMatrix` via
 * [FilamentCameraController].
 */
internal interface CameraController {
    /** @param columnMajorMat4 a flat 16-element column-major mat4. */
    fun setModelMatrix(columnMajorMat4: FloatArray)
}

/** Production [CameraController]: forwards to a Filament [Camera]. */
internal class FilamentCameraController(private val camera: Camera) : CameraController {
    /** Reusable flat JS array — a camera sync never allocates (#2268 scratch pattern). */
    private val jsScratch: dynamic = js("new Array(16)")

    override fun setModelMatrix(columnMajorMat4: FloatArray) {
        for (i in 0..15) {
            jsScratch[i] = columnMajorMat4[i]
        }
        camera.setModelMatrix(jsScratch)
    }
}
