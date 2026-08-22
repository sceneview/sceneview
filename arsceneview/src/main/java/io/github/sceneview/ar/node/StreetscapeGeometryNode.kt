package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
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
    val meshNode = MeshNode(
        engine = engine,
        primitiveType = PrimitiveType.TRIANGLES,
        vertexBuffer = VertexBuffer.Builder()
            // POSITION + TANGENTS + UV0, one backing buffer each (#3215).
            //
            // ARCore ships positions only, but the lit materials this node is normally given
            // (`opaque_colored` / `transparent_colored` from MaterialLoader.createColorInstance)
            // require POSITION|TANGENTS|UV0 (MAT_REQA 0xB). Filament does not fail the
            // mismatch — it logs `missing required attributes (0xb), declared=0x1` and shades
            // the overlay with a constant fallback normal. So the node derives smooth
            // per-vertex normals from the mesh once, at construction, and encodes them as
            // tangent quaternions; UV0 is a zero buffer (no natural parameterisation, and the
            // colored materials never sample it). See StreetscapeMeshAttributes.kt.
            .bufferCount(BUFFER_COUNT)
            // Position Attribute (x, y, z)
            .attribute(VertexAttribute.POSITION, BUFFER_INDEX_POSITION, AttributeType.FLOAT3)
            // Tangent frame quaternion (x, y, z, w) — Filament's per-vertex normal input.
            // No `.normalized(TANGENTS)`: that flag is for integer formats, these are FLOAT4.
            .attribute(
                VertexAttribute.TANGENTS,
                BUFFER_INDEX_TANGENT,
                AttributeType.FLOAT4,
                0,
                STREETSCAPE_TANGENT_STRIDE
            )
            .attribute(
                VertexAttribute.UV0,
                BUFFER_INDEX_UV,
                AttributeType.FLOAT2,
                0,
                STREETSCAPE_UV_STRIDE
            )
            .vertexCount(streetscapeGeometry.mesh.vertexListSize)
            .build(engine)
            .apply {
                val mesh = streetscapeGeometry.mesh
                setBufferAt(engine, BUFFER_INDEX_POSITION, mesh.vertexList)
                setBufferAt(
                    engine,
                    BUFFER_INDEX_TANGENT,
                    computeStreetscapeTangents(mesh.vertexList, mesh.indexList, mesh.vertexListSize)
                )
                setBufferAt(engine, BUFFER_INDEX_UV, zeroStreetscapeUvs(mesh.vertexListSize))
            },
        indexBuffer = IndexBuffer.Builder()
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .indexCount(streetscapeGeometry.mesh.indexListSize)
            .build(engine)
            .apply {
                setBuffer(engine, streetscapeGeometry.mesh.indexList)
            },
        materialInstance = meshMaterialInstance,
        // This node builds the VertexBuffer/IndexBuffer above just for this MeshNode and
        // owns them exclusively — free them when the node is destroyed (#2037). The mesh
        // node is a child of this node, so Node.destroy()'s recursive child teardown
        // (#2036) reaches it when the StreetscapeGeometryNode is destroyed.
        destroyBuffersOnDispose = true,
        builder = builder
    ).apply { parent = this@StreetscapeGeometryNode }

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
    }

    override fun update(trackable: StreetscapeGeometry?) {
        super.update(trackable)

        // Bail while the constructor dispatch is in flight (#2624) — init applies the state below.
        if (!constructed) return
        applyTrackableState()
    }

    /** The class-specific trackable refresh — gated behind [constructed] (#2624). */
    private fun applyTrackableState() {
        if (streetscapeGeometry.trackingState == TrackingState.TRACKING) {
            pose = streetscapeGeometry.meshPose
        }
    }
}

// Vertex buffer slots of a StreetscapeGeometryNode. File-private: a `const val` in a
// `private companion object` still compiles to a public static field on the class and
// trips apiCheck.
private const val BUFFER_INDEX_POSITION = 0
private const val BUFFER_INDEX_TANGENT = 1
private const val BUFFER_INDEX_UV = 2
private const val BUFFER_COUNT = 3
