package io.github.sceneview.demo.demos.internal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.sceneview.demo.demos.internal.CosmosSpacetime.BODY_COUNT
import io.github.sceneview.demo.demos.internal.CosmosSpacetime.RADIUS
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.viewer.ViewerBackdrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Spacetime contract shared with iOS (`CosmosSpacetimeTests`): same bodies, same profile,
 * same reference table, same invariants. A value changed here changes there too.
 */
class CosmosSpacetimeTest {

    private val qa = CosmosSpacetime.QA_TIME

    private fun field(time: Float = qa, well: Float = 1f) = SpacetimeField().apply { prepare(time, well) }

    @Test
    fun `the reference table at 9 s, full depth`() {
        val f = field()
        val golden = listOf(
            Triple(0f, 0f, -0.3375f),
            Triple(1f, 0f, 0.2812f),
            Triple(0f, -2f, 0.8139f),
            Triple(-3f, 1.5f, 1.2977f),
            Triple(4f, 4f, 1.7000f),
            Triple(6f, -6f, 1.9835f),
            Triple(10.5f, 0f, 2.0882f),
            Triple(12f, 0f, 2.1321f),
        )
        for ((x, z, h) in golden) assertEquals("H($x, $z)", h, f.height(x, z), 1e-3f)
        assertEquals("C", 2.1321f, f.offset, 1e-3f)
    }

    @Test
    fun `rest heights at 9 s`() {
        val f = field()
        assertEquals(-1.2185f, f.x(CosmosSpacetime.EMBER), 1e-3f)
        assertEquals(0.4531f, f.z(CosmosSpacetime.EMBER), 1e-3f)
        val expected = mapOf(
            CosmosSpacetime.EMBER to -0.0599f,
            CosmosSpacetime.AZURE to 0.4144f,
            CosmosSpacetime.RINGED to 1.0843f,
            CosmosSpacetime.OCHRE to 1.2130f,
            CosmosSpacetime.ICE to 1.4937f,
            CosmosSpacetime.MOON_I to 1.4382f,
            CosmosSpacetime.MOON_O to 0.9509f,
        )
        for ((index, y) in expected) assertEquals(CosmosSpacetime.NAMES[index], y, f.rest(index), 1e-3f)
    }

    @Test
    fun `the star's centre stays at the origin at every well depth`() {
        for (step in 0..21) {
            val well = step * 0.05f
            val f = field(time = step * 7.3f, well = well)
            assertEquals("w $well", 0f, f.rest(CosmosSpacetime.STAR), 1e-4f)
        }
    }

    @Test
    fun `the analytic gradient matches finite differences, the rim window included`() {
        val f = field()
        val g = FloatArray(2)
        val h = 1e-2f
        val points = listOf(0.3f to 0.2f, 1f to 0f, -0.4f to 1.4f, 4f to 4f, -4.2f to -2.5f, 10.5f to 0.5f, -7f to 8f)
        for ((x, z) in points) {
            f.gradient(x, z, g)
            val dx = (f.height(x + h, z) - f.height(x - h, z)) / (2 * h)
            val dz = (f.height(x, z + h) - f.height(x, z - h)) / (2 * h)
            assertEquals("∂x at ($x, $z)", dx, g[0], 1e-3f)
            assertEquals("∂z at ($x, $z)", dz, g[1], 1e-3f)
        }
    }

