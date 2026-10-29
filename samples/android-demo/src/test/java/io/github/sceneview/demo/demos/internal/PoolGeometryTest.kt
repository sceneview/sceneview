package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Pins the meshes [PoolGeometry] generates for [io.github.sceneview.demo.demos.ARPoolDemo].
 *
 * The pool only reads as dug into the lawn if four meshes agree to the centimetre: the liner
 * inside the occlusion hull, the coping over the gap between them, the water at its level.
 * None of it throws when it is wrong — it shows as a sliver of camera feed through a wall, a
 * black wall facing the wrong way, or a pool that floats — so every preset is checked here,
 * on the JVM, with no Filament engine involved.
 */
class PoolGeometryTest {

    private val specs = PoolPreset.entries.map { it.spec } + PoolSpec(length = 4f, width = 2f)

    @Test
    fun `the floor is deep at the near wall and shallow at the far wall`() {
        for (spec in specs) {
            assertEquals(spec.deepDepth, spec.floorDepthAt(spec.zNear), EPS)
            assertEquals(spec.shallowDepth, spec.floorDepthAt(spec.zFar), EPS)
            var previous = Float.MAX_VALUE
            for (j in 0..SAMPLES) {
                val depth = spec.floorDepthAt(spec.zNear - spec.length * j / SAMPLES)
                assertTrue("the floor rises towards the deep end at step $j", depth <= previous + EPS)
                previous = depth
            }
        }
    }

    @Test
    fun `every normal is unit length`() {
        for (spec in specs) {
            for (mesh in listOf(PoolGeometry.liner(spec), PoolGeometry.water(spec), PoolGeometry.coping(spec))) {
                for (vertex in mesh.vertices) assertEquals(1f, length(vertex.normal!!), EPS)
            }
        }
    }

    @Test
    fun `the liner colour carries its object normal and flags only the step noses`() {
        for (spec in specs) {
            val liner = PoolGeometry.liner(spec)
            var noses = 0
            for (vertex in liner.vertices) {
                val color = vertex.color!!
                val decoded = Direction(color.x * 2f - 1f, color.y * 2f - 1f, color.z * 2f - 1f)
                assertTrue("colour must encode the normal", length(decoded - vertex.normal!!) < EPS)
                assertTrue("the flag is 0 or 1", color.w == 0f || color.w == 1f)
                if (color.w == 1f) {
                    noses++
                    assertEquals("a nose band lies on a tread", 1f, vertex.normal!!.y, EPS)
                }
            }
            assertTrue("every step has its nose band", noses > 0)
        }
    }

    @Test
    fun `every liner normal points into the water volume`() {
        for (spec in specs) {
            for (vertex in PoolGeometry.liner(spec).vertices) {
                val probe = vertex.position + vertex.normal!! * PROBE
                assertTrue("normal of ${vertex.position} points out of the pool", insidePool(spec, probe))
            }
        }
    }

    @Test
    fun `every triangle is wound towards its normals`() {
        for (spec in specs) {
            for (mesh in listOf(PoolGeometry.liner(spec), PoolGeometry.water(spec), PoolGeometry.coping(spec))) {
                mesh.triangles { a, b, c ->
                    val winding = cross(b.position - a.position, c.position - a.position)
                    if (length(winding) > DEGENERATE) {
                        val normal = a.normal!! + b.normal!! + c.normal!!
                        assertTrue("triangle at ${a.position} is back-facing", dot(winding, normal) > 0f)
                    }
                }
            }
        }
    }

    @Test
    fun `the hull faces outwards`() {
        for (spec in specs) {
            val center = Position(0f, -spec.deepDepth / 2f, spec.zCenter)
            PoolGeometry.hull(spec).triangles { a, b, c ->
                val winding = cross(b.position - a.position, c.position - a.position)
                val centroid = (a.position + b.position + c.position) / 3f
                assertTrue("hull triangle at $centroid faces inwards", dot(winding, centroid - center) > 0f)
            }
        }
    }

    @Test
    fun `indices are in range and come in whole triangles`() {
        for (spec in specs) {
            for (mesh in allMeshes(spec)) {
                assertEquals(0, mesh.indices.size % 3)
                assertTrue(mesh.indices.all { it in mesh.vertices.indices })
            }
        }
    }

    @Test
    fun `every vertex of a mesh carries the same attributes`() {
        for (spec in specs) {
            val liner = PoolGeometry.liner(spec).vertices
            assertTrue(liner.all { it.normal != null && it.color != null && it.uvCoordinate == null })
            for (mesh in listOf(PoolGeometry.water(spec), PoolGeometry.coping(spec))) {
                assertTrue(mesh.vertices.all { it.normal != null && it.color == null && it.uvCoordinate == null })
            }
            val hull = PoolGeometry.hull(spec).vertices
            assertTrue(hull.all { it.normal == null && it.color == null && it.uvCoordinate == null })
        }
    }

