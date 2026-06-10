package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.sin

/**
 * A torus (donut) [Geometry] generated from a major/minor segment grid, with smooth
 * per-vertex normals and UV coordinates, suitable for use as a renderable mesh.
 *
 * [majorRadius] is the distance from the torus center to the center of the tube;
 * [minorRadius] is the radius of the tube itself. Instances are immutable once built;
 * create one with the [Builder] and mutate it afterwards through [update]. All Filament
 * buffer operations run synchronously and must be called on the main thread.
 */
class Torus private constructor(
    primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    vertexBuffer: VertexBuffer,
    indices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    primitivesOffsets: List<IntRange>,
    boundingBox: Box,
    majorRadius: Float,
    minorRadius: Float,
    center: Position,
    majorSegments: Int,
    minorSegments: Int
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
     * Builder for [Torus]. Configure [majorRadius], [minorRadius], [center] and the
     * [majorSegments]/[minorSegments] tessellation, then call [build].
     */
    class Builder : Geometry.Builder(PrimitiveType.TRIANGLES) {
        /** Distance from the torus center to the tube center, in scene units. Defaults to [DEFAULT_MAJOR_RADIUS]. */
        var majorRadius: Float = DEFAULT_MAJOR_RADIUS
            private set

        /** Radius of the tube cross-section, in scene units. Defaults to [DEFAULT_MINOR_RADIUS]. */
        var minorRadius: Float = DEFAULT_MINOR_RADIUS
            private set

        /** Local-space position of the torus center. Defaults to [DEFAULT_CENTER] (the origin). */
        var center: Position = DEFAULT_CENTER
            private set

        /** Number of segments around the main ring. Higher is smoother. Defaults to [DEFAULT_MAJOR_SEGMENTS]. */
        var majorSegments: Int = DEFAULT_MAJOR_SEGMENTS
            private set

        /** Number of segments around the tube cross-section. Higher is smoother. Defaults to [DEFAULT_MINOR_SEGMENTS]. */
        var minorSegments: Int = DEFAULT_MINOR_SEGMENTS
            private set

        /** Sets the major radius in scene units. Returns this builder for chaining. */
        fun majorRadius(majorRadius: Float) = apply { this.majorRadius = majorRadius }

        /** Sets the minor (tube) radius in scene units. Returns this builder for chaining. */
        fun minorRadius(minorRadius: Float) = apply { this.minorRadius = minorRadius }

        /** Sets the local-space center of the torus. Returns this builder for chaining. */
        fun center(center: Position) = apply { this.center = center }

        /** Sets the number of segments around the main ring. Returns this builder for chaining. */
        fun majorSegments(majorSegments: Int) = apply { this.majorSegments = majorSegments }

        /** Sets the number of segments around the tube cross-section. Returns this builder for chaining. */
        fun minorSegments(minorSegments: Int) = apply { this.minorSegments = minorSegments }

        /**
         * Builds the [Torus], allocating its Filament vertex and index buffers on [engine].
         * Must be called on the main thread.
         */
        override fun build(engine: Engine): Torus {
            vertices(getVertices(majorRadius, minorRadius, center, majorSegments, minorSegments))
            primitivesIndices(getIndices(majorSegments, minorSegments))
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Torus(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer, offsets,
                    boundingBox, majorRadius, minorRadius, center, majorSegments, minorSegments
                )
            }
        }
    }

    /** Current distance from the torus center to the tube center, in scene units. */
    var majorRadius: Float = majorRadius
        private set

    /** Current radius of the tube cross-section, in scene units. */
    var minorRadius: Float = minorRadius
        private set

    /** Current local-space center of the torus. */
    var center: Position = center
        private set

    /** Current number of segments around the main ring. */
    var majorSegments: Int = majorSegments
        private set

    /** Current number of segments around the tube cross-section. */
    var minorSegments: Int = minorSegments
        private set

    /**
     * Regenerates the torus vertices in place and re-uploads them to the existing Filament
     * vertex buffer. The index buffer is rebuilt only when [majorSegments] or [minorSegments]
     * change. Must be called on the main thread. Returns this geometry for chaining.
     */
    fun update(
        engine: Engine,
        majorRadius: Float = this.majorRadius,
        minorRadius: Float = this.minorRadius,
        center: Position = this.center,
        majorSegments: Int = this.majorSegments,
        minorSegments: Int = this.minorSegments
    ) = apply {
        update(
            engine = engine,
            vertices = getVertices(majorRadius, minorRadius, center, majorSegments, minorSegments),
            primitivesIndices = if (majorSegments != this.majorSegments || minorSegments != this.minorSegments) {
                getIndices(majorSegments, minorSegments)
            } else {
                primitivesIndices
            }
        )
        this.majorRadius = majorRadius
        this.minorRadius = minorRadius
        this.center = center
        this.majorSegments = majorSegments
        this.minorSegments = minorSegments
    }

    companion object {
        val DEFAULT_MAJOR_RADIUS = 1.0f
        val DEFAULT_MINOR_RADIUS = 0.3f
        val DEFAULT_CENTER = Position(0.0f)
        val DEFAULT_MAJOR_SEGMENTS = 32
        val DEFAULT_MINOR_SEGMENTS = 16

        fun getVertices(
            majorRadius: Float, minorRadius: Float, center: Position,
            majorSegments: Int, minorSegments: Int
        ) = buildList {
            for (i in 0..majorSegments) {
                val u = i.toFloat() / majorSegments
                val theta = u * TWO_PI
                val cosTheta = cos(theta)
                val sinTheta = sin(theta)

                for (j in 0..minorSegments) {
                    val v = j.toFloat() / minorSegments
                    val phi = v * TWO_PI
                    val cosPhi = cos(phi)
                    val sinPhi = sin(phi)

                    val x = (majorRadius + minorRadius * cosPhi) * cosTheta
                    val y = minorRadius * sinPhi
                    val z = (majorRadius + minorRadius * cosPhi) * sinTheta

                    val normal = normalize(
                        Direction(
                            x = cosPhi * cosTheta,
                            y = sinPhi,
                            z = cosPhi * sinTheta
                        )
                    )

                    add(
                        Vertex(
                            position = Position(x, y, z) + center,
                            normal = normal,
                            uvCoordinate = UvCoordinate(x = u, y = v)
                        )
                    )
                }
            }
        }

        fun getIndices(majorSegments: Int, minorSegments: Int) = buildList {
            val stride = minorSegments + 1
            val triangleIndices = mutableListOf<Int>()
            for (i in 0 until majorSegments) {
                for (j in 0 until minorSegments) {
                    val a = i * stride + j
                    val b = a + stride
                    val c = a + 1
                    val d = b + 1
                    // Counter-clockwise (outward-facing) winding so the front face matches the
                    // outward shading normal under the default single-sided material (Filament
                    // back-face-culls clockwise triangles). See issue #2469.
                    triangleIndices.addAll(listOf(a, d, b, a, c, d))
                }
            }
            add(triangleIndices)
        }
    }
}