    @Test
    fun `every world sits in a hollow of its own, over 10 minutes`() {
        val f = SpacetimeField()
        val g = FloatArray(2)
        for (step in 0..1200) {
            f.prepare(step * 0.5f, 1f)
            for (index in 1 until BODY_COUNT) {
                // Gradient descent from the body's centre: the minimum it reaches is under the body.
                val cx = f.x(index)
                val cz = f.z(index)
                var x = cx
                var z = cz
                for (i in 0 until DESCENT_STEPS) {
                    f.gradient(x, z, g)
                    val slope = hypot(g[0], g[1])
                    // Stop on flat ground, or once well past the body (the assert below fails).
                    if (slope < 1e-6f || hypot(x - cx, z - cz) > 2 * RADIUS[index]) break
                    val k = DESCENT_RATE / max(1f, slope)
                    x -= g[0] * k
                    z -= g[1] * k
                }
                val offset = hypot(x - cx, z - cz)
                assertTrue(
                    "${CosmosSpacetime.NAMES[index]} at t=${step * 0.5f}: minimum ${offset / RADIUS[index]} ρ away",
                    offset < RADIUS[index],
                )
            }
        }
    }

    @Test
    fun `the rings clear the sheet as drawn, all round ten minutes of orbit`() {
        // Independent of ringedRest's own edge sampler: the whole annulus, nine radii by 192
        // angles, against the sheet as the GPU draws it — the grid's triangles, linearly
        // interpolated between vertex heights.
        val f = SpacetimeField()
        val ringed = CosmosSpacetime.RINGED
        var worst = Float.MAX_VALUE
        var worstProfile = Float.MAX_VALUE
        for (step in 0..1200) {
            f.prepare(step * 0.5f, 1f)
            val cx = f.x(ringed)
            val cz = f.z(ringed)
            val y = f.ringedRest()
            val n = f.ringNormal
            // The rings stay mostly level, along the slope.
            assertTrue(n[1] > 0.8f)
            val u = floatArrayOf(1f - n[0] * n[0], -n[0] * n[1], -n[0] * n[2])
            val ul = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
            for (k in 0..2) u[k] /= ul
            val v = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
            for (r in 0..8) {
                val radius = CosmosSystem.RING_INNER + r * (CosmosSystem.RING_OUTER - CosmosSystem.RING_INNER) / 8f
                for (i in 0 until 192) {
                    val a = 2f * PI.toFloat() * i / 192
                    val ca = cos(a)
                    val sa = sin(a)
                    val qx = cx + radius * (ca * u[0] + sa * v[0])
                    val qy = y + radius * (ca * u[1] + sa * v[1])
                    val qz = cz + radius * (ca * u[2] + sa * v[2])
                    worst = min(worst, qy - drawnHeight(f, qx, qz))
                    worstProfile = min(worstProfile, qy - f.height(qx, qz))
                }
            }
        }
        // Over the profile itself the gap stays within a hair of RING_CLEARANCE (0.014); the
        // drawn chords sit a little higher in the hollows, and still leave over 0.013.
        assertTrue("worst gap over the profile $worstProfile", worstProfile >= 0.0135f)
        assertTrue("worst gap over the drawn sheet $worst", worst >= 0.013f)
    }

    /** The sheet's height at ([x], [z]) as drawn: the grid triangle under it, interpolated. */
    private fun drawnHeight(f: SpacetimeField, x: Float, z: Float): Float {
        val r = hypot(x, z)
        val ring = (r / CosmosSpacetime.INNER_EXTENT * CosmosSpacetime.INNER_RINGS).toInt()
        assertTrue("inside the even rings", ring < CosmosSpacetime.INNER_RINGS)
        var theta = atan2(z, x)
        if (theta < 0f) theta += 2f * PI.toFloat()
        val sector = (theta / (2f * PI.toFloat()) * CosmosSpacetime.SECTORS).toInt() % CosmosSpacetime.SECTORS
        fun vertex(ring: Int, sector: Int): FloatArray {
            val rr = CosmosSpacetime.ringRadius(ring)
            val aa = 2f * PI.toFloat() * (sector % CosmosSpacetime.SECTORS) / CosmosSpacetime.SECTORS
            val vx = rr * cos(aa)
            val vz = rr * sin(aa)
            return floatArrayOf(vx, vz, f.height(vx, vz))
        }
        val a = vertex(ring, sector)
        val b = vertex(ring, sector + 1)
        val c = vertex(ring + 1, sector)
        val d = vertex(ring + 1, sector + 1)
        // The grid's two triangles per cell, (a, b, c) and (b, d, c): the one holding the point.
        fun weights(p: FloatArray, q: FloatArray, s: FloatArray): FloatArray {
            val det = (q[1] - s[1]) * (p[0] - s[0]) + (s[0] - q[0]) * (p[1] - s[1])
            val w0 = ((q[1] - s[1]) * (x - s[0]) + (s[0] - q[0]) * (z - s[1])) / det
            val w1 = ((s[1] - p[1]) * (x - s[0]) + (p[0] - s[0]) * (z - s[1])) / det
            return floatArrayOf(w0, w1, 1f - w0 - w1)
        }
        val first = weights(a, b, c)
        val second = weights(b, d, c)
        return if ((first.minOrNull() ?: 0f) >= (second.minOrNull() ?: 0f)) {
            first[0] * a[2] + first[1] * b[2] + first[2] * c[2]
        } else {
            second[0] * b[2] + second[1] * d[2] + second[2] * c[2]
        }
    }

