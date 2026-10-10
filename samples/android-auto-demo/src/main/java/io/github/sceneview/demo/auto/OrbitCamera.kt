package io.github.sceneview.demo.auto

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Spherical camera around the car, eased toward its target every frame.
 *
 * A drag or a pinch only writes a target; [step] moves the eased values toward it, so a finger
 * never teleports the camera and a released drag coasts to a stop. Pitch is clamped above the
 * floor, distance between a close-up and the whole podium.
 *
 * [fit] widens every distance for a screen narrower than the landscape the home framing was
 * tuned on, so a portrait head unit shows the whole car instead of cropping its ends.
 */
@Stable
internal class OrbitCamera {

    var fit by mutableFloatStateOf(1f)

    private var targetYaw = HOME_YAW
    private var targetPitch = HOME_PITCH
    private var targetDistance = HOME_DISTANCE

    private var yaw = targetYaw
    private var pitch = targetPitch
    private var distance = targetDistance
    private var appliedFit = 0f

    /** `true` once the eased values have reached their targets and [applyTo] has nothing to move. */
    val settled: Boolean
        get() = abs(targetYaw - yaw) < SETTLE_DEGREES &&
            abs(targetPitch - pitch) < SETTLE_DEGREES &&
            abs(targetDistance - distance) < SETTLE_DISTANCE &&
            appliedFit == fit

    fun orbitBy(dYaw: Float, dPitch: Float) {
        targetYaw += dYaw
        targetPitch = (targetPitch + dPitch).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** [factor] above 1 moves away, below 1 moves in. */
    fun zoomBy(factor: Float) {
        targetDistance = (targetDistance * factor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    }

    fun step(dt: Float) {
        val k = 1f - exp(-FOLLOW_RATE * dt)
        yaw += (targetYaw - yaw) * k
        pitch += (targetPitch - pitch) * k
        distance += (targetDistance - distance) * k
    }

    fun applyTo(camera: CameraNode) {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val radius = distance * fit
        camera.position = Position(
            x = (radius * cos(pitchRad) * sin(yawRad)).toFloat(),
            y = TARGET_HEIGHT + (radius * sin(pitchRad)).toFloat(),
            z = (radius * cos(pitchRad) * cos(yawRad)).toFloat(),
        )
        camera.lookAt(Position(0f, TARGET_HEIGHT, 0f))
        appliedFit = fit
    }

    companion object {
        /** Height the camera looks at: roughly a car's beltline on the podium. */
        const val TARGET_HEIGHT = 0.42f
        private const val HOME_YAW = 32f
        private const val HOME_PITCH = 11f
        private const val HOME_DISTANCE = 7.4f
        private const val MIN_PITCH = 3f
        private const val MAX_PITCH = 55f
        private const val MIN_DISTANCE = 4.2f
        private const val MAX_DISTANCE = 11f
        private const val FOLLOW_RATE = 9f
        private const val SETTLE_DEGREES = 0.02f
        private const val SETTLE_DISTANCE = 0.001f
    }
}
