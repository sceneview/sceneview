package io.github.sceneview.ar.camera

import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Coordinates3d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

/** EIS geometry and the original display-transformed 2D UVs used while stabilization is off. */
internal class CameraStreamCoordinates(private val vertices: FloatArray) {
    private val vertexCount = vertices.size / 3
    private val ndc = buffer(vertexCount * 2).apply {
        for (i in 0 until vertexCount) {
            put(vertices[i * 3])
            put(vertices[i * 3 + 1])
        }
        rewind()
    }
    private val screenNdc = buffer(vertexCount * 2)
    private val viewUvs = buffer(vertexCount * 2)
    private var initialized = false
    private var stabilized = false
    val positions: FloatBuffer = buffer(vertices.size).apply { put(vertices); rewind() }
    val cameraUvs: FloatBuffer = buffer(vertices.size)
    val imageUvs: FloatBuffer = buffer(vertexCount * 2)

    init {
        for (i in 0 until vertexCount) {
            val u = (vertices[i * 3] + 1f) / 2f
            val v = (1f - vertices[i * 3 + 1]) / 2f
            cameraUvs.put(u).put(1f - v).put(1f)
            imageUvs.put(u).put(v)
            viewUvs.put(u).put(v)
        }
        cameraUvs.rewind()
        imageUvs.rewind()
        viewUvs.rewind()
    }

    /** Returns whether the vertex data changed and needs uploading. */
    fun update(
        mode: Config.ImageStabilizationMode,
        displayGeometryChanged: Boolean,
        transform3d: (FloatBuffer, Coordinates3d, FloatBuffer) -> Unit,
        transform2d: (Coordinates2d, FloatBuffer, FloatBuffer) -> Unit,
    ): Boolean {
        val eis = mode == Config.ImageStabilizationMode.EIS
        if (!eis && initialized && !stabilized && !displayGeometryChanged) return false
        positions.rewind()
        imageUvs.rewind()
        if (eis) {
            ndc.rewind()
            transform3d(ndc, Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES, positions)
            // Device-domain Filament shaders take raster depth directly from POSITION.
            for (i in 0 until vertexCount) positions.put(i * 3 + 2, 1f)
            ndc.rewind()
            cameraUvs.rewind()
            transform3d(ndc, Coordinates3d.EIS_TEXTURE_NORMALIZED, cameraUvs)

            // Retain UV0 for custom materials that use the physical-camera mapping.
            screenNdc.rewind()
            for (i in 0 until vertexCount) {
                screenNdc.put(positions[i * 3])
                screenNdc.put(positions[i * 3 + 1])
            }
            screenNdc.rewind()
            transform2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, screenNdc, imageUvs)
        } else {
            // Match main exactly: original geometry, VIEW_NORMALIZED input, same Y flip.
            // Never ask ARCore for EIS coordinates while OFF (#2728).
            positions.put(vertices)
            viewUvs.rewind()
            transform2d(Coordinates2d.VIEW_NORMALIZED, viewUvs, imageUvs)
        }
        for (i in 0 until vertexCount) {
            imageUvs.put(i * 2 + 1, 1f - imageUvs[i * 2 + 1])
        }
        positions.rewind()
        cameraUvs.rewind()
        imageUvs.rewind()
        initialized = true
        stabilized = eis
        return true
    }
}

/** A bounded ring: a slot stays immutable until all three asynchronous uploads complete. */
internal class CameraStreamUploadRing(vertexCount: Int, size: Int = 3) {
    class Slot(vertexCount: Int) {
        val positions = buffer(vertexCount * 3)
        val imageUvs = buffer(vertexCount * 2)
        val cameraUvs = buffer(vertexCount * 3)
        private val pending = AtomicInteger(0)
        val onConsumed = Runnable { pending.decrementAndGet() }

        fun acquire(): Boolean = pending.compareAndSet(0, 3)
        fun cancel() { pending.set(0) }

        fun copyFrom(coordinates: CameraStreamCoordinates) {
            copy(coordinates.positions, positions)
            copy(coordinates.imageUvs, imageUvs)
            copy(coordinates.cameraUvs, cameraUvs)
        }

        private fun copy(source: FloatBuffer, target: FloatBuffer) {
            target.clear()
            target.put(source.duplicate())
            target.flip()
        }
    }

    private val slots = Array(size) { Slot(vertexCount) }
    private var next = 0

    fun acquire(): Slot? {
        repeat(slots.size) {
            val slot = slots[next]
            next = (next + 1) % slots.size
            if (slot.acquire()) return slot
        }
        // Keep the last geometry if the renderer falls behind; never overwrite an upload.
        return null
    }
}

private fun buffer(size: Int): FloatBuffer =
    ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
