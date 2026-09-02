package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the mesh [TorusKnot] generates for
 * [io.github.sceneview.demo.demos.CustomGeometryDemo].
 *
 * A procedural mesh fails quietly: a normal of the wrong length shades slightly wrong, an
 * index one past the end draws a stray triangle across the screen, a seam that does not
 * close leaves a hairline crack, and none of it throws. Everything below is arithmetic that
 * runs on the JVM in milliseconds, with no Filament engine involved — the generator is
 * deliberately pure so it can be checked here rather than by staring at a capture.
 *
 * The framing cases mirror `GeometryLayoutTest` (#2873/#2937): the knot has to clear a
 * phone-portrait frame at the camera distance the demo actually ships.
 */
class TorusKnotTest {

    private val sweep = listOf(
        KnotParameters(segments = TorusKnot.MIN_SEGMENTS, twistTurns = 0f, ripple = 0f),
        KnotParameters(),
        KnotParameters(
            segments = TorusKnot.MAX_SEGMENTS,
            twistTurns = TorusKnot.MAX_TWIST_TURNS,
            ripple = TorusKnot.MAX_RIPPLE,
        ),
    )

    // ── Topology ──────────────────────────────────────────────────────────────

    @Test
    fun `vertex count matches the announced grid`() {
        for (parameters in sweep) {
            assertEquals(
                "segments=${parameters.segments}",
                TorusKnot.vertexCount(parameters.segments),
                TorusKnot.vertices(parameters).size,
            )
        }
    }

    @Test
    fun `triangle indices come in whole triangles, all in range`() {
        for (parameters in sweep) {
            val indices = TorusKnot.triangleIndices(parameters.segments)
            val vertexCount = TorusKnot.vertexCount(parameters.segments)
            assertEquals(
                "segments=${parameters.segments}",
                TorusKnot.triangleCount(parameters.segments) * 3,
                indices.size,
            )
            assertEquals("index list must be a whole number of triangles", 0, indices.size % 3)
            assertTrue(
                "an index points past the end of the vertex buffer",
                indices.all { it in 0 until vertexCount },
            )
        }
    }

    @Test
    fun `line indices come in whole segments, all in range`() {
        for (parameters in sweep) {
            val indices = TorusKnot.lineIndices(parameters.segments)
            val vertexCount = TorusKnot.vertexCount(parameters.segments)
            assertEquals("index list must be a whole number of lines", 0, indices.size % 2)
            assertTrue(
                "an index points past the end of the vertex buffer",
                indices.all { it in 0 until vertexCount },
            )
            // One edge along the knot and one around the ribbon per interior vertex.
            val ring = TorusKnot.ringSegments(parameters.segments)
            assertEquals(
                "segments=${parameters.segments}",
                (parameters.segments * (ring + 1) + ring * (parameters.segments + 1)) * 2,
                indices.size,
            )
        }
    }

    @Test
    fun `every triangle has three distinct corners`() {
        val indices = TorusKnot.triangleIndices(TorusKnot.DEFAULT_SEGMENTS)
        for (t in indices.indices step 3) {
            val (a, b, c) = Triple(indices[t], indices[t + 1], indices[t + 2])
            assertTrue("degenerate triangle at $t", a != b && b != c && a != c)
        }
    }

    // ── Vertex attributes ─────────────────────────────────────────────────────

    @Test
    fun `every normal is a unit vector`() {
        for (parameters in sweep) {
            for ((i, vertex) in TorusKnot.vertices(parameters).withIndex()) {
                val normal = requireNotNull(vertex.normal) { "vertex $i has no normal" }
                assertEquals(
                    "normal $i of $parameters is not unit length",
                    1f,
                    length(normal),
                    1e-2f,
                )
            }
        }
    }

    @Test
    fun `every normal points away from the curve it was swept around`() {
        // The demo's material is double-sided, so an inward normal would not punch a hole —
        // it would light the ribbon from the wrong side, which is far harder to spot.
        for (parameters in sweep) {
            val vertices = TorusKnot.vertices(parameters)
            // A normal that agreed with the outward direction at every vertex would be
            // trivially true for a sphere; on a knot the surface curves back on itself, so
            // the meaningful check is that the normal never points back at the local curve
            // centre. Approximate that centre by the ring's own average position.
            val perRing = TorusKnot.ringSegments(parameters.segments) + 1
            for (start in vertices.indices step perRing) {
                val ringVertices = vertices.subList(start, start + perRing)
                // Average the UNIQUE samples only: a ring's last vertex duplicates its
                // first, and counting it would bias the centre towards angle 0.
                val unique = ringVertices.subList(0, perRing - 1)
                val centre = unique
                    .map { it.position }
                    .reduce { acc, p -> acc + p } / unique.size.toFloat()
                for (vertex in ringVertices) {
                    val outward = vertex.position - centre
                    if (length(outward) < 1e-4f) continue
                    val alignment = dot(requireNotNull(vertex.normal), outward)
                    assertTrue("normal points inward at $parameters", alignment > 0f)
                }
            }
        }
    }

    @Test
    fun `uv coordinates span the unit square`() {
        for (parameters in sweep) {
            val uvs = TorusKnot.vertices(parameters).map {
                requireNotNull(it.uvCoordinate) { "vertex has no UV" }
            }
            assertTrue(
                "UVs must stay inside [0, 1]",
                uvs.all { it.x in 0f..1f && it.y in 0f..1f },
            )
            assertEquals("u must reach 0", 0f, uvs.minOf { it.x }, 1e-6f)
            assertEquals("u must reach 1", 1f, uvs.maxOf { it.x }, 1e-6f)
            assertEquals("v must reach 0", 0f, uvs.minOf { it.y }, 1e-6f)
            assertEquals("v must reach 1", 1f, uvs.maxOf { it.y }, 1e-6f)
        }
    }

    @Test
    fun `the mesh closes on itself`() {
        // The closing ring must trace exactly the same polygon as the opening one, or the
        // ribbon shows a hairline crack where it joins. Vertex *j* need not equal vertex *j*:
        // a half-integer twist rotates the ellipse onto itself by π, which shifts the
        // correspondence by half a ring — which is why the ring count is forced even and the
        // Twist slider is stepped in half-turns. What has to hold is set equality.
        for (parameters in sweep) {
            val vertices = TorusKnot.vertices(parameters)
            val perRing = TorusKnot.ringSegments(parameters.segments) + 1
            val opening = vertices.take(perRing).map { it.position }
            val closing = vertices.takeLast(perRing).map { it.position }
            for (p in closing) {
                val nearest = opening.minOf { length(it - p) }
                assertEquals(
                    "seam does not close for $parameters: $p has no match on the first ring",
                    0f,
                    nearest,
                    1e-3f,
                )
            }
        }
    }

    @Test
    fun `the ring count is even at every resolution`() {
        // See TorusKnot.ringSegments: an odd count puts the closing ring half a sample out
        // of phase at half-integer twists, which is the one way this mesh can crack.
        for (segments in TorusKnot.MIN_SEGMENTS..TorusKnot.MAX_SEGMENTS) {
            assertEquals(
                "ring count at segments=$segments is odd",
                0,
                TorusKnot.ringSegments(segments) % 2,
            )
        }
    }

    @Test
    fun `moving a slider actually moves vertices`() {
        val rest = TorusKnot.vertices(KnotParameters(ripple = 0f, twistTurns = 0f))
        val twisted = TorusKnot.vertices(KnotParameters(ripple = 0f, twistTurns = 2f))
        val rippled = TorusKnot.vertices(KnotParameters(ripple = TorusKnot.MAX_RIPPLE, twistTurns = 0f))

        assertEquals("twist must not change the vertex count", rest.size, twisted.size)
        assertEquals("ripple must not change the vertex count", rest.size, rippled.size)
        assertTrue(
            "twist changed nothing",
            rest.indices.any { length(rest[it].position - twisted[it].position) > 1e-3f },
        )
        assertTrue(
            "ripple changed nothing",
            rest.indices.any { length(rest[it].position - rippled[it].position) > 1e-3f },
        )
    }

    // ── Bounds and framing ────────────────────────────────────────────────────

    @Test
    fun `no vertex escapes the radius the framing is derived from`() {
        for (parameters in sweep) {
            val furthest = TorusKnot.vertices(parameters).maxOf { length(it.position) }
            assertTrue(
                "a vertex at $furthest m exceeds maxRadius ${TorusKnot.maxRadius} for $parameters",
                furthest <= TorusKnot.maxRadius + 1e-3f,
            )
        }
    }

    @Test
    fun `maxRadius is not wastefully loose`() {
        // A bound twice the real extent would frame the knot as a distant speck. The
        // worst-case parameters must come within 10 % of it.
        val worst = KnotParameters(
            segments = TorusKnot.MAX_SEGMENTS,
            twistTurns = 0f,
            ripple = TorusKnot.MAX_RIPPLE,
        )
        val furthest = TorusKnot.vertices(worst).maxOf { length(it.position) }
        assertTrue(
            "maxRadius ${TorusKnot.maxRadius} is loose: the mesh only reaches $furthest",
            furthest >= TorusKnot.maxRadius * 0.8f,
        )
    }

    @Test
    fun `the knot clears a phone-portrait frame with margin`() {
        val fill = TorusKnot.horizontalFillRatio(
            TorusKnot.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        assertTrue("knot fills $fill of the frame width — clipped", fill < 1f)
        assertTrue("knot fills only $fill of the frame width — too small to read", fill > 0.6f)
        assertTrue(
            "knot is taller than the frame",
            TorusKnot.verticalFillRatio(TorusKnot.CAMERA_DISTANCE) < 1f,
        )
    }

    @Test
    fun `the knot still clears the narrowest expected viewport`() {
        val fill = TorusKnot.horizontalFillRatio(
            TorusKnot.CAMERA_DISTANCE,
            GeometryLayout.NARROWEST_EXPECTED_ASPECT,
        )
        assertTrue("knot is clipped at aspect ${GeometryLayout.NARROWEST_EXPECTED_ASPECT}", fill < 1f)
    }

    @Test
    fun `orbitHomeOffset has the length it was asked for`() {
        for (distance in listOf(0.5f, TorusKnot.CAMERA_DISTANCE, 12f)) {
            assertEquals(distance, length(TorusKnot.orbitHomeOffset(distance)), 1e-4f)
        }
        assertTrue(
            "the camera must sit above the knot, not level with it",
            TorusKnot.orbitHomeOffset(TorusKnot.CAMERA_DISTANCE).y > 0f,
        )
    }

    // ── Slider snapping ───────────────────────────────────────────────────────

    @Test
    fun `segment snapping stays on the generated stops and inside the range`() {
        for (raw in listOf(-40f, 0f, 25f, 167.99998f, 200.4f, 1_000f)) {
            val snapped = TorusKnot.snapSegments(raw)
            assertTrue("$snapped out of range", snapped in TorusKnot.MIN_SEGMENTS..TorusKnot.MAX_SEGMENTS)
            assertEquals(
                "$snapped is not on a slider stop",
                0,
                (snapped - TorusKnot.MIN_SEGMENTS) % TorusKnot.SEGMENT_STEP,
            )
        }
        assertEquals(TorusKnot.DEFAULT_SEGMENTS, TorusKnot.snapSegments(167.99998f))
    }

    @Test
    fun `twist snapping only ever yields a closing twist`() {
        for (raw in listOf(-1f, 0f, 0.24f, 0.26f, 1.49f, 3.9f, 99f)) {
            val snapped = TorusKnot.snapTwist(raw)
            assertTrue("$snapped out of range", snapped in 0f..TorusKnot.MAX_TWIST_TURNS)
            val steps = snapped / TorusKnot.TWIST_STEP
            assertEquals("$snapped is not a multiple of TWIST_STEP", 0f, abs(steps - Math.round(steps)), 1e-5f)
        }
    }

    @Test
    fun `the slider step counts match the values the sliders can reach`() {
        // `Slider(steps = n)` offers n + 2 stops in total, ends included.
        assertEquals(
            (TorusKnot.MAX_SEGMENTS - TorusKnot.MIN_SEGMENTS) / TorusKnot.SEGMENT_STEP + 1,
            TorusKnot.segmentSliderSteps + 2,
        )
        assertEquals(
            (TorusKnot.MAX_TWIST_TURNS / TorusKnot.TWIST_STEP).toInt() + 1,
            TorusKnot.twistSliderSteps + 2,
        )
        assertEquals(
            "the default segment count must itself be a reachable stop",
            TorusKnot.DEFAULT_SEGMENTS,
            TorusKnot.snapSegments(TorusKnot.DEFAULT_SEGMENTS.toFloat()),
        )
        assertEquals(
            "the default twist must itself be a reachable stop",
            TorusKnot.DEFAULT_TWIST_TURNS,
            TorusKnot.snapTwist(TorusKnot.DEFAULT_TWIST_TURNS),
            1e-6f,
        )
    }

    @Test
    fun `ring segments stay in a sane band across the whole slider`() {
        var previous = 0
        for (segments in TorusKnot.MIN_SEGMENTS..TorusKnot.MAX_SEGMENTS step TorusKnot.SEGMENT_STEP) {
            val ring = TorusKnot.ringSegments(segments)
            assertTrue("ring=$ring at segments=$segments is degenerate", ring >= 3)
            assertTrue("ring=$ring at segments=$segments is wasteful", ring <= 18)
            assertTrue("ring count must not decrease with resolution", ring >= previous)
            previous = ring
        }
    }
}
