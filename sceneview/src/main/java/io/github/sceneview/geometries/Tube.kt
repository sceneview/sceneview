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
import kotlin.math.max
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
 *     TubeNode(points = pts, radii = widths, capStyle = Tube.CapStyle.Round, capacity = 256, materialInstance = m)
 * }
 * ```
 *
 * The cross-section is swept with **rotation-minimising frames** (parallel transport), so the
 * tube does not spin around its own axis at inflection points the way a naive Frenet frame does,
 * and it stays stable through straight runs where the Frenet normal is undefined. When [closed]
 * is `true` the twist the loop accumulates on the way round is spread evenly over the rings, so
 * the seam is no sharper than any other joint.
 *
 * Vertex layout: with `capacity = 0` (exact) and [CapStyle.None] or [CapStyle.Flat],
 * `pointCount` (or `pointCount + 1` when [closed]) rings of `radialSegments + 1` vertices — the
 * seam vertex is duplicated so UVs stay continuous — are followed, for an open tube with flat
 * caps, by two end discs, preserving the legacy order. With a positive (reserved) [capacity] or
 * [CapStyle.Round], capacity-sized body rings are followed by the caps, every live triangle is an
 * index-buffer prefix, and unused padding rings collapse onto the last live point so they do not
 * enlarge the bounds.
 *
 * Instances are immutable once built; create one with the [Builder] and mutate it afterwards
 * through [update]. All Filament buffer operations run synchronously and must be called on the
 * main thread.
 *
 * @see Path for the 1-px GPU polyline this replaces.
 */
class Tube private constructor(
    primitiveType: PrimitiveType, vertices: List<Vertex>, vertexBuffer: VertexBuffer,
    indices: List<List<Int>>, indexBuffer: IndexBuffer, primitivesOffsets: List<IntRange>,
    boundingBox: Box, points: List<Position>, radius: Float, radii: List<Float>?,
    radialSegments: Int, closed: Boolean, capStyle: CapStyle, capacity: Int,
    capacityExplicit: Boolean, liveIndexCount: Int,
) : Geometry(
    primitiveType, vertices, vertexBuffer, indices, indexBuffer, primitivesOffsets, boundingBox
) {
    // Kept because its synthetic accessor is part of sceneview.api.
    @Suppress("DEPRECATION")
    private constructor(
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
        caps: Boolean,
    ) : this(
        primitiveType, vertices, vertexBuffer, indices, indexBuffer, primitivesOffsets,
        boundingBox, points, radius, null, radialSegments, closed,
        if (caps) CapStyle.Flat else CapStyle.None, points.size, false,
        liveIndexCount(
            points.size, radialSegments, closed,
            if (caps) CapStyle.Flat else CapStyle.None,
        ),
    )

    /** How an open tube is closed. Closed tubes ignore this setting. */
    enum class CapStyle { None, Flat, Round }

    /**
     * Builder for [Tube]. Configure the [points] the tube is swept along, its [radius], the
     * [radialSegments] tessellation and whether the path is [closed] / [caps]ped, then [build].
     * [radii] can vary its width, [capStyle] selects the end shape, and [capacity] reserves room
     * for growth in the same Filament buffers.
     */
    class Builder : Geometry.Builder(PrimitiveType.TRIANGLES) {
        /** Ordered polyline the tube is swept along. At least 2 points. */
        var points: List<Position> = DEFAULT_POINTS
            private set

        /** Cross-section radius in scene units (metres). Defaults to [DEFAULT_RADIUS]. */
        var radius: Float = DEFAULT_RADIUS
            private set

        /**
         * Optional cross-section radius at each [points] entry, in scene units (metres).
         * `null` uses the uniform [radius].
         */
        var radii: List<Float>? = null
            private set

        /** Number of segments around the cross-section. Defaults to [DEFAULT_RADIAL_SEGMENTS]. */
        var radialSegments: Int = DEFAULT_RADIAL_SEGMENTS
            private set

        /** When `true`, the last point is joined back to the first with a seamless frame. */
        var closed: Boolean = DEFAULT_CLOSED
            private set

        /** End treatment for an open tube. Round caps make strokes close smoothly. */
        var capStyle: CapStyle = DEFAULT_CAP_STYLE
            private set

        /**
         * Path points to reserve in the buffers. `0`, the default, allocates exactly [points] and
         * the buffers follow the point count. Any positive value reserves
         * `max(capacity, points.size)` points: [Tube.update] grows and shrinks the live path
         * inside the same Filament buffers, and only exceeding them reallocates (to at least
         * twice the size). Reserved buffers never shrink.
         */
        var capacity: Int = 0
            private set

        /** When `true` (and not [closed]), both ends are filled with a flat disc. */
        @Deprecated("Use capStyle", ReplaceWith("capStyle != Tube.CapStyle.None"))
        val caps: Boolean get() = capStyle != CapStyle.None

        /** Sets the polyline to sweep along. Returns this builder for chaining. */
        fun points(points: List<Position>) = apply { this.points = points }

        /** Sets the cross-section radius in scene units. Returns this builder for chaining. */
        fun radius(radius: Float) = apply { this.radius = radius }

        /**
         * Sets one cross-section radius per point in scene units, or `null` to use [radius].
         * Returns this builder for chaining.
         */
        fun radii(radii: List<Float>?) = apply { this.radii = radii }

        /** Sets the cross-section tessellation. Returns this builder for chaining. */
        fun radialSegments(radialSegments: Int) = apply { this.radialSegments = radialSegments }

        /** Sets whether the path loops back on itself. Returns this builder for chaining. */
        fun closed(closed: Boolean) = apply { this.closed = closed }

        /** Sets the end treatment for an open tube. Returns this builder for chaining. */
        fun capStyle(capStyle: CapStyle) = apply { this.capStyle = capStyle }

        /**
         * Reserves buffers for this many path points; `0` allocates exactly [points]. See
         * [capacity] for the rule. Returns this builder for chaining.
         */
        fun capacity(capacity: Int) = apply { this.capacity = capacity }

        /** Sets whether open ends are filled with a disc. Returns this builder for chaining. */
        @Deprecated(
            "Use capStyle",
            ReplaceWith("capStyle(if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None)"),
        )
        fun caps(caps: Boolean) = capStyle(if (caps) CapStyle.Flat else CapStyle.None)

        /**
         * Builds the [Tube], allocating its Filament vertex and index buffers on [engine].
         * Must be called on the main thread.
         */
        override fun build(engine: Engine): Tube {
            validate(points, radii, radialSegments, capacity)
            val allocated = max(points.size, capacity.takeIf { it > 0 } ?: points.size)
            val tubeVertices = getVertices(
                points, radius, radialSegments, closed, capStyle, radii, allocated
            )
            val tubeIndices = getIndices(
                points.size, radialSegments, closed, capStyle,
                if (capacity > 0) allocated else 0,
            )
            vertices(tubeVertices)
            primitivesIndices(tubeIndices)
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                // Route legacy calls through the constructor whose synthetic accessor is public API.
                if (radii == null && capacity == 0 && capStyle != CapStyle.Round) {
                    @Suppress("DEPRECATION")
                    Tube(
                        primitiveType, tubeVertices, vertexBuffer, tubeIndices, indexBuffer,
                        offsets, boundingBox, points, radius, radialSegments, closed,
                        capStyle != CapStyle.None,
                    )
                } else {
                    Tube(
                        primitiveType, tubeVertices, vertexBuffer, tubeIndices, indexBuffer,
                        offsets, boundingBox, points, radius, radii, radialSegments, closed,
                        capStyle, allocated, capacity > 0,
                        liveIndexCount(points.size, radialSegments, closed, capStyle),
                    )
                }
            }
        }
    }

    /** Current polyline the tube is swept along. */
    var points: List<Position> = points
        private set

    /** Current cross-section radius in scene units. */
    var radius: Float = radius
        private set

    /** Current cross-section radius per point in scene units, or `null` for [radius]. */
    var radii: List<Float>? = radii
        private set

    /** Current number of segments around the cross-section. */
    var radialSegments: Int = radialSegments
        private set

    /** Whether the path currently loops back on itself. */
    var closed: Boolean = closed
        private set

    /** Current end treatment for an open tube, ignored when [closed]. */
    var capStyle: CapStyle = capStyle
        private set

    /** Number of path points for which the current Filament buffers are allocated. */
    var capacity: Int = capacity
        private set

    /** Whether open ends are currently filled with a disc. */
    @Deprecated("Use capStyle", ReplaceWith("capStyle != Tube.CapStyle.None"))
    val caps: Boolean get() = capStyle != CapStyle.None

    private var capacityExplicit = capacityExplicit
    private var liveIndexCount = liveIndexCount

    override fun drawnOffsets(primitivesIndices: List<List<Int>>) = listOf(0 until liveIndexCount)

    /**
     * Regenerates the tube and re-uploads it to the existing Filament buffers.
     *
     * Moving points, changing radii, or changing the live point count while it fits reserved
     * [capacity] reuses the same vertex and index buffers. Exceeding reserved capacity grows it
     * to the larger of the requested size and twice its old size; reserved buffers never shrink.
     * A tube built with `capacity = 0` is exact: its buffers follow the point count, growing or
     * shrinking. The rule is the [Builder.capacity] one: `0`, the default here, keeps the current
     * mode, and any positive [capacity] switches an exact tube to reserved capacity of at least
     * that many points. Changing [radialSegments], [closed] or [capStyle] may rebuild the buffers.
     * Must be called on the main thread. Returns this geometry for chaining.
     */
    fun update(
        engine: Engine,
        points: List<Position> = this.points,
        radius: Float = this.radius,
        radialSegments: Int = this.radialSegments,
        closed: Boolean = this.closed,
        capStyle: CapStyle = this.capStyle,
        radii: List<Float>? = this.radii,
        capacity: Int = 0,
    ) = apply {
        validate(points, radii, radialSegments, capacity)
        val newCapacityExplicit = isCapacityExplicit(capacityExplicit, capacity)
        val newCapacity = grownCapacity(
            points.size, this.capacity, capacity, newCapacityExplicit
        )
        // Reserved indices depend on the capacity, exact ones on the point count (== capacity):
        // the live point count alone never changes them.
        val topologyChanged = radialSegments != this.radialSegments || closed != this.closed ||
            capStyle != this.capStyle || newCapacity != this.capacity ||
            newCapacityExplicit != capacityExplicit
        val newIndices = if (topologyChanged) {
            getIndices(
                points.size, radialSegments, closed, capStyle,
                if (newCapacityExplicit) newCapacity else 0,
            )
        } else primitivesIndices
        // Read by [drawnOffsets] while the base class describes the buffers, during the call or
        // once a waiting rebuild goes out: it must already describe the new state.
        val previousLiveIndexCount = liveIndexCount
        liveIndexCount = liveIndexCount(points.size, radialSegments, closed, capStyle)
        try {
            super.update(
                engine,
                getVertices(points, radius, radialSegments, closed, capStyle, radii, newCapacity),
                newIndices,
            )
        } catch (failure: Throwable) {
            liveIndexCount = previousLiveIndexCount
            throw failure
        }
        this.points = points
        this.radius = radius
        this.radii = radii
        this.radialSegments = radialSegments
        this.closed = closed
        this.capStyle = capStyle
        this.capacity = newCapacity
        capacityExplicit = newCapacityExplicit
    }

    /**
     * Binary bridge for code compiled against the flat-cap API. It keeps the JVM name and the
     * original defaults (`caps` defaults to the current caps), so an old call that omitted `caps`
     * still keeps them. Hidden so it does not compete with the `capStyle` overload in source.
     */
    @Deprecated("Use capStyle", level = DeprecationLevel.HIDDEN)
    fun update(
        engine: Engine, points: List<Position> = this.points, radius: Float = this.radius,
        radialSegments: Int = this.radialSegments, closed: Boolean = this.closed,
        caps: Boolean = this.capStyle != CapStyle.None,
    ) = update(
        engine, points, radius, radialSegments, closed, legacyCapStyle(caps, capStyle), radii, 0,
    )

    /** Source-compatible flat-cap overload: `true` keeps the current caps, or adds flat ones. */
    @Deprecated(
        "Use capStyle",
        ReplaceWith("update(engine, points, radius, radialSegments, closed, if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None)"),
    )
    @JvmName("updateWithCaps")
    fun update(
        engine: Engine, points: List<Position> = this.points, radius: Float = this.radius,
        radialSegments: Int = this.radialSegments, closed: Boolean = this.closed, caps: Boolean,
    ) = update(
        engine, points, radius, radialSegments, closed, legacyCapStyle(caps, capStyle), radii, 0,
    )

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

        /** Flat ends preserve the historical default. */
        val DEFAULT_CAP_STYLE = CapStyle.Flat

        /** Open ends are capped by default — a hollow stroke end reads as a rendering glitch. */
        @Deprecated("Use DEFAULT_CAP_STYLE", ReplaceWith("DEFAULT_CAP_STYLE != Tube.CapStyle.None"))
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
        /** Latitude-ring count for each hemispherical cap, including its pole ring. */
        fun roundCapRings(radialSegments: Int): Int = max(2, (radialSegments + 2) / 3)

        /**
         * Pure point-capacity decision used by [update]. An exact allocation is always the point
         * count, so its buffers shrink with the path and its indices keep the exact layout. An
         * explicit one never shrinks, and grows to at least twice its size.
         */
        internal fun grownCapacity(
            pointCount: Int, currentCapacity: Int, requestedCapacity: Int, explicit: Boolean,
        ): Int {
            require(requestedCapacity >= 0) { "Tube capacity must be non-negative" }
            if (!explicit) return pointCount
            val requested = max(pointCount, requestedCapacity)
            if (requested <= currentCapacity) return currentCapacity
            return max(requested, currentCapacity * 2)
        }

        /**
         * Whether [requestedCapacity] asks for reserved capacity: any positive value, the same
         * rule as [Builder.capacity]. `0` asks for nothing.
         */
        internal fun isExplicitCapacityRequest(requestedCapacity: Int) = requestedCapacity > 0

        /** Whether the tube remains in, or newly enters, reserved allocation mode. */
        internal fun isCapacityExplicit(currentExplicit: Boolean, requestedCapacity: Int) =
            currentExplicit || isExplicitCapacityRequest(requestedCapacity)

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
            if (length(tangents[0]) < EPSILON) tangents[0] = Direction(x = 1f)
            for (i in 1 until count) {
                if (length(tangents[i]) < EPSILON) tangents[i] = tangents[i - 1]
            }
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
         * capped tube, the two ends. Reserved [capacity] pads the body with rings collapsed onto
         * the last live point, and [radii] supplies one non-negative radius per point.
         *
         * @param points Ordered polyline, at least 2 points.
         * @param radius Cross-section radius in scene units, used when [radii] is `null`.
         * @param radialSegments Sides of the cross-section, at least 3.
         * @param closed Whether the last point joins back to the first.
         * @param capStyle How open ends are closed. Ignored when [closed].
         * @param radii Cross-section radius at each point in scene units, or `null` for [radius].
         * @param capacity Number of path points to allocate, at least [points]; `0` means exactly
         *   [points]. Pair a positive value with the reserved [getIndices] layout.
         */
        fun getVertices(
            points: List<Position>, radius: Float = DEFAULT_RADIUS,
            radialSegments: Int = DEFAULT_RADIAL_SEGMENTS, closed: Boolean = DEFAULT_CLOSED,
            capStyle: CapStyle = DEFAULT_CAP_STYLE, radii: List<Float>? = null, capacity: Int = 0,
        ): List<Vertex> {
            validate(points, radii, radialSegments, capacity)
            val allocated = max(points.size, capacity.takeIf { it > 0 } ?: points.size)
            val frames = frames(points, closed)
            val values = radii ?: List(points.size) { radius }
            val slopes = radiusSlopes(points, values, closed)
            val rings = ringCount(allocated, closed)
            val liveRings = ringCount(points.size, closed)
            val result = ArrayList<Vertex>()
            for (i in 0 until rings) {
                // The extra ring of a closed tube re-uses point 0's position AND frame, so
                // the seam is geometrically identical to the start — only its UV differs.
                val seam = closed && i == points.size
                val live = i < points.size || seam
                val source = if (seam) 0 else i.coerceAtMost(points.lastIndex)
                val center = if (live) points[source] else points.last()
                val frame = frames[source]
                val u = i.coerceAtMost(liveRings - 1).toFloat() / (liveRings - 1)
                for (j in 0..radialSegments) {
                    val v = j.toFloat() / radialSegments
                    if (!live) {
                        result += Vertex(center, frame.normal, UvCoordinate(u, v))
                    } else {
                        val angle = v * TWO_PI
                        val outward = normalize(frame.normal * cos(angle) + frame.binormal * sin(angle))
                        val slope = slopes[source]
                        val normal = if (slope == 0f) outward else normalize(outward - frame.tangent * slope)
                        result += Vertex(center + outward * values[source], normal, UvCoordinate(u, v))
                    }
                }
            }
            if (!closed) when (capStyle) {
                CapStyle.None -> Unit
                CapStyle.Flat -> {
                    result += flatCapVertices(
                        points.first(), frames.first(), values.first(), radialSegments, true
                    )
                    result += flatCapVertices(
                        points.last(), frames.last(), values.last(), radialSegments, false
                    )
                }
                CapStyle.Round -> {
                    result += roundCapVertices(
                        points.first(), frames.first(), values.first(), slopes.first(),
                        radialSegments, true
                    )
                    result += roundCapVertices(
                        points.last(), frames.last(), values.last(), slopes.last(),
                        radialSegments, false
                    )
                }
            }
            return result
        }

        /** Binary bridge for the flat-cap API, with its original `caps = true` default. */
        @Deprecated("Use capStyle", level = DeprecationLevel.HIDDEN)
        @Suppress("DEPRECATION")
        fun getVertices(
            points: List<Position>, radius: Float = DEFAULT_RADIUS,
            radialSegments: Int = DEFAULT_RADIAL_SEGMENTS, closed: Boolean = DEFAULT_CLOSED,
            caps: Boolean = DEFAULT_CAPS,
        ) = getVertices(points, radius, radialSegments, closed, legacyCapStyle(caps), null, 0)

        /** Source-compatible flat-cap overload: `true` is flat caps, `false` is none. */
        @Deprecated(
            "Use capStyle",
            ReplaceWith("getVertices(points, radius, radialSegments, closed, if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None)"),
        )
        @JvmName("getVerticesWithCaps")
        fun getVertices(
            points: List<Position>, radius: Float = DEFAULT_RADIUS,
            radialSegments: Int = DEFAULT_RADIAL_SEGMENTS, closed: Boolean = DEFAULT_CLOSED,
            caps: Boolean,
        ) = getVertices(points, radius, radialSegments, closed, legacyCapStyle(caps), null, 0)

        /**
         * Triangle indices matching [getVertices] for a path of [pointCount] points.
         *
         * Winding is counter-clockwise as seen from outside, matching the outward shading normal
         * under the default single-sided material (Filament back-face-culls clockwise triangles —
         * same contract as [Torus], see issue #2469). A positive [capacity] is the reserved layout
         * for `max(capacity, pointCount)` points; with it or with round caps, live triangles
         * occupy a prefix of the index buffer. `0` is the exact layout for [pointCount].
         */
        fun getIndices(
            pointCount: Int, radialSegments: Int = DEFAULT_RADIAL_SEGMENTS,
            closed: Boolean = DEFAULT_CLOSED, capStyle: CapStyle = DEFAULT_CAP_STYLE,
            capacity: Int = 0,
        ): List<List<Int>> {
            require(pointCount >= 2) { "Tube requires at least 2 points" }
            require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
            require(capacity >= 0) { "Tube capacity must be non-negative" }
            val allocated = max(pointCount, capacity.takeIf { it > 0 } ?: pointCount)
            val prefix = capacity > 0 || capStyle == CapStyle.Round
            val triangles = mutableListOf<Int>()
            val stride = radialSegments + 1
            val bodyRings = ringCount(allocated, closed)
            if (prefix && !closed) appendCaps(triangles, bodyRings * stride, radialSegments, capStyle)
            repeat(if (closed) allocated else allocated - 1) {
                appendBodySegment(triangles, it, stride, radialSegments)
            }
            if (!prefix && !closed) appendCaps(triangles, bodyRings * stride, radialSegments, capStyle)
            return listOf(triangles)
        }

        /** Binary bridge for the flat-cap API, with its original `caps = true` default. */
        @Deprecated("Use capStyle", level = DeprecationLevel.HIDDEN)
        @Suppress("DEPRECATION")
        fun getIndices(
            pointCount: Int, radialSegments: Int = DEFAULT_RADIAL_SEGMENTS,
            closed: Boolean = DEFAULT_CLOSED, caps: Boolean = DEFAULT_CAPS,
        ) = getIndices(pointCount, radialSegments, closed, legacyCapStyle(caps), 0)

        /** Source-compatible flat-cap overload: `true` is flat caps, `false` is none. */
        @Deprecated(
            "Use capStyle",
            ReplaceWith("getIndices(pointCount, radialSegments, closed, if (caps) Tube.CapStyle.Flat else Tube.CapStyle.None)"),
        )
        @JvmName("getIndicesWithCaps")
        fun getIndices(
            pointCount: Int, radialSegments: Int = DEFAULT_RADIAL_SEGMENTS,
            closed: Boolean = DEFAULT_CLOSED, caps: Boolean,
        ) = getIndices(pointCount, radialSegments, closed, legacyCapStyle(caps), 0)

        /**
         * Maps the flat-cap `caps` flag onto a [CapStyle]. With a [current] style, a flag that
         * already describes it keeps it, so `caps = true` does not turn round caps flat.
         */
        internal fun legacyCapStyle(caps: Boolean, current: CapStyle? = null): CapStyle = when {
            current != null && caps == (current != CapStyle.None) -> current
            caps -> CapStyle.Flat
            else -> CapStyle.None
        }

        internal fun liveIndexCount(
            pointCount: Int, radialSegments: Int, closed: Boolean, capStyle: CapStyle,
        ): Int {
            val body = (if (closed) pointCount else pointCount - 1) * radialSegments * 6
            val caps = if (closed) 0 else when (capStyle) {
                CapStyle.None -> 0
                CapStyle.Flat -> radialSegments * 3 * 2
                CapStyle.Round -> roundCapRings(radialSegments) * radialSegments * 6 * 2
            }
            return body + caps
        }

        private fun validate(
            points: List<Position>, radii: List<Float>?, radialSegments: Int, capacity: Int,
        ) {
            // [radius] is not checked: a negative or NaN radius rendered before [radii] existed and
            // must keep doing so. Only the inputs this API added are validated.
            require(points.size >= 2) { "Tube requires at least 2 points" }
            require(radialSegments >= 3) { "Tube requires at least 3 radial segments" }
            require(capacity >= 0) { "Tube capacity must be non-negative" }
            radii?.let {
                require(it.size == points.size) { "Tube radii must match points" }
                require(it.all { value -> value >= 0f }) { "Tube radii must be non-negative" }
            }
        }

        private fun radiusSlopes(points: List<Position>, radii: List<Float>, closed: Boolean) =
            FloatArray(points.size) { i ->
                val before = if (closed) (i - 1 + points.size) % points.size else max(i - 1, 0)
                val after = if (closed) (i + 1) % points.size else minOf(i + 1, points.lastIndex)
                val run = length(points[after] - points[before])
                if (run < EPSILON) 0f else ((radii[after] - radii[before]) / run).coerceIn(-1f, 1f)
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
        private fun flatCapVertices(
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

        private fun roundCapVertices(
            center: Position, frame: Frame, radius: Float, slope: Float,
            radialSegments: Int, start: Boolean,
        ): List<Vertex> = buildList {
            val rings = roundCapRings(radialSegments)
            val direction = if (start) -frame.tangent else frame.tangent
            // Duplicate the equator so both caps keep fixed indices while the live body end moves
            // within a larger capacity. It is geometrically identical to the endpoint body ring.
            for (k in 0..rings) {
                val phi = k.toFloat() / rings * (kotlin.math.PI.toFloat() / 2f)
                val along = sin(phi)
                val across = cos(phi)
                val ringCenter = center + direction * (radius * along)
                for (j in 0..radialSegments) {
                    val v = j.toFloat() / radialSegments
                    val angle = v * TWO_PI
                    val outward = frame.normal * cos(angle) + frame.binormal * sin(angle)
                    val normal = if (k == 0) {
                        if (slope == 0f) outward else normalize(outward - frame.tangent * slope)
                    } else {
                        normalize(outward * across + direction * along)
                    }
                    add(Vertex(
                        ringCenter + outward * (radius * across), normal,
                        UvCoordinate(k.toFloat() / rings, v),
                    ))
                }
            }
        }

        private fun appendBodySegment(
            triangles: MutableList<Int>, ring: Int, stride: Int, radialSegments: Int,
        ) {
            for (j in 0 until radialSegments) {
                val a = ring * stride + j
                val b = a + stride
                val c = a + 1
                val d = b + 1
                triangles.addAll(listOf(a, d, b, a, c, d))
            }
        }

        private fun appendCaps(
            triangles: MutableList<Int>, base: Int, radialSegments: Int, capStyle: CapStyle,
        ) {
            when (capStyle) {
                CapStyle.None -> Unit
                CapStyle.Flat -> {
                    // Start disc faces backwards along the path, so its fan winds the other way.
                    val next = appendFlatCapIndices(triangles, base, radialSegments, true)
                    appendFlatCapIndices(triangles, next, radialSegments, false)
                }
                CapStyle.Round -> {
                    val stride = radialSegments + 1
                    val capRings = roundCapRings(radialSegments)
                    appendRoundCapIndices(triangles, base, radialSegments, true)
                    appendRoundCapIndices(
                        triangles, base + (capRings + 1) * stride, radialSegments, false,
                    )
                }
            }
        }

        /**
         * Appends one cap fan starting at vertex [base] and returns the next free vertex index.
         * The start cap faces backwards along the path, so its fan winds opposite to the end cap.
         */
        private fun appendFlatCapIndices(
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

        private fun appendRoundCapIndices(
            triangles: MutableList<Int>, capBase: Int, radialSegments: Int, start: Boolean,
        ) {
            val stride = radialSegments + 1
            repeat(roundCapRings(radialSegments)) { k ->
                val from = capBase + k * stride
                val to = from + stride
                for (j in 0 until radialSegments) {
                    val a = from + j
                    val b = to + j
                    val c = a + 1
                    val d = b + 1
                    triangles.addAll(
                        if (start) listOf(a, b, d, a, d, c)
                        else listOf(a, d, b, a, c, d)
                    )
                }
            }
        }
    }
}
