package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Transform
import io.github.sceneview.math.toTransform
import kotlin.math.tan

/**
 * The phone's eye the view is leaving (#4719): when 3D Scan's live view stops seeing through the
 * phone ([ScanLens]) while it still follows — the save starting, or tracking lost — the camera does
 * not jump onto the orbit. It starts exactly on the last eye and lens ARCore gave, and eases onto
 * the orbit's transform and lens over [SECONDS], so the first frame after the stop tap is the
 * frame before it.
 *
 * [eye] and [lens] are copies of [ScanLens.cameraToWorld] and [ScanLens.projection] at the
 * hand-off, column-major. [seconds] is advanced by [ArDebugOrbitCamera.update].
 */
class EyeHandoff(private val eye: FloatArray, private val lens: FloatArray) {

    /** Seconds since the hand-off began. */
    var seconds: Float = 0f

    /** Whether the view stands on the orbit by now, the eye and its lens let go of. */
    val done: Boolean get() = seconds >= SECONDS

    /** How far the view has moved from the eye onto the orbit, 0…1, eased in and out. */
    val weight: Float
        get() {
            val t = (seconds / SECONDS).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

    /**
     * The camera's model matrix: the eye at the start, [orbit] at the end, its position, forward
     * and up axes mixed in between, so the move neither rolls the room over nor cuts through it.
     */
    fun transform(orbit: Transform): Transform {
        val w = weight
        if (w <= 0f) return eye.toTransform()
        if (w >= 1f) return orbit
        fun mix(a: Float3, b: Float3) = a + (b - a) * w
        val position = mix(Float3(eye[12], eye[13], eye[14]), orbit.w.xyz)
        val forward = normalize(mix(-Float3(eye[8], eye[9], eye[10]), -orbit.z.xyz))
        val up = normalize(mix(Float3(eye[4], eye[5], eye[6]), orbit.y.xyz))
        return Transform(lookAt(eye = position, target = position + forward, up = up))
    }

    /**
     * The projection, column-major, into [out], for [ScanLens.NEAR_M]…[ScanLens.FAR_M]: the
     * phone's lens at the start, the orbit's — [verticalFovDegrees] on a view of [aspect] — at the
     * end, so the picture zooms from the camera's field of view to the orbit's instead of cutting.
     */
    fun projection(verticalFovDegrees: Double, aspect: Float, out: FloatArray) {
        orbitProjection(verticalFovDegrees, aspect, out)
        val w = weight
        for (i in out.indices) out[i] = lens[i] + (out[i] - lens[i]) * w
    }

    companion object {
        /** The hand-off from the phone's eye onto the orbit, seconds: a breath, not a cut. */
        const val SECONDS = 1.2f

        /**
         * The orbit's own lens as a matrix for [ScanLens.NEAR_M]…[ScanLens.FAR_M], into [out]: what
         * `Camera.setLensProjection` draws for [verticalFovDegrees] on a view of [aspect].
         */
        fun orbitProjection(verticalFovDegrees: Double, aspect: Float, out: FloatArray) {
            val tanY = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
            out.fill(0f)
            out[0] = 1f / (tanY * aspect)
            out[5] = 1f / tanY
            out[10] = (ScanLens.FAR_M + ScanLens.NEAR_M) / (ScanLens.NEAR_M - ScanLens.FAR_M)
            out[11] = -1f
            out[14] = 2f * ScanLens.FAR_M * ScanLens.NEAR_M / (ScanLens.NEAR_M - ScanLens.FAR_M)
        }
    }
}
