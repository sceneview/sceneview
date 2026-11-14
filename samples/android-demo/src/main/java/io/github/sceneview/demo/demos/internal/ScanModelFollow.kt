package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.exp

/**
 * Where 3D Scan's live model is seen from: behind and above the phone, so what the phone faces
 * is the far side of the screen and the phone's view cone stands in the near half (2026-11-13,
 * Thomas's workshop scan: a view frozen at one oblique angle tied nothing he did to what grew).
 *
 * Nothing here jerks. The heading eases towards the phone's over [turnSeconds], so a sweep of
 * the wall left and right barely sways the view and a real turn swings it round in a few
 * seconds; the reach only grows, so the view steps back as the room grows and never dives in
 * when a turn would fit the room closer. [ArDebugOrbitCamera]'s own ease smooths what is left.
 */
class ScanModelFollow(private val turnSeconds: Float = TURN_SECONDS) {
    private var heading: Float? = null
    private var phoneHeading: Float? = null
    private var at = Float.NaN
    private var reach = 0f

    /**
     * The pose for the phone at [camera], [time] seconds into the capture, over the scanned room's
     * [bounds] (`minX, minY, minZ, maxX, maxY, maxZ`). The view looks at the room's centre pulled
     * [PHONE_PULL] of the way towards the phone, and [fit] frames, from a heading, a box about
     * that centre grown by the pull on each side, holding the phone too: the room and the phone
     * stay whole on screen wherever the phone stands (Codex's review of u26: a fixed margin let a
     * corner out once the phone walked past the cloud). Before the phone has a heading, the view
     * keeps [fallbackHeading]. `null` without a room.
     */
    fun poseFor(
        camera: DebugPose?,
        time: Float,
        fallbackHeading: Float,
        bounds: FloatArray?,
        fit: (bounds: FloatArray, headingDegrees: Float) -> OrbitPose?,
    ): OrbitPose? {
        val seen = camera?.let { ScanFollow.headingOf(it, phoneHeading) }
        if (seen != null) phoneHeading = seen
        val dt = if (at.isNaN()) 0f else (time - at).coerceIn(0f, MAX_STEP_S)
        at = time
        val before = heading
        val next = when {
            seen == null -> before ?: fallbackHeading
            before == null -> seen
            else -> before + CameraRig.shortestDelta(before, seen) * (1f - exp(-dt / turnSeconds))
        }
        heading = next
        val framed = fit(viewBox(bounds ?: return null, camera), next) ?: return null
        reach = maxOf(reach, framed.distance * REACH_MARGIN)
        return framed.copy(azimuthDegrees = next, distance = reach)
    }

    companion object {
        /** The heading's time constant: about two seconds to turn most of the way round with the phone. */
        const val TURN_SECONDS = 1.8f

        /** How far the view's centre leans from the room's towards the phone, so the phone stays in it. */
        const val PHONE_PULL = 0.3f

        /** The fit stepped back a hair, for the view cone and the path drawn round the phone. */
        const val REACH_MARGIN = 1.05f

        /**
         * The box the view frames: [bounds] with [camera]'s position in it, then grown about its
         * centre pulled [PHONE_PULL] towards the phone by that pull on each side, so it holds the
         * whole room however far the centre leant.
         */
        fun viewBox(bounds: FloatArray, camera: DebugPose?): FloatArray {
            val b = bounds.copyOf()
            if (camera != null) {
                b[0] = minOf(b[0], camera.x); b[1] = minOf(b[1], camera.y); b[2] = minOf(b[2], camera.z)
                b[3] = maxOf(b[3], camera.x); b[4] = maxOf(b[4], camera.y); b[5] = maxOf(b[5], camera.z)
            }
            camera ?: return b
            val sx = (camera.x - (b[0] + b[3]) / 2f) * PHONE_PULL
            val sz = (camera.z - (b[2] + b[5]) / 2f) * PHONE_PULL
            return floatArrayOf(b[0] + sx - abs(sx), b[1], b[2] + sz - abs(sz), b[3] + sx + abs(sx), b[4], b[5] + sz + abs(sz))
        }

        /** The longest step one call eases over: a stalled frame never swings the view at once. */
        private const val MAX_STEP_S = 0.5f
    }
}
