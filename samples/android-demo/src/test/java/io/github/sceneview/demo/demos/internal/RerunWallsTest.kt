package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * A room's walls survive fusion and meshing (#4719). A 6 × 5 m room scanned from three stops, its
 * depth as noisy as ARCore's far away (σ about 1 % of d²: 4 cm at 2 m, 16 cm at 4 m): the noise's
 * faint sheets used to fill the triangle cap before the walls scanned last were meshed, and about
 * a quarter of the walls went missing.
 */
class RerunWallsTest {
    private val min = Vec3(-3f + OFF, OFF, -2.5f + OFF)
    private val max = Vec3(3f + OFF, 2.7f + OFF, 2.5f + OFF)
    private val scene = SyntheticScene(
        listOf(SyntheticShape.Room(min, max) { _, y, _ -> if (y < 0.02f) FLOOR else WALL }),
    )

    /** Three stops, a full turn at each, looking down, level and up; depth 160 × 120 like ARCore's raw depth. */
    private fun frames(noiseK: Float, seed: Int): List<DepthFrame> {
        val random = Random(seed)
        val out = ArrayList<DepthFrame>()
        val stops = listOf(Vec3(-1.2f, 1.4f, 0f), Vec3(0f, 1.4f, 0f), Vec3(1.2f, 1.4f, 0.5f))
        for (stop in stops) for (pitch in listOf(-30.0, -5.0, 20.0)) for (step in 0 until 36) {
            val pose = RerunSyntheticRoom.lookPose(stop, Math.toRadians(step * 10.0).toFloat(), Math.toRadians(pitch).toFloat())
            val clean = scene.render(pose, width = 160, height = 120)
            val depth = clean.depthMm.copyOf()
            val confidence = ByteArray(depth.size)
            for (p in depth.indices) {
                val d = (depth[p].toInt() and 0xFFFF) / 1000f
                if (d == 0f) continue
                // About a unit normal deviate: three uniforms summed.
                val g = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) * 2f
                depth[p] = ((d + g * noiseK * d * d) * 1000f).toInt().coerceIn(1, Short.MAX_VALUE.toInt()).toShort()
                confidence[p] = CONFIDENCE.toByte()
            }
            out += DepthFrame(clean.width, clean.height, depth, confidence, clean.colors, clean.fx, clean.fy, clean.cx, clean.cy, pose)
        }
        return out
    }

    private class Cover(val walls: Float, val grey: Float) {
        override fun toString() = "walls ${"%.2f".format(walls)} grey ${"%.3f".format(grey)}"
    }

    /** Share of the four walls' 10 cm cells holding a vertex, and the share of all vertices no colour reached. */
    private fun cover(mesh: RerunMesh): Cover {
        val cells = HashSet<Long>()
        var grey = 0
        for (v in 0 until mesh.vertexCount) {
            if (mesh.colors[v] == RerunMarchingCubes.NO_COLOR) grey++
            val x = mesh.positions[v * 3]
            val y = mesh.positions[v * 3 + 1]
            val z = mesh.positions[v * 3 + 2]
            if (y < 0.1f || y > 2.6f) continue
            val wall = when {
                abs(x - min.x) < NEAR_WALL_M -> 0
                abs(x - max.x) < NEAR_WALL_M -> 1
                abs(z - min.z) < NEAR_WALL_M -> 2
                abs(z - max.z) < NEAR_WALL_M -> 3
                else -> continue
            }
            val u = if (wall < 2) z else x
            cells += wall * 1_000_000L + ((u + 4f) / 0.1f).toLong() * 1000 + (y / 0.1f).toLong()
        }
        val total = 2 * (50 * 25) + 2 * (60 * 25)
        return Cover(minOf(1f, cells.size / total.toFloat()), if (mesh.vertexCount == 0) 0f else grey / mesh.vertexCount.toFloat())
    }

    private fun fused(noiseK: Float, seed: Int) = RerunTsdf().also { tsdf -> frames(noiseK, seed).forEach { tsdf.integrate(it) } }

    @Test
    fun `a noisy room keeps all four walls`() {
        val tsdf = fused(NOISY, 3)
        // What the result used to show: one pass, cut at the cap in the order the room was scanned.
        val before = RerunMarchingCubes.extract(tsdf, minWeight = 1f)
        val after = RerunMarchingCubes.extractWhole(tsdf, minWeight = 1f)
        println(
            "noisy before: ${before.mesh.triangleCount} tris ${cover(before.mesh)} capped ${before.capped}; " +
                "after: ${after.mesh.triangleCount} tris ${cover(after.mesh)} capped ${after.capped}",
        )
        assertTrue("the old pass reached the cap", before.capped)
        assertFalse("still capped", after.capped)
        assertTrue("walls ${cover(after.mesh)}", cover(after.mesh).walls >= 0.97f)
    }

    @Test
    fun `a clean room is meshed at full detail, in one pass`() {
        val tsdf = fused(0f, 1)
        val one = RerunMarchingCubes.extract(tsdf, minWeight = 1f)
        val whole = RerunMarchingCubes.extractWhole(tsdf, minWeight = 1f)
        println("clean: ${whole.mesh.triangleCount} tris ${cover(whole.mesh)}")
        assertFalse(one.capped)
        assertTrue(whole.mesh.triangleCount == one.mesh.triangleCount)
        assertTrue("walls ${cover(whole.mesh)}", cover(whole.mesh).walls >= 0.97f)
    }

    @Test
    fun `a coarser stride meshes the same room in fewer triangles`() {
        val tsdf = fused(0f, 1)
        val fine = RerunMarchingCubes.extract(tsdf, minWeight = 1f)
        val coarse = RerunMarchingCubes.extract(tsdf, minWeight = 1f, stride = 2)
        assertTrue(coarse.mesh.triangleCount < fine.mesh.triangleCount / 3)
        assertTrue("walls ${cover(coarse.mesh)}", cover(coarse.mesh).walls >= 0.97f)
    }

    private companion object {
        const val NOISY = 0.01f
        const val OFF = 0.0137f
        const val CONFIDENCE = 200
        const val NEAR_WALL_M = 0.15f
        const val FLOOR = 0xFF806040.toInt()
        const val WALL = 0xFFE0E0D8.toInt()
    }
}
