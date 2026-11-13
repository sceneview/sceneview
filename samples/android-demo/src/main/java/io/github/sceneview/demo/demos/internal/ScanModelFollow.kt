package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
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
     * The pose for the phone at [camera], [time] seconds into the capture: [room] frames the
     * scanned room from a heading (its target the room's centre, its distance the fit), and the
     * view looks at that centre pulled [PHONE_PULL] of the way towards the phone. Before the
     * phone has a heading, the view keeps [fallbackHeading]. `null` without a room to frame.
     */
    fun poseFor(
        camera: DebugPose?,
        time: Float,
        fallbackHeading: Float,
        room: (headingDegrees: Float) -> OrbitPose?,
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
        val framed = room(next) ?: return null
        reach = maxOf(reach, framed.distance * REACH_MARGIN)
        val target = camera?.let {
            Position(
                framed.target.x + (it.x - framed.target.x) * PHONE_PULL,
                framed.target.y,
                framed.target.z + (it.z - framed.target.z) * PHONE_PULL,
            )
        } ?: framed.target
        return framed.copy(target = target, azimuthDegrees = next, distance = reach)
    }

    companion object {
        /** The heading's time constant: about two seconds to turn most of the way round with the phone. */
        const val TURN_SECONDS = 1.8f

        /** How far the view's centre leans from the room's towards the phone, so the phone stays in it. */
        const val PHONE_PULL = 0.3f

        /** The room's fit stepped back a little, for the lean towards the phone to keep it all in view. */
        const val REACH_MARGIN = 1.12f

        /** The longest step one call eases over: a stalled frame never swings the view at once. */
        private const val MAX_STEP_S = 0.5f
    }
}
