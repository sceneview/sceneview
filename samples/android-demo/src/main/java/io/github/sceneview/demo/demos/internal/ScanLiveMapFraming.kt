package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.max
import kotlin.math.sqrt

/**
 * How the live 3D map of 3D Scan looks at the room while it is scanned: from above and behind the
 * phone, heading-up like the live view ([ScanFollow]), but high and far enough that the whole
 * floor found so far is on screen — what is covered in the camera's colours, what is not hatched.
 * A Polycam or Scaniverse live scan's overview, rather than the phone's own eye.
 */
object ScanLiveMapFraming {

    /** Steeper than the live view's 38°: a map of the room with depth, its walls still standing. */
    const val ELEVATION_DEGREES = 55f

    /** The nearest the map stands from the phone: a corner of a room still reads as one. */
    const val MIN_DISTANCE_M = 3.5f

    /** The furthest: a hall, seen whole, its points still points. */
    const val MAX_DISTANCE_M = 18f

    /** Distance per metre of floor reach round the phone, at the map's ~45° field of view. */
    const val DISTANCE_PER_REACH = 1.7f

    /** Where the phone sits in the clear part of the map, from its top: most of it ahead. */
    const val PHONE_AT = 0.62f

    /**
     * The follow pose for the phone at [phone], heading [headingDegrees], over the floor
     * [extent] ((x, z) of [CoverageGrid.floorExtent], empty before any).
     */
    fun poseAt(phone: DebugPose, headingDegrees: Float, extent: List<Pair<Float, Float>>): OrbitPose = OrbitPose(
        target = Position(phone.x, phone.y, phone.z),
        azimuthDegrees = headingDegrees,
        elevationDegrees = ELEVATION_DEGREES,
        distance = distanceFor(extent, phone.x, phone.z),
    )

    /** How far the map stands to keep [extent] round (x, z) in view, in metres. */
    fun distanceFor(extent: List<Pair<Float, Float>>, x: Float, z: Float): Float {
        var reach = 0f
        for ((ex, ez) in extent) {
            val dx = ex - x
            val dz = ez - z
            reach = max(reach, sqrt(dx * dx + dz * dz))
        }
        return (reach * DISTANCE_PER_REACH).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)
    }

    /**
     * The map's band in a view [viewHeight] tall whose top [clearTop] is under the chrome above:
     * the phone drawn [PHONE_AT] down the clear part, the room framed in it.
     */
    fun band(clearTop: Float, viewHeight: Float): OrbitBand {
        if (viewHeight <= 0f || clearTop.isNaN()) return ScanFollow.BAND
        val top = clearTop.coerceIn(0f, viewHeight * MAX_COVERED_SHARE)
        val clear = viewHeight - top
        val phone = top + clear * PHONE_AT
        fun hundredths(value: Float) = (value * HUNDRED).toInt() / HUNDRED
        return OrbitBand(
            halfWidth = OrbitBand.CARD.halfWidth,
            halfHeight = hundredths(clear / viewHeight * OrbitBand.MEASURED_FILL),
            lift = hundredths(HALF - phone / viewHeight),
        )
    }

    /** The chrome never takes more than this share of the map: the map stays a map. */
    const val MAX_COVERED_SHARE = 0.6f

    private const val HALF = 0.5f
    private const val HUNDRED = 100f
}
