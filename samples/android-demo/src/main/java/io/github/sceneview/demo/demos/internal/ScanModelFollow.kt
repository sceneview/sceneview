package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where 3D Scan's live model is seen from: behind and above the phone, so what the phone faces
 * is the far side of the screen and the phone's view cone stands in the near half (2026-11-13,
 * Thomas's workshop scan: a view frozen at one oblique angle tied nothing he did to what grew).
 *
 * Nothing here jerks. The heading eases towards the phone's over [turnSeconds], so a sweep of
 * the wall left and right barely sways the view and a real turn swings it round in a few
 * seconds. The view frames a circle on the ground ([ScanModelView]), the same from every side,
 * so a turn never moves it nearer or further: it steps back as the model grows or the phone
 * walks out of it, and comes closer again only slowly ([RELEASE_SECONDS]).
 * [ArDebugOrbitCamera]'s own ease smooths what is left.
 */
class ScanModelFollow(private val turnSeconds: Float = TURN_SECONDS) {
    private var heading: Float? = null
    private var phoneHeading: Float? = null
    private var at = Float.NaN
    private var reach = 0f

    /**
     * The pose for the phone at [camera], [time] seconds into the capture, over the scanned
     * model's [bounds] (`minX, minY, minZ, maxX, maxY, maxZ`) reaching [radius] on the ground
     * from their middle (their half diagonal without one). [fit] frames, from a heading, the
     * [ScanModelView] that holds the model and the phone. Before the phone has a heading, the
     * view keeps [fallbackHeading]. `null` without a model.
     */
    fun poseFor(
        camera: DebugPose?,
        time: Float,
        fallbackHeading: Float,
        bounds: FloatArray?,
        radius: Float? = null,
        fit: (view: ScanModelView, headingDegrees: Float) -> OrbitPose?,
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
        val framed = fit(ScanModelView.of(bounds ?: return null, radius, camera), next) ?: return null
        // Back at once, closer slowly: a step out of the model and in again does not pump the view.
        reach = if (framed.distance >= reach) framed.distance else reach + (framed.distance - reach) * (1f - exp(-dt / RELEASE_SECONDS))
        return framed.copy(azimuthDegrees = next, distance = reach)
    }

    companion object {
        /** The heading's time constant: about two seconds to turn most of the way round with the phone. */
        const val TURN_SECONDS = 1.8f

        /** The time constant of the view coming closer once the phone is back in the model. */
        const val RELEASE_SECONDS = 6f

        /** The longest step one call eases over: a stalled frame never swings the view at once. */
        private const val MAX_STEP_S = 0.5f
    }
}

/**
 * What the live model's view frames: the circle on the ground about ([x], [z]), [radius] wide,
 * from [minY] up to [maxY]. A circle, not the model's box: a box is wider seen from its corner
 * than from its side, so a view that turns with the phone had to stand back for the widest and
 * showed the model at half the size it could (2026-11-14, the first hand-held scan of a room:
 * the model a fifth of the screen's height).
 */
class ScanModelView(val x: Float, val z: Float, val radius: Float, val minY: Float, val maxY: Float) {

    /** The circle's axis as a box of no width, for a fit that takes one. */
    fun axis(): FloatArray = floatArrayOf(x, minY, z, x, maxY, z)

    /** The circle at its foot and at its top, as points (flat xyz): what a fit has to hold. */
    fun rings(): FloatArray {
        val out = FloatArray(RING_POINTS * 2 * 3)
        for (i in 0 until RING_POINTS) {
            val angle = 2.0 * PI * i / RING_POINTS
            val px = x + radius * cos(angle).toFloat()
            val pz = z + radius * sin(angle).toFloat()
            for (level in 0..1) {
                val o = (i * 2 + level) * 3
                out[o] = px
                out[o + 1] = if (level == 0) minY else maxY
                out[o + 2] = pz
            }
        }
        return out
    }

    companion object {
        /** Round the phone: its view cone and the path drawn behind it. */
        const val PHONE_MARGIN_M = 0.3f

        /** Points round each ring: within half a percent of the circle. */
        private const val RING_POINTS = 32

        /**
         * The circle over [bounds] (`minX, minY, minZ, maxX, maxY, maxZ`), [radius] about their
         * middle (their half diagonal without one), grown just enough to hold the phone at
         * [camera] too: a phone inside the model leaves the view on the model, one that walks
         * out pulls the view half the way after it.
         */
        fun of(bounds: FloatArray, radius: Float?, camera: DebugPose?): ScanModelView {
            var x = (bounds[0] + bounds[3]) / 2f
            var z = (bounds[2] + bounds[5]) / 2f
            var r = radius ?: (hypot(bounds[3] - bounds[0], bounds[5] - bounds[2]) / 2f)
            var minY = bounds[1]
            var maxY = bounds[4]
            if (camera != null) {
                minY = minOf(minY, camera.y)
                maxY = maxOf(maxY, camera.y)
                val away = hypot(camera.x - x, camera.z - z)
                val out = away + PHONE_MARGIN_M - r
                if (out > 0f && away > 0f) {
                    // The smallest circle holding the old one and the phone.
                    x += (camera.x - x) / away * out / 2f
                    z += (camera.z - z) / away * out / 2f
                    r += out / 2f
                }
            }
            return ScanModelView(x, z, r, minY, maxY)
        }
    }
}
