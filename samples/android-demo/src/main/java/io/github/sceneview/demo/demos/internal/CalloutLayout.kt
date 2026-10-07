package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.fitOrbitRadius
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Five pickable parts; the three fin nodes deliberately share one material choice. */
internal enum class RocketPart(val originalMaterial: Int) {
    Nose(1), Body(0), Window(1), Fins(2), Engine(2),
}

/** Metres and Y-up rotations for the rocket, its attached card and the media gallery. */
internal object CalloutLayout {
    const val FLOOR_SIZE = 90f
    const val ELEVATION_DEGREES = 10f
    const val CARD_WIDTH_METERS = 0.58f
    const val VIEW_PIXELS_PER_UNIT = 250f
    const val PULSE_SCALE = 1.10f
    val INSPECT_EXTENT = Size(1.45f, 1.25f, 1.45f)
    val INSPECT_TARGET = Position(y = 0.5f)
    val MEDIA_EXTENT = Size(1.8f, 1.5f, 0.65f)
    val MEDIA_TARGET = Position(y = 1.05f)
    const val BODY_RADIUS = 0.16f
    const val BODY_HEIGHT = 0.56f
    const val NOSE_HEIGHT = 0.29f
    const val WINDOW_RADIUS = 0.075f
    const val ENGINE_RADIUS = 0.105f
    const val ENGINE_HEIGHT = 0.15f
    val FIN_SIZE = Size(0.045f, 0.4f, 0.24f)
    val WINDOW_SCALE = Size(1f, 1f, 0.45f)
    val FIN_YAWS = listOf(0f, 120f, 240f)
    val PICTURE_SIZE = Size(0.62f, 0.496f, 0f)
    val SCREEN_SIZE = Size(0.7f, 0.39375f, 0f)
    val BADGE_SIZE = Size(0.3f, 0.3f, 0f)
    val CAPTION_SIZE = Size(0.62f, 0.155f, 0f)
    const val FRAME_BORDER = 0.025f
    const val FRAME_DEPTH = 0.035f
    val CONTENT_OFFSET = Position(z = FRAME_DEPTH / 2f + 0.002f)

    fun partPosition(part: RocketPart): Position = when (part) {
        RocketPart.Nose -> Position(y = 0.855f)
        RocketPart.Body -> Position(y = 0.43f)
        RocketPart.Window -> Position(y = 0.53f, z = 0.155f)
        RocketPart.Fins -> Position(y = 0.2f)
        RocketPart.Engine -> Position(y = 0.075f)
    }

    fun finPosition(yaw: Float): Position = rotateY(Position(y = 0.2f, z = 0.2f), yaw)

    /** The anchor is chosen on selection, then stays in the object's local frame during spin. */
    fun cardAnchor(part: RocketPart, camera: Position, parentYaw: Float): Position {
        val eye = rotateY(camera, -parentYaw)
        val bearing = Math.toDegrees(atan2(eye.x.toDouble(), eye.z.toDouble())).toFloat()
        return rotateY(Position(x = 0.36f, z = 0.22f), bearing).copy(
            y = partPosition(part).y.coerceIn(0.24f, 0.82f)
        )
    }

    fun rotateY(local: Position, yawDegrees: Float): Position {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        return Position(local.x * c + local.z * s, local.y, -local.x * s + local.z * c)
    }

    /** Turns a quad's +Z towards the eye, subtracting its parent's yaw for a local rotation. */
    fun billboardYawDegrees(world: Position, camera: Position, parentYaw: Float = 0f): Float {
        val dx = camera.x - world.x
        val dz = camera.z - world.z
        if (dx * dx + dz * dz < 1e-4f) return 0f
        return normalizeDegrees(Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat() - parentYaw)
    }

    fun normalizeDegrees(degrees: Float): Float {
        val wrapped = ((degrees + 180f) % 360f + 360f) % 360f - 180f
        return if (wrapped == -180f) 180f else if (wrapped == 0f) 0f else wrapped
    }

    fun nextTurntableYaw(previous: Float, deltaNanos: Long): Float =
        if (deltaNanos <= 0) previous else normalizeDegrees(previous + 15f * deltaNanos / 1_000_000_000f)

    fun cameraDistance(extent: Size, aspect: Float): Float = fitOrbitRadius(
        extent.x, extent.y, extent.z, aspect, ELEVATION_DEGREES,
        azimuthInvariant = false,
    )

    /** Eye offset from the orbit target; add the target before passing it to the manipulator. */
    fun cameraHome(distance: Float): Position {
        val radians = Math.toRadians(ELEVATION_DEGREES.toDouble())
        val radius = distance.coerceAtLeast(0.6f)
        return Position(y = radius * sin(radians).toFloat(), z = radius * cos(radians).toFloat())
    }

    fun movedPerceptibly(previous: Position, current: Position): Boolean =
        abs(previous.x - current.x) > 0.001f || abs(previous.y - current.y) > 0.001f ||
            abs(previous.z - current.z) > 0.001f

    /** Two fixed surfaces and a raised central sprite form a shallow arc towards the home eye. */
    val GALLERY = listOf(
        Exhibit(Position(-0.48f, 0.85f, 0f), 14f, PICTURE_SIZE),
        Exhibit(Position(0.48f, 0.85f, 0f), -14f, SCREEN_SIZE),
        Exhibit(Position(0f, 1.65f, -0.16f), 0f, BADGE_SIZE),
    )

    fun captionPosition(exhibit: Exhibit): Position = exhibit.position.copy(
        y = exhibit.position.y - exhibit.size.y / 2f - CAPTION_SIZE.y / 2f - 0.045f
    )

    fun frameSize(size: Size): Size = Size(
        size.x + FRAME_BORDER * 2f, size.y + FRAME_BORDER * 2f, FRAME_DEPTH
    )
}

internal data class Exhibit(val position: Position, val yaw: Float, val size: Size)
