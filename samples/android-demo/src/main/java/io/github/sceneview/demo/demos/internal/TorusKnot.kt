package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.geometries.UvCoordinate
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The live parameters of the ribbon knot in
 * [io.github.sceneview.demo.demos.CustomGeometryDemo] — one value per on-screen slider.
 *
 * Kept as a value type so the demo can `remember(parameters)` the generated vertex list:
 * a structurally equal set of parameters produces byte-identical geometry, so a
 * recomposition that changed nothing rebuilds nothing.
 *
 * @param segments   Rings generated **along** the knot curve. Drives [TorusKnot.ringSegments]
 *                   too, so one slider controls the whole tessellation.
 * @param twistTurns Full turns the cross-section makes around the curve over one loop.
 *                   Must be a multiple of ½ or the ribbon does not join up — see
 *                   [TorusKnot.TWIST_STEP].
 * @param ripple     Sinusoidal modulation of the ribbon's half-width, `0` (constant) to
 *                   [TorusKnot.MAX_RIPPLE].
 */
internal data class KnotParameters(
    val segments: Int = TorusKnot.DEFAULT_SEGMENTS,
    val twistTurns: Float = TorusKnot.DEFAULT_TWIST_TURNS,
    val ripple: Float = TorusKnot.DEFAULT_RIPPLE,
)

/**
 * The mesh generator behind the *Custom Geometry* demo: a **(2, 3) torus knot** swept by a
 * flattened, twisting, rippling cross-section.
 *
 * ## Why a generator and not a primitive
 *
 * SceneView ships `CubeNode`, `SphereNode`, `TorusNode` and friends — every one of them a
 * built-in whose vertices you never see. This object exists to show the other half of the
 * API: **you can hand Filament your own vertices.** Everything below is ordinary Kotlin
 * arithmetic; the only SceneView type involved is [Geometry.Vertex], the plain
 * `(position, normal, uv, color)` record that `Geometry.Builder` uploads into a
 * `VertexBuffer`. Copy [vertices] and [triangleIndices] into your own project, change the
 * two formulas, and you have your own procedural mesh.
 *
 * ## The three pieces every custom mesh needs
 *
 * 1. **Positions** — [curvePoint] walks the knot; [surfacePoint] offsets that walk by a
 *    cross-section to sweep a surface. This is the "what shape is it" part, and it is the
 *    only part most people change.
 * 2. **Normals** — without them a mesh renders as a flat silhouette, whatever the material.
 *    They are computed here by **finite differences on the surface itself** ([normalAt]):
 *    take two neighbouring surface points, cross their offsets, normalise. That works for
 *    *any* parametric surface and — unlike averaging face normals — gives the two halves of
 *    the seam ring the same normal, so the ribbon has no visible crease where it joins up.
 * 3. **UVs** — `(u, v)` runs 0..1 along the knot and 0..1 around the ribbon, which is what a
 *    texture would need. The demo ships a plain colour material, but the coordinates are
 *    generated (and asserted in `TorusKnotTest`) so swapping in
 *    `materialLoader.createTextureInstance(...)` just works.
 *
 * ## Closure — why Twist is a stepped slider
 *
 * The mesh is a **closed** surface: the last ring must coincide with the first. The ripple
 * uses an integer number of cycles ([RIPPLE_CYCLES]) so it always does. The twist cannot be
 * continuous for the same reason: the cross-section is an ellipse, which maps onto itself
 * every **half** turn, so only multiples of [TWIST_STEP] leave the seam invisible. The
 * demo's slider is stepped accordingly rather than quietly shipping a torn ribbon.
 *
 * ## Framing
 *
 * The camera constants at the bottom follow [GeometryLayout]'s model — the orbit distance is
 * the **length** of `orbitHomePosition`, and the frustum relation is reused from there rather
 * than restated — so the fit is arithmetic a unit test can check (`TorusKnotTest`) instead of
 * a number tuned by eye. See [GeometryLayout] for the full derivation and the
 * `autoCenterContent` precondition, which this demo also leaves at its default.
 */
internal object TorusKnot {

    private const val TWO_PI = 2f * PI.toFloat()

    /** Winding of the knot around the torus axis — the `p` of a (p, q) torus knot. */
    const val P = 2

    /** Winding of the knot through the torus hole — the `q`. `(2, 3)` is the trefoil. */
    const val Q = 3

    /** Radius of the torus the knot is drawn on, in metres. */
    const val MAJOR_RADIUS = 0.22f

