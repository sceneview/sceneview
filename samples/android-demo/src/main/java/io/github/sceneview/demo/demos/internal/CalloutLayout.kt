package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.fitOrbitRadius
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Five pickable parts; the three fin nodes deliberately share one material choice. */
internal enum class RocketPart(val originalMaterial: Int) {
    Nose(1), Body(0), Window(1), Fins(2), Engine(2),
}

/** Metres and Y-up rotations for the rocket, its attached card and the media gallery. */
internal object CalloutLayout {
    const val FLOOR_SIZE = 90f
    const val ELEVATION_DEGREES = 10f
    const val FOCAL_LENGTH_MM = 28.0
    const val VIEW_PIXELS_PER_UNIT = 250f
    const val PULSE_SCALE = 1.10f
    /** The rocket alone, fins included; [inspectDistance] then makes room for the card. */
    val INSPECT_EXTENT = Size(0.72f, 1f, 0.72f)
    val INSPECT_TARGET = Position(y = 0.5f)
    /** Narrower than the 1.49 m hung: the fit keeps a depth margin a near-flat wall has no use for. */
    val MEDIA_EXTENT = Size(1.38f, 1.4f, 0.3f)
    val MEDIA_TARGET = Position(y = 1.04f)
    const val BODY_RADIUS = 0.15f
    const val BODY_HEIGHT = 0.46f
    const val NOSE_HEIGHT = 0.34f
    const val WINDOW_RADIUS = 0.06f
    /** The nozzle is a cone whose tip is buried in the body: only its flare shows. */
    const val ENGINE_RADIUS = 0.12f
    const val ENGINE_HEIGHT = 0.26f
    /** A slab leaning on the body: its foot sweeps outwards, the floor hides what goes under. */
    val FIN_SIZE = Size(0.03f, 0.34f, 0.16f)
    val FIN_CENTER = Position(y = 0.16f, z = 0.22f)
    const val FIN_TILT_DEGREES = -22f
    /** Height the leaning slab reaches; the card stays above it so no fin is hidden. */
    const val FIN_TOP = 0.35f
    val WINDOW_SCALE = Size(1f, 1f, 0.45f)
    /** None at yaw 0: the front, where the window is, stays clear. */
    val FIN_YAWS = listOf(60f, 180f, 300f)
    const val CARD_GAP = 0.02f
    const val MAX_CARD_RETREAT = 1.8f
    val PICTURE_SIZE = Size(0.64f, 0.512f, 0f)
    val SCREEN_SIZE = Size(0.64f, 0.36f, 0f)
    val BADGE_SIZE = Size(0.3f, 0.3f, 0f)
    /** TextNode rasters 512 x 128: keep the quad at that 4:1 or the glyphs stretch. */
    val CAPTION_SIZE = Size(0.72f, 0.18f, 0f)
    const val FRAME_BORDER = 0.025f
    const val FRAME_DEPTH = 0.035f
    val CONTENT_OFFSET = Position(z = FRAME_DEPTH / 2f + 0.002f)

    fun partPosition(part: RocketPart): Position = when (part) {
        RocketPart.Nose -> Position(y = 0.83f)
        RocketPart.Body -> Position(y = 0.43f)
        RocketPart.Window -> Position(y = 0.52f, z = 0.145f)
        RocketPart.Fins -> Position(y = FIN_CENTER.y)
        RocketPart.Engine -> Position(y = ENGINE_HEIGHT / 2f)
    }

    fun finPosition(yaw: Float): Position = rotateY(FIN_CENTER, yaw)

    /**
     * Metres one dp spans at [distance] from the eye. `contentPadding` makes the lens' field of
     * view span the free area, so its height — not the window's — is what the fov divides.
     */
    fun metersPerDp(distance: Float, freeHeightDp: Float): Float {
        if (freeHeightDp <= 0f) return 0f
        val halfFov = Math.toRadians(verticalFovDegreesForFocalLength(FOCAL_LENGTH_MM) / 2.0)
        return (2.0 * tan(halfFov) * distance / freeHeightDp).toFloat()
    }

    /**
     * Orbit distance for Inspect: the rocket as large as the free area allows, pulled back until
     * a card [cardWidthDp] wide and its [marginDp] fit between the body and the screen edge. A
     * card is sized in dp, the rocket in metres, so a narrow phone trades rocket for card — up to
     * [MAX_CARD_RETREAT], past which the card overlaps the body rather than shrink the scene.
     *
     * The eye looks down by [ELEVATION_DEGREES], so a card beside the nose is nearer to it than
     * the orbit target and drawn larger: the margin is kept for that nearest card.
     */
    fun inspectDistance(aspect: Float, freeWidthDp: Float, cardWidthDp: Float, marginDp: Float): Float {
        val fit = cameraDistance(INSPECT_EXTENT, aspect)
        val room = freeWidthDp / 2f - cardWidthDp - marginDp
        if (aspect <= 0f || freeWidthDp <= 0f) return fit
        if (room <= 0f) return fit * MAX_CARD_RETREAT
        val perDp = metersPerDp(1f, freeWidthDp / aspect)
        val elevation = Math.toRadians(ELEVATION_DEGREES.toDouble())
        val lift = (1f + CARD_GAP - INSPECT_TARGET.y) * sin(elevation).toFloat()
        val forCard = ((BODY_RADIUS + CARD_GAP) / perDp + (freeWidthDp / 2f - marginDp) * lift) / room
        return forCard.coerceIn(fit, fit * MAX_CARD_RETREAT)
    }

