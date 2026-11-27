package io.github.sceneview.ar.scene

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Coordinates3d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import io.github.sceneview.ar.camera.CameraStreamCoordinates
import io.github.sceneview.ar.depth.DepthOcclusionFilter
import io.github.sceneview.material.setTexture
import io.github.sceneview.safeDestroyTexture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor

/**
 * Depth owned exclusively by the plane material. All calls except upload completion run on the
 * main thread. Owning the texture here also works with a null/custom camera stream and avoids
 * retaining a texture destroyed when the app replaces its camera stream.
 */
// Filament 1.77.3 Texture.setImage still requires the deprecated nested descriptor type.
@Suppress("DEPRECATION")
internal class PlaneDepthOcclusion(private val engine: Engine) {
    private val instances = LinkedHashSet<MaterialInstance>()
    private val filter = DepthOcclusionFilter()
    private val uploads = ConcurrentLinkedQueue<ByteBuffer>()
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )
    private var texture = createTexture(1, 1).also {
        it.setImage(engine, 0, Texture.PixelBufferDescriptor(
            ByteBuffer.allocateDirect(2), Texture.Format.RG, Texture.Type.UBYTE, 1,
        ))
    }
    private var width = 1
    private var height = 1
    private var mode = Config.DepthMode.DISABLED
    private var active = false
    private var coordinates = newCoordinates()
    private val uvRows = FloatArray(9)
    private var transformFrame: Frame? = null
    private val transform3d: (FloatBuffer, Coordinates3d, FloatBuffer) -> Unit = { input, type, output ->
        checkNotNull(transformFrame).transformCoordinates3d(
            Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, input, type, output,
        )
    }
    private val transform2d: (Coordinates2d, FloatBuffer, FloatBuffer) -> Unit = { type, input, output ->
        checkNotNull(transformFrame).transformCoordinates2d(
            type, input, Coordinates2d.TEXTURE_NORMALIZED, output,
        )
    }

    fun bind(instance: MaterialInstance) {
        instances.add(instance)
        instance.setTexture("depthTexture", texture, sampler)
        instance.setParameter("depthEnabled", active)
        writeCoordinates(instance)
    }

    fun unbind(instance: MaterialInstance) {
        instances.remove(instance)
    }

    fun update(session: Session, frame: Frame, enabled: Boolean) {
        val config = session.config
        val nextMode = config.depthMode.takeIf { shouldOccludePlanes(it, enabled) }
            ?: Config.DepthMode.DISABLED
        if (mode != nextMode) {
            reset()
            mode = nextMode
        }
        if (mode == Config.DepthMode.DISABLED) return

        transformFrame = frame
        val changed = try {
            coordinates.update(
                config.imageStabilizationMode, frame.hasDisplayGeometryChanged(),
                transform3d, transform2d,
            )
        } finally {
            transformFrame = null
        }
        if (changed) {
            planeDepthUvRows(coordinates, config.imageStabilizationMode, uvRows)
            instances.forEach(::writeCoordinates)
        }

        val depth = runCatching {
            when (mode) {
                Config.DepthMode.AUTOMATIC -> frame.acquireDepthImage16Bits()
                Config.DepthMode.RAW_DEPTH_ONLY -> frame.acquireRawDepthImage16Bits()
                else -> null
            }
        }.getOrNull() ?: return
        val fresh = depth.use {
            val plane = it.planes[0]
            it.width > 0 && it.height > 0 &&
                filter.update(it.timestamp, plane.buffer, it.width, it.height, plane.rowStride)
        }
        if (!fresh) return

        if (width != filter.width || height != filter.height) {
            val previous = texture
            texture = createTexture(filter.width, filter.height)
            width = filter.width
            height = filter.height
            // Rebind ALL instances, including the default for future planes, before release.
            instances.forEach { it.setTexture("depthTexture", texture, sampler) }
            engine.safeDestroyTexture(previous)
        }
        val pixels = filter.output
        val upload = uploads.poll()?.takeIf { it.capacity() == pixels.capacity() }
            ?: ByteBuffer.allocateDirect(pixels.capacity()).order(ByteOrder.LITTLE_ENDIAN)
        upload.clear()
        upload.put(pixels.duplicate().apply { clear() })
        upload.flip()
        // ARCore's Image is already closed. Filament owns this copy until completion; a non-null
        // Executor is required for its callback to run and return the buffer to the pool.
        texture.setImage(engine, 0, Texture.PixelBufferDescriptor(
            upload, Texture.Format.RG, Texture.Type.UBYTE, 1, 0, 0, 0, uploadExecutor,
        ) { uploads.offer(upload) })
        setActive(true)
    }

    /** Stale pixels remain allocated but cannot be sampled until a new depth image arrives. */
    fun reset() {
        mode = Config.DepthMode.DISABLED
        filter.reset()
        setActive(false)
        coordinates = newCoordinates()
    }

    /** Called after the renderer has destroyed every material instance that samples the texture. */
    fun destroy() {
        instances.clear()
        engine.safeDestroyTexture(texture)
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        instances.forEach { it.setParameter("depthEnabled", value) }
    }

    private fun writeCoordinates(instance: MaterialInstance) {
        instance.setParameter("depthUvU", uvRows[0], uvRows[1], uvRows[2])
        instance.setParameter("depthUvV", uvRows[3], uvRows[4], uvRows[5])
        instance.setParameter("depthUvW", uvRows[6], uvRows[7], uvRows[8])
    }

    private fun createTexture(width: Int, height: Int): Texture = Texture.Builder()
        .width(width).height(height).levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.RG8).build(engine)

    private fun newCoordinates() = CameraStreamCoordinates(floatArrayOf(
        -1f, -1f, 1f, 3f, -1f, 1f, -1f, 3f, 1f,
    ))

    private companion object {
        val uploadExecutor = Executor { it.run() }
    }
}

/**
 * Screen NDC → homogeneous depth UV, matching the camera material's interpolation. EIS uses
 * the transformed triangle and homogeneous camera UVs; OFF undoes CameraStreamCoordinates'
 * UV0 Y flip (Filament normally undoes it when sampling UV0, but this material has no UV0).
 * The three affine rows avoid a matrix allocation/inversion on every stabilized frame.
 */
internal fun planeDepthUvRows(
    coordinates: CameraStreamCoordinates,
    mode: Config.ImageStabilizationMode,
    out: FloatArray,
) {
    val p = coordinates.positions
    val x0 = p[0]
    val y0 = p[1]
    val dx1 = p[3] - x0
    val dy1 = p[4] - y0
    val dx2 = p[6] - x0
    val dy2 = p[7] - y0
    val determinant = dx1 * dy2 - dx2 * dy1
    for (row in 0..2) {
        fun value(vertex: Int): Float = if (mode == Config.ImageStabilizationMode.EIS) {
            coordinates.cameraUvs[vertex * 3 + row]
        } else when (row) {
            0 -> coordinates.imageUvs[vertex * 2]
            1 -> 1f - coordinates.imageUvs[vertex * 2 + 1]
            else -> 1f
        }
        val v0 = value(0)
        val dv1 = value(1) - v0
        val dv2 = value(2) - v0
        val a = (dv1 * dy2 - dv2 * dy1) / determinant
        val b = (dx1 * dv2 - dx2 * dv1) / determinant
        out[row * 3] = a
        out[row * 3 + 1] = b
        out[row * 3 + 2] = v0 - a * x0 - b * y0
    }
}