    @Test
    fun `the liner sits inside the occlusion hull with the gap all round`() {
        for (spec in specs) {
            val liner = bounds(PoolGeometry.liner(spec))
            val hull = bounds(PoolGeometry.hull(spec))
            val gap = PoolGeometry.HULL_GAP
            assertEquals(gap, liner.min.x - hull.min.x, EPS)
            assertEquals(gap, hull.max.x - liner.max.x, EPS)
            assertEquals(gap, liner.min.z - hull.min.z, EPS)
            assertEquals(gap, hull.max.z - liner.max.z, EPS)
            assertEquals(gap, liner.min.y - hull.min.y, EPS)
            // Both open at the lawn: the hull must not cap the opening the pool is seen through.
            assertEquals(0f, liner.max.y, EPS)
            assertEquals(0f, hull.max.y, EPS)
        }
    }

    @Test
    fun `the hull is closed except for its top`() {
        for (spec in specs) {
            val edges = HashMap<Pair<Key, Key>, Int>()
            PoolGeometry.hull(spec).triangles { a, b, c ->
                for ((p, q) in listOf(a to b, b to c, c to a)) {
                    val ends = listOf(Key(p.position), Key(q.position)).sortedWith(KEY_ORDER)
                    edges.merge(ends[0] to ends[1], 1, Int::plus)
                }
            }
            for ((edge, count) in edges) {
                val rim = edge.first.y == 0L && edge.second.y == 0L
                assertEquals("edge $edge", if (rim) 1 else 2, count)
            }
        }
    }

    @Test
    fun `the coping covers the gap between the water and the hull`() {
        for (spec in specs) {
            val top = PoolGeometry.coping(spec).upFacingTriangles()
            val a = spec.halfWidth
            val gap = PoolGeometry.HULL_GAP
            val offsets = listOf(0f, gap / 2f, gap, PoolGeometry.COPING_WIDTH - EDGE)
            for (d in offsets) {
                for (j in 0..SAMPLES) {
                    val f = j.toFloat() / SAMPLES
                    val z = spec.zNear + (spec.zFar - spec.zNear) * f
                    val x = -a + spec.width * f
                    val points = listOf(
                        Position(-a - d, 0f, z),
                        Position(a + d, 0f, z),
                        Position(x, 0f, spec.zNear + d),
                        Position(x, 0f, spec.zFar - d),
                    )
                    for (p in points) assertTrue("lawn at $p is not under the coping", top.any { it.covers(p) })
                }
            }
        }
    }

    @Test
    fun `the coping overhangs the water and sinks just below the lawn`() {
        for (spec in specs) {
            val coping = bounds(PoolGeometry.coping(spec))
            assertEquals(PoolGeometry.COPING_HEIGHT, coping.max.y, EPS)
            assertTrue("the outer face must reach below the lawn", coping.min.y < 0f)
            val overhang = PoolGeometry.coping(spec).vertices.count {
                abs(it.position.x) < spec.halfWidth - EPS && it.position.z < spec.zNear - EPS &&
                    it.position.z > spec.zFar + EPS
            }
            assertTrue("the bull-nose must overhang the water", overhang > 0)
        }
    }

    @Test
    fun `the origin is the middle of the coping's outer edge on the viewer's side`() {
        for (spec in specs) {
            val coping = bounds(PoolGeometry.coping(spec))
            assertEquals(0f, coping.max.z, EPS)
            assertEquals(-(spec.halfWidth + PoolGeometry.COPING_WIDTH), coping.min.x, EPS)
            assertEquals(spec.halfWidth + PoolGeometry.COPING_WIDTH, coping.max.x, EPS)
            assertEquals(spec.zFar - PoolGeometry.COPING_WIDTH, coping.min.z, EPS)
        }
    }

    @Test
    fun `the steps fit inside the flat shallow end`() {
        for (spec in specs) {
            val radius = PoolGeometry.stairRadius(spec)
            assertTrue(radius <= spec.width / 2f)
            assertEquals(spec.shallowDepth, spec.floorDepthAt(spec.zFar + radius), EPS)
            // The lowest riser lands exactly on the floor: no step hangs in the water.
            val floorAtSteps = PoolGeometry.liner(spec).vertices.count {
                abs(it.position.y + spec.shallowDepth) < EPS &&
                    abs(length(Position(it.position.x + spec.halfWidth, 0f, it.position.z - spec.zFar)) - radius) < EPS
            }
            assertTrue("the lowest riser must reach the floor", floorAtSteps > 0)
        }
    }

    @Test
    fun `the water lies flat below the lawn and over the top step`() {
        for (spec in specs) {
            val water = bounds(PoolGeometry.water(spec))
            assertEquals(-PoolGeometry.WATER_DROP, water.min.y, EPS)
            assertEquals(-PoolGeometry.WATER_DROP, water.max.y, EPS)
            assertTrue(PoolGeometry.WATER_DROP < PoolGeometry.STEP_TOP)
            assertEquals(-spec.halfWidth, water.min.x, EPS)
            assertEquals(spec.halfWidth, water.max.x, EPS)
            assertEquals(spec.zFar, water.min.z, EPS)
            assertEquals(spec.zNear, water.max.z, EPS)
        }
    }

    @Test
    fun `the night veil covers the whole screen at mid-depth`() {
        val screen = PoolGeometry.screen()
        assertEquals(3, screen.vertices.size)
        assertEquals(listOf(0, 1, 2), screen.indices)
        // Its material is unlit in the device domain: it reads the position and nothing else.
        assertTrue(screen.vertices.all { it.normal == null && it.color == null && it.uvCoordinate == null })
        // Filament's clip space runs from 0 at the far plane to 1 at the near one.
        assertTrue(screen.vertices.all { it.position.z > 0f && it.position.z < 1f })
        val triangle = screen.vertices.map { it.position }.let { Triple(it[0], it[1], it[2]) }
        for (x in listOf(-1f, 0f, 1f)) {
            for (y in listOf(-1f, 0f, 1f)) {
                val p = Position(x, y, 0f)
                assertTrue("screen point $p is not under the veil", triangle.covers(p) { it.y })
            }
        }
    }

    @Test
    fun `the presets are real pool sizes`() {
        assertEquals(listOf(6f to 3f, 8f to 4f, 10f to 5f), PoolPreset.entries.map { it.length to it.width })
        for (preset in PoolPreset.entries) {
            assertEquals(1.2f, preset.spec.shallowDepth, EPS)
            assertEquals(1.5f, preset.spec.deepDepth, EPS)
        }
    }

    private fun allMeshes(spec: PoolSpec) =
        listOf(PoolGeometry.liner(spec), PoolGeometry.water(spec), PoolGeometry.coping(spec), PoolGeometry.hull(spec))

    private fun insidePool(spec: PoolSpec, p: Position): Boolean =
        abs(p.x) <= spec.halfWidth + EPS &&
            p.z <= spec.zNear + EPS && p.z >= spec.zFar - EPS &&
            p.y <= PROBE + EPS && p.y >= -spec.floorDepthAt(p.z) - EPS

    private inline fun PoolMesh.triangles(block: (Geometry.Vertex, Geometry.Vertex, Geometry.Vertex) -> Unit) {
        for (t in indices.indices step 3) {
            block(vertices[indices[t]], vertices[indices[t + 1]], vertices[indices[t + 2]])
        }
    }

    private fun PoolMesh.upFacingTriangles(): List<Triple<Position, Position, Position>> {
        val result = mutableListOf<Triple<Position, Position, Position>>()
        triangles { a, b, c -> if (a.normal!!.y > 0f) result += Triple(a.position, b.position, c.position) }
        return result
    }

    /** Whether [p] falls inside this triangle seen from above (x/z only). */
    private fun Triple<Position, Position, Position>.covers(p: Position) = covers(p) { it.z }

    /** Whether [p] falls inside this triangle projected on x and [axis], edges included. */
    private fun Triple<Position, Position, Position>.covers(p: Position, axis: (Position) -> Float): Boolean {
        fun side(a: Position, b: Position) = (b.x - a.x) * (axis(p) - axis(a)) - (axis(b) - axis(a)) * (p.x - a.x)
        val s1 = side(first, second)
        val s2 = side(second, third)
        val s3 = side(third, first)
        val hasNegative = s1 < -AREA_EPS || s2 < -AREA_EPS || s3 < -AREA_EPS
        val hasPositive = s1 > AREA_EPS || s2 > AREA_EPS || s3 > AREA_EPS
        return !(hasNegative && hasPositive)
    }

    private class Bounds(val min: Position, val max: Position)

    private fun bounds(mesh: PoolMesh): Bounds {
        val min = Position(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = Position(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (vertex in mesh.vertices) {
            val p = vertex.position
            min.x = minOf(min.x, p.x)
            min.y = minOf(min.y, p.y)
            min.z = minOf(min.z, p.z)
            max.x = max(max.x, p.x)
            max.y = max(max.y, p.y)
            max.z = max(max.z, p.z)
        }
        return Bounds(min, max)
    }

    /** A position snapped to 0.1 mm, so the hull's per-face duplicates compare equal. */
    private data class Key(val x: Long, val y: Long, val z: Long) {
        constructor(p: Position) : this(snap(p.x), snap(p.y), snap(p.z))

        companion object {
            fun snap(v: Float) = (v * 10_000f).roundToLong()
        }
    }

    private companion object {
        const val EPS = 1e-4f
        const val PROBE = 0.01f
        const val DEGENERATE = 1e-7f
        const val AREA_EPS = 1e-7f
        const val EDGE = 0.005f
        const val SAMPLES = 40
        val KEY_ORDER = compareBy<Key>({ it.x }, { it.y }, { it.z })
    }
}
