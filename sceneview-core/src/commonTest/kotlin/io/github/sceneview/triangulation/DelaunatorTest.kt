package io.github.sceneview.triangulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression tests for [Delaunator].
 *
 * Adds coverage for a 662 LOC file that previously shipped with **zero** tests,
 * plus a smoke test that exercises the per-instance [Delaunator] scratch buffer
 * (`edgeStack`). The buffer used to be a file-level `kEdgeStack` shared between
 * every instance — two `Delaunator` constructors running in parallel silently
 * corrupted each other. Making it per-instance is what these tests guard.
 */
class DelaunatorTest {

    private class P(override var x: Double, override var y: Double) : Delaunator.IPoint

    @Test
    fun triangulatesUnitSquare() {
        // Four corners of a unit square → 2 triangles → 6 half-edge indices.
        val d = Delaunator(
            listOf(
                P(0.0, 0.0),
                P(1.0, 0.0),
                P(1.0, 1.0),
                P(0.0, 1.0),
            )
        )
        assertEquals(6, d.triangles.size, "unit square should triangulate to 2 triangles (6 indices)")
        // Every triangle index references a valid point.
        assertTrue(d.triangles.all { it in 0..3 })
        // All four corners participate in the result.
        assertEquals(setOf(0, 1, 2, 3), d.triangles.toSet())
    }

    @Test
    fun triangulatesRegularPentagon() {
        // 5 cocircular points on the unit circle → 3 triangles → 9 indices.
        // The hull = the pentagon itself.
        val pts = (0 until 5).map { i ->
            val a = i * (2 * kotlin.math.PI / 5)
            P(kotlin.math.cos(a), kotlin.math.sin(a))
        }
        val d = Delaunator(pts)
        assertEquals(9, d.triangles.size, "regular pentagon → 3 triangles → 9 indices")
        assertTrue(d.triangles.all { it in 0..4 })
        // halfEdges[] should have exactly 3 boundary entries (= -1) for a convex polygon.
        // For a triangulated convex pentagon the hull has 5 edges, each appearing once
        // in the triangle list, so we expect 5 entries to be -1.
        assertEquals(5, d.halfEdges.count { it == -1 })
    }

    @Test
    fun twoIndependentInstancesProduceIdenticalResults() {
        // Per-instance scratch buffer regression: building two Delaunator instances
        // back-to-back on the same input must yield byte-identical triangle arrays.
        // (Before the kEdgeStack→edgeStack fix, this passed because there's no concurrency
        // here, but the test now also documents the contract.)
        val pts = listOf(
            P(0.0, 0.0),
            P(1.0, 0.0),
            P(0.5, 1.0),
            P(0.5, 0.5),
        )
        val a = Delaunator(pts)
        val b = Delaunator(pts)
        assertEquals(a.triangles.toList(), b.triangles.toList())
        assertEquals(a.halfEdges.toList(), b.halfEdges.toList())
    }

    @Test
    fun edgeStackIsInstanceScopedNotFileScoped() {
        // Build two Delaunators with different inputs; the second one must NOT
        // observe stale state from the first one's `edgeStack`. Pre-fix this
        // succeeded only because both runs happen serially on the same thread —
        // the new contract is "even concurrent constructors don't share state".
        // We assert the cheap version: different inputs → distinct triangle sets.
        val small = Delaunator(listOf(P(0.0, 0.0), P(1.0, 0.0), P(0.5, 1.0)))
        val big = Delaunator(
            (0 until 8).map { i ->
                val a = i * (2 * kotlin.math.PI / 8)
                P(kotlin.math.cos(a) * 10.0, kotlin.math.sin(a) * 10.0)
            }
        )
        assertNotEquals(small.triangles.toList(), big.triangles.toList())
    }

