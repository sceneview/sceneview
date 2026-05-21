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
            // Position + Normals + UV Coordinates
            .bufferCount(1)
            // Position Attribute (x, y, z)
            .attribute(VertexAttribute.POSITION, 0, AttributeType.FLOAT3)
            .vertexCount(streetscapeGeometry.mesh.vertexListSize)
            .build(engine)
            .apply {
                setBufferAt(engine, 0, streetscapeGeometry.mesh.vertexList)
            },
        indexBuffer = IndexBuffer.Builder()
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .indexCount(streetscapeGeometry.mesh.indexListSize)
            .build(engine)
            .apply {
                setBuffer(engine, streetscapeGeometry.mesh.indexList)
            },
        materialInstance = meshMaterialInstance,
        builder = builder
    ).apply { parent = this@StreetscapeGeometryNode }

    val type get() = streetscapeGeometry.type
    val quality get() = streetscapeGeometry.quality

    init {
        trackable = streetscapeGeometry
    }

    override fun update(trackable: StreetscapeGeometry?) {
        super.update(trackable)

        if (streetscapeGeometry.trackingState == TrackingState.TRACKING) {
            pose = streetscapeGeometry.meshPose
        }
    }
}