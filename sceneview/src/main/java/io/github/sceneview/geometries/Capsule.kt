package io.github.sceneview.geometries

import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.RenderableManager.PrimitiveType
import com.google.android.filament.VertexBuffer
import dev.romainguy.kotlin.math.HALF_PI
import dev.romainguy.kotlin.math.TWO_PI
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.sin

/**
 * A capsule [Geometry] — a cylinder side capped by two hemispheres — generated with smooth
 * per-vertex normals and UV coordinates, suitable for use as a renderable mesh.
 *
 * [height] is the length of the straight cylindrical section only; the total extent along
 * the axis is `height + 2 * radius`. Instances are immutable once built; create one with the
 * [Builder] and mutate it afterwards through [update]. All Filament buffer operations run
 * synchronously and must be called on the main thread.
 */
class Capsule private constructor(
    primitiveType: PrimitiveType,
    vertices: List<Vertex>,
    vertexBuffer: VertexBuffer,
    indices: List<List<Int>>,
    indexBuffer: IndexBuffer,
    primitivesOffsets: List<IntRange>,
    boundingBox: Box,
    radius: Float,
    height: Float,
    center: Position,
    capStacks: Int,
    sideSlices: Int
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
     * Builder for [Capsule]. Configure [radius], [height], [center] and the
     * [capStacks]/[sideSlices] tessellation, then call [build].
     */
    class Builder : Geometry.Builder(PrimitiveType.TRIANGLES) {
        /** Radius of the hemispherical caps and the cylinder side, in scene units. Defaults to [DEFAULT_RADIUS]. */
        var radius: Float = DEFAULT_RADIUS
            private set

        /** Length of the straight cylindrical section only, in scene units. Defaults to [DEFAULT_HEIGHT]. */
        var height: Float = DEFAULT_HEIGHT
            private set

        /** Local-space position of the capsule center. Defaults to [DEFAULT_CENTER] (the origin). */
        var center: Position = DEFAULT_CENTER
            private set

        /** Number of latitude bands per hemispherical cap. Higher is smoother. Defaults to [DEFAULT_CAP_STACKS]. */
        var capStacks: Int = DEFAULT_CAP_STACKS
            private set

        /** Number of segments around the axis. Higher is smoother. Defaults to [DEFAULT_SIDE_SLICES]. */
        var sideSlices: Int = DEFAULT_SIDE_SLICES
            private set

        /** Sets the radius in scene units. Returns this builder for chaining. */
        fun radius(radius: Float) = apply { this.radius = radius }

        /** Sets the cylindrical section length in scene units. Returns this builder for chaining. */
        fun height(height: Float) = apply { this.height = height }

        /** Sets the local-space center of the capsule. Returns this builder for chaining. */
        fun center(center: Position) = apply { this.center = center }

        /** Sets the number of latitude bands per cap. Returns this builder for chaining. */
        fun capStacks(capStacks: Int) = apply { this.capStacks = capStacks }

        /** Sets the number of segments around the axis. Returns this builder for chaining. */
        fun sideSlices(sideSlices: Int) = apply { this.sideSlices = sideSlices }

        /**
         * Builds the [Capsule], allocating its Filament vertex and index buffers on [engine].
         * Must be called on the main thread.
         */
        override fun build(engine: Engine): Capsule {
            vertices(getVertices(radius, height, center, capStacks, sideSlices))
            primitivesIndices(getIndices(capStacks, sideSlices))
            return build(engine) { vertexBuffer, indexBuffer, offsets, boundingBox ->
                Capsule(
                    primitiveType, vertices, vertexBuffer, indices, indexBuffer, offsets,
                    boundingBox, radius, height, center, capStacks, sideSlices
                )
            }
        }
    }

    /** Current radius of the caps and cylinder side, in scene units. */
    var radius: Float = radius
        private set

    /** Current length of the straight cylindrical section, in scene units. */
    var height: Float = height
        private set

    /** Current local-space center of the capsule. */
    var center: Position = center
        private set

    /** Current number of latitude bands per hemispherical cap. */
    var capStacks: Int = capStacks
        private set

    /** Current number of segments around the axis. */
    var sideSlices: Int = sideSlices
        private set

    /**
     * Regenerates the capsule vertices in place and re-uploads them to the existing Filament
     * vertex buffer. The index buffer is rebuilt only when [capStacks] or [sideSlices] change.
     * Must be called on the main thread. Returns this geometry for chaining.
     */
    fun update(
        engine: Engine,
        radius: Float = this.radius,
        height: Float = this.height,
        center: Position = this.center,
        capStacks: Int = this.capStacks,
        sideSlices: Int = this.sideSlices
    ) = apply {
        update(
            engine = engine,
            vertices = getVertices(radius, height, center, capStacks, sideSlices),
            primitivesIndices = if (capStacks != this.capStacks || sideSlices != this.sideSlices) {
                getIndices(capStacks, sideSlices)
            } else {
                primitivesIndices
            }
        )
        this.radius = radius
        this.height = height
        this.center = center
        this.capStacks = capStacks
        this.sideSlices = sideSlices
    }

    companion object {
        val DEFAULT_RADIUS = 0.5f
        val DEFAULT_HEIGHT = 2.0f
        val DEFAULT_CENTER = Position(0.0f)
        val DEFAULT_CAP_STACKS = 8
        val DEFAULT_SIDE_SLICES = 24

        fun getVertices(
            radius: Float, height: Float, center: Position,
            capStacks: Int, sideSlices: Int
        ) = buildList {
            val halfCylHeight = height / 2

            // Top hemisphere (from pole to equator)
            for (stack in 0..capStacks) {
                val phi = HALF_PI * (1.0f - stack.toFloat() / capStacks) // pi/2 -> 0
                val cosPhi = cos(phi)
                val sinPhi = sin(phi)
                val y = radius * sinPhi + halfCylHeight
                val ringRadius = radius * cosPhi
                val v = 0.5f * stack.toFloat() / capStacks // 0 -> 0.5 mapped to top cap

                for (slice in 0..sideSlices) {
                    val theta = TWO_PI * slice.toFloat() / sideSlices
                    val cosTheta = cos(theta)
                    val sinTheta = sin(theta)

                    add(
                        Vertex(
                            position = Position(ringRadius * cosTheta, y, ringRadius * sinTheta) + center,
                            normal = normalize(Position(cosPhi * cosTheta, sinPhi, cosPhi * sinTheta)),
                            uvCoordinate = UvCoordinate(
                                x = slice.toFloat() / sideSlices,
                                y = v
                            )
                        )
                    )
                }
            }

            // Bottom hemisphere (from equator to pole)
            for (stack in 1..capStacks) {
                val phi = -HALF_PI * stack.toFloat() / capStacks // 0 -> -pi/2
                val cosPhi = cos(phi)
                val sinPhi = sin(phi)
                val y = radius * sinPhi - halfCylHeight
                val ringRadius = radius * cosPhi
                val v = 0.5f + 0.5f * stack.toFloat() / capStacks // 0.5 -> 1.0

                for (slice in 0..sideSlices) {
                    val theta = TWO_PI * slice.toFloat() / sideSlices
                    val cosTheta = cos(theta)
                    val sinTheta = sin(theta)

                    add(
                        Vertex(
                            position = Position(ringRadius * cosTheta, y, ringRadius * sinTheta) + center,
                            normal = normalize(Position(cosPhi * cosTheta, sinPhi, cosPhi * sinTheta)),
                            uvCoordinate = UvCoordinate(
                                x = slice.toFloat() / sideSlices,
                                y = v
                            )
                        )
                    )
                }
            }
        }

        fun getIndices(capStacks: Int, sideSlices: Int) = buildList {
            val totalStacks = capStacks * 2 // top cap + bottom cap
            val stride = sideSlices + 1
            val triangleIndices = mutableListOf<Int>()

            for (stack in 0 until totalStacks) {
                for (slice in 0 until sideSlices) {
                    val a = stack * stride + slice
                    val b = a + stride
                    val c = a + 1
                    val d = b + 1
                    // Counter-clockwise (outward-facing) winding so the front face matches the
                    // outward shading normal under the default single-sided material (Filament
                    // back-face-culls clockwise triangles). See issue #2470.
                    triangleIndices.addAll(listOf(a, d, b, a, c, d))
                }
            }
            add(triangleIndices)
        }
    }
}