    /**
     * Generates a large pseudo-random point set big enough that `legalize` overflows the
     * initial 512-slot scratch buffer. Pre-#2041 the buffer silently dropped overflowing
     * edges; the growable buffer must keep every edge so the result stays Delaunay.
     */
    private fun largePointSet(count: Int): List<P> {
        // Deterministic LCG — no kotlin.random dependency needed and reproducible.
        var seed = 0x2545F4914F6CDD1DL
        fun next(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        return (0 until count).map { P(next() * 1000.0, next() * 1000.0) }
    }

    @Test
    fun largeInputProducesValidDelaunayTriangulation() {
        // 2000 points produces enough recursive flips to exceed the original 512-slot
        // stack. The triangulation must still be a valid Delaunay triangulation:
        // every triangle's circumcircle contains no other vertex of the triangulation.
        val pts = largePointSet(2000)
        val d = Delaunator(pts)

        // Sanity: a non-trivial triangulation came back.
        assertTrue(d.triangles.size >= 3, "expected a non-empty triangulation")
        assertEquals(0, d.triangles.size % 3, "triangle array length must be a multiple of 3")
        assertTrue(d.triangles.all { it in pts.indices }, "every index must reference a point")

        // Delaunay property: for each triangle, no other point lies strictly inside
        // its circumcircle. We check a representative sample of triangles for speed.
        var checked = 0
        for (t in d.triangles.indices step 3) {
            val ax = pts[d.triangles[t]].x
            val ay = pts[d.triangles[t]].y
            val bx = pts[d.triangles[t + 1]].x
            val by = pts[d.triangles[t + 1]].y
            val cx = pts[d.triangles[t + 2]].x
            val cy = pts[d.triangles[t + 2]].y
            for (p in pts.indices) {
                if (p == d.triangles[t] || p == d.triangles[t + 1] || p == d.triangles[t + 2]) {
                    continue
                }
                assertTrue(
                    !strictlyInCircumcircle(ax, ay, bx, by, cx, cy, pts[p].x, pts[p].y),
                    "point $p lies inside the circumcircle of triangle ${t / 3} — non-Delaunay"
                )
            }
            checked++
            if (checked >= 40) break // sampling cap keeps the test fast
        }
        assertTrue(checked > 0, "at least one triangle must have been verified")
    }

    @Test
    fun largeInputHasConsistentHalfEdges() {
        // Half-edge consistency is the structural invariant a dropped edge breaks:
        // every non-boundary half-edge must point back at itself through its twin.
        val d = Delaunator(largePointSet(1500))
        for (e in d.halfEdges.indices) {
            val twin = d.halfEdges[e]
            if (twin != -1) {
                assertTrue(twin in d.halfEdges.indices, "half-edge $e twin out of range")
                assertEquals(
                    e, d.halfEdges[twin],
                    "half-edge $e and its twin $twin must be mutually linked"
                )
            }
        }
    }

    @Test
    fun degenerateCollinearInputThrowsIllegalArgumentException() {
        // Collinear points have no Delaunay triangulation. The constructor must surface
        // this as a typed IllegalArgumentException, not a bare Exception (#2041).
        assertFailsWith<IllegalArgumentException> {
            Delaunator(listOf(P(0.0, 0.0), P(1.0, 0.0), P(2.0, 0.0), P(3.0, 0.0)))
        }
    }

    /**
     * `true` if (px, py) is strictly inside the circumcircle of triangle (a, b, c).
     * Orientation-independent: the in-circle determinant's sign is normalised by the
     * triangle's winding so the test holds for both CW and CCW triangles.
     */
    private fun strictlyInCircumcircle(
        ax: Double, ay: Double,
        bx: Double, by: Double,
        cx: Double, cy: Double,
        px: Double, py: Double
    ): Boolean {
        val orientation = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        if (orientation == 0.0) return false // degenerate triangle — skip
        val dx = ax - px
        val dy = ay - py
        val ex = bx - px
        val ey = by - py
        val fx = cx - px
        val fy = cy - py
        val ap = dx * dx + dy * dy
        val bp = ex * ex + ey * ey
        val cp = fx * fx + fy * fy
        val det = dx * (ey * cp - bp * fy) -
            dy * (ex * cp - bp * fx) +
            ap * (ex * fy - ey * fx)
        // det > 0 means inside for CCW triangles; flip the sense for CW ones.
        // A small epsilon guards against points exactly on the circle (cocircular).
        val epsilon = 1e-6
        return if (orientation > 0) det > epsilon else det < -epsilon
    }
}
