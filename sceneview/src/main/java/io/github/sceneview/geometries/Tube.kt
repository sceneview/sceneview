package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * A **visible-width line**: a triangle tube swept along a polyline.
 *
 * [Line] and [Path] draw with Filament's [PrimitiveType.LINES], which every mobile GL/Vulkan
 * backend rasterises at exactly **one device pixel** with no width control. On a 420 dpi phone
 * that is ~0.4 dp — a hairline that anti-aliasing and tone mapping erase completely (#3397).
 * `Tube` is the fix: real geometry, so a line has a radius in metres, catches light, occludes
 * correctly and reads at any screen density.
 *
 * ```kotlin
 * SceneView {
 *     val material = rememberMaterialInstance(materialLoader, Color.Blue)
 *     TubeNode(points = routeSamples, radius = 0.02f, materialInstance = material)
 * }
 * ```
 *
 * The cross-section is swept with **rotation-minimising frames** (parallel transport), so the
 * tube does not spin around its own axis at inflection points the way a naive Frenet frame does,
 * and it stays stable through straight runs where the Frenet normal is undefined. When [closed]
 * is `true` the twist the loop accumulates on the way round is spread evenly over the rings, so
 * the seam is no sharper than any other joint.
 *
 * Vertex layout: `pointCount` (or `pointCount + 1` when [closed]) rings of `radialSegments + 1`
 * vertices — the seam vertex is duplicated so UVs stay continuous — followed, for an open tube
 * with [caps], by two flat end discs.
 *
 * Instances are immutable once built; create one with the [Builder] and mutate it afterwards
 * through [update]. All Filament buffer operations run synchronously and must be called on the
 * main thread.
 *
 * @see Path for the 1-px GPU polyline this replaces.
 */
class Tube private constructor(
    primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    vertexBuffer: VertexBuffer,
    indices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    primitivesOffsets: List<IntRange>,
    boundingBox: Box,
    points: List<Position>,
    radius: Float,
    radialSegments: Int,
    closed: Boolean,
    caps: Boolean
) : Geometry(
    primitiveType,
    vertices,
    vertexBuffer,
    indices,
    indexBuffer,
    primitivesOffsets,
    boundingBox
) {
    /**
     * Builder for [Tube]. Configure the [points] the tube is swept along, its [radius], the
     * [radialSegments] tessellation and whether the path is [closed] / [caps]ped, then [build].
     */
    class Builder : Geometry.Builder(PrimitiveType.TRIANGLES) {
        /** Ordered polyline the tube is swept along. At least 2 points. */
        var points: List<Position> = DEFAULT_POINTS
            private set

        /** Cross-section radius in scene units (metres). Defaults to [DEFAULT_RADIUS]. */
        var radius: Float = DEFAULT_RADIUS
            private set

        /** Number of segments around the cross-section. Defaults to [DEFAULT_RADIAL_SEGMENTS]. */
        var radialSegments: Int = DEFAULT_RADIAL_SEGMENTS
            private set

        /** When `true`, the last point is joined back to the first with a seamless frame. */
        var closed: Boolean = DEFAULT_CLOSED
            private set

        /** When `true` (and not [closed]), both ends are filled with a flat disc. */
        var caps: Boolean = DEFAULT_CAPS
            private set

        /** Sets the polyline to sweep along. Returns this builder for chaining. */
        fun points(points: List<Position>) = apply { this.points = points }

        /** Sets the cross-section radius in scene units. Returns this builder for chaining. */
        fun radius(radius: Float) = apply { this.radius = radius }

        /** Sets the cross-section tessellation. Returns this builder for chaining. */
        fun radialSegments(radialSegments: Int) = apply { this.radialSegments = radialSegments }

        /** Sets whether the path loops back on itself. Returns this builder for chaining. */
        fun closed(closed: Boolean) = apply { this.closed = closed }

        /** Sets whether open ends are filled with a disc. Returns this builder for chaining. */
        fun caps(caps: Boolean) = apply { this.caps = caps }

        /**
         * Builds the [Tube], allocating its Filament vertex and index buffers on [engine].
         * Must be called on the main thread.
         */
        override fun build(engine: Engine): Tube {
            require(points.size >= 2) { "Tube requires at least 2 points" }
            require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
            vertices(getVertices(points, radius, radialSegments, closed, caps))
            primitivesIndices(getIndices(points.size, radialSegments, closed, caps))
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Tube(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer, offsets,
                    boundingBox, points, radius, radialSegments, closed, caps
                )
            }
        }
    }

    /** Current polyline the tube is swept along. */
    var points: List<Position> = points
        private set

    /** Current cross-section radius in scene units. */
    var radius: Float = radius
        private set

    /** Current number of segments around the cross-section. */
    var radialSegments: Int = radialSegments
        private set

    /** Whether the path currently loops back on itself. */
    var closed: Boolean = closed
        private set

    /** Whether open ends are currently filled with a disc. */
    var caps: Boolean = caps
        private set

    /**
     * Regenerates the tube and re-uploads it to the existing Filament buffers.
     *
     * The index buffer is rebuilt only when the topology changes ([points] count,
     * [radialSegments], [closed] or [caps]); moving the same number of points or changing only
     * the [radius] reuses the allocated index buffer, which is what makes per-frame animation of
     * a path affordable. Must be called on the main thread. Returns this geometry for chaining.
     */
    fun update(
        engine: Engine,
        points: List<Position> = this.points,
        radius: Float = this.radius,
        radialSegments: Int = this.radialSegments,
        closed: Boolean = this.closed,
        caps: Boolean = this.caps
    ) = apply {
        require(points.size >= 2) { "Tube requires at least 2 points" }
        require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
        val topologyChanged = points.size != this.points.size ||
                radialSegments != this.radialSegments ||
                closed != this.closed ||
                caps != this.caps
        update(
            engine = engine,
            vertices = getVertices(points, radius, radialSegments, closed, caps),
            primitivesIndices = if (topologyChanged) {
                getIndices(points.size, radialSegments, closed, caps)
            } else {
                primitivesIndices
            }
        )
        this.points = points
        this.radius = radius
        this.radialSegments = radialSegments
        this.closed = closed
        this.caps = caps
    }

    /**
     * An orthonormal cross-section frame at one point of the swept path.
     *
     * @property tangent Unit direction of travel along the path.
     * @property normal Unit "up" of the cross-section, transported from the previous frame.
     * @property binormal `tangent × normal`, completing the right-handed basis.
     */
    data class Frame(val tangent: Direction, val normal: Direction, val binormal: Direction)

    companion object {
        /** A unit-length tube along +X — the same default span as [Line]. */
        val DEFAULT_POINTS = listOf(Position(0f, 0f, 0f), Position(1f, 0f, 0f))

        /**
         * 2 cm. A stroke that reads clearly at arm's length on a phone without turning the line
         * into a pipe — the whole point of this geometry over [Path]'s 1-px primitive.
         */
        const val DEFAULT_RADIUS = 0.02f

        /** 8 sides. Smooth enough to read as round at stroke widths, cheap enough to animate. */
        const val DEFAULT_RADIAL_SEGMENTS = 8

        /** Paths are open by default, like [Path]. */
        const val DEFAULT_CLOSED = false

        /** Open ends are capped by default — a hollow stroke end reads as a rendering glitch. */
        const val DEFAULT_CAPS = true

        /** Below this, two consecutive path points count as coincident and share a tangent. */
        private const val EPSILON = 1e-6f

        /**
         * Number of rings of cross-section vertices for a path of [pointCount] points.
         *
         * A closed path gets one extra ring: the first point repeated, so the seam has its own
         * UV column instead of wrapping the texture backwards over the last segment.
         */
        fun ringCount(pointCount: Int, closed: Boolean) = if (closed) pointCount + 1 else pointCount

        /**
         * Rotation-minimising frames along [points], one per point.
         *
         * The first frame picks an arbitrary normal perpendicular to the tangent (the world axis
         * least aligned with it, so the choice is numerically stable), and every following frame
         * is the previous one rotated by the angle between consecutive tangents around their
         * common perpendicular — parallel transport. Unlike Frenet frames this never flips at an
         * inflection point and stays defined along straight runs.
         *
         * When [closed] is `true` the frames come back to the start rotated by the loop's
         * holonomy. That rotation is a property of the loop and cannot be removed, only shared:
         * it is measured and spread evenly over the rings, so the seam joint carries no more of
         * it than any other joint and the loop closes without a crease.
         */
        fun frames(points: List<Position>, closed: Boolean = false): List<Frame> {
            require(points.size >= 2) { "Tube requires at least 2 points" }
            val count = points.size
            val tangents = MutableList(count) { i -> tangentAt(points, i, closed) }
            // A degenerate (repeated) point yields a zero tangent — inherit the previous one so a
            // duplicated control point dents the tube instead of collapsing it to NaN.
            for (i in 1 until count) {
                if (length(tangents[i]) < EPSILON) tangents[i] = tangents[i - 1]
            }
            if (length(tangents[0]) < EPSILON) tangents[0] = Direction(x = 1f)
            for (i in 0 until count) tangents[i] = normalize(tangents[i])

            val normals = MutableList(count) { Direction(x = 1f) }
            normals[0] = initialNormal(tangents[0])
            for (i in 1 until count) {
                // Re-project onto the plane perpendicular to the tangent: floating-point drift
                // over hundreds of samples otherwise tilts the cross-section out of square.
                normals[i] = orthonormalize(
                    transport(normals[i - 1], tangents[i - 1], tangents[i]),
                    tangents[i]
                )
            }
            if (closed && count > 2) {
                // Holonomy: transporting all the way around a non-planar loop and back across the
                // closing segment does NOT return the normal it started from — it comes back
                // rotated by an angle that is a property of the loop itself and cannot be removed.
                // It can only be *distributed*: left alone, the whole rotation lands on the single
                // joint where the last ring meets the first and the tube shows a crease exactly
                // there. Adding `holonomy · i / count` to frame i makes every joint, seam
                // included, carry the same `holonomy / count` share, which at any usable
                // tessellation is invisible.
                //
                // The angle has to be measured *after* transport onto `tangents[0]`, and signed
                // about it: comparing `normals[0]` to `normals[count - 1]` directly compares two
                // vectors lying in different planes, which is not the holonomy and leaves the
                // seam untouched.
                val wrapped = transport(normals[count - 1], tangents[count - 1], tangents[0])
                val holonomy = signedAngle(wrapped, normals[0], tangents[0])
                val perIndex = holonomy / count
                for (i in 1 until count) {
                    normals[i] = orthonormalize(
                        rotateAround(normals[i], tangents[i], perIndex * i),
                        tangents[i]
                    )
                }
            }
            return List(count) { i ->
                Frame(tangents[i], normals[i], normalize(cross(tangents[i], normals[i])))
            }
        }

        /**
         * Sweeps the cross-section along [points], producing ring vertices then, for an open
         * capped tube, the two end discs.
         *
         * @param points Ordered polyline, at least 2 points.
         * @param radius Cross-section radius in scene units.
         * @param radialSegments Sides of the cross-section, at least 3.
         * @param closed Whether the last point joins back to the first.
         * @param caps Whether open ends are filled with a disc. Ignored when [closed].
         */
        fun getVertices(
            points: List<Position>,
            radius: Float = DEFAULT_RADIUS,
            radialSegments: Int = DEFAULT_RADIAL_SEGMENTS,
            closed: Boolean = DEFAULT_CLOSED,
            caps: Boolean = DEFAULT_CAPS
        ): List<Vertex> {
            require(points.size >= 2) { "Tube requires at least 2 points" }
            require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
            val frames = frames(points, closed)
            val rings = ringCount(points.size, closed)
            val lastRing = rings - 1

            return buildList {
                for (i in 0 until rings) {
                    // The extra ring of a closed tube re-uses point 0's position AND frame, so
                    // the seam is geometrically identical to the start — only its UV differs.
                    val source = if (i == points.size) 0 else i
                    val center = points[source]
                    val frame = frames[source]
                    val u = i.toFloat() / lastRing
                    for (j in 0..radialSegments) {
                        val v = j.toFloat() / radialSegments
                        val angle = v * TWO_PI
                        val outward = normalize(
                            frame.normal * cos(angle) + frame.binormal * sin(angle)
                        )
                        add(
                            Vertex(
                                position = center + outward * radius,
                                normal = outward,
                                uvCoordinate = UvCoordinate(x = u, y = v)
                            )
                        )
                    }
                }
                if (!closed && caps) {
                    addAll(capVertices(points.first(), frames.first(), radius, radialSegments, start = true))
                    addAll(capVertices(points.last(), frames.last(), radius, radialSegments, start = false))
                }
            }
        }

        /**
         * Triangle indices matching [getVertices] for a path of [pointCount] points.
         *
         * Winding is counter-clockwise as seen from outside, matching the outward shading normal
         * under the default single-sided material (Filament back-face-culls clockwise triangles —
         * same contract as [Torus], see issue #2469).
         */
        fun getIndices(
            pointCount: Int,
            radialSegments: Int = DEFAULT_RADIAL_SEGMENTS,
            closed: Boolean = DEFAULT_CLOSED,
            caps: Boolean = DEFAULT_CAPS
        ): List<List<Int>> {
            require(pointCount >= 2) { "Tube requires at least 2 points" }
            require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
            val rings = ringCount(pointCount, closed)
            val stride = radialSegments + 1
            val triangles = mutableListOf<Int>()
            for (i in 0 until rings - 1) {
                for (j in 0 until radialSegments) {
                    val a = i * stride + j
                    val b = a + stride
                    val c = a + 1
                    val d = b + 1
                    triangles.addAll(listOf(a, d, b, a, c, d))
                }
            }
            if (!closed && caps) {
                var base = rings * stride
                // Start disc faces backwards along the path, so its fan winds the other way.
                base = appendCapIndices(triangles, base, radialSegments, start = true)
                appendCapIndices(triangles, base, radialSegments, start = false)
            }
            return listOf(triangles)
        }

        /** Central-difference tangent, wrapping when [closed] and one-sided at open ends. */
        private fun tangentAt(points: List<Position>, i: Int, closed: Boolean): Direction {
            val count = points.size
            return when {
                closed -> points[(i + 1) % count] - points[(i - 1 + count) % count]
                i == 0 -> points[1] - points[0]
                i == count - 1 -> points[count - 1] - points[count - 2]
                else -> points[i + 1] - points[i - 1]
            }
        }

        /**
         * An arbitrary unit normal perpendicular to [tangent], seeded from the world axis the
         * tangent is *least* aligned with so the cross product never degenerates.
         */
        private fun initialNormal(tangent: Direction): Direction {
            val ax = kotlin.math.abs(tangent.x)
            val ay = kotlin.math.abs(tangent.y)
            val az = kotlin.math.abs(tangent.z)
            val axis = when {
                ax <= ay && ax <= az -> Direction(x = 1f)
                ay <= az -> Direction(y = 1f)
                else -> Direction(z = 1f)
            }
            return orthonormalize(axis, tangent)
        }

        /** Gram-Schmidt: the component of [vector] perpendicular to unit [tangent], normalised. */
        private fun orthonormalize(vector: Direction, tangent: Direction): Direction {
            val projected = vector - tangent * dot(vector, tangent)
            return if (length(projected) > EPSILON) {
                normalize(projected)
            } else {
                initialNormalFallback(tangent)
            }
        }

        /** Used only when [orthonormalize] is handed a vector parallel to the tangent. */
        private fun initialNormalFallback(tangent: Direction): Direction {
            val candidate = if (kotlin.math.abs(tangent.x) < 0.9f) {
                Direction(x = 1f)
            } else {
                Direction(y = 1f)
            }
            return normalize(candidate - tangent * dot(candidate, tangent))
        }

        /**
         * Parallel-transports [vector] from unit tangent [from] to unit tangent [to]: the
         * smallest rotation that carries one tangent onto the other, applied to the vector.
         * Identity when the tangents are collinear, which is what keeps a straight run untwisted.
         */
        private fun transport(vector: Direction, from: Direction, to: Direction): Direction {
            val axis = cross(from, to)
            if (length(axis) <= EPSILON) return vector
            val angle = acos(dot(from, to).coerceIn(-1f, 1f))
            return rotateAround(vector, normalize(axis), angle)
        }

        /**
         * Angle in radians from [from] to [to] measured **about** unit [axis], signed by the
         * right-hand rule. Both vectors are assumed perpendicular to the axis.
         */
        private fun signedAngle(from: Direction, to: Direction, axis: Direction): Float {
            val cosine = dot(from, to).coerceIn(-1f, 1f)
            val sine = dot(cross(from, to), axis)
            return kotlin.math.atan2(sine, cosine)
        }

        /** Rodrigues' rotation of [vector] around unit [axis] by [angle] radians. */
        private fun rotateAround(vector: Direction, axis: Direction, angle: Float): Direction {
            val c = cos(angle)
            val s = sin(angle)
            return vector * c + cross(axis, vector) * s + axis * (dot(axis, vector) * (1f - c))
        }

        /**
         * One flat end disc: a fan centre followed by `radialSegments + 1` rim vertices, all
         * carrying the cap's flat normal (±tangent) so the end shades as a face, not as a
         * continuation of the tube.
         */
        private fun capVertices(
            center: Position,
            frame: Frame,
            radius: Float,
            radialSegments: Int,
            start: Boolean
        ): List<Vertex> {
            val normal = if (start) -frame.tangent else frame.tangent
            return buildList {
                add(
                    Vertex(
                        position = center,
                        normal = normal,
                        uvCoordinate = UvCoordinate(x = 0.5f, y = 0.5f)
                    )
                )
                for (j in 0..radialSegments) {
                    val angle = j.toFloat() / radialSegments * TWO_PI
                    val outward = frame.normal * cos(angle) + frame.binormal * sin(angle)
                    add(
                        Vertex(
                            position = center + outward * radius,
                            normal = normal,
                            uvCoordinate = UvCoordinate(
                                x = 0.5f + 0.5f * cos(angle),
                                y = 0.5f + 0.5f * sin(angle)
                            )
                        )
                    )
                }
            }
        }

        /**
         * Appends one cap fan starting at vertex [base] and returns the next free vertex index.
         * The start cap faces backwards along the path, so its fan winds opposite to the end cap.
         */
        private fun appendCapIndices(
            triangles: MutableList<Int>,
            base: Int,
            radialSegments: Int,
            start: Boolean
        ): Int {
            val center = base
            for (j in 0 until radialSegments) {
                val first = base + 1 + j
                val second = base + 1 + j + 1
                if (start) {
                    triangles.addAll(listOf(center, second, first))
                } else {
                    triangles.addAll(listOf(center, first, second))
                }
            }
            return base + radialSegments + 2
        }
    }
}