    @Test
    fun `no two worlds ever touch, rings counted`() {
        val f = SpacetimeField()
        var worst = Float.MAX_VALUE
        for (step in 0..2400) {
            f.prepare(step * 0.25f, 1f)
            for (a in 1 until BODY_COUNT) {
                for (b in a + 1 until BODY_COUNT) {
                    val rings = if (a == CosmosSpacetime.RINGED || b == CosmosSpacetime.RINGED) {
                        CosmosSystem.RING_OUTER - RADIUS[CosmosSpacetime.RINGED]
                    } else {
                        0f
                    }
                    val gap = hypot(f.x(a) - f.x(b), f.z(a) - f.z(b)) - RADIUS[a] - RADIUS[b] - rings
                    worst = minOf(worst, gap)
                }
            }
        }
        assertTrue("closest approach $worst", worst > 0.1f)
    }

    @Test
    fun `the ringed world keeps Starlight's orbit`() {
        for (step in 0..50) {
            val t = step * 3.7f
            assertEquals(CosmosSystem.orbitAngle(t), CosmosSpacetime.angle(CosmosSpacetime.RINGED, t), 1e-5f)
        }
    }

    @Test
    fun `the camera frames the star at 58 percent, a tenth of the width, Ochre's orbit on screen`() {
        for ((aspect, front) in listOf(9f / 16f to 0.75f, 9f / 19.5f to 0.71f)) {
            val pose = CosmosSpacetime.pose(aspect, 0f, CosmosSpacetime.ELEVATION_DEGREES, FloatArray(9))
            val f = field()
            val star = CosmosSystem.project(pose, aspect, floatArrayOf(0f, 0f, 0f))!!
            val starY = (1f - star[1]) / 2f
            assertTrue("star at $starY of the height", starY in 0.575f..0.62f)
            val left = CosmosSystem.project(pose, aspect, floatArrayOf(-RADIUS[0], 0f, 0f))!!
            val right = CosmosSystem.project(pose, aspect, floatArrayOf(RADIUS[0], 0f, 0f))!!
            val width = (right[0] - left[0]) / 2f
            assertTrue("star spans $width of the width", width in 0.095f..0.12f)
            // The front of Ochre's orbit.
            val r = CosmosSpacetime.ORBIT[CosmosSpacetime.OCHRE]
            val y = f.height(0f, r) + 0.75f * RADIUS[CosmosSpacetime.OCHRE]
            val ochre = CosmosSystem.project(pose, aspect, floatArrayOf(0f, y, r))!!
            assertEquals("Ochre's front at aspect $aspect", front, (1f - ochre[1]) / 2f, 0.02f)
        }
    }

