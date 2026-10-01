package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import com.google.android.filament.VertexBuffer.AttributeType
import com.google.android.filament.VertexBuffer.VertexAttribute
import io.github.sceneview.node.MeshNode
import io.github.sceneview.node.Node
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Owns the renderable [MeshNode] of a [StreetscapeGeometryNode] and keeps it in step with the
 * ARCore mesh.
 *
 * Split out of the node so the build / rebuild / drop logic runs on real Filament in an
 * instrumented test without an ARCore session: `StreetscapeGeometry` cannot be constructed
 * outside a live Geospatial session.
 *
 * @param parent the node the [meshNode] is attached to.
 */
internal class StreetscapeMeshRenderable(
    private val engine: Engine,
    private val parent: Node,
    private val materialInstance: MaterialInstance?,
    private val builder: RenderableManager.Builder.() -> Unit,
) {
    /** The current renderable, or `null` while the mesh is empty. */
    var meshNode: MeshNode? = null
        private set

    // Counts of the mesh the current [meshNode] was built from. -1 forces the first sync to build.
    private var builtVertexCount = -1
    private var builtIndexCount = -1

    /**
     * Builds the renderable on the first call, then rebuilds it when the vertex or index count
     * changed, or drops it when the mesh became empty. The previous [meshNode] is destroyed.
     *
     * The buffers are only read when a (re)build happens.
     *
     * @return `true` when [meshNode] was replaced (or dropped).
     */
    fun sync(
        vertexCount: Int,
        indexCount: Int,
        vertexList: () -> FloatBuffer,
        indexList: () -> IntBuffer,
    ): Boolean {
        if (vertexCount == builtVertexCount && indexCount == builtIndexCount) return false
        builtVertexCount = vertexCount
        builtIndexCount = indexCount
        meshNode?.destroy()
        meshNode = build(vertexCount, indexCount, vertexList, indexList)
        return true
    }

    /**
     * Builds the renderable, or returns `null` when the mesh is empty.
     *
     * The bounding box is mandatory: without one Filament aborts in
     * `RenderableManager.Builder.build` ("AABB can't be empty") because the renderable receives
     * shadows by default.
     */
    private fun build(
        vertexCount: Int,
        indexCount: Int,
        vertexList: () -> FloatBuffer,
        indexList: () -> IntBuffer,
    ): MeshNode? {
        if (!isStreetscapeMeshRenderable(vertexCount, indexCount)) return null
        val positions = vertexList()
        val boundingBox = computeStreetscapeAabb(positions, vertexCount) ?: return null
        val indices = indexList()
        return MeshNode(
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
                // per-vertex normals from the mesh once per build, and encodes them as tangent
                // quaternions; UV0 is a zero buffer (no natural parameterisation, and the
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
                .vertexCount(vertexCount)
                .build(engine)
                .apply {
                    setBufferAt(engine, BUFFER_INDEX_POSITION, positions)
                    setBufferAt(
                        engine,
                        BUFFER_INDEX_TANGENT,
                        computeStreetscapeTangents(positions, indices, vertexCount)
                    )
                    setBufferAt(engine, BUFFER_INDEX_UV, zeroStreetscapeUvs(vertexCount))
                },
            indexBuffer = IndexBuffer.Builder()
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .indexCount(indexCount)
                .build(engine)
                .apply {
                    setBuffer(engine, indices)
                },
            boundingBox = boundingBox,
            materialInstance = materialInstance,
            // The VertexBuffer/IndexBuffer above are built just for this MeshNode and owned by
            // it exclusively — free them when the node is destroyed (#2037). The mesh node is a
            // child of [parent], so Node.destroy()'s recursive child teardown (#2036) reaches it
            // when the StreetscapeGeometryNode is destroyed.
            destroyBuffersOnDispose = true,
            builder = builder
        ).apply { parent = this@StreetscapeMeshRenderable.parent }
    }
}

// Vertex buffer slots of a Streetscape mesh. File-private: a `const val` in a
// `private companion object` still compiles to a public static field on the class and
// trips apiCheck.
private const val BUFFER_INDEX_POSITION = 0
private const val BUFFER_INDEX_TANGENT = 1
private const val BUFFER_INDEX_UV = 2
private const val BUFFER_COUNT = 3
