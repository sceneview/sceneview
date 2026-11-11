package io.github.sceneview.demo.demos.internal

import android.os.Handler
import android.os.Looper
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.VertexBuffer
import io.github.sceneview.demo.demos.internal.feel.FeelSimulation
import io.github.sceneview.demo.demos.internal.feel.MatterGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Main-thread GPU boundary. Staging slots are reused only after Filament releases the upload. */
internal class MatterMesh(private val engine: Engine, val geometry: MatterGeometry, private val xzScale: Float = 1f) {
    private val handler = Handler(Looper.getMainLooper())
    private val staging = Array(3) {
        ByteBuffer.allocateDirect(geometry.positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    }
    private val available = BooleanArray(staging.size) { true }
    private var pending = false
    private var destroyed = false
    private val released = Array(staging.size) { slot -> Runnable { available[slot] = true; flush() } }
    val buffers: SheetBuffers

    init {
        val vertices = VertexBuffer.Builder().bufferCount(1).vertexCount(geometry.positions.size / 3)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .build(engine)
        val indices = IndexBuffer.Builder().indexCount(geometry.indices.size)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT).build(engine)
        val indexData = ByteBuffer.allocateDirect(geometry.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indexData.put(geometry.indices).flip()
        indices.setBuffer(engine, indexData)
        buffers = SheetBuffers(vertices, indices, Box(
            0f, -FeelSimulation.PAD_THICKNESS / 2f, 0f,
            (FeelSimulation.PAD_HALF_WIDTH + 0.01f) * xzScale, FeelSimulation.PAD_THICKNESS / 2f + 0.01f,
            (FeelSimulation.PAD_HALF_LENGTH + 0.01f) * xzScale,
        ))
        upload()
    }

    fun upload() {
        pending = true
        flush()
    }

    private fun flush() {
        if (destroyed || !pending) return
        val slot = available.indexOfFirst { it }
        if (slot < 0) return // Coalesce to the latest shape until a staging slot is released.
        val data = staging[slot]
        data.clear()
        for (i in geometry.positions.indices step 3) {
            data.put(geometry.positions[i] * xzScale)
            data.put(geometry.positions[i + 1])
            data.put(geometry.positions[i + 2] * xzScale)
        }
        data.flip()
        available[slot] = false
        pending = false
        buffers.vertexBuffer.setBufferAt(engine, 0, data, 0, geometry.positions.size, handler, released[slot])
    }

    fun destroy() {
        destroyed = true
        buffers.destroy(engine)
    }
}