    @Test
    fun `a drag turns the camera round the star and keeps it framed`() {
        val aspect = 9f / 19.5f
        for (yaw in listOf(-90f, -30f, 45f, 170f)) {
            for (elevation in listOf(CosmosSpacetime.MIN_ELEVATION_DEGREES, CosmosSpacetime.MAX_ELEVATION_DEGREES)) {
                val pose = CosmosSpacetime.pose(aspect, yaw, elevation, FloatArray(9))
                val star = CosmosSystem.project(pose, aspect, floatArrayOf(0f, 0f, 0f))!!
                assertEquals(0f, star[0], 1e-4f)
                assertEquals(0.58f, (1f - star[1]) / 2f, 0.01f)
                assertTrue("above the sheet", pose[1] > 0f)
            }
        }
    }

    @Test
    fun `the grid has the contract's size, 16-bit indices and faces up`() {
        val grid = CosmosSpacetime.grid()
        assertEquals(39_520, CosmosSpacetime.GRID_VERTICES)
        assertEquals(78_208, CosmosSpacetime.GRID_TRIANGLES)
        assertEquals(39_520 * CosmosSpacetime.GRID_STRIDE, grid.vertices.size)
        assertEquals(78_208 * 3, grid.indices.size)
        assertEquals(CosmosSpacetime.SHEET_RADIUS, CosmosSpacetime.ringRadius(CosmosSpacetime.GRID_RINGS - 1), 1e-4f)
        val stride = CosmosSpacetime.GRID_STRIDE
        var up = 0
        for (t in 0 until CosmosSpacetime.GRID_TRIANGLES) {
            val i = IntArray(3) { grid.indices[t * 3 + it].toInt() and 0xFFFF }
            assertTrue(i.all { it < CosmosSpacetime.GRID_VERTICES })
            val ax = grid.vertices[i[0] * stride]
            val az = grid.vertices[i[0] * stride + 2]
            val ux = grid.vertices[i[1] * stride] - ax
            val uz = grid.vertices[i[1] * stride + 2] - az
            val vx = grid.vertices[i[2] * stride] - ax
            val vz = grid.vertices[i[2] * stride + 2] - az
            // y of u × v: positive is a face looking up. The centre ring's triangles are degenerate.
            val cy = uz * vx - ux * vz
            assertTrue("triangle $t faces down", cy >= -1e-6f)
            if (cy > 0f) up++
        }
        assertTrue(up >= CosmosSpacetime.GRID_TRIANGLES - CosmosSpacetime.SECTORS)
    }

    @Test
    fun `the star's hollow falls into shadow, about 30 to 1 against the plane`() {
        val f = field()
        val g = FloatArray(2)
        fun screen(x: Float, z: Float): Float {
            f.gradient(x, z, g)
            val d = sqrt(x * x + z * z)
            return CosmosSpacetime.screenFactor(
                CosmosSpacetime.shade(g[0], g[1], CosmosSpacetime.horizonVisibility(x, z), d),
                d,
            )
        }
        val l = CosmosSpacetime.LIGHT
        val lh = hypot(l[0], l[2])
        // Flat, lit sheet is the base grey; the hollow on the light's side is black.
        val plane = CosmosSpacetime.screenFactor(CosmosSpacetime.SHADE_FLAT, 0f)
        assertEquals(CosmosSpacetime.SHADE_GAIN, plane, 1e-5f)
        val lobe = screen(1.2f * l[0] / lh, 1.2f * l[2] / lh)
        assertTrue("plane / lobe ${plane / lobe}", plane / lobe >= 12f)
        // The wall facing the light, behind the star, is at least as bright as the plane.
        var wall = 0f
        for (i in 0..40) {
            val r = 0.5f + i * 0.05f
            wall = max(wall, screen(-r * l[0] / lh, -r * l[2] / lh))
        }
        assertTrue("wall $wall vs plane $plane", wall >= plane)
        // No visible rim: the spot is out before the sheet ends.
        assertTrue(screen(0f, -11.8f) <= 0.02f * plane)
    }

