package io.github.sceneview.ar.depth

import com.google.ar.core.Pose
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * Where an [ArDepthFrame] came from.
 *
 * Every consumer of depth (occlusion, depth hits, meshes) reads the same [ArDepthFrame]
 * whatever its source; the source is there for logging, for a debug overlay, and for a
 * consumer that wants to be stricter with an estimated map than with a measured one.
 */
enum class DepthFrameSource {
    /** ARCore's Depth API (`Frame.acquireDepthImage16Bits`), measured by the device. */
    ARCORE,

    /**
     * A monocular depth network ([MonocularDepthEstimator]) run on the CPU camera image and
     * scaled to metres against ARCore's own plane hits and feature points ([MlDepthSession]).
     */
    ML,
}

/**
 * Pinhole intrinsics of a depth map, in **its own** pixel grid ([ArDepthFrame.width] ×
 * [ArDepthFrame.height]).
 *
 * A depth map never shares the camera texture's intrinsics: ARCore's depth image and the CPU
 * image each have their own field of view and resolution, and assuming one for the other
 * puts a 33 % error on every unprojected point (#4221). Each frame therefore carries the
 * intrinsics it was measured with, and no consumer has to guess them.
 */
data class DepthIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

/**
 * One depth map in metres, in the camera **sensor** orientation (landscape, the same
 * orientation as ARCore's CPU image and depth image), with the camera pose at the instant
 * the image was taken.
 *
 * The buffers are **borrowed** from a small pool owned by the producer: read them, or copy
 * what you need, before the next two frames arrive. Never write to them.
 *
 * @property timestampNs `Frame.timestamp` of the camera image this depth was computed from.
 * @property millimetres `width × height` unsigned 16-bit depths along the optical axis, in
 *   millimetres, row-major. `0` means "no depth here".
 * @property confidence Optional `width × height` bytes, `0` (no trust) to `255` (full trust).
 * @property intrinsics Pinhole intrinsics in this map's pixel grid.
 * @property cameraPose `Camera.getPose()` of the frame the depth belongs to — the physical
 *   camera, `+X` right and `+Y` up in the image, looking down `-Z`. For a depth map that is
 *   older than the current frame (ML depth is 100–300 ms late), unproject with this pose,
 *   never with the current one.
 */
class ArDepthFrame(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val millimetres: ShortBuffer,
    val confidence: ByteBuffer?,
    val intrinsics: DepthIntrinsics,
    val cameraPose: Pose,
    val source: DepthFrameSource,
) {
    /** Depth in metres at pixel ([x], [y]) of this map, or `NaN` where there is none. */
    fun depthMetersAt(x: Int, y: Int): Float {
        if (x !in 0 until width || y !in 0 until height) return Float.NaN
        val mm = millimetres.get(y * width + x).toInt() and MAX_U16
        return if (mm == 0) Float.NaN else mm / MM_PER_METER
    }

    /** Confidence (0–1) at pixel ([x], [y]); `1` when the source carries no confidence. */
    fun confidenceAt(x: Int, y: Int): Float {
        val conf = confidence ?: return 1f
        if (x !in 0 until width || y !in 0 until height) return 0f
        return (conf.get(y * width + x).toInt() and MAX_U8) / MAX_U8.toFloat()
    }

    private companion object {
        const val MAX_U16 = 0xFFFF
        const val MAX_U8 = 0xFF
        const val MM_PER_METER = 1000f
    }
}
