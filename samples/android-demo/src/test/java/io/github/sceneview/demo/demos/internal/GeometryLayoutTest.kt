package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Aabb
import io.github.sceneview.CameraFit
import io.github.sceneview.demo.demos.internal.GeometryLayout.Arrangement
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * JVM tests for [GeometryLayout] — the layout and framing arithmetic behind
 * [io.github.sceneview.demo.demos.GeometryDemo].
 *
 * These exist because the defects they guard against are numbers, invisible to every other
 * gate: a row wider than the frame it was viewed in
 * ([#2873](https://github.com/sceneview/sceneview/issues/2873)), then a landscape viewport
 * squeezed until the shapes were a dozen pixels across. The demo compiled and rendered through
 * both. So the numbers are what get asserted — in the free band of real windows, portrait and
 * landscape.
 *
 * The frustum relation used here (`halfHeight = distance · 12 / focalLength`) is the one
 * Filament's `setLensProjection` implements, and it reproduced on-device pixel measurements
 * to within 1.5 % on the QA emulator. The fit itself is the SDK's `fitCameraToBounds`; what is
 * checked here is what the demo asks of it — which box, from where, into which band.
 */
class GeometryLayoutTest {

    /** The band a window's chrome leaves free, in dp — what the scene hands to `contentPadding`. */
    private data class Band(val name: String, val width: Float, val height: Float) {
        val aspect: Float get() = width / height
    }

    /** The QA phone (426 × 952 dp), portrait: title row above, two chip rows and the dock below. */
    private val portraitPhone = Band("portrait phone", width = 426f, height = 612f)

    /** The same phone turned: one chip row and the dock leave a band shallower than a slot row. */
    private val landscapePhone = Band("landscape phone", width = 860f, height = 160f)

    /** A tablet-like window: wide, but tall enough for two rows. */
    private val tablet = Band("tablet", width = 800f, height = 500f)

    private val bands = listOf(portraitPhone, landscapePhone, tablet)

    private fun Band.arrangement() = GeometryLayout.arrangementFor(aspect)

    private fun Band.framing() = GeometryLayout.framing(arrangement(), aspect)

    /** Tangent of half the vertical field of view of the lens the demo picks for a band. */
    private fun tanHalfFov(aspect: Float) = 12f / GeometryLayout.focalLengthMm(aspect)

    /**
     * Where the eight corners of the framed box land in a band of [aspect], as shares of its
     * half-width and half-height (±1 is the edge) — a plain pinhole, independent of the SDK's fit.
     */
    private fun projectedCorners(fit: CameraFit, bounds: Aabb, aspect: Float): List<Pair<Float, Float>> {
        val forward = normalize(fit.target - fit.eye)
        val right = normalize(cross(forward, Position(0f, 1f, 0f)))
        val up = cross(right, forward)
        val signs = listOf(-1f, 1f)
        return signs.flatMap { sx ->
            signs.flatMap { sy ->
                signs.map { sz ->
                    val corner = bounds.center +
                        Position(sx * bounds.halfExtent.x, sy * bounds.halfExtent.y, sz * bounds.halfExtent.z)
                    val fromEye = corner - fit.eye
                    val depth = dot(fromEye, forward)
                    val tan = tanHalfFov(aspect)
                    dot(fromEye, right) / (depth * tan * aspect) to dot(fromEye, up) / (depth * tan)
                }
            }
        }
    }

    // ── Slots ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every arrangement has one slot per shape`() {
        Arrangement.entries.forEach { arrangement ->
            assertEquals("$arrangement", GeometryShape.entries.size, arrangement.rows.sum())
        }
    }

    @Test
    fun `the block is centred on the origin, which is the orbit pivot`() {
        Arrangement.entries.forEach { arrangement ->
            val slots = GeometryShape.entries.map { GeometryLayout.position(it, arrangement) }
            assertEquals("$arrangement x", 0f, slots.minOf { it.x } + slots.maxOf { it.x }, EPSILON)
            assertEquals("$arrangement y", 0f, slots.minOf { it.y } + slots.maxOf { it.y }, EPSILON)
            assertTrue("$arrangement z", slots.all { it.z == 0f })
        }
    }

    @Test
    fun `slots read in chip order, left to right then top to bottom`() {
        Arrangement.entries.forEach { arrangement ->
            val slots = GeometryShape.entries.map { GeometryLayout.position(it, arrangement) }
            slots.zipWithNext { a, b ->
                val sameRow = abs(a.y - b.y) < EPSILON
                assertTrue("$arrangement: $a then $b", if (sameRow) b.x > a.x else b.y < a.y)
            }
        }
    }

    @Test
    fun `no two shapes can touch, whatever their rotation`() {
        Arrangement.entries.forEach { arrangement ->
            val shapes = GeometryShape.entries
            for (i in shapes.indices) for (j in i + 1 until shapes.size) {
                val a = GeometryLayout.position(shapes[i], arrangement)
                val b = GeometryLayout.position(shapes[j], arrangement)
                val gap = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)) -
                    GeometryLayout.boundingRadius(shapes[i]) - GeometryLayout.boundingRadius(shapes[j])
                assertTrue("$arrangement: ${shapes[i]} and ${shapes[j]} are $gap m apart", gap > 0.02f)
            }
        }
    }

    @Test
    fun `every shape stays inside the extents the camera is fitted to`() {
        Arrangement.entries.forEach { arrangement ->
            GeometryShape.entries.forEach { shape ->
                val slot = GeometryLayout.position(shape, arrangement)
                val radius = GeometryLayout.boundingRadius(shape)
                assertTrue("$arrangement $shape x", abs(slot.x) + radius <= arrangement.extentX / 2f + EPSILON)
                assertTrue("$arrangement $shape y", abs(slot.y) + radius <= arrangement.extentY / 2f + EPSILON)
                assertTrue("$arrangement $shape z", radius <= GeometryLayout.DEPTH / 2f + EPSILON)
            }
        }
    }

    // ── Arrangement follows the free band ────────────────────────────────────────────────────────

    @Test
    fun `a portrait phone gets the staggered block`() {
        assertEquals(Arrangement.Honeycomb, portraitPhone.arrangement())
    }

    @Test
    fun `a landscape phone gets a single row`() {
        assertEquals(Arrangement.SingleRow, landscapePhone.arrangement())
    }

    @Test
    fun `a wide band with room for two rows gets two rows`() {
        assertEquals(Arrangement.TwoRows, tablet.arrangement())
    }

    // ── Framing ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the block fits the free band, centred, and fills it on its limiting axis`() {
        bands.forEach { band ->
            val corners = projectedCorners(band.framing(), GeometryLayout.bounds(band.arrangement()), band.aspect)
            val xs = corners.map { it.first }
            val ys = corners.map { it.second }
            assertTrue("${band.name} width ${xs.max()}", xs.max() <= GeometryLayout.HORIZONTAL_FILL + FIT_EPSILON)
            assertTrue("${band.name} height ${ys.max()}", ys.max() <= GeometryLayout.VERTICAL_FILL + FIT_EPSILON)
            assertEquals("${band.name} is not centred across", 0f, xs.min() + xs.max(), FIT_EPSILON)
            val slack = minOf(
                GeometryLayout.HORIZONTAL_FILL - xs.max(),
                GeometryLayout.VERTICAL_FILL - maxOf(ys.max(), -ys.min()),
            )
            assertEquals("${band.name} is not fitted tight", 0f, slack, FIT_EPSILON)
        }
    }

    @Test
    fun `shapes are big enough to read in portrait and in landscape`() {
        // On main the landscape viewport was squeezed until the sphere was ~4 dp across.
        bands.forEach { band ->
            val metresPerDp = 2f * band.framing().distance * tanHalfFov(band.aspect) / band.height
            val sphereDp = 2f * GeometryLayout.SPHERE_RADIUS / metresPerDp
            assertTrue("${band.name}: sphere is $sphereDp dp across", sphereDp >= 64f)
        }
    }

    // ── Lens ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a band taller than wide keeps the default lens`() {
        assertEquals(GeometryLayout.FOCAL_LENGTH_MM, GeometryLayout.focalLengthMm(portraitPhone.aspect), EPSILON)
        assertEquals(GeometryLayout.FOCAL_LENGTH_MM, GeometryLayout.focalLengthMm(1f), EPSILON)
    }

    @Test
    fun `a wide band never sees wider than the default lens does on its tall side`() {
        // At 28 mm the landscape band's horizontal field was 140°: the end shapes were drawn
        // twice as wide as they are tall.
        val defaultTan = 12f / GeometryLayout.FOCAL_LENGTH_MM
        bands.forEach { band ->
            val horizontalTan = tanHalfFov(band.aspect) * band.aspect
            assertTrue("${band.name}: half-width tangent $horizontalTan", horizontalTan <= defaultTan + EPSILON)
            assertTrue("${band.name}: half-height tangent", tanHalfFov(band.aspect) <= defaultTan + EPSILON)
        }
    }

    @Test
    fun `an unmeasured band gets the default lens`() {
        listOf(0f, -4f, Float.NaN, Float.POSITIVE_INFINITY).forEach { aspect ->
            val lens = GeometryLayout.focalLengthMm(aspect)
            assertEquals("aspect $aspect", GeometryLayout.FOCAL_LENGTH_MM, lens, EPSILON)
        }
    }

    @Test
    fun `a band with no room still yields a camera`() {
        // Not a throw: the first layout pass can report a zero or unmeasured band.
        listOf(0f, -4f, Float.NaN, Float.POSITIVE_INFINITY).forEach { aspect ->
            val fit = GeometryLayout.framing(GeometryLayout.arrangementFor(aspect), aspect)
            assertTrue("aspect $aspect gave ${fit.distance}", fit.distance.isFinite() && fit.distance > 0f)
        }
    }

    // ── Camera ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the camera looks at the block from slightly above the horizon, square on`() {
        bands.forEach { band ->
            val fit = band.framing()
            val back = fit.eye - fit.target
            assertEquals("${band.name} is off to one side", 0f, back.x, EPSILON)
            assertEquals(
                band.name,
                GeometryLayout.CAMERA_PITCH_DEGREES.toDouble(),
                Math.toDegrees(atan2(back.y, back.z).toDouble()),
                1e-3,
            )
        }
    }

    @Test
    fun `a QA distance moves the eye along the same line of sight`() {
        val fitted = portraitPhone.framing()
        val forced = GeometryLayout.framing(portraitPhone.arrangement(), portraitPhone.aspect, distance = 5f)
        assertEquals(fitted.target, forced.target)
        assertEquals(5f, forced.distance, EPSILON)
        val fittedBack = normalize(fitted.eye - fitted.target)
        val forcedBack = normalize(forced.eye - forced.target)
        assertEquals(1f, dot(fittedBack, forcedBack), EPSILON)
    }

    private companion object {
        const val EPSILON = 1e-4f

        /** Float arithmetic through a perspective divide: a thousandth of the half-view. */
        const val FIT_EPSILON = 1e-3f
    }
}
