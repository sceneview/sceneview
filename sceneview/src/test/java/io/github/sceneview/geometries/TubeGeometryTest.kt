package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * JVM coverage for [Tube] — the geometry that gives a line a visible width (#3397).
 *
 * Everything asserted here is pure vertex/index arithmetic, so none of it needs a Filament
 * engine. The properties that matter are: the topology matches between vertices and indices,
 * the swept cross-section stays perpendicular to the path and the right radius from it, the
 * frames do not twist along a straight run, a closed loop's seam matches, and the winding is
 * outward so the default single-sided material does not cull the tube away.
 */
class TubeGeometryTest {

    private val straight = listOf(
        Position(0f, 0f, 0f),
        Position(1f, 0f, 0f),
        Position(2f, 0f, 0f),
        Position(3f, 0f, 0f),
    )

    private fun ring(index: Int, radialSegments: Int) = (0..radialSegments).toList()
        .map { index * (radialSegments + 1) + it }

    // ── Defaults ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `default radius is 2 cm`() {
        assertEquals(0.02f, Tube.DEFAULT_RADIUS, 0f)
    }

    @Test
    fun `default cross-section has 8 sides`() {
        assertEquals(8, Tube.DEFAULT_RADIAL_SEGMENTS)
    }

    @Test
    fun `default points span the unit X segment like Line`() {
        assertEquals(Line.DEFAULT_START, Tube.DEFAULT_POINTS.first())
        assertEquals(Line.DEFAULT_END, Tube.DEFAULT_POINTS.last())
    }

    // ── Topology ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an open tube has one ring per point`() {
        assertEquals(4, Tube.ringCount(pointCount = 4, closed = false))
    }

    @Test
    fun `a closed tube repeats the first ring so the seam gets its own UV column`() {
        assertEquals(5, Tube.ringCount(pointCount = 4, closed = true))
    }

    @Test
    fun `vertex count is rings times radial segments plus one, plus two cap discs`() {
        val segments = 6
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = true)
        val rings = 4 * (segments + 1)
        val caps = 2 * (segments + 2)
        assertEquals(rings + caps, vertices.size)
    }

    @Test
    fun `an uncapped open tube is rings only`() {
        val segments = 6
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = false)
        assertEquals(4 * (segments + 1), vertices.size)
    }

    @Test
    fun `getIndices returns a single primitive`() {
        assertEquals(1, Tube.getIndices(4, 6, closed = false, caps = true).size)
    }

    @Test
    fun `index count is six per quad plus three per cap triangle`() {
        val segments = 6
        val indices = Tube.getIndices(4, segments, closed = false, caps = true)[0]
        assertEquals((4 - 1) * segments * 6 + 2 * segments * 3, indices.size)
    }

    @Test
    fun `a closed tube has one more quad ring than an open one`() {
        val segments = 6
        val open = Tube.getIndices(4, segments, closed = false, caps = false)[0].size
        val closed = Tube.getIndices(4, segments, closed = true, caps = false)[0].size
        assertEquals(open + segments * 6, closed)
    }

    @Test
    fun `every index addresses a real vertex, open capped`() {
        val segments = 7
        val vertices = Tube.getVertices(straight, 0.05f, segments, closed = false, caps = true)
        Tube.getIndices(4, segments, closed = false, caps = true)[0].forEach {
            assertTrue("index $it out of range [0, ${vertices.size})", it in vertices.indices)
        }
    }

    @Test
    fun `every index addresses a real vertex, closed`() {
        val segments = 7
        val loop = squareLoop()
        val vertices = Tube.getVertices(loop, 0.05f, segments, closed = true, caps = true)
        Tube.getIndices(loop.size, segments, closed = true, caps = true)[0].forEach {
            assertTrue("index $it out of range [0, ${vertices.size})", it in vertices.indices)
        }
    }

    @Test
    fun `caps are skipped on a closed tube, which has no ends`() {
        val loop = squareLoop()
        val withCaps = Tube.getVertices(loop, 0.05f, 6, closed = true, caps = true)
        val without = Tube.getVertices(loop, 0.05f, 6, closed = true, caps = false)
        assertEquals(without.size, withCaps.size)
    }

    // ── Sweep geometry ────────────────────────────────────────────────────────────────────────

    @Test
    fun `every ring vertex sits exactly the radius from its path point`() {
        val radius = 0.13f
        val segments = 8
        val loop = squareLoop()
        val vertices = Tube.getVertices(loop, radius, segments, closed = true, caps = false)
        for (i in loop.indices) {
            ring(i, segments).forEach { v ->
                val offset = vertices[v].position - loop[i]
                assertEquals("ring $i vertex $v", radius, length(offset), 1e-4f)
            }
        }
    }

    @Test
    fun `the cross-section is perpendicular to the path`() {
        val segments = 8
        val loop = squareLoop()
        val frames = Tube.frames(loop, closed = true)
        val vertices = Tube.getVertices(loop, 0.1f, segments, closed = true, caps = false)
        for (i in loop.indices) {
            ring(i, segments).forEach { v ->
                val offset = normalize(vertices[v].position - loop[i])
                assertEquals(
                    "ring $i vertex $v is not perpendicular to the tangent",
                    0f, dot(offset, frames[i].tangent), 1e-3f,
                )
            }
        }
    }

    @Test
    fun `ring normals point outward, away from the path`() {
        val segments = 8
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = false)
        for (i in straight.indices) {
            ring(i, segments).forEach { v ->
                val outward = normalize(vertices[v].position - straight[i])
                val normal = vertices[v].normal
                assertNotNull("ring vertex $v has no normal", normal)
                assertEquals("ring $i vertex $v normal", 1f, dot(outward, normal!!), 1e-3f)
            }
        }
    }

    @Test
    fun `the seam vertex duplicates the first, so UVs stay continuous`() {
        val segments = 8
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = false)
        val first = vertices[0]
        val seam = vertices[segments]
        assertEquals(first.position.x, seam.position.x, 1e-5f)
        assertEquals(first.position.y, seam.position.y, 1e-5f)
        assertEquals(first.position.z, seam.position.z, 1e-5f)
        // Same point, different texture coordinate — that is what the duplicate buys.
        assertEquals(0f, first.uvCoordinate!!.y, 1e-5f)
        assertEquals(1f, seam.uvCoordinate!!.y, 1e-5f)
    }

    @Test
    fun `u runs 0 to 1 along the path`() {
        val segments = 5
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = false)
        assertEquals(0f, vertices.first().uvCoordinate!!.x, 1e-5f)
        assertEquals(1f, vertices[4 * (segments + 1) - 1].uvCoordinate!!.x, 1e-5f)
    }

    @Test
    fun `cap vertices carry the flat end normal, not the tube's outward one`() {
        val segments = 6
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = true)
        val capBase = 4 * (segments + 1)
        val startCapNormal = vertices[capBase].normal!!
        val endCapNormal = vertices[capBase + segments + 2].normal!!
        // The path runs along +X, so the start disc faces -X and the end disc +X.
        assertEquals(-1f, startCapNormal.x, 1e-4f)
        assertEquals(1f, endCapNormal.x, 1e-4f)
    }

    // ── Frames ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `frames are orthonormal everywhere`() {
        Tube.frames(helix(), closed = false).forEachIndexed { i, frame ->
            assertEquals("tangent $i not unit", 1f, length(frame.tangent), 1e-3f)
            assertEquals("normal $i not unit", 1f, length(frame.normal), 1e-3f)
            assertEquals("binormal $i not unit", 1f, length(frame.binormal), 1e-3f)
            assertEquals("frame $i not square", 0f, dot(frame.tangent, frame.normal), 1e-3f)
            assertEquals("frame $i not square", 0f, dot(frame.tangent, frame.binormal), 1e-3f)
            assertEquals("frame $i not square", 0f, dot(frame.normal, frame.binormal), 1e-3f)
        }
    }

    @Test
    fun `frames are right-handed`() {
        Tube.frames(helix(), closed = false).forEachIndexed { i, frame ->
            val expected = cross(frame.tangent, frame.normal)
            assertEquals("frame $i handedness", 1f, dot(expected, frame.binormal), 1e-3f)
        }
    }

    @Test
    fun `a straight run does not twist the cross-section`() {
        // Parallel transport's whole point: with no curvature there is no rotation to transport,
        // so every frame along a straight line must be identical. A Frenet frame would be
        // undefined here (zero second derivative) and typically flips or NaNs.
        val frames = Tube.frames(straight, closed = false)
        val reference = frames.first()
        frames.forEachIndexed { i, frame ->
            assertEquals("frame $i twisted", 1f, dot(reference.normal, frame.normal), 1e-4f)
        }
    }

    @Test
    fun `a closed loop closes without a crease at the seam`() {
        // Parallel transport around a non-planar loop comes back rotated by the loop's holonomy.
        // Left uncorrected, that entire rotation lands on the single joint where the last ring
        // meets the first, and the tube shows a visible crease exactly there. The correction
        // spreads it over the interior joints, so the seam joint must not be the worst one.
        val loop = trefoilLoop()
        val frames = Tube.frames(loop, closed = true)
        val interior = (0 until loop.size - 1).map { twistBetween(frames[it], frames[it + 1]) }
        val seam = twistBetween(frames.last(), frames.first())
        assertTrue(
            "the seam twists $seam deg, worse than the worst interior joint (${interior.max()})",
            seam <= interior.max() + 1f,
        )
    }

    @Test
    fun `the fixture really does accumulate twist, so the seam test is not vacuous`() {
        // Measured from the TANGENTS alone, which the holonomy correction never touches: carry
        // one vector all the way round the loop by parallel transport and it comes back rotated.
        // That rotation is a property of the loop, not of the implementation — if it were zero
        // the seam test above would pass on a fixture with nothing to correct.
        val loop = trefoilLoop()
        val frames = Tube.frames(loop, closed = true)
        var carried = frames.first().normal
        for (i in 1 until frames.size) {
            carried = transportVector(carried, frames[i - 1].tangent, frames[i].tangent)
        }
        carried = transportVector(carried, frames.last().tangent, frames.first().tangent)
        val holonomy = angleDegrees(carried, frames.first().normal)
        assertTrue("this fixture has no holonomy to correct ($holonomy deg)", holonomy > 5f)
    }

    /**
     * Residual rotation, in degrees, between two consecutive frames: [from]'s normal parallel-
     * transported onto [to]'s tangent, then compared to [to]'s own normal. Zero means the
     * cross-section did not spin between the two rings.
     */
    private fun twistBetween(from: Tube.Frame, to: Tube.Frame): Float =
        angleDegrees(transportVector(from.normal, from.tangent, to.tangent), to.normal)

    /** The smallest rotation carrying unit tangent [from] onto [to], applied to [vector]. */
    private fun transportVector(
        vector: Position,
        from: Position,
        to: Position,
    ): Position {
        val axis = cross(from, to)
        if (length(axis) <= 1e-6f) return vector
        val angle = kotlin.math.acos(dot(from, to).coerceIn(-1f, 1f))
        val unit = normalize(axis)
        val c = cos(angle)
        val s = sin(angle)
        return vector * c + cross(unit, vector) * s + unit * (dot(unit, vector) * (1f - c))
    }

    private fun angleDegrees(a: Position, b: Position): Float = Math.toDegrees(
        kotlin.math.acos(dot(normalize(a), normalize(b)).coerceIn(-1f, 1f)).toDouble()
    ).toFloat()

    @Test
    fun `a repeated control point does not produce NaN`() {
        val dented = listOf(
            Position(0f, 0f, 0f),
            Position(1f, 0f, 0f),
            Position(1f, 0f, 0f), // duplicate — zero-length segment
            Position(2f, 0f, 0f),
        )
        Tube.getVertices(dented, 0.1f, 6, closed = false, caps = true).forEach { vertex ->
            assertTrue("NaN position", vertex.position.x.isFinite())
            assertTrue("NaN position", vertex.position.y.isFinite())
            assertTrue("NaN position", vertex.position.z.isFinite())
            assertTrue("NaN normal", vertex.normal!!.x.isFinite())
        }
    }

    // ── Winding ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `side triangles wind counter-clockwise seen from outside`() {
        // Filament back-face-culls clockwise triangles under the default single-sided material,
        // so a flipped winding renders the tube inside-out — i.e. invisible. Same contract as
        // Torus (#2469): the geometric normal must agree with the shading normal.
        val segments = 8
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = false)
        val indices = Tube.getIndices(4, segments, closed = false, caps = false)[0]
        var checked = 0
        for (t in indices.indices step 3) {
            val a = vertices[indices[t]]
            val b = vertices[indices[t + 1]]
            val c = vertices[indices[t + 2]]
            val geometric = cross(b.position - a.position, c.position - a.position)
            if (length(geometric) < 1e-8f) continue
            assertTrue(
                "triangle at $t winds inward",
                dot(normalize(geometric), a.normal!!) > 0f,
            )
            checked++
        }
        assertTrue("no triangle was checked", checked > 0)
    }

    @Test
    fun `cap triangles wind outward too`() {
        val segments = 8
        val vertices = Tube.getVertices(straight, 0.1f, segments, closed = false, caps = true)
        val sideIndices = (4 - 1) * segments * 6
        val indices = Tube.getIndices(4, segments, closed = false, caps = true)[0]
        var checked = 0
        for (t in sideIndices until indices.size step 3) {
            val a = vertices[indices[t]]
            val b = vertices[indices[t + 1]]
            val c = vertices[indices[t + 2]]
            val geometric = cross(b.position - a.position, c.position - a.position)
            if (length(geometric) < 1e-8f) continue
            assertTrue(
                "cap triangle at $t winds inward",
                dot(normalize(geometric), a.normal!!) > 0f,
            )
            checked++
        }
        assertEquals(2 * segments, checked)
    }

    // ── Guards ────────────────────────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `a tube needs at least two points`() {
        Tube.getVertices(listOf(Position(0f, 0f, 0f)), 0.1f, 6, closed = false, caps = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a tube needs at least three radial segments`() {
        Tube.getVertices(straight, 0.1f, 2, closed = false, caps = true)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    /** A flat closed square — four right-angle corners for the closed-seam cases. */
    private fun squareLoop() = listOf(
        Position(1f, 0f, 0f),
        Position(0f, 0f, 1f),
        Position(-1f, 0f, 0f),
        Position(0f, 0f, -1f),
    )

    /**
     * A closed (2, 3) trefoil knot.
     *
     * A merely *tilted* ring is still planar, and a planar loop has zero holonomy — its frames
     * come back exactly as they left, so it cannot tell a correct closure correction from a
     * missing one. A trefoil's tangents sweep a genuinely non-planar path on the unit sphere,
     * which is what makes the seam tests above mean something.
     */
    private fun trefoilLoop() = List(24) { i ->
        val t = i / 24f * 2f * Math.PI.toFloat()
        Position(
            x = sin(t) + 2f * sin(2f * t),
            y = cos(t) - 2f * cos(2f * t),
            z = -sin(3f * t),
        )
    }

    /** An open helix — curvature and torsion in every span. */
    private fun helix() = List(24) { i ->
        val t = i / 23f
        val a = t * 4f * Math.PI.toFloat()
        Position(cos(a) * 0.5f, t * 2f, sin(a) * 0.5f)
    }
}
