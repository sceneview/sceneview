package io.github.sceneview.demo.auto

import io.github.sceneview.math.Position
import io.github.sceneview.node.CameraNode
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The camera that follows the car in Drive mode: behind and above it, looking past its nose.
 *
 * It trails the car's heading instead of being bolted to it — a turn swings the car across the
 * frame before the camera comes round — which is what makes a kinematic car read as driven.
 *
 * @param bodyLength Length of the car being followed, in metres: a small car is followed closer.
 */
internal class ChaseCamera(bodyLength: Float) {

    private val distance = bodyLength * DISTANCE_PER_LENGTH + DISTANCE_BASE
    private val height = distance * HEIGHT_RATIO
    private var trailingHeading = 0f

    /** Puts the camera straight behind the car, with no easing: the first frame of a drive. */
    fun snapTo(drive: DriveModel) {
        trailingHeading = drive.heading
    }

    /**
     * @param fit  The orbit camera's aspect factor: a narrow screen follows from further back.
     * @param floorY World height of the floor the car drives on.
     */
    fun step(dt: Float, drive: DriveModel, camera: CameraNode, fit: Float, floorY: Float) {
        // Shortest way round: 350° to 10° is a 20° turn, not a 340° one.
        val turn = ((drive.heading - trailingHeading + HALF_TURN) % FULL_TURN + FULL_TURN) % FULL_TURN - HALF_TURN
        trailingHeading += turn * (1f - exp(-FOLLOW_RATE * dt))
        val radians = Math.toRadians(trailingHeading.toDouble())
        val backX = -sin(radians).toFloat()
        val backZ = -cos(radians).toFloat()
        val reach = distance * fit
        camera.position = Position(
            x = drive.x + backX * reach,
            y = floorY + height * fit,
            z = drive.z + backZ * reach,
        )
        camera.lookAt(
            Position(
                x = drive.x - backX * LOOK_AHEAD,
                y = floorY + LOOK_HEIGHT,
                z = drive.z - backZ * LOOK_AHEAD,
            )
        )
    }

    private companion object {
        const val DISTANCE_PER_LENGTH = 1.5f
        const val DISTANCE_BASE = 2.2f
        const val HEIGHT_RATIO = 0.48f
        const val LOOK_AHEAD = 3.5f
        const val LOOK_HEIGHT = 0.6f
        const val FOLLOW_RATE = 3.5f
        const val FULL_TURN = 360f
        const val HALF_TURN = 180f
    }
}