    /** Half-width of the swept ribbon at rest (before [KnotParameters.ripple]), in metres. */
    const val RIBBON_HALF_WIDTH = 0.055f

    /**
     * How flat the cross-section is: `1` would sweep a round tube, `0` a zero-thickness
     * band. A ribbon is what makes [KnotParameters.twistTurns] legible — a round tube looks
     * identical however much you twist it.
     */
    const val CROSS_SECTION_FLATTEN = 0.42f

    /** Ripple cycles over one loop. Integer, so the modulation closes on itself. */
    const val RIPPLE_CYCLES = 6

    /** Smallest twist increment that still closes the ribbon: half a turn of an ellipse. */
    const val TWIST_STEP = 0.5f

    const val MIN_SEGMENTS = 24
    const val MAX_SEGMENTS = 264
    const val DEFAULT_SEGMENTS = 168

    const val MAX_TWIST_TURNS = 4f
    /**
     * Default twist. Deliberately not the smallest interesting value: below ~2 turns the
     * flattened cross-section reads as a round tube on a phone screen, and the demo's first
     * frame should already show that the ribbon *is* a ribbon.
     */
    const val DEFAULT_TWIST_TURNS = 2.5f

    const val MAX_RIPPLE = 0.6f
    const val DEFAULT_RIPPLE = 0.3f

    /**
     * Discrete stops offered by the Segments slider, so the readout is always the segment
     * count actually generated and a drag cannot ask for a rebuild per pixel.
     */
    const val SEGMENT_STEP = 8

    /** Number of stops *between* the ends of the Segments slider, as `Slider(steps = …)`. */
    val segmentSliderSteps: Int get() = (MAX_SEGMENTS - MIN_SEGMENTS) / SEGMENT_STEP - 1

    /** Number of stops *between* the ends of the Twist slider. */
    val twistSliderSteps: Int get() = (MAX_TWIST_TURNS / TWIST_STEP).toInt() - 1

    /**
     * Vertices around the ribbon, derived from [segments] so the tessellation stays square-ish
     * at every resolution: a 24-segment knot reads as chunky low-poly in both directions, a
     * 264-segment one as smooth in both.
     *
     * **Always even**, and that is load-bearing. A half-integer twist rotates the ellipse
     * onto itself by π, which maps sample `j` of the closing ring onto sample `j + ring/2` of
     * the opening one — the two rings trace the *same* polygon, so the surface is watertight,
     * but only if `ring/2` is a whole sample. An odd count would leave the closing ring
     * half a sample out of phase and open a sliver along the seam.
     */
    fun ringSegments(segments: Int): Int = ((segments / 14).coerceIn(4, 18) / 2) * 2

    /** Vertices [vertices] will generate for [segments] — one duplicated seam row and column. */
    fun vertexCount(segments: Int): Int = (segments + 1) * (ringSegments(segments) + 1)

    /** Triangles [triangleIndices] will generate for [segments]: two per grid quad. */
    fun triangleCount(segments: Int): Int = segments * ringSegments(segments) * 2

    /** Snaps a raw slider value to the nearest generated segment count. */
    fun snapSegments(raw: Float): Int =
        (((raw - MIN_SEGMENTS) / SEGMENT_STEP).roundToInt() * SEGMENT_STEP + MIN_SEGMENTS)
            .coerceIn(MIN_SEGMENTS, MAX_SEGMENTS)

    /** Snaps a raw slider value to the nearest twist that still closes the ribbon. */
    fun snapTwist(raw: Float): Float =
        ((raw / TWIST_STEP).roundToInt() * TWIST_STEP).coerceIn(0f, MAX_TWIST_TURNS)

    // ── Mesh generation ───────────────────────────────────────────────────────────────────

