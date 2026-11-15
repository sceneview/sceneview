package io.github.sceneview.ar.camera

import com.google.ar.core.Coordinates3d
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Per-frame EIS geometry. Camera UVs remain homogeneous until the fragment shader interpolates
 * them. UV0 stays a separate, unstabilized mapping for depth/semantics and custom materials.
 * ARCore returns identity compensation when EIS is off, so toggling needs no cached-mode state.
 */
internal class CameraStreamCoordinates(vertices: FloatArray) {
    private val vertexCount = vertices.size / 3
    private val ndc = buffer(vertexCount * 2).apply {
        for (i in 0 until vertexCount) {
            put(vertices[i * 3])
            put(vertices[i * 3 + 1])
        }
        rewind()
    }
    private val screenNdc = buffer(vertexCount * 2)
    val positions: FloatBuffer = buffer(vertices.size).apply { put(vertices); rewind() }
    val cameraUvs: FloatBuffer = buffer(vertices.size)
    val imageUvs: FloatBuffer = buffer(vertexCount * 2)

    init {
        for (i in 0 until vertexCount) {
            val u = (vertices[i * 3] + 1f) / 2f
            val v = (1f - vertices[i * 3 + 1]) / 2f
            cameraUvs.put(u).put(1f - v).put(1f)
            imageUvs.put(u).put(v)
        }
        cameraUvs.rewind()
        imageUvs.rewind()
    }

    /** Both transforms use OPENGL_NORMALIZED_DEVICE_COORDINATES as their input space. */
    fun update(
        transform3d: (FloatBuffer, Coordinates3d, FloatBuffer) -> Unit,
        transform2d: (FloatBuffer, FloatBuffer) -> Unit,
    ) {
        ndc.rewind()
        positions.rewind()
        transform3d(ndc, Coordinates3d.EIS_NORMALIZED_DEVICE_COORDINATES, positions)
        // Filament's device-domain shader takes raster depth from POSITION, independently of
        // material.worldPosition. ARCore's screen Z (0 with EIS OFF) must not replace the
        // background's far-plane Z=1, which Filament maps to reverse-Z depth 0.
        for (i in 0 until vertexCount) {
            positions.put(i * 3 + 2, 1f)
        }
        ndc.rewind()
        cameraUvs.rewind()
        transform3d(ndc, Coordinates3d.EIS_TEXTURE_NORMALIZED, cameraUvs)

        // Depth/semantics describe the physical camera, independent of EIS. Map the actual
        // screen positions so their UVs stay fixed to the view even when EIS warps the mesh.
        screenNdc.rewind()
        for (i in 0 until vertexCount) {
            screenNdc.put(positions[i * 3])
            screenNdc.put(positions[i * 3 + 1])
        }
        screenNdc.rewind()
        imageUvs.rewind()
        transform2d(screenNdc, imageUvs)

        for (i in 0 until vertexCount) {
            // Filament automatically flips UV0 again in the vertex shader. CUSTOM0 has no
            // automatic flip: keep ARCore's homogeneous camera UVs in their GPU orientation.
            imageUvs.put(i * 2 + 1, 1f - imageUvs[i * 2 + 1])
        }
        positions.rewind()
        cameraUvs.rewind()
        imageUvs.rewind()
    }

    private fun buffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
}

/** Filament consumes uploads asynchronously; the next ARCore frame must not overwrite them. */
internal fun FloatBuffer.cameraStreamUploadBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(remaining() * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(this@cameraStreamUploadBuffer.duplicate())
            flip()
        }
