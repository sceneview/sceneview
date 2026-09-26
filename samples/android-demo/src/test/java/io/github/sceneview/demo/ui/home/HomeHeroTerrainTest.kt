package io.github.sceneview.demo.ui.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the home hero's landscape (#3948): the tiling contract that lets
 * the strip slide forever, and the shape of the mesh the stage uploads to Filament.
 * No Filament, no Compose, no Robolectric.
 */
class HomeHeroTerrainTest {

    @Test
    fun `heightfield repeats exactly every period along Z`() {
        val period = 40f
        for (x in listOf(-40f, -12f, -3f, 0f, 2.5f, 17f, 41f)) {
            for (z in listOf(-95f, -37.25f, 0f, 3f, 12.5f)) {
                val here = heroTerrainHeight(x, z, period)
                assertEquals("x=$x z=$z", here, heroTerrainHeight(x, z + period, period), 1e-3f)
                assertEquals("x=$x z=$z", here, heroTerrainHeight(x, z - 3 * period, period), 1e-3f)
            }
        }
    }

    @Test
    fun `the valley floor is low and flat, the ridges climb with distance from the centre line`() {
        val period = 40f
        val floor = (0 until 200).map { heroTerrainHeight(0f, it * 0.37f, period) }
        assertTrue("floor max ${floor.max()}", floor.max() < 0.2f)
        assertTrue("floor min ${floor.min()}", floor.min() > -1.0f)
        val ridge = (0 until 200).map { heroTerrainHeight(34f, it * 0.37f, period) }
        assertTrue("ridge mean", ridge.average() > 4.0)
        assertTrue("ridge peak ${ridge.max()}", ridge.max() > 7.0f)
        // The corridor is symmetric enough that the flight never hugs one wall.
        assertEquals(
            (0 until 50).map { heroTerrainHeight(30f, it.toFloat(), period) }.average(),
            (0 until 50).map { heroTerrainHeight(-30f, it.toFloat(), period) }.average(),
            2.0,
        )
    }

    @Test
    fun `mesh is a flat-shaded triangle soup of the spec's size`() {
        val spec = HeroTerrainSpec(columns = 6, rows = 10)
        val mesh = buildHeroTerrain(spec)
        val triangles = spec.columns * spec.rows * 2
        assertEquals(triangles, mesh.triangleCount)
        assertEquals(triangles * 3, mesh.vertexCount)
        assertEquals(mesh.vertexCount * 3, mesh.positions.size)
        assertEquals(mesh.vertexCount * 3, mesh.normals.size)
        assertEquals(mesh.vertexCount * 4, mesh.colors.size)
        // Trivial indices: vertex i is used by triangle i / 3 and by nothing else.
        assertArrayEquals(IntArray(mesh.vertexCount) { it }, mesh.indices)
    }

    @Test
    fun `every face normal is unit length, points up and is shared by its three vertices`() {
        val mesh = buildHeroTerrain(HeroTerrainSpec(columns = 8, rows = 12))
        for (t in 0 until mesh.triangleCount) {
            val base = t * 3
            for (v in base until base + 3) {
                val nx = mesh.normals[v * 3]
                val ny = mesh.normals[v * 3 + 1]
                val nz = mesh.normals[v * 3 + 2]
                assertEquals("triangle $t vertex $v", 1f, sqrt(nx * nx + ny * ny + nz * nz), 1e-4f)
                assertTrue("triangle $t vertex $v ny=$ny", ny > 0f)
                assertEquals(mesh.normals[base * 3], nx, 0f)
                assertEquals(mesh.normals[base * 3 + 1], ny, 0f)
                assertEquals(mesh.normals[base * 3 + 2], nz, 0f)
            }
        }
    }

    @Test
    fun `the strip covers the spec's extent and winds counter-clockwise seen from above`() {
        val spec = HeroTerrainSpec(columns = 4, rows = 6, width = 20f, period = 10f, periods = 3, zNear = 5f)
        val mesh = buildHeroTerrain(spec)
        val xs = (0 until mesh.vertexCount).map { mesh.positions[it * 3] }
        val zs = (0 until mesh.vertexCount).map { mesh.positions[it * 3 + 2] }
        assertEquals(-10f, xs.min(), 1e-4f)
        assertEquals(10f, xs.max(), 1e-4f)
        assertEquals(spec.zFar, zs.min(), 1e-4f)
        assertEquals(spec.zNear, zs.max(), 1e-4f)
        assertEquals(-25f, spec.zFar, 0f)
        for (t in 0 until mesh.triangleCount) {
            val a = t * 3
            val ax = mesh.positions[a * 3]; val az = mesh.positions[a * 3 + 2]
            val bx = mesh.positions[(a + 1) * 3]; val bz = mesh.positions[(a + 1) * 3 + 2]
            val cx = mesh.positions[(a + 2) * 3]; val cz = mesh.positions[(a + 2) * 3 + 2]
            // Signed area in the XZ plane, seen from +Y (Filament's front face is CCW).
            val area = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
            assertTrue("triangle $t area=$area", area > 0f)
        }
    }

    @Test
    fun `colours are linear RGB in range, opaque, and climb the palette with altitude`() {
        val mesh = buildHeroTerrain(HeroTerrainSpec.Light)
        for (i in 0 until mesh.vertexCount) {
            for (c in 0 until 3) {
                val value = mesh.colors[i * 4 + c]
                assertTrue("vertex $i channel $c = $value", value in 0f..1f)
            }
            assertEquals(1f, mesh.colors[i * 4 + 3], 0f)
        }
        val valley = heroTerrainColor(-0.4f)
        val peak = heroTerrainColor(9.5f)
        assertTrue("snow is brighter than the valley", peak.sum() > valley.sum() * 5)
        assertTrue("jitter scales the colour", heroTerrainColor(3f, 0.06f)[0] > heroTerrainColor(3f, -0.06f)[0])
    }

    @Test
    fun `two builds of the same spec are byte-identical`() {
        val a = buildHeroTerrain(HeroTerrainSpec(columns = 5, rows = 7))
        val b = buildHeroTerrain(HeroTerrainSpec(columns = 5, rows = 7))
        assertArrayEquals(a.positions, b.positions, 0f)
        assertArrayEquals(a.normals, b.normals, 0f)
        assertArrayEquals(a.colors, b.colors, 0f)
    }

    @Test
    fun `the light tier is a quarter of the full tier with the same silhouette`() {
        val full = HeroTerrainSpec.Full
        val light = HeroTerrainSpec.Light
        assertEquals(full.columns * full.rows, light.columns * light.rows * 4)
        assertEquals(full.width, light.width, 0f)
        assertEquals(full.period, light.period, 0f)
        assertEquals(full.length, light.length, 0f)
        assertEquals(17_920, buildHeroTerrain(full).triangleCount)
        assertEquals(4_480, buildHeroTerrain(light).triangleCount)
        // Both strips are long enough that the far end sits past the fog cut-off.
        assertTrue(abs(full.zFar) > 80f)
    }
}