    /**
     * The vertex list for [parameters] — position, normal and UV for every point of the
     * `(segments + 1) × (ringSegments + 1)` grid, in row-major order (all of ring 0, then all
     * of ring 1, …), which is the order [triangleIndices] and [lineIndices] index into.
     *
     * Pure arithmetic and pure Kotlin: safe to call from any thread. It is the *upload* —
     * `Geometry.Builder.build(engine)` / `Geometry.update(engine, …)` — that must stay on the
     * main thread, like every Filament JNI call.
     */
    fun vertices(parameters: KnotParameters): List<Geometry.Vertex> {
        val segments = parameters.segments
        val ring = ringSegments(segments)
        // One frame per ring, plus the same frames nudged forward along the curve: the
        // nudged copy is what gives normalAt its "next point along" without recomputing a
        // Frenet frame per vertex.
        val frames = Array(segments + 1) { frameAt(it.toFloat() / segments) }
        val framesAhead = Array(segments + 1) { frameAt(it.toFloat() / segments + DELTA) }

        return buildList(vertexCount(segments)) {
            for (i in 0..segments) {
                val u = i.toFloat() / segments
                for (j in 0..ring) {
                    val v = j.toFloat() / ring
                    val position = surfacePoint(frames[i], u, v, parameters)
                    add(
                        Geometry.Vertex(
                            position = position,
                            normal = normalAt(frames[i], framesAhead[i], u, v, position, parameters),
                            uvCoordinate = UvCoordinate(u, v),
                        )
                    )
                }
            }
        }
    }

    /**
     * Triangle indices for a [segments]-ring grid — two triangles per quad, consistently
     * wound, referencing the row-major vertex order [vertices] emits.
     *
     * Topology depends only on the segment counts, never on twist or ripple, which is why
     * the demo can rebuild the *vertex buffer* on every slider tick while reusing the index
     * buffer untouched.
     */
    fun triangleIndices(segments: Int): List<Int> {
        val ring = ringSegments(segments)
        return buildList(triangleCount(segments) * 3) {
            for (i in 0 until segments) {
                for (j in 0 until ring) {
                    val a = i * (ring + 1) + j
                    val b = a + 1
                    val c = (i + 1) * (ring + 1) + j
                    val d = c + 1
                    add(a); add(b); add(d)
                    add(a); add(d); add(c)
                }
            }
        }
    }

    /**
     * Line indices for the same grid — one segment along the knot and one around the ribbon
     * per vertex. Drawn with `PrimitiveType.LINES`, this is the demo's Wireframe mode: the
     * exact edges of the triangles above, so what the Segments slider does to the
     * tessellation becomes something you can count.
     */
    fun lineIndices(segments: Int): List<Int> {
        val ring = ringSegments(segments)
        return buildList(segments * ring * 4) {
            for (i in 0..segments) {
                for (j in 0..ring) {
                    val a = i * (ring + 1) + j
                    if (i < segments) {
                        add(a); add(a + ring + 1)
                    }
                    if (j < ring) {
                        add(a); add(a + 1)
                    }
                }
            }
        }
    }

    // ── The surface ───────────────────────────────────────────────────────────────────────

    /** A point on the knot curve plus the two axes the cross-section is drawn in. */
    private class Frame(val point: Position, val normal: Direction, val binormal: Direction)

    /**
     * Parameter nudge used for the finite differences. Small enough that the truncation error
     * is invisible, large enough that the subtraction keeps ~4 significant digits in `Float`.
     */
    private const val DELTA = 1e-3f

    /**
     * The knot curve itself, at [u] ∈ `[0, 1]` around the loop.
     *
     * The standard (p, q) torus-knot parametrisation: wind [P] times around the torus axis
     * while the tube radius pulses [Q] times, and the curve closes on itself having passed
     * through the hole [Q] times. Both terms are `2π`-periodic in [u], so `u = 1` lands
     * exactly on `u = 0`.
     */
    private fun curvePoint(u: Float): Position {
        val a = u * P * TWO_PI
        val b = u * Q * TWO_PI
        val radial = MAJOR_RADIUS * (2f + cos(b)) * 0.5f
        return Position(
            x = radial * cos(a),
            y = radial * sin(a),
            z = MAJOR_RADIUS * sin(b) * 0.5f,
        )
    }

    /**
     * An orthonormal frame at [u]: the curve point, and two axes perpendicular to the
     * tangent that the cross-section is swept in.
     *
     * The second axis is seeded from `p2 + p1` rather than from a fixed world axis — the
     * knot doubles back on itself, and any constant seed goes parallel to the tangent
     * somewhere along the loop, which would collapse the frame and pinch the ribbon there.
     */
    private fun frameAt(u: Float): Frame {
        val p1 = curvePoint(u)
        val p2 = curvePoint(u + DELTA)
        val tangent = p2 - p1
        val binormal = normalize(cross(tangent, p2 + p1))
        return Frame(p1, normalize(cross(binormal, tangent)), binormal)
    }

