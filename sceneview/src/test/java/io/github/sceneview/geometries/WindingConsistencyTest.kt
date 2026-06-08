package io.github.sceneview.geometries

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Triangle-winding consistency across every Android [Geometry] generator.
 *
 * SceneView's library-default material (`opaque_colored.filamat`) is **single-sided**: it
 * does not override `culling`, so Filament applies its default back-face culling with a
 * counter-clockwise front face. A triangle whose geometric winding is *opposite* to its
 * outward per-vertex shading normal therefore has its visible (front) face pointing into the
 * solid — the surface is culled and the mesh renders **inside-out** with no rotation. This is
 * the class of bug that produced #2469 (Torus) and #2470 (Capsule): both emitted each quad as
 * `(a, b, d, a, d, c)` — clockwise for their vertex ring layout — inverting *every* face.
 *
 * For each triangle this test computes the geometric face normal `(v1 - v0) × (v2 - v0)` and
 * the mean of the three vertex shading normals, and asserts `dot(faceNormal, meanShadingNormal)`
 * is **not negative** (no inverted face) for every generator, and **strictly positive** (the
 * front face genuinely points outward) for the closed/curved solids whose shading normal is the
 * true outward direction.
 *
 * The generators are pure functions over `kotlin-math` types, so they are fully testable without
 * a Filament [Engine].
 *
 * Pre-fix this test fails on Torus (1024/1024 inverted) and Capsule (720/720 inverted); the
 * reference generators (Sphere, Cylinder, Cone, Cube) are unaffected because they already wind
 * outward. Plane is a documented special case (see [planeDefaultIsNeverInverted]).
 */
class WindingConsistencyTest {

    private val origin = Float3(0f)

    /** A vertex's position as a [Float3]. */
    private fun Geometry.Vertex.pos(): Float3 = position

    /** A vertex's shading normal; every generator under test populates it. */
    private fun Geometry.Vertex.nrm(): Float3 = requireNotNull(normal) { "vertex is missing a normal" }

    /**
     * For every triangle, the signed alignment of its geometric face normal with the mean of
     * its three vertex shading normals. A value < 0 means the front face (Filament default CCW)
     * points opposite the outward normal → the triangle renders inside-out. Zero-area
     * (degenerate) triangles are reported separately and excluded from the signed counts, since
     * their face normal is undefined.
     */
    private data class WindingReport(val outward: Int, val inverted: Int, val degenerate: Int) {
        val total: Int get() = outward + inverted + degenerate
    }

    private fun analyze(vertices: List<Geometry.Vertex>, primitives: List<List<Int>>): WindingReport {
        var outward = 0
        var inverted = 0
        var degenerate = 0
        for (indices in primitives) {
            assertEquals("index list is not a whole number of triangles", 0, indices.size % 3)
            var t = 0
            while (t < indices.size) {
                val i0 = indices[t]
                val i1 = indices[t + 1]
                val i2 = indices[t + 2]
                for (idx in intArrayOf(i0, i1, i2)) {
                    assertTrue("index $idx out of range [0, ${vertices.size})", idx in vertices.indices)
                }
                val p0 = vertices[i0].pos()
                val p1 = vertices[i1].pos()
                val p2 = vertices[i2].pos()
                val faceNormal = cross(p1 - p0, p2 - p0)
                if (length(faceNormal) <= 1e-7f) {
                    degenerate++
                } else {
                    val meanNormal = vertices[i0].nrm() + vertices[i1].nrm() + vertices[i2].nrm()
                    val alignment = dot(normalize(faceNormal), normalize(meanNormal))
                    when {
                        alignment > 1e-4f -> outward++
                        alignment < -1e-4f -> inverted++
                        else -> degenerate++ // perpendicular (e.g. Plane's decoupled normal)
                    }
                }
                t += 3
            }
        }
        return WindingReport(outward, inverted, degenerate)
    }

    /**
     * Strong invariant for closed/curved solids and the cube: every non-degenerate triangle
     * winds strictly outward (front face aligned with the outward shading normal), and there
     * are no inverted triangles. [maxDegenerate] allows the benign zero-area triangles at the
     * cap apex of pole-based meshes (e.g. Capsule), where the apex ring collapses to a point.
     */
    private fun assertOutward(
        name: String,
        vertices: List<Geometry.Vertex>,
        primitives: List<List<Int>>,
        maxDegenerate: Int = 0
    ) {
        val r = analyze(vertices, primitives)
        assertEquals("$name has inverted (inside-out) triangles: $r", 0, r.inverted)
        assertTrue("$name produced no triangles", r.total > 0)
        assertTrue(
            "$name has too many degenerate triangles ($r, allowed $maxDegenerate)",
            r.degenerate <= maxDegenerate
        )
        assertTrue("$name has no outward-facing triangles: $r", r.outward > 0)
    }

    /** Weak invariant: no inverted faces (used for Plane, whose shading normal is decoupled). */
    private fun assertNotInverted(name: String, vertices: List<Geometry.Vertex>, primitives: List<List<Int>>) {
        val r = analyze(vertices, primitives)
        assertEquals("$name has inverted (inside-out) triangles: $r", 0, r.inverted)
        assertTrue("$name produced no triangles", r.total > 0)
    }

    private fun assertNormalsUnitLength(name: String, vertices: List<Geometry.Vertex>) {
        for (v in vertices) {
            val len = length(v.nrm())
            if (len > 1e-6f) {
                assertEquals("$name: normal not unit length: $len", 1f, len, 1e-2f)
            }
        }
    }

