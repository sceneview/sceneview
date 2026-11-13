package io.github.sceneview.demo.demos.internal

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterGeometry
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** Main-thread GPU boundary: immutable uploads shared by the soft and rigid sample families. No frame upload API. */
internal class MatterMesh(private val engine: Engine, data: Upload) {
    /** CPU-only packing; build this on Dispatchers.Default before entering the GPU boundary. */
    class Upload(geometry: MatterGeometry) {
        val vertexCount = geometry.positions.size / 3
        val indexCount = geometry.indices.size
        val vertices: FloatBuffer = ByteBuffer.allocateDirect(vertexCount * 36)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                repeat(vertexCount) { i ->
                    put(geometry.positions, i * 3, 3)
                    put(geometry.tangents, i * 4, 4)
                    put(geometry.uv0, i * 2, 2)
                }
                flip()
            }
        val indices: ShortBuffer = ByteBuffer.allocateDirect(indexCount * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(geometry.indices)
                flip()
            }
    }
    val vertexBuffer: VertexBuffer
    val indexBuffer: IndexBuffer
    val boundingBox: Box
    init {
        vertexBuffer = VertexBuffer.Builder().bufferCount(1).vertexCount(data.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 36)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, 36)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 28, 36)
            .build(engine)
        vertexBuffer.setBufferAt(engine, 0, data.vertices)
        indexBuffer = IndexBuffer.Builder().indexCount(data.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        indexBuffer.setBuffer(engine, data.indices)
        // Includes gel overshoot/bulge and the rigid contact bounce. Bottom remains supported.
        boundingBox = Box(
            0f, -FeelSimulation.PAD_THICKNESS * .3f, 0f,
            FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_THICKNESS * .8f,
            FeelSimulation.PAD_HALF_LENGTH,
        )
    }
    fun destroy() {
        engine.safeDestroyVertexBuffer(vertexBuffer)
        engine.safeDestroyIndexBuffer(indexBuffer)
    }
}
