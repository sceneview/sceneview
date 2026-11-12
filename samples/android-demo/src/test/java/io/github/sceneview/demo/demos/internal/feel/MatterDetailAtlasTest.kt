package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.floor

class MatterDetailAtlasTest {
    private val materials = listOf(File("src/main/materials"),
        File("samples/android-demo/src/main/materials")).first { it.isDirectory }

    private fun sample(pixels: ByteArray, u: Float, v: Float, channel: Int = 0): Float {
        val x = u * MatterDetailAtlas.WIDTH - .5f
        val y = v * MatterDetailAtlas.HEIGHT - .5f
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        fun texel(dx: Int, dy: Int) = (pixels[((iy + dy) * MatterDetailAtlas.WIDTH + ix + dx) * 2 +
            channel].toInt() and 255) / 255f
        val a = texel(0, 0) * (1f - (x - ix)) + texel(1, 0) * (x - ix)
        val b = texel(0, 1) * (1f - (x - ix)) + texel(1, 1) * (x - ix)
        return a * (1f - (y - iy)) + b * (y - iy)
    }

    @Test fun materialUvConventionSamplesTheIntendedFaceInsteadOfNeighbouringCharts() {
        val pixels = MatterDetailAtlas.bake(FeelMaterial.Marble)
        val point = FloatArray(3)
        val field = MatterDetailAtlas.Field()
        for (name in listOf("soft", "velvet", "leather", "marble", "steel")) {
            val source = File(materials, "matter_$name.mat").readText()
            // Filament 1.77.3 defaults to true. Include that transform in the CPU round trip:
            // v10's geometry-only test missed it, sending the front into two unrelated charts.
            val flipV = Regex("\"flipUV\"\\s*:\\s*(true|false)").find(source)
                ?.groupValues?.get(1)?.toBoolean() ?: true
            for ((face, chart) in MatterDetailAtlas.charts.withIndex()) {
                for (i in 1..15) {
                    val u = i / 16f; val v = .37f
                    chart.point(u, v, point)
                    field.sample(FeelMaterial.Marble, point[0], point[1], point[2])
                    val atlasV = chart.v(v).let { if (flipV) 1f - it else it }
                    assertEquals("$name face=$face u=$u", field.red,
                        sample(pixels, chart.u(u), atlasV), .025f)
                }
            }
        }
    }

    @Test fun bakedMarbleMeetsAcrossAllTwelveRoundedEdges() {
        val pixels = MatterDetailAtlas.bake(FeelMaterial.Marble)
        val aPoint = FloatArray(3); val bPoint = FloatArray(3)
        var edges = 0
        for ((i, a) in MatterDetailAtlas.charts.withIndex()) {
            for (b in MatterDetailAtlas.charts.drop(i + 1)) {
                if (a.axis == b.axis) continue
                edges++
                for (step in 0..64) {
                    val coordinates = FloatArray(3) { step / 64f }
                    coordinates[a.axis] = (a.sign + 1f) / 2f
                    coordinates[b.axis] = (b.sign + 1f) / 2f
                    a.point(coordinates[a.uAxis], coordinates[a.vAxis], aPoint)
                    b.point(coordinates[b.uAxis], coordinates[b.vAxis], bPoint)
                    assertArrayEquals(aPoint, bPoint, .000001f)
                    for (channel in 0..1) assertEquals("edge=$edges step=$step channel=$channel",
                        sample(pixels, a.u(coordinates[a.uAxis]), a.v(coordinates[a.vAxis]), channel),
                        sample(pixels, b.u(coordinates[b.uAxis]), b.v(coordinates[b.vAxis]), channel), .025f)
                }
            }
        }
        assertEquals(12, edges)
    }

    @Test fun cellNeighbourhoodCacheIsIndependentOfSamplingOrder() {
        val reused = MatterDetailAtlas.Field()
        for (kind in listOf(FeelMaterial.MemoryFoam, FeelMaterial.Leather)) {
            for (i in -24..24) {
                val x = i * .1f; val z = i * -.037f
                val fresh = MatterDetailAtlas.Field()
                fresh.sample(kind, x, -.04f, z)
                reused.sample(kind, x, -.04f, z)
                assertEquals(fresh.red, reused.red, 0f)
                // Return to the first point after visiting a different 3D neighbourhood.
                reused.sample(kind, -x, -.7f, -z)
                reused.sample(kind, x, -.04f, z)
                assertEquals(fresh.red, reused.red, 0f)
            }
        }
    }

    @Test fun chartsDoNotOverlapAndSharedEdgesAddressTheSameRestPoint() {
        val occupancy = BooleanArray(MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT)
        for (chart in MatterDetailAtlas.charts) {
            for (y in chart.y until chart.y + chart.height) for (x in chart.x until chart.x + chart.width) {
                val i = y * MatterDetailAtlas.WIDTH + x
                assertFalse(occupancy[i]); occupancy[i] = true
            }
        }
        val top = FloatArray(3); val side = FloatArray(3)
        for (i in 0..100) {
            val u = i / 100f
            MatterDetailAtlas.charts[0].point(u, 0f, top)
            MatterDetailAtlas.charts[2].point(u, 1f, side)
            assertArrayEquals(top, side, .000001f)
        }
    }

    @Test fun leavingTheScreenCancelsBeforeFinishingABake() {
        var rows = 0
        try {
            MatterDetailAtlas.bake(FeelMaterial.MemoryFoam) {
                if (++rows == 3) throw CancellationException()
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(3, rows)
        }
    }

    @Test fun bakedCarraraRetainsBroadHazeWithoutBlackVeins() {
        val pixels = MatterDetailAtlas.bake(FeelMaterial.Marble)
        assertEquals(MatterDetailAtlas.WIDTH * MatterDetailAtlas.HEIGHT * 2, pixels.size)
        val chart = MatterDetailAtlas.charts[0]
        var low = 255; var high = 0
        for (y in 4 until chart.height - 4) for (x in 4 until chart.width - 4) {
            val mineral = pixels[(y * MatterDetailAtlas.WIDTH + x) * 2].toInt() and 255
            low = minOf(low, mineral); high = maxOf(high, mineral)
        }
        assertTrue("Whole slab haze", low >= 25)
        assertTrue("Broad and secondary seams survive the bake", high > 140)
        assertTrue("v9 mineral cap", high <= 184)
    }
}