    // ----- Torus (#2469) -----

    @Test
    fun `torus winds outward on default params`() {
        val v = Torus.getVertices(
            Torus.DEFAULT_MAJOR_RADIUS, Torus.DEFAULT_MINOR_RADIUS, origin,
            Torus.DEFAULT_MAJOR_SEGMENTS, Torus.DEFAULT_MINOR_SEGMENTS
        )
        val i = Torus.getIndices(Torus.DEFAULT_MAJOR_SEGMENTS, Torus.DEFAULT_MINOR_SEGMENTS)
        assertOutward("Torus(default)", v, i)
        assertNormalsUnitLength("Torus(default)", v)
    }

    @Test
    fun `torus winds outward on non-default params`() {
        for ((maj, min) in listOf(8 to 6, 12 to 8, 48 to 24)) {
            val v = Torus.getVertices(2f, 0.5f, Float3(1f, -2f, 3f), maj, min)
            assertOutward("Torus($maj,$min)", v, Torus.getIndices(maj, min))
        }
    }

    // ----- Capsule (#2470) -----

    @Test
    fun `capsule winds outward on default params`() {
        val v = Capsule.getVertices(
            Capsule.DEFAULT_RADIUS, Capsule.DEFAULT_HEIGHT, origin,
            Capsule.DEFAULT_CAP_STACKS, Capsule.DEFAULT_SIDE_SLICES
        )
        val i = Capsule.getIndices(Capsule.DEFAULT_CAP_STACKS, Capsule.DEFAULT_SIDE_SLICES)
        // The two cap apex rings collapse to a point, so each emits `sideSlices` zero-area
        // triangles (2 * 24 = 48 by default). These are benign (not rasterized) and pre-existing
        // — #2470 is purely a winding fix — so they are allowed but every real face must be outward.
        assertOutward("Capsule(default)", v, i, maxDegenerate = 2 * Capsule.DEFAULT_SIDE_SLICES)
        assertNormalsUnitLength("Capsule(default)", v)
    }

    @Test
    fun `capsule winds outward on non-default params`() {
        for ((cap, side) in listOf(4 to 8, 6 to 16, 10 to 32)) {
            val v = Capsule.getVertices(0.4f, 3f, Float3(-1f, 0f, 2f), cap, side)
            assertOutward("Capsule($cap,$side)", v, Capsule.getIndices(cap, side), maxDegenerate = 2 * side)
        }
    }

    // ----- Reference generators (already correct; must NOT regress) -----

    @Test
    fun `sphere winds outward`() {
        for ((stacks, slices) in listOf(Sphere.DEFAULT_STACKS to Sphere.DEFAULT_SLICES, 8 to 12, 16 to 16)) {
            val v = Sphere.getVertices(1f, origin, stacks, slices)
            assertOutward("Sphere($stacks,$slices)", v, Sphere.getIndices(stacks, slices))
            assertNormalsUnitLength("Sphere($stacks,$slices)", v)
        }
    }

    @Test
    fun `cylinder winds outward`() {
        for (sides in listOf(Cylinder.DEFAULT_SIDE_COUNT, 8, 32)) {
            val v = Cylinder.getVertices(1f, 2f, origin, sides)
            assertOutward("Cylinder($sides)", v, Cylinder.getIndices(sides))
            assertNormalsUnitLength("Cylinder($sides)", v)
        }
    }

    @Test
    fun `cone winds outward`() {
        for (sides in listOf(Cone.DEFAULT_SIDE_COUNT, 8, 32)) {
            val v = Cone.getVertices(1f, 2f, origin, sides)
            assertOutward("Cone($sides)", v, Cone.getIndices(sides))
            assertNormalsUnitLength("Cone($sides)", v)
        }
    }

    @Test
    fun `cube winds outward`() {
        val v = Cube.getVertices(Cube.DEFAULT_SIZE, origin)
        assertOutward("Cube(default)", v, Cube.INDICES)
        val v2 = Cube.getVertices(Float3(2f, 3f, 4f), Float3(5f, 6f, 7f))
        assertOutward("Cube(2,3,4)", v2, Cube.INDICES)
        assertNormalsUnitLength("Cube", v)
    }

    // ----- Plane: documented special case -----

    /**
     * The default [Plane] lies in the XY span (z = 0) so its true geometric face normal is ±Z,
     * yet its shading normal is hard-coded to the requested `normal` (default +Y) and the
     * vertices are not reoriented — `dot(faceNormal, shadingNormal) == 0` (perpendicular). This
     * is a known, accepted convention (pinned by `PlaneGeometryTest`, audit #2468 lower-confidence
     * note), distinct from the inside-out class fixed here. The winding must at least never be
     * *inverted*; when given a geometry-consistent normal the face is genuinely outward.
     */
    @Test
    fun `plane default is never inverted`() {
        val v = Plane.getVertices(Plane.DEFAULT_SIZE, origin, Plane.DEFAULT_NORMAL)
        assertNotInverted("Plane(default)", v, Plane.INDICES)
    }

    @Test
    fun `plane with geometry-consistent normal winds outward`() {
        // size with z = 0 => face lies in XY, true normal is +Z; give it that normal.
        val v = Plane.getVertices(Float3(1f, 1f, 0f), origin, Float3(0f, 0f, 1f))
        assertOutward("Plane(normal +Z)", v, Plane.INDICES)
    }
}
