package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingState
import io.github.sceneview.node.MeshNode

/**
 * Defines geometry such as terrain, buildings, or other structures obtained from the Streetscape
 * Geometry API. See the <a
 * href="https://developers.google.com/ar/develop/java/geospatial/streetscape-geometry">Streetscape
 * Geometry Developer Guide</a> for additional information.
 *
 * Obtained from a call to [Session.getAllTrackables] or [Frame.getUpdatedTrackables] when
 * [Config.StreetscapeGeometryMode] is set to [Config.StreetscapeGeometryMode.ENABLED] and
 * [Config.GeospatialMode] is set to [Config.GeospatialMode.ENABLED].
 *
 * ### Empty meshes
 *
 * The renderable [meshNode] is built with an axis-aligned bounding box computed from the mesh
 * vertices. When ARCore reports a mesh with no vertex or no whole triangle, no renderable is
 * built and [meshNode] is `null`; the node then draws nothing. Each time ARCore reports the
 * geometry as updated with a different vertex or index count, the renderable is rebuilt (or
 * dropped, if the mesh became empty). A rebuild replaces the [meshNode] instance, so changes made
 * directly on it are lost; configure it through `meshMaterialInstance` and `builder`.
 *
 * ### Geospatial Depth (ARCore 1.54+, #1731)
 *
 * Enabling Streetscape Geometry together with [Config.DepthMode.AUTOMATIC] activates ARCore's
 * **Geospatial Depth** fusion: motion-stereo depth (reliable to ~8 m) is fused with Streetscape
 * Geometry + device sensors so [com.google.ar.core.Frame.acquireDepthImage16Bits] returns valid
 * pixels out to **~65 m**. No additional API surface is required — every depth consumer
 * ([com.google.ar.core.Frame.hitTestDepth][io.github.sceneview.ar.arcore.hitTestDepth],
 * [io.github.sceneview.ar.node.DepthMeshNode], [io.github.sceneview.ar.physics.rememberDepthCollider],
 * `ARCameraStream` occlusion) sees the extended range transparently. Outside VPS-covered areas
 * depth falls back to the motion-stereo ~8 m range.
 */
open class StreetscapeGeometryNode(
    engine: Engine,
    val streetscapeGeometry: StreetscapeGeometry,
    meshMaterialInstance: MaterialInstance? = null,
    builder: RenderableManager.Builder.() -> Unit = {},
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((StreetscapeGeometry) -> Unit)? = null
) : TrackableNode<StreetscapeGeometry>(
    engine = engine,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated
) {
    // Builds, rebuilds and drops the renderable as the ARCore mesh changes.
    private val meshRenderable = StreetscapeMeshRenderable(
        engine = engine,
        parent = this,
        materialInstance = meshMaterialInstance,
        builder = builder
    )

    /**
     * The renderable mesh of [streetscapeGeometry], or `null` while ARCore reports an empty
     * mesh (no vertex or no whole triangle). Filament cannot build a renderable for an empty
     * mesh, so none is created; the node draws nothing until a geometry update brings vertices.
     *
     * The instance is replaced when ARCore changes the mesh (see "Empty meshes" above): changes
     * made directly on it (visibility, layer, material, touchability, ...) are lost on a rebuild.
     * Configure the renderable through the constructor's `meshMaterialInstance` and `builder`,
     * which every rebuild re-applies, or on this node.
     */
    val meshNode: MeshNode? get() = meshRenderable.meshNode

    val type get() = streetscapeGeometry.type
    val quality get() = streetscapeGeometry.quality

    // `trackable = streetscapeGeometry` below virtually dispatches the open update() — a subclass
    // override runs BEFORE the subclass's own fields are initialized (#2624, the bug class behind
    // the 4.21.0 ShadowReceiverPlaneNode crash #2621; the in-repo subclass SceneMeshNode does not
    // override update(), but the trap stays armed for user subclasses). This flag gates this
    // class's update() tail until construction completes; init then applies the initial state
    // explicitly, so the construction end-state is byte-for-byte unchanged.
    private var constructed = false

    init {
        trackable = streetscapeGeometry
        constructed = true
        // Apply the initial pose that the gated update() skipped during the constructor dispatch.
        applyTrackableState()
        // Build the initial renderable (none for an empty mesh).
        syncMesh()
    }

    override fun update(trackable: StreetscapeGeometry?) {
        super.update(trackable)

        // Bail while the constructor dispatch is in flight (#2624) — init applies the state below.
        if (!constructed) return
        applyTrackableState()
        // Only reached when ARCore reported this geometry as updated this frame.
        syncMesh()
    }

    /** The class-specific trackable refresh — gated behind [constructed] (#2624). */
    private fun applyTrackableState() {
        if (streetscapeGeometry.trackingState == TrackingState.TRACKING) {
            pose = streetscapeGeometry.meshPose
        }
    }

    /**
     * Builds [meshNode] on the first call, then rebuilds it when the mesh's vertex or index count
     * changed since it was built, and drops it when the mesh became empty. Only reads the vertex
     * and index buffers when a (re)build happens.
     */
    private fun syncMesh() {
        val mesh = streetscapeGeometry.mesh
        meshRenderable.sync(
            vertexCount = mesh.vertexListSize,
            indexCount = mesh.indexListSize,
            vertexList = { mesh.vertexList },
            indexList = { mesh.indexList }
        )
    }
}