    /**
     * Centre of a [card] (metres) beside the part, to the viewer's right, in the plane through the
     * rocket's axis that faces the viewer — the depth [inspectDistance] sized it for. Chosen on
     * selection, it then stays in the object's local frame during spin. The card clears the body
     * sideways and stays above the fins, which reach further out than the body, and under the nose.
     *
     * A card too tall for that — a phone held sideways, where the rocket is a strip high and the
     * card as tall as it — stands outside the fins instead, centred on the rocket's height.
     */
    fun cardAnchor(part: RocketPart, camera: Position, parentYaw: Float, card: Size): Position {
        val eye = rotateY(camera, -parentYaw)
        val bearing = Math.toDegrees(atan2(eye.x.toDouble(), eye.z.toDouble())).toFloat()
        val lowest = FIN_TOP + card.y / 2f
        val highest = 1f + CARD_GAP - card.y / 2f
        val outside = lowest > highest
        val reach = if (outside) INSPECT_EXTENT.x / 2f else BODY_RADIUS
        val height = if (outside) INSPECT_TARGET.y else partPosition(part).y.coerceIn(lowest, highest)
        return rotateY(Position(x = reach + CARD_GAP + card.x / 2f), bearing).copy(y = height)
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

    /**
     * Local X rotation that, after [billboardYawDegrees], leans a quad back until it is parallel to
     * the image plane of an eye looking at [world] from above: its edges then stay upright on screen.
     */
    fun billboardPitchDegrees(world: Position, camera: Position): Float {
        val flat = hypot(camera.x - world.x, camera.z - world.z)
        if (flat < 1e-2f) return 0f
        return -Math.toDegrees(atan2((camera.y - world.y).toDouble(), flat.toDouble())).toFloat()
    }

    fun normalizeDegrees(degrees: Float): Float {
        val wrapped = ((degrees + 180f) % 360f + 360f) % 360f - 180f
        return if (wrapped == -180f) 180f else if (wrapped == 0f) 0f else wrapped
    }

    fun nextTurntableYaw(previous: Float, deltaNanos: Long): Float =
        if (deltaNanos <= 0) previous else normalizeDegrees(previous + 15f * deltaNanos / 1_000_000_000f)

    fun cameraDistance(extent: Size, aspect: Float): Float = fitOrbitRadius(
        extent.x, extent.y, extent.z, aspect, ELEVATION_DEGREES,
        azimuthInvariant = false, focalLengthMm = FOCAL_LENGTH_MM,
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

    /**
     * Two fixed surfaces hung centre to centre and a sprite raised above the gap between them.
     * The two wall captions share one line, under the taller frame.
     */
    val GALLERY = listOf(
        Exhibit(Position(-0.385f, 0.86f, 0f), 14f, PICTURE_SIZE, Position(-0.385f, 0.44f, 0.05f)),
        Exhibit(Position(0.385f, 0.86f, 0f), -14f, SCREEN_SIZE, Position(0.385f, 0.44f, 0.05f)),
        Exhibit(Position(0f, 1.56f, -0.1f), 0f, BADGE_SIZE, Position(0f, 1.28f, -0.1f)),
    )

    /**
     * A phone held sideways leaves a strip: the same three on one line, the sprite between the two
     * surfaces, so the captions are half as large again as the upright wall would leave them.
     */
    val GALLERY_STRIP = listOf(
        Exhibit(Position(-0.8f, 0.86f, 0f), 14f, PICTURE_SIZE, Position(-0.8f, 0.44f, 0.05f)),
        Exhibit(Position(0.8f, 0.86f, 0f), -14f, SCREEN_SIZE, Position(0.8f, 0.44f, 0.05f)),
        Exhibit(Position(0f, 0.86f, -0.1f), 0f, BADGE_SIZE, Position(0f, 0.44f, -0.1f)),
    )
    val MEDIA_STRIP_EXTENT = Size(2.34f, 0.82f, 0.3f)
    val MEDIA_STRIP_TARGET = Position(y = 0.745f)

    fun gallery(strip: Boolean): List<Exhibit> = if (strip) GALLERY_STRIP else GALLERY

    fun mediaExtent(strip: Boolean): Size = if (strip) MEDIA_STRIP_EXTENT else MEDIA_EXTENT

    fun mediaTarget(strip: Boolean): Position = if (strip) MEDIA_STRIP_TARGET else MEDIA_TARGET

    fun frameSize(size: Size): Size = Size(
        size.x + FRAME_BORDER * 2f, size.y + FRAME_BORDER * 2f, FRAME_DEPTH
    )
}

/** One exhibit: where it hangs, how it is turned, its size and where its caption sits. */
internal data class Exhibit(val position: Position, val yaw: Float, val size: Size, val caption: Position)
