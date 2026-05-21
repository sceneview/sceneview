package io.github.sceneview.ar.xr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.xr.arcore.Face
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Opt-in marker for the **preview** Jetpack XR layer of SceneView.
 *
 * Every public symbol that wraps `androidx.xr.arcore` (`1.0.0-alpha14` at the
 * time of writing) carries this annotation. The upstream SDK is alpha and
 * subject to breaking changes; requiring an explicit opt-in makes the preview
 * status visible at every call site rather than burying it in a doc comment.
 *
 * Opt in per call site with `@OptIn(XrPreviewApi::class)`, or module-wide via
 * the `-opt-in` compiler flag.
 *
 * See [arsceneview/docs/JETPACK-XR-INTEGRATION.md](https://github.com/sceneview/sceneview/blob/main/arsceneview/docs/JETPACK-XR-INTEGRATION.md)
 * — tracking issue [#1738](https://github.com/sceneview/sceneview/issues/1738).
 */
@RequiresOptIn(
    message = "Jetpack XR API is preview (androidx.xr.arcore 1.0.0-alpha14) and may change.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class XrPreviewApi

/**
 * A scene-graph node that mirrors a face tracked by **ARCore for Jetpack XR**
 * (`androidx.xr.arcore.Face`) on an Android XR headset or glasses.
 *
 * `XrFaceNode` is the Jetpack XR sibling of
 * [io.github.sceneview.ar.node.AugmentedFaceNode] — the same "one node per
 * tracked face, child nodes following named regions" shape, but driven by the
 * headset's user-facing cameras instead of the phone's front (selfie) camera.
 * The two coexist: phones keep using `AugmentedFaceNode`, Android XR devices
 * get `XrFaceNode`.
 *
 * It exposes:
 *  - one child [Node] per [XrFaceRegion] ([regionNodes]) whose transform
 *    follows the live region pose — attach a model to [regionNode] of
 *    [XrFaceRegion.NOSE_TIP] to anchor glasses, of [XrFaceRegion.CENTER] for
 *    a full-face overlay;
 *  - the decoded dense mesh ([mesh]) — a runtime-free [XrFaceMeshData] of
 *    vertex / normal / index buffers — so a consumer can build its own
 *    [io.github.sceneview.node.MeshNode] skin overlay. `XrFaceNode` does not
 *    create any Filament mesh resource itself; building geometry stays a
 *    deliberate, main-thread step the consumer owns.
 *
 * **Updating.** This node holds no `StateFlow` subscription of its own — the
 * consumer drives it. Collect [Face.state] on the main dispatcher and call
 * [update] with each emitted snapshot, or call the no-arg [update] to pull the
 * current `Face.state.value`. The composable wrapper [SceneScope.XrFaceNode]
 * does this for you.
 *
 * **Threading.** [update] only writes Filament `Node` transforms (position /
 * quaternion) and decodes the mesh into plain arrays — it performs no Filament
 * JNI resource creation — but, like every `Node` mutation, it MUST run on the
 * main thread. Drive it from a Compose `LaunchedEffect` collecting the flow on
 * `Dispatchers.Main` (the composable wrapper already does). See `CLAUDE.md`
 * "Critical threading rule".
 *
 * **Availability.** Only meaningful when the Jetpack XR runtime is present —
 * gate construction on [XrFeatures.isAvailable]. On a phone-only build the
 * `androidx.xr.arcore` classes are absent and merely referencing [Face] would
 * throw `NoClassDefFoundError` at class-load time, so never instantiate this
 * node unless [XrFeatures.isAvailable] returned `true`.
 *
 * Preview: this API wraps `androidx.xr.arcore` `1.0.0-alpha14` and may change —
 * see [XrPreviewApi].
 *
 * @property engine             The Filament [Engine] that owns the region child nodes.
 * @property face               The upstream [Face] trackable this node mirrors.
 * @property meshMaterialInstance Optional material the consumer would assign to a
 *                              skin-overlay [io.github.sceneview.node.MeshNode]
 *                              built from [mesh]. Mirrors the
 *                              `AugmentedFaceNode` constructor parameter for API
 *                              familiarity; `XrFaceNode` itself does not upload a
 *                              mesh, so it merely stores this for the consumer.
 */
@XrPreviewApi
open class XrFaceNode(
    engine: Engine,
    val face: Face,
    val meshMaterialInstance: MaterialInstance? = null,
    /**
     * Optional per-frame callback fired after [update] has applied the latest
     * region poses and decoded the mesh. Receives this node so the consumer can
     * read [isTracking] / [mesh] / [regionNodes] and react.
     */
    private val onUpdated: ((XrFaceNode) -> Unit)? = null,
) : Node(engine) {

    /**
     * One child [Node] per [XrFaceRegion], indexed by [XrFaceRegion.ordinal].
     *
     * Each region node's local transform is rewritten every [update] to the
     * latest tracked pose. A region that is not tracked this frame has its node
     * hidden ([Node.isVisible] = `false`) rather than left at a stale pose.
     * Attach renderable geometry — or a loaded model — as children of these
     * nodes.
     */
    val regionNodes: List<Node> = List(XrFaceMesh.regionCount) {
        Node(engine).apply { parent = this@XrFaceNode }
    }

    /**
     * Returns the region node for [region] — a convenience over
     * `regionNodes[region.ordinal]`.
     */
    fun regionNode(region: XrFaceRegion): Node = regionNodes[region.ordinal]

    /**
     * Snapshot of each region's local position from the most recent [update],
     * indexed by [XrFaceRegion.ordinal]. A `null` slot is a region that was not
     * tracked. Pass this to [XrFaceMesh.trackedRegionCount] /
     * [XrFaceMesh.regionDistance] — it is the bridge between the preview XR
     * poses and the pure-logic face math.
     */
    val regionPositions: Array<Position?> = arrayOfNulls(XrFaceMesh.regionCount)

    /**
     * The dense face mesh decoded from the most recent [update] — a runtime-free
     * [XrFaceMeshData] of vertex / normal / index buffers — or `null` until the
     * first tracked frame with mesh data.
     *
     * Feed it to [XrFaceMesh.centroid] / [XrFaceMesh.extent] / [XrFaceMesh.isValid],
     * or upload it to a [io.github.sceneview.node.MeshNode] to draw a skin
     * overlay (a main-thread step the consumer owns).
     */
    var mesh: XrFaceMeshData? = null
        private set

    /** Whether the face was tracked in the most recent [update]. */
    var isTracking: Boolean = false
        private set

    /**
     * Pulls the current [Face.state] snapshot and applies it — equivalent to
     * `update(readFaceRegions(face))` plus `mesh = readFaceMesh(face)`.
     */
    fun update() {
        update(readFaceRegions(face), readFaceMesh(face))
    }

    /**
     * Applies a pre-extracted set of region poses and an optional decoded mesh.
     *
     * [regions] is keyed by [XrFaceRegion]; a missing key is an untracked
     * region. [meshData] is the decoded dense mesh or `null` when no mesh was
     * available this frame. Use this overload when the consumer already
     * collected [Face.state] and converted the poses (e.g. transformed into a
     * different coordinate space) — it never touches the preview XR types.
     *
     * Must run on the main thread (writes `Node` transforms).
     */
    fun update(regions: Map<XrFaceRegion, Position>, meshData: XrFaceMeshData?) {
        var tracked = 0
        for (region in XrFaceRegion.entries) {
            val node = regionNodes[region.ordinal]
            val position = regions[region]
            if (position != null) {
                node.position = position
                node.isVisible = true
                regionPositions[region.ordinal] = position
                tracked++
            } else {
                node.isVisible = false
                regionPositions[region.ordinal] = null
            }
        }
        // Only accept a mesh that passes the buffer-layout sanity check — a
        // malformed alpha-SDK frame is dropped rather than carried forward.
        mesh = meshData?.takeIf { XrFaceMesh.isValid(it) }
        isTracking = tracked > 0 || (mesh?.vertexCount ?: 0) > 0
        onUpdated?.invoke(this)
    }

    override fun destroy() {
        // Region child nodes are owned by this node — destroy them before the
        // parent chain tears down so their entities leave the Filament scene
        // first (same ordering rationale as AugmentedFaceNode.destroy()).
        regionNodes.forEach { runCatching { it.destroy() } }
        super.destroy()
    }
}

/**
 * Reads the live named-region poses of [face] into a [XrFaceRegion]-keyed map.
 *
 * Bridges the upstream `androidx.xr.arcore` perception types to SceneView's own
 * [Position]. The upstream `Face.State` exposes a `meshCenterPose` plus a
 * `regionPoseMap: Map<FaceMeshRegion, Pose>`; this function matches the center
 * pose to [XrFaceRegion.CENTER] and each upstream `FaceMeshRegion` to a
 * SceneView [XrFaceRegion] **by constant name** ([XrFaceRegion.upstreamName])
 * so the mapping survives the alpha SDK adding or renaming regions — an
 * unrecognised upstream region is simply skipped.
 *
 * Every upstream access is reflective so this function never hard-references an
 * individual `FaceMeshRegion` constant a future alpha might rename. It still
 * requires the `androidx.xr.arcore` runtime to be present — only call it behind
 * [XrFeatures.isAvailable].
 *
 * Returns an empty map when the face is not currently tracked or the upstream
 * state cannot be read.
 */
@XrPreviewApi
fun readFaceRegions(face: Face): Map<XrFaceRegion, Position> = runCatching {
    // `Face.state` is a StateFlow<Face.State>; `.value` is the current snapshot.
    val state: Any = face.state.value
    val result = HashMap<XrFaceRegion, Position>(XrFaceMesh.regionCount)

    // CENTER comes from `meshCenterPose`, not the region map.
    runCatching {
        val centerPose = state.javaClass.getMethod("getMeshCenterPose").invoke(state)
        centerPose?.toPosition()?.let { result[XrFaceRegion.CENTER] = it }
    }

    // Named regions — `regionPoseMap: Map<FaceMeshRegion, Pose>`.
    runCatching {
        @Suppress("UNCHECKED_CAST")
        val regionMap = state.javaClass.getMethod("getRegionPoseMap").invoke(state)
            as? Map<Any, Any> ?: return@runCatching
        val byName = XrFaceRegion.entries
            .filter { it.upstreamName != null }
            .associateBy { it.upstreamName }
        for ((regionType, pose) in regionMap) {
            // FaceMeshRegion exposes its constant name via toString().
            val sceneRegion = byName[regionType.toString()]
            val position = sceneRegion?.let { pose.toPosition() }
            if (sceneRegion != null && position != null) {
                result[sceneRegion] = position
            }
        }
    }
    result
}.getOrDefault(emptyMap())

/**
 * Reads the dense face mesh of [face] into a runtime-free [XrFaceMeshData].
 *
 * The upstream `Face.State.meshData` is a `Mesh` of NIO buffers — a
 * `ShortBuffer` of triangle indices, `FloatBuffer`s of vertices and normals.
 * This copies them into plain Kotlin arrays so every downstream consumer (the
 * geometry upload, the JVM tests) works on a value with no `androidx.xr.arcore`
 * dependency.
 *
 * Every upstream access is reflective so a rename of the `meshData` property or
 * the buffer accessors on the alpha SDK degrades to `null` (mesh hidden)
 * instead of a hard crash. Returns `null` when the face is not tracked or the
 * upstream mesh cannot be read.
 */
@XrPreviewApi
fun readFaceMesh(face: Face): XrFaceMeshData? = runCatching {
    val state: Any = face.state.value
    val meshObj = state.javaClass.getMethod("getMeshData").invoke(state) ?: return null

    val indicesBuffer = meshObj.javaClass.getMethod("getTriangleIndices").invoke(meshObj)
        as? ShortBuffer ?: return null
    val verticesBuffer = meshObj.javaClass.getMethod("getVertices").invoke(meshObj)
        as? FloatBuffer ?: return null
    val normalsBuffer = meshObj.javaClass.getMethod("getNormals").invoke(meshObj)
        as? FloatBuffer

    val vertices = verticesBuffer.toFloatArray()
    val normals = normalsBuffer?.toFloatArray()
    val indices = indicesBuffer.toIntArray()
    XrFaceMeshData(vertices = vertices, normals = normals, indices = indices)
}.getOrNull()

/** Copies a [FloatBuffer]'s remaining contents into a fresh [FloatArray]. */
private fun FloatBuffer.toFloatArray(): FloatArray {
    val out = FloatArray(remaining())
    duplicate().get(out)
    return out
}

/** Copies a [ShortBuffer]'s remaining contents into a fresh [IntArray]. */
private fun ShortBuffer.toIntArray(): IntArray {
    val dup = duplicate()
    return IntArray(dup.remaining()) { dup.get().toInt() and 0xFFFF }
}

/**
 * Converts an upstream `androidx.xr.runtime.math.Pose` to a SceneView
 * [Position] by reading its `translation` ( `Vector3` ) reflectively. Returns
 * `null` if the pose shape is not what this preview integration expects.
 */
private fun Any.toPosition(): Position? = runCatching {
    val translation = javaClass.getMethod("getTranslation").invoke(this)
    val x = translation.javaClass.getMethod("getX").invoke(translation) as Float
    val y = translation.javaClass.getMethod("getY").invoke(translation) as Float
    val z = translation.javaClass.getMethod("getZ").invoke(translation) as Float
    Position(x = x, y = y, z = z)
}.getOrNull()

/**
 * Declarative [XrFaceNode] for use inside a `SceneView { }` / `ARSceneView { }`
 * content block.
 *
 * Mirrors the `SceneScope.SphereNode(...)` etc. composable style: it creates
 * and remembers the imperative [XrFaceNode], attaches it to the scene for the
 * lifetime of the composition, and exposes the region child nodes through a
 * [io.github.sceneview.NodeScope] `content` block so renderable geometry can
 * be nested under them.
 *
 * **Pose refresh.** The composable re-reads the live face pose in a
 * `SideEffect`, which runs after every successful (re)composition on the main
 * thread. Consumers that recompose once per XR frame (e.g. by hoisting frame
 * state from `onSessionUpdated`) get a per-frame face update for free, without
 * spinning up a separate `StateFlow` collector. Consumers that need an update
 * cadence decoupled from recomposition can instead collect [Face.state]
 * themselves and call [XrFaceNode.update] on the imperative node.
 *
 * ```kotlin
 * @OptIn(XrPreviewApi::class)
 * SceneView {
 *     if (XrFeatures.isAvailable(context)) {
 *         XrFaceNode(face = Face.getUserFace(session)) {
 *             // `this` is a NodeScope rooted at the face node — declare per-region
 *             // geometry; each region node follows the tracked pose.
 *         }
 *     }
 * }
 * ```
 *
 * Preview: see [XrPreviewApi].
 *
 * @param face                The upstream [Face] trackable to mirror.
 * @param meshMaterialInstance Optional material for a consumer-built skin overlay.
 * @param onUpdated           Optional callback fired after each update with the node.
 * @param apply               Imperative configuration applied once on creation.
 * @param content             Optional child nodes declared in a `NodeScope` rooted
 *                            at the face node.
 */
@XrPreviewApi
@Composable
fun SceneScope.XrFaceNode(
    face: Face,
    meshMaterialInstance: MaterialInstance? = null,
    onUpdated: ((XrFaceNode) -> Unit)? = null,
    apply: XrFaceNode.() -> Unit = {},
    content: (@Composable NodeScope.() -> Unit)? = null,
) {
    val node = remember(engine, face) {
        XrFaceNode(
            engine = engine,
            face = face,
            meshMaterialInstance = meshMaterialInstance,
            onUpdated = onUpdated,
        ).apply(apply)
    }
    // Re-read the live face pose on every recomposition. The consumer triggers a
    // recomposition per XR frame (e.g. from `onSessionUpdated`), so SideEffect —
    // which runs after a successful composition on the main thread — is the
    // right place to push transforms without spinning up a coroutine collector.
    SideEffect {
        node.update()
    }
    NodeLifecycle(node, content)
}