    @Test
    fun `the horizon map is the march, texel for texel, and clear on its border`() {
        val map = CosmosSpacetime.horizonMap()
        val n = CosmosSpacetime.HORIZON_MAP_SIZE
        assertEquals(n * n, map.size)
        fun texel(i: Int, j: Int) = (map[j * n + i].toInt() and 0xFF) / 255f
        for ((i, j) in listOf(0 to 0, 300 to 220, 330 to 200, 260 to 270, 511 to 400, 150 to 256)) {
            val x = CosmosSpacetime.horizonTexel(i)
            val z = CosmosSpacetime.horizonTexel(j)
            val expected = CosmosSpacetime.horizonVisibility(x, z)
            assertEquals("texel ($i, $j)", expected, texel(i, j), 1f / 255f)
        }
        // Clamp-to-edge repeats the border past the map: it must be fully lit.
        for (k in 0 until n) {
            for ((i, j) in listOf(k to 0, k to n - 1, 0 to k, n - 1 to k)) assertEquals(1f, texel(i, j), 1e-6f)
        }
        // Texel (i, j) is at (x, z) = (horizonTexel i, horizonTexel j): the hollow on the light's
        // side (+x, −z) is dark, the wall facing the light (−x, +z) is lit.
        val l = CosmosSpacetime.LIGHT
        val lh = hypot(l[0], l[2])
        fun at(x: Float, z: Float): Float {
            val i = ((x / CosmosSpacetime.HORIZON_MAP_EXTENT + 1f) * n / 2f).toInt()
            val j = ((z / CosmosSpacetime.HORIZON_MAP_EXTENT + 1f) * n / 2f).toInt()
            return texel(i, j)
        }
        assertTrue(at(1.2f * l[0] / lh, 1.2f * l[2] / lh) < 0.01f)
        assertTrue(at(-1.2f * l[0] / lh, -1.2f * l[2] / lh) > 0.99f)
    }

    @Test
    fun `the baked march matches a plain one, every step and the exact well`() {
        // The bake reads the star's well from a table and walks coarse steps, then fine ones
        // round the peak. Against every step of the exact formula: within a byte, map-wide.
        val l = CosmosSpacetime.LIGHT
        val lh = hypot(l[0], l[2])
        val te = kotlin.math.tan(CosmosSpacetime.LIGHT_ELEVATION_DEGREES * PI.toFloat() / 180f)
        fun exact(x: Float, z: Float): Float {
            val d2 = x * x + z * z
            val well = CosmosSpacetime.well(d2, CosmosSpacetime.DEPTH[0], CosmosSpacetime.WIDTH[0])
            return well * CosmosSpacetime.window(kotlin.math.sqrt(d2))
        }
        fun plain(x: Float, z: Float): Float {
            val h0 = exact(x, z)
            var steepest = -Float.MAX_VALUE
            var k = 1
            while (k * CosmosSpacetime.HORIZON_STEP <= CosmosSpacetime.HORIZON_REACH) {
                val s = k * CosmosSpacetime.HORIZON_STEP
                steepest = maxOf(steepest, (exact(x + s * l[0] / lh, z + s * l[2] / lh) - h0) / s)
                k++
            }
            val soft = CosmosSpacetime.HORIZON_SOFTNESS
            return 1f - CosmosSpacetime.smoothstep(te - soft, te + soft, steepest)
        }
        var worst = 0f
        for (j in 0 until CosmosSpacetime.HORIZON_MAP_SIZE step 3) {
            for (i in 0 until CosmosSpacetime.HORIZON_MAP_SIZE step 3) {
                val x = CosmosSpacetime.horizonTexel(i)
                val z = CosmosSpacetime.horizonTexel(j)
                worst = maxOf(worst, kotlin.math.abs(CosmosSpacetime.horizonVisibility(x, z) - plain(x, z)))
            }
        }
        assertTrue("worst $worst", worst <= 1f / 255f)
    }

