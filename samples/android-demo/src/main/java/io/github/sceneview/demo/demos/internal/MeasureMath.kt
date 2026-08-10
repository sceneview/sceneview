package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure measurement maths for the `ar-measure` demo.
 *
 * Deliberately free of ARCore and Filament types: everything here takes plain world-space
 * [Position]s so it runs in a JVM unit test with no device, no emulator and no GL context.
 * The demo composable owns the AR plumbing (hit tests, anchors, node rendering); this file
 * owns the arithmetic that produces the numbers shown to the user — which is the part that
 * can be *wrong* in a way no screenshot would reveal.
 *
 * See `samples/android-demo/AR_MEASURE.md` for what these numbers are actually worth: the
 * arithmetic below is exact, the world-space points it is fed are not.
 */

/** Straight-line distance between two world-space points, in metres. */
fun measureDistanceMeters(a: Position, b: Position): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val dz = b.z - a.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Midpoint of the segment `a`–`b` — where the demo anchors a segment's 3D label. */
fun measureMidpoint(a: Position, b: Position): Position =
    Position(x = (a.x + b.x) / 2f, y = (a.y + b.y) / 2f, z = (a.z + b.z) / 2f)

/**
 * Formats a metre distance as centimetres for display.
 *
 * One decimal, never more: a second decimal would render tenths of a millimetre, which
 * would be a lie about the precision of the underlying pose (see `AR_MEASURE.md`). The
 * value is reported in centimetres at every magnitude — "312.7 cm" rather than "3.13 m" —
 * so a chain of segments stays visually comparable without a unit switch mid-list.
 */
fun formatCentimeters(meters: Float): String =
    "%.1f cm".format(Locale.US, meters * 100f)

/**
 * Total length of the polyline through [points], in metres.
 *
 * @param closed when `true`, also counts the segment from the last point back to the first,
 *               turning the chain into a perimeter.
 */
fun measurePerimeterMeters(points: List<Position>, closed: Boolean): Float {
    if (points.size < 2) return 0f
    var total = 0f
    for (i in 0 until points.size - 1) {
        total += measureDistanceMeters(points[i], points[i + 1])
    }
    if (closed && points.size > 2) {
        total += measureDistanceMeters(points.last(), points.first())
    }
    return total
}

/**
 * The three side lengths of the bounding box around [points], in metres.
 *
 * **Axis-aligned to the ARCore *world* frame, not to the measured object.** ARCore's `+Y`
 * is gravity-up, so [heightMeters] is a physically meaningful vertical extent. `X` and `Z`,
 * however, are fixed at session start from wherever the device happened to be pointing —
 * so [widthMeters] and [depthMeters] are the extents along two arbitrary horizontal axes,
 * *not* along the object's own edges. Measuring a table that sits at 45° to the session
 * axes yields a box noticeably larger than the table.
 *
 * This is a deliberate limitation, not an oversight: an object-aligned box would need a
 * horizontal-plane PCA over the point set and a convention for which side is "width", and
 * the demo would then be teaching that instead of teaching AR measurement. To get useful
 * `W`/`D` numbers, start the AR session roughly square to the object.
 */
data class BoxDimensions(
    val widthMeters: Float,
    val heightMeters: Float,
    val depthMeters: Float,
)

/** Returns `null` for fewer than two points — a single point has no extent to report. */
fun measureBoundingBox(points: List<Position>): BoxDimensions? {
    if (points.size < 2) return null
    var minX = points[0].x; var maxX = points[0].x
    var minY = points[0].y; var maxY = points[0].y
    var minZ = points[0].z; var maxZ = points[0].z
    for (p in points) {
        if (p.x < minX) minX = p.x
        if (p.x > maxX) maxX = p.x
        if (p.y < minY) minY = p.y
        if (p.y > maxY) maxY = p.y
        if (p.z < minZ) minZ = p.z
        if (p.z > maxZ) maxZ = p.z
    }
    return BoxDimensions(
        widthMeters = abs(maxX - minX),
        heightMeters = abs(maxY - minY),
        depthMeters = abs(maxZ - minZ),
    )
}

/**
 * True when any point of [current] has moved more than [epsilonMeters] from [previous],
 * or when the two lists have different sizes.
 *
 * The demo re-reads every anchor pose on every ARCore frame, because anchors drift as
 * tracking refines. Pushing those poses into Compose state unconditionally would
 * recompose the whole scene subtree at frame rate for sub-millimetre jitter that no user
 * can see. Gating the state write on this predicate keeps the labels stable and the
 * recompositions rare, while still following a real anchor correction when one lands.
 *
 * 1 mm is well below the demo's own accuracy floor (centimetres at best — `AR_MEASURE.md`),
 * so nothing observable is discarded.
 */
fun measurePointsMoved(
    previous: List<Position>,
    current: List<Position>,
    epsilonMeters: Float = 0.001f,
): Boolean {
    if (previous.size != current.size) return true
    for (i in previous.indices) {
        if (measureDistanceMeters(previous[i], current[i]) > epsilonMeters) return true
    }
    return false
}
