package io.github.sceneview.demo.demos.internal

/**
 * The phone camera's own view, from the latest ARCore frame: where it stands
 * ([cameraToWorld], ARCore's display-oriented pose) and how it projects ([projection], ARCore's
 * projection for the AR view's display geometry). Both are column-major 4×4 matrices, as
 * `Pose.toMatrix` and `Camera.getProjectionMatrix` write them.
 *
 * 3D Scan's live view draws its points from this eye while it follows the phone (#4716), so each
 * point sits on the camera pixel it was captured from. It used to stand 2.6 m behind and above
 * the phone, through a lens of its own, over the same camera image: the points of a wall came
 * out a fifth of the screen below the wall.
 *
 * Main thread only: [update] from `onSessionUpdated`, read by the live view's frame loop. On the
 * emulator, which has no AR, the `record` QA take feeds it the sample room's photo poses and lens
 * ([poseMatrix], [cropProjection]) and draws that photo under the view, so the registration can be
 * checked on a capture.
 */
class ScanLens {

    /** The camera's model matrix in the world, column-major. Valid while [ready]. */
    val cameraToWorld = FloatArray(MATRIX_SIZE)

    /** ARCore's projection for [NEAR_M]…[FAR_M], column-major. Valid while [ready]. */
    val projection = FloatArray(MATRIX_SIZE)

    /** Whether the matrices hold a tracked frame; `false` before one and once tracking is lost. */
    var ready: Boolean = false
        private set

    /**
     * Whether the matrices hold a tracked eye: from the first [ready] frame on, and kept once
     * [invalidate]d — the last eye ARCore gave, which the saving orbit sets out from (#4719).
     */
    var held: Boolean = false
        private set

    /** Bumped on each [update], so a reader applies a new projection once, not every frame. */
    var version: Long = 0L
        private set

    /**
     * Takes the frame's eye: [write] fills [cameraToWorld] and [projection] (ARCore's
     * `displayOrientedPose.toMatrix` and `getProjectionMatrix(…, NEAR_M, FAR_M)`).
     */
    inline fun update(write: (cameraToWorld: FloatArray, projection: FloatArray) -> Unit) {
        write(cameraToWorld, projection)
        markUpdated()
    }

    @PublishedApi
    internal fun markUpdated() {
        ready = cameraToWorld.all { it.isFinite() } && projection.all { it.isFinite() } &&
            projection[PERSPECTIVE_TERM] != 0f
        if (ready) held = true
        version++
    }

    /** Tracking was lost: the live view stops drawing from an eye ARCore no longer vouches for. */
    fun invalidate() {
        if (!ready) return
        ready = false
        version++
    }

    companion object {
        const val MATRIX_SIZE = 16

        /**
         * Whether the live view should see through the phone in [phase]: whenever ARCore tracks,
         * paused included — the frames keep coming, so the cloud stays put in the room and
         * resuming does not move the view — except while saving, when the saving orbit round the
         * whole cloud (#4719) owns the view.
         */
        fun seesThroughPhone(tracking: Boolean, phase: ScanPhase): Boolean =
            tracking && phase != ScanPhase.Saving

        /** [pose] as a column-major model matrix, the layout `Pose.toMatrix` writes, into [out]. */
        fun poseMatrix(pose: DebugPose, out: FloatArray) {
            val x = pose.qx
            val y = pose.qy
            val z = pose.qz
            val w = pose.qw
            out[0] = 1f - 2f * (y * y + z * z)
            out[1] = 2f * (x * y + w * z)
            out[2] = 2f * (x * z - w * y)
            out[3] = 0f
            out[4] = 2f * (x * y - w * z)
            out[5] = 1f - 2f * (x * x + z * z)
            out[6] = 2f * (y * z + w * x)
            out[7] = 0f
            out[8] = 2f * (x * z + w * y)
            out[9] = 2f * (y * z - w * x)
            out[10] = 1f - 2f * (x * x + y * y)
            out[11] = 0f
            out[12] = pose.x
            out[13] = pose.y
            out[14] = pose.z
            out[15] = 1f
        }

        /**
         * The projection of a camera of [lens] whose upright picture fills a view of
         * [viewportAspect] (width / height), cropped about its centre — what ARCore's
         * `getProjectionMatrix` gives for its display geometry, and what `ContentScale.Crop`
         * draws the picture as. Column-major, for [NEAR_M]…[FAR_M], into [out].
         */
        fun cropProjection(lens: ReplayLens, viewportAspect: Float, out: FloatArray) {
            val pictureAspect = lens.halfWidthPerDepth / lens.halfHeightPerDepth
            val tanX: Float
            val tanY: Float
            if (viewportAspect <= pictureAspect) {
                tanY = lens.halfHeightPerDepth
                tanX = tanY * viewportAspect
            } else {
                tanX = lens.halfWidthPerDepth
                tanY = tanX / viewportAspect
            }
            out.fill(0f)
            out[0] = 1f / tanX
            out[5] = 1f / tanY
            out[10] = (FAR_M + NEAR_M) / (NEAR_M - FAR_M)
            out[11] = -1f
            out[14] = 2f * FAR_M * NEAR_M / (NEAR_M - FAR_M)
        }

        /** The projection's near plane, metres: a hand's length, nothing scanned is nearer. */
        const val NEAR_M = 0.05f

        /** The projection's far plane, metres: a garden's far end and well past it. */
        const val FAR_M = 100f

        /** The `-1` in a perspective matrix's third column (row 3, column 2): zero is no projection. */
        private const val PERSPECTIVE_TERM = 11
    }
}
