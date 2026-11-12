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

/** Main-thread GPU boundary: immutable uploads shared by the soft and rigid sample families. No frame upload API. */
internal class MatterMesh(private val engine: Engine, geometry: MatterGeometry) {
    val vertexBuffer: VertexBuffer
    val indexBuffer: IndexBuffer
    val boundingBox: Box
    init {
        val count = geometry.positions.size / 3
        vertexBuffer = VertexBuffer.Builder().bufferCount(1).vertexCount(count)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 36)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 0, VertexBuffer.AttributeType.FLOAT4, 12, 36)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, 28, 36)
            .build(engine)
        val vertexData = ByteBuffer.allocateDirect(count * 36).order(ByteOrder.nativeOrder()).asFloatBuffer()
        repeat(count) { i ->
            vertexData.put(geometry.positions, i * 3, 3)
            vertexData.put(geometry.tangents, i * 4, 4)
            vertexData.put(geometry.uv0, i * 2, 2)
        }
        vertexData.flip()
        vertexBuffer.setBufferAt(engine, 0, vertexData)
        indexBuffer = IndexBuffer.Builder().indexCount(geometry.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        val indexData = ByteBuffer.allocateDirect(geometry.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indexData.put(geometry.indices).flip()
        indexBuffer.setBuffer(engine, indexData)
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