    /**
     * A point on the swept surface: the curve point at [u], offset by the cross-section at
     * [v] ∈ `[0, 1]` around the ribbon.
     *
     * The cross-section is an ellipse ([CROSS_SECTION_FLATTEN]) whose angle advances with
     * [KnotParameters.twistTurns] along the loop and whose size breathes with
     * [KnotParameters.ripple].
     */
    private fun surfacePoint(
        frame: Frame,
        u: Float,
        v: Float,
        parameters: KnotParameters,
    ): Position {
        val angle = (v + parameters.twistTurns * u) * TWO_PI
        val halfWidth = RIBBON_HALF_WIDTH *
            (1f + parameters.ripple * sin(RIPPLE_CYCLES * u * TWO_PI))
        return frame.point +
            frame.normal * (halfWidth * cos(angle)) +
            frame.binormal * (halfWidth * CROSS_SECTION_FLATTEN * sin(angle))
    }

    /**
     * The outward unit normal at ([u], [v]), by finite differences: step once along the knot,
     * once around the ribbon, and cross the two offsets.
     *
     * Deriving the normal from the *surface* rather than from the ideal tube is what keeps
     * the shading honest once ripple deforms it — the analytic tube normal would light a
     * shape that is no longer there. The final dot-product test flips the result outward, so
     * the winding of the index buffer and the direction of the normals cannot disagree.
     */
    private fun normalAt(
        frame: Frame,
        frameAhead: Frame,
        u: Float,
        v: Float,
        position: Position,
        parameters: KnotParameters,
    ): Direction {
        val alongKnot = surfacePoint(frameAhead, u + DELTA, v, parameters) - position
        val aroundRibbon = surfacePoint(frame, u, v + DELTA, parameters) - position
        val normal = normalize(cross(alongKnot, aroundRibbon))
        return if (dot(normal, position - frame.point) < 0f) -normal else normal
    }

    // ── Framing ───────────────────────────────────────────────────────────────────────────

    /**
     * Largest distance any generated vertex can reach from the origin, in metres, over the
     * whole parameter space.
     *
     * The curve's own extent is `1.5 × MAJOR_RADIUS` (the `(2 + cos)/2` factor peaks at
     * `1.5`), and the ribbon adds its half-width at maximum ripple on top. `TorusKnotTest`
     * checks the mesh actually generated never exceeds this.
     */
    val maxRadius: Float
        get() = 1.5f * MAJOR_RADIUS + RIBBON_HALF_WIDTH * (1f + MAX_RIPPLE)

    /** Depth the knot is authored at, and the camera's orbit target. */
    const val TARGET_Z = 0f

    /**
     * Camera-to-knot distance, in metres, when no `camera_distance` override is supplied.
     *
     * Chosen so the knot fills ~74 % of the frame width at
     * [GeometryLayout.PHONE_PORTRAIT_ASPECT] — large enough to read at Play Store thumbnail
     * size, with margin on every side — and still clears the frame at
     * [GeometryLayout.NARROWEST_EXPECTED_ASPECT]. `TorusKnotTest` pins the margin.
     */
    const val CAMERA_DISTANCE = 2.4f

    /**
     * Sine of the camera's elevation above the knot: a ~13° downward tilt, which is what
     * separates the three lobes of a trefoil instead of stacking them into a flat rosette.
     * Expressed as a fraction of the distance so a `camera_distance` override keeps it.
     */
    private const val ELEVATION_RATIO = 0.22f

    /**
     * Orbit offset to pass as `orbitHomePosition` for a camera [distance] metres away: a
     * vector whose **length is exactly [distance]**, lifted [ELEVATION_RATIO] above the view
     * axis. See [GeometryLayout] for why the length — not the distance to `targetPosition` —
     * is what the manipulator reads.
     *
     * @param distance Camera-to-knot distance in metres. Must be `> 0`.
     */
    fun orbitHomeOffset(distance: Float): Position {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        return Position(
            x = 0f,
            y = distance * ELEVATION_RATIO,
            z = distance * sqrt(1f - ELEVATION_RATIO * ELEVATION_RATIO),
        )
    }

    /**
     * Fraction of the frame **width** the knot occupies at [distance] on an [aspect]
     * viewport. `1.0` touches both edges, above `1.0` is clipped.
     */
    fun horizontalFillRatio(distance: Float, aspect: Float): Float =
        maxRadius / GeometryLayout.frameHalfWidth(distance, aspect)

    /** Fraction of the frame **height** the knot occupies at [distance]. */
    fun verticalFillRatio(distance: Float): Float =
        maxRadius / GeometryLayout.frameHalfHeight(distance)
}
