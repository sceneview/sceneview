package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How the live 3D map of 3D Scan looks at the room while it is scanned. Scanning, it stands over
 * the phone's shoulder, a three-quarter view a little ahead of where it aims, heading-up like the
 * live view ([ScanFollow]); near a part of the floor still to scan it turns partway toward it.
 * Paused or finished, it steps back over the whole room found so far. The orbit camera eases from
 * one pose to the next ([ArDebugOrbitCamera.follow]), so none of these is a cut.
 */
object ScanLiveMapFraming {

    /** Over the shoulder: a three-quarter view, the room's walls standing, the floor ahead. */
    const val ELEVATION_DEGREES = 40f

    /** How far ahead of the phone the map aims, where it is about to scan. */
    const val AHEAD_M = 0.8f

    /** The nearest the map stands from the phone: a corner of a room still reads as one. */
    const val MIN_DISTANCE_M = 3f

    /** The furthest while following: the phone and its surroundings stay legible. */
    const val MAX_DISTANCE_M = 12f

    /** Distance per metre of floor reach round the phone, at the map's ~45° field of view. */
    const val DISTANCE_PER_REACH = 1.3f

    /** Stepped back over the room: a plan with depth. */
    const val OVERVIEW_ELEVATION_DEGREES = 62f

    /** Distance per metre of the room's radius, round its centre. */
    const val OVERVIEW_DISTANCE_PER_RADIUS = 4.2f

    const val OVERVIEW_MIN_DISTANCE_M = 3.5f
    const val OVERVIEW_MAX_DISTANCE_M = 18f

    /** A missed zone nearer than this turns the map toward it. */
    const val PIVOT_RADIUS_M = 2.5f

    /** The share of the turn toward the zone the map takes: the phone stays in view. */
    const val PIVOT_SHARE = 0.5f

    /** Never more than this away from the phone's heading. */
    const val MAX_PIVOT_DEGREES = 50f

    /** How far toward the zone the map's aim moves. */
    const val PIVOT_TARGET_SHARE = 0.5f

    /** A hole smaller than this (two 25 cm cells) is not worth turning for. */
    const val MIN_ZONE_M2 = 0.12f

    /** Where the phone sits in the clear part of the map, from its top: most of it ahead. */
    const val PHONE_AT = 0.62f

    /**
     * The follow pose for the phone at [phone], heading [headingDegrees], over the floor
     * [extent] ((x, z) of [CoverageGrid.floorExtent], empty before any), turned toward the
     * nearest of the [missed] zones' centres within [PIVOT_RADIUS_M].
     */
    fun poseAt(
        phone: DebugPose,
        headingDegrees: Float,
        extent: List<Pair<Float, Float>>,
        missed: List<Pair<Float, Float>> = emptyList(),
    ): OrbitPose {
        val rad = Math.toRadians(headingDegrees.toDouble())
        var x = phone.x - AHEAD_M * sin(rad).toFloat()
        var z = phone.z - AHEAD_M * cos(rad).toFloat()
        var azimuth = headingDegrees
        nearestMissed(phone.x, phone.z, missed)?.let { (zx, zz) ->
            val bearing = Math.toDegrees(atan2(-(zx - phone.x), -(zz - phone.z)).toDouble()).toFloat()
            val turn = (CameraRig.shortestDelta(headingDegrees, bearing) * PIVOT_SHARE)
                .coerceIn(-MAX_PIVOT_DEGREES, MAX_PIVOT_DEGREES)
            azimuth = headingDegrees + turn
            x += (zx - x) * PIVOT_TARGET_SHARE
            z += (zz - z) * PIVOT_TARGET_SHARE
        }
        return OrbitPose(
            target = Position(x, phone.y, z),
            azimuthDegrees = azimuth,
            elevationDegrees = ELEVATION_DEGREES,
            distance = distanceFor(extent, phone.x, phone.z),
        )
    }

    /**
     * The pose over the whole room [extent], seen from the phone's side ([headingDegrees]) so the
     * step back keeps its bearings; on the [phone] while no floor is found. It looks at the floor
     * at [floorY] once known, so the room sits in the middle of the view, not below it.
     */
    fun overview(
        extent: List<Pair<Float, Float>>,
        headingDegrees: Float,
        phone: DebugPose,
        floorY: Float? = null,
    ): OrbitPose {
        if (extent.isEmpty()) {
            return OrbitPose(Position(phone.x, phone.y, phone.z), headingDegrees, OVERVIEW_ELEVATION_DEGREES, OVERVIEW_MIN_DISTANCE_M)
        }
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for ((x, z) in extent) {
            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minZ = minOf(minZ, z)
            maxZ = maxOf(maxZ, z)
        }
        val cx = (minX + maxX) / 2f
        val cz = (minZ + maxZ) / 2f
        var radius = 0f
        for ((x, z) in extent) radius = max(radius, sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)))
        return OrbitPose(
            target = Position(cx, floorY ?: phone.y, cz),
            azimuthDegrees = headingDegrees,
            elevationDegrees = OVERVIEW_ELEVATION_DEGREES,
            distance = (radius * OVERVIEW_DISTANCE_PER_RADIUS).coerceIn(OVERVIEW_MIN_DISTANCE_M, OVERVIEW_MAX_DISTANCE_M),
        )
    }

    /** The nearest of [zones] to (x, z) within [PIVOT_RADIUS_M], or null. */
    fun nearestMissed(x: Float, z: Float, zones: List<Pair<Float, Float>>): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        var bestDistance = PIVOT_RADIUS_M
        for (zone in zones) {
            val dx = zone.first - x
            val dz = zone.second - z
            val d = sqrt(dx * dx + dz * dz)
            if (d <= bestDistance) {
                best = zone
                bestDistance = d
            }
        }
        return best
    }

    /** The centres of [ground]'s holes large enough to go back to ([MIN_ZONE_M2]). */
    fun zonesOf(ground: ScanGround): List<Pair<Float, Float>> =
        RescanSources.holes(ground).filter { it.area >= MIN_ZONE_M2 }.map { hole ->
            val o = hole.outline
            var x = 0f
            var z = 0f
            val n = o.size / 2
            for (i in 0 until n) {
                x += o[i * 2]
                z += o[i * 2 + 1]
            }
            x / n to z / n
        }

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
     * The map's band in a view [viewHeight] tall whose top [clearTop] is under the chrome above
     * and whose part below [clearBottom] is under the controls: the phone drawn [PHONE_AT] down
     * the clear part between, the room framed in it.
     */
    fun band(clearTop: Float, viewHeight: Float, clearBottom: Float = viewHeight): OrbitBand {
        if (viewHeight <= 0f || clearTop.isNaN()) return ScanFollow.BAND
        val top = clearTop.coerceIn(0f, viewHeight * MAX_COVERED_SHARE)
        val bottom = clearBottom.takeIf { it.isFinite() }?.coerceIn(top + viewHeight * MIN_CLEAR_SHARE, viewHeight)
            ?: viewHeight
        val clear = bottom - top
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

    /** Nor leaves less than this between card and controls. */
    private const val MIN_CLEAR_SHARE = 0.35f

    private const val HALF = 0.5f
    private const val HUNDRED = 100f
}