    @Test
    fun `at the QA frame every world rests clear of the hollow's shadow`() {
        val f = field()
        for (body in 1 until BODY_COUNT) {
            val x = f.x(body)
            val z = f.z(body)
            // The body's footprint: its centre and a ring of its radius round it.
            var least = CosmosSpacetime.horizonVisibility(x, z)
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4f
                val edge = CosmosSpacetime.horizonVisibility(x + RADIUS[body] * cos(a), z + RADIUS[body] * sin(a))
                least = min(least, edge)
            }
            assertTrue("${CosmosSpacetime.NAMES[body]} at ($x, $z): $least", least > 0.9f)
        }
        // Ember and Azure in front of the star and left of it, on the wall facing the light.
        assertTrue(f.x(CosmosSpacetime.EMBER) < -1.1f && f.z(CosmosSpacetime.EMBER) > 0.3f)
        assertTrue(f.x(CosmosSpacetime.AZURE) < -1.2f && f.z(CosmosSpacetime.AZURE) > 0.8f)
    }

    @Test
    fun `the light is the contract's`() {
        val l = CosmosSpacetime.LIGHT
        assertEquals(0.7948f, l[0], 1e-3f)
        assertEquals(0.2419f, l[1], 1e-3f)
        assertEquals(-0.5565f, l[2], 1e-3f)
        assertEquals(0.3246f, CosmosSpacetime.SHADE_FLAT, 1e-3f)
    }

    @Test
    fun `the sheet writes the inverse of the tone mapper`() {
        for (i in 0..95) {
            val y = i / 100f
            assertEquals(y, CosmosSpacetime.filmic(CosmosSpacetime.inverseFilmic(y)), 1e-3f)
        }
        // The base grey before the tone mapper: the curve the viewer's backdrop already uses.
        val base = CosmosSpacetime.sheetBaseLinear().map(CosmosSpacetime::inverseFilmic)
        val backdrop = ViewerBackdrop.linearBeforeFilmic(Color(CosmosSpacetime.SHEET_BASE_SRGB))
        for (c in 0..2) assertEquals(backdrop[c], base[c], 1e-4f)
    }

    @Test
    fun `the entry sequence runs its windows`() {
        assertEquals(0f, CosmosSpacetime.wellDepth(0.6f), 0f)
        assertEquals(1.05f, CosmosSpacetime.wellDepth(1.75f), 1e-4f)
        assertEquals(1f, CosmosSpacetime.wellDepth(2.2f), 1e-4f)
        var peak = 0f
        var peakAt = 0f
        for (i in 0..220) {
            val w = CosmosSpacetime.wellDepth(i / 100f)
            if (w > peak) {
                peak = w
                peakAt = i / 100f
            }
        }
        assertEquals(1.75f, peakAt, 0.02f)
        assertEquals(1f, CosmosSpacetime.glow(0f), 0f)
        assertEquals(0f, CosmosSpacetime.glow(0.35f), 0f)
        assertEquals(0f, CosmosSpacetime.sheetIntensity(0.2f), 0f)
        assertEquals(1f, CosmosSpacetime.sheetIntensity(0.9f), 0f)
        assertEquals(1f, CosmosSpacetime.starField(0.9f), 0f)
        assertEquals(0f, CosmosSpacetime.starField(1.9f), 0f)
        assertEquals(1f, CosmosSpacetime.flight(0.9f), 1e-5f)
        assertEquals(0.1f, CosmosSpacetime.bloom(0.45f, 2.2f), 1e-6f)
        // Ember lands first, then Azure, Ochre and its moon, Ice and its moon.
        assertEquals(0f, CosmosSpacetime.arrival(CosmosSpacetime.EMBER, 0.8f), 0f)
        assertEquals(1f, CosmosSpacetime.arrival(CosmosSpacetime.EMBER, 1.0f), 1e-5f)
        assertEquals(1f, CosmosSpacetime.fallHeight(CosmosSpacetime.ICE, 1.25f), 1e-5f)
        assertEquals(0f, CosmosSpacetime.fallHeight(CosmosSpacetime.ICE, 1.75f), 0f)
        assertEquals(0f, CosmosSpacetime.fallHeight(CosmosSpacetime.RINGED, 0f), 0f)
    }

    @Test
    fun `the transition turns round from where it is, and back is faster`() {
        val transition = SpacetimeTransition()
        var nanos = 1_000_000_000L
        transition.advance(nanos, instant = false)
        transition.enter(true)
        repeat(60) {
            nanos += 16_666_667L
            transition.advance(nanos, instant = false)
        }
        val half = transition.clock
        assertEquals(1f, half, 0.02f)
        transition.enter(false)
        nanos += 16_666_667L
        transition.advance(nanos, instant = false)
        assertTrue(transition.clock < half)
        repeat(60) {
            nanos += 16_666_667L
            transition.advance(nanos, instant = false)
        }
        assertEquals(0f, transition.clock, 0f)
        transition.enter(true)
        transition.advance(nanos + 1, instant = true)
        assertTrue(transition.settled)
    }

    @Test
    fun `the mode pill reads at 3 to 1 on any ground, its labels at 8 to 1`() {
        val pill = SceneViewTokens.ModePill
        val container = CosmosSpacetime.luminance(pill.container.toArgb())
        val outline = CosmosSpacetime.luminance(pill.outline.toArgb())
        for (i in 0..100) {
            val ground = i / 100f
            val best = max(CosmosSpacetime.contrast(container, ground), CosmosSpacetime.contrast(outline, ground))
            assertTrue("ground L $ground: $best", best >= 2.99f)
        }
        val onContainer = CosmosSpacetime.luminance(pill.onContainer.toArgb())
        val selected = CosmosSpacetime.luminance(pill.selectedContainer.toArgb())
        val onSelected = CosmosSpacetime.luminance(pill.onSelected.toArgb())
        assertTrue(CosmosSpacetime.contrast(onContainer, container) >= 8f)
        assertTrue(CosmosSpacetime.contrast(onSelected, selected) >= 8f)
        // The selected segment stands out of the container.
        assertTrue(CosmosSpacetime.contrast(selected, container) >= 3f)
    }

    @Test
    fun `the ringed world's spin axis lies along its rings' normal, and the slerp lands`() {
        val field = SpacetimeField()
        field.prepare(CosmosSpacetime.QA_TIME, 1f)
        field.ringedRest()
        val n = field.ringNormal
        val q = CosmosSpacetime.ringedRotation(n, CosmosSpacetime.QA_TIME, FloatArray(4))
        // The rotated +y axis: column 1 of the quaternion's matrix.
        val x = q[0]
        val y = q[1]
        val z = q[2]
        val w = q[3]
        assertEquals(n[0], 2f * (x * y - w * z), 1e-5f)
        assertEquals(n[1], 1f - 2f * (x * x + z * z), 1e-5f)
        assertEquals(n[2], 2f * (y * z + w * x), 1e-5f)
        val start = floatArrayOf(0f, 0.38268343f, 0f, 0.9238795f)
        val out = FloatArray(4)
        CosmosSpacetime.slerp(start, q, 0f, out).forEachIndexed { i, v -> assertEquals(start[i], v, 1e-5f) }
        CosmosSpacetime.slerp(start, q, 1f, out)
        // The same rotation, whichever sign the short way round left it with.
        assertEquals(1f, kotlin.math.abs(out[0] * q[0] + out[1] * q[1] + out[2] * q[2] + out[3] * q[3]), 1e-5f)
    }

    private companion object {
        const val DESCENT_STEPS = 4000
        const val DESCENT_RATE = 0.002f
    }
}
