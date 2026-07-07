package io.github.sceneview.web

import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.web.nodes.Node

/**
 * The viewer-side callbacks a [NodeHandle] fires on mutation — the seam between
 * the exported handle and the [SceneView] behind it. An interface (not
 * [SceneViewJS] directly) so the handle's delegation logic is `jsTest`-checkable
 * with a fake host, without the Filament WASM module (Karma cannot load it). The
 * production host is [SceneViewJS].
 */
internal interface NodeHost {
    /** Repaint request after a transform/hierarchy change (#2332). */
    fun requestRender()

    /** Apply the render-side visibility cascade for [node] (scene add/remove). */
    fun applyNodeVisibility(node: Node)

    /** Detach [node] from the scene graph (without destroying it). */
    fun removeNodeInternal(node: Node)
}

/**
 * `NodeHandle` — the first `@JsExport` scene-graph surface of `sceneview-web`
 * (issue #2024, slice 3 / P4 of `.claude/plans/v5-web-node-graph.md`).
 *
 * An **opaque** JS handle to a retained Kotlin [Node] living in a
 * [SceneViewJS]'s scene graph. Plain-JS callers get one back from
 * `sv.addNode()` / `sv.addCubeNode(...)` / `sv.addModelNode(...)` etc. and use
 * it to *address* a node after creation — the thing the fire-and-forget builder
 * DSL (Surface A) could never do. Method names read the same as the Kotlin /
 * Android [Node] API so an AI that knows one emits correct code for the other.
 *
 * Opaqueness is deliberate: the wrapped [Node] and its Filament internals are
 * **not** exposed to JS. The handle offers only the minimal, stable mutators
 * below — a first export slice, additive by design (§3.3 of the design doc).
 * Transform setters are flat scalar triples (not a transform object) so the JS
 * ergonomics match `sceneview.js`'s existing hand-written mental model; richer
 * shapes can be added later without breaking these.
 *
 * All numbers are plain JS `number`s. Angles are **Euler degrees** (ZYX), the
 * same convention as [Node.rotation] and Android `Node.rotation`.
 *
 * @see SceneViewJS.addNode
 * @see SceneViewJS.addModelNode
 */
@JsExport
@JsName("NodeHandle")
class NodeHandle internal constructor(
    internal val node: Node,
    private val viewer: NodeHost,
) {

    /**
     * Sets the node's position in its parent's coordinate space (world space
     * for a root node). +x right, +y up, -z forward — the same axes as Android
     * `Node.position`.
     */
    @JsName("setPosition")
    fun setPosition(x: Double, y: Double, z: Double) {
        node.position = Position(x.toFloat(), y.toFloat(), z.toFloat())
        viewer.requestRender()
    }

    /**
     * Sets the node's rotation as Euler angles in **degrees** (ZYX order), in
     * the parent's coordinate space — mirrors Android `Node.rotation`.
     */
    @JsName("setRotation")
    fun setRotation(x: Double, y: Double, z: Double) {
        node.rotation = Rotation(x.toFloat(), y.toFloat(), z.toFloat())
        viewer.requestRender()
    }

    /**
     * Sets a non-uniform scale on each axis. Prefer [setScaleUniform] for the
     * common uniform case (avoids the `Scale(x = …)` one-component gotcha).
     */
    @JsName("setScale")
    fun setScale(x: Double, y: Double, z: Double) {
        node.scale = Scale(x.toFloat(), y.toFloat(), z.toFloat())
        viewer.requestRender()
    }

    /** Sets a uniform scale on all three axes — the common case. */
    @JsName("setScaleUniform")
    fun setScaleUniform(s: Double) {
        node.scale = Scale(s.toFloat())
        viewer.requestRender()
    }

    /**
     * Shows or hides the node's renderable content. For a content node
     * ([io.github.sceneview.web.nodes.ModelNode] /
     * [io.github.sceneview.web.nodes.GeometryNode]) this adds/removes the
     * node's asset entities from the Filament scene, so the model actually
     * disappears/reappears — the render-side visibility cascade (design doc
     * Phase 4). An empty pivot node has no renderable of its own; toggling it
     * only flips the stored flag (children are addressed individually).
     */
    @JsName("setVisible")
    fun setVisible(v: Boolean) {
        node.isVisible = v
        viewer.applyNodeVisibility(node)
    }

    /** Whether the node is currently marked visible. */
    val visible: Boolean get() = node.isVisible

    /**
     * Parents [child] under this node. The child keeps its local transform, so
     * its world transform changes to compose under this node — Android's
     * parent-setter contract.
     */
    @JsName("addChild")
    fun addChild(child: NodeHandle) {
        node.addChildNode(child.node)
        viewer.requestRender()
    }

    /** Detaches [child] from this node (no-op if it is not a child). */
    @JsName("removeChild")
    fun removeChild(child: NodeHandle) {
        node.removeChildNode(child.node)
        viewer.requestRender()
    }

    /**
     * The node's world-space position as a fresh `[x, y, z]` JS array —
     * composed through the parent chain by the shared `sceneview-core` math,
     * exactly like Android `Node.worldPosition`.
     */
    @JsName("getWorldPosition")
    fun getWorldPosition(): Array<Double> {
        val p = node.worldPosition
        return arrayOf(p.x.toDouble(), p.y.toDouble(), p.z.toDouble())
    }

    /**
     * Removes the node from the graph and frees its own Filament entity (and
     * its whole subtree). Idempotent — a second call is a no-op. After this the
     * handle must not be used.
     */
    @JsName("destroy")
    fun destroy() {
        viewer.removeNodeInternal(node)
        node.destroy()
        viewer.requestRender()
    }
}
