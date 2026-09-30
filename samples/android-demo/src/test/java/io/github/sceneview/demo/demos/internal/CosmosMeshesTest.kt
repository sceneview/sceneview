package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CosmosMeshesTest {

    private fun assertWellFormed(mesh: GlowMesh) {
        assertEquals("vertex array is whole vertices", 0, mesh.vertices.size % mesh.stride)
        assertEquals("indices are whole triangles", 0, mesh.indices.size % 3)
        assertTrue("mesh is not empty", mesh.indices.isNotEmpty())
        val count = mesh.vertexCount
        assertTrue("every index is in range", mesh.indices.all { it in 0 until count })
        assertTrue("every value is finite", mesh.vertices.all { it.isFinite() })
        for (v in 0 until count) {
            for (axis in 0 until 3) {
                val p = mesh.vertices[v * mesh.stride + axis]
                assertTrue("vertex inside its bounds", p >= mesh.boundsMin[axis] && p <= mesh.boundsMax[axis])
            }
        }
    }

    @Test
    fun `every scene builds well-formed buffers`() {
        listOf(
            CosmosMeshes.galaxyLayer(0),
            CosmosMeshes.galaxyLayer(CosmosMeshes.GALAXY_LAYERS - 1),
            CosmosMeshes.galaxyDust(),
            CosmosMeshes.starField(),
            CosmosMeshes.burst(),
            CosmosMeshes.burstSparks(),
            CosmosMeshes.burstCore(),
            CosmosMeshes.flowField(),
            CosmosMeshes.flowDust(),
            CosmosMeshes.flowBackdrop(),
            CosmosMeshes.prominences(),
            CosmosMeshes.starHalo(),
            CosmosSystem.ring(),
            CosmosSystem.orbitTrail(),
        ).forEach(::assertWellFormed)
    }

    @Test
    fun `builders are deterministic`() {
        assertArrayEquals(CosmosMeshes.galaxyLayer(3).vertices, CosmosMeshes.galaxyLayer(3).vertices, 0f)
        assertArrayEquals(CosmosMeshes.galaxyDust().vertices, CosmosMeshes.galaxyDust().vertices, 0f)
        assertArrayEquals(CosmosMeshes.burst().vertices, CosmosMeshes.burst().vertices, 0f)
    }

    @Test
    fun `a sprite is four corners around one centre`() {
        val mesh = SpriteBuilder(1).apply { add(1f, 2f, 3f, 1f, 1f, 1f, radius = 0.5f, phase = 0.25f) }.build()
        assertEquals(4, mesh.vertexCount)
        assertEquals(6, mesh.indices.size)
        val corners = (0 until 4).map { v ->
            val o = v * SPRITE_STRIDE
            assertEquals(1f, mesh.vertices[o], 0f)
            assertEquals(2f, mesh.vertices[o + 1], 0f)
            assertEquals(3f, mesh.vertices[o + 2], 0f)
            assertEquals(0.5f, mesh.vertices[o + 9], 0f)
            mesh.vertices[o + 7] to mesh.vertices[o + 8]
        }
        assertEquals(setOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f), corners.toSet())
        assertArrayEquals(floatArrayOf(0.5f, 1.5f, 2.5f), mesh.boundsMin, 1e-6f)
        assertArrayEquals(floatArrayOf(1.5f, 2.5f, 3.5f), mesh.boundsMax, 1e-6f)
    }

    @Test
    fun `a ribbon carries unit tangents, both sides, and arc length`() {
        val mesh = RibbonBuilder(4).apply {
            addCurve(
                points = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 2f, 0f),
                colors = FloatArray(9) { 1f },
                halfWidth = 0.1f,
                seed = 0.5f,
            )
        }.build()
        assertEquals(6, mesh.vertexCount)
        assertEquals(12, mesh.indices.size)
        for (v in 0 until mesh.vertexCount) {
            val o = v * RIBBON_STRIDE
            val t = sqrt(
                mesh.vertices[o + 7] * mesh.vertices[o + 7] +
                    mesh.vertices[o + 8] * mesh.vertices[o + 8] +
                    mesh.vertices[o + 9] * mesh.vertices[o + 9],
            )
            assertEquals("tangent is unit length", 1f, t, 1e-5f)
            assertEquals("sides alternate", if (v % 2 == 0) -1f else 1f, mesh.vertices[o + 10], 0f)
        }
        // Arc length of the last point: 1 + 2.
        assertEquals(3f, mesh.vertices[5 * RIBBON_STRIDE + 14], 1e-5f)
        // t runs 0 → 1 along the curve.
        assertEquals(0f, mesh.vertices[12], 0f)
        assertEquals(1f, mesh.vertices[5 * RIBBON_STRIDE + 12], 0f)
    }

    @Test
    fun `the galaxy is a disc of the advertised radius and star count`() {
        var outside = 0
        var sprites = 0
        repeat(CosmosMeshes.GALAXY_LAYERS) { layer ->
            val mesh = CosmosMeshes.galaxyLayer(layer)
            sprites += mesh.vertexCount / 4
            for (v in 0 until mesh.vertexCount step 4) {
                val o = v * SPRITE_STRIDE
                val r = sqrt(mesh.vertices[o] * mesh.vertices[o] + mesh.vertices[o + 2] * mesh.vertices[o + 2])
                if (r > CosmosMeshes.GALAXY_RADIUS * 1.3f) outside++
                assertTrue("thin disc", abs(mesh.vertices[o + 1]) < 0.25f)
            }
        }
        assertTrue("almost every star inside the disc, got $outside outside", outside < sprites / 100)
        assertTrue("the caption's 60,000 stars are there, got $sprites", sprites >= CosmosMeshes.GALAXY_STARS)
    }

    @Test
    fun `the first galaxy layer alone already spans the whole disc`() {
        val mesh = CosmosMeshes.galaxyLayer(0)
        var reach = 0f
        for (v in 0 until mesh.vertexCount step 4) {
            val o = v * SPRITE_STRIDE
            val x = mesh.vertices[o]
            val z = mesh.vertices[o + 2]
            reach = maxOf(reach, sqrt(x * x + z * z))
        }
        assertTrue("layer 0 reaches the rim, got $reach", reach > 0.9f * CosmosMeshes.GALAXY_RADIUS)
    }

    @Test
    fun `the flow field covers the whole viewport at every aspect and time`() {
        // Phone portrait, tablet portrait, tablet landscape, phone landscape.
        for (aspect in floatArrayOf(0.45f, 0.75f, 1.33f, 2.2f)) {
            for (step in 0..40) {
                val pose = CosmosFraming.pose(CosmosScene.Flow, time = step * 0.5f, aspect = aspect)
                for (sx in floatArrayOf(-1f, 1f)) {
                    for (sy in floatArrayOf(-1f, 1f)) {
                        val hit = CosmosFraming.planeHit(pose, aspect, sx, sy)
                        assertTrue("corner ($sx, $sy) at aspect $aspect looks past the plane", hit != null)
                        hit!!
                        assertTrue(
                            "corner ($sx, $sy) at aspect $aspect lands off the field: ${hit.toList()}",
                            abs(hit[0]) <= CosmosMeshes.FLOW_HALF_WIDTH &&
                                abs(hit[1]) <= CosmosMeshes.FLOW_HALF_HEIGHT,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `flow streamlines stay in the field`() {
        val mesh = CosmosMeshes.flowField()
        assertTrue(mesh.boundsMax[0] <= CosmosMeshes.FLOW_HALF_WIDTH + 0.2f)
        assertTrue(mesh.boundsMax[1] <= CosmosMeshes.FLOW_HALF_HEIGHT + 0.2f)
        assertTrue(mesh.boundsMin[0] >= -CosmosMeshes.FLOW_HALF_WIDTH - 0.2f)
        assertTrue(mesh.boundsMin[1] >= -CosmosMeshes.FLOW_HALF_HEIGHT - 0.2f)
    }

    @Test
    fun `the burst detonates, holds, then fades out before looping`() {
        val start = CosmosMeshes.burstEnvelope(0f)
        val grown = CosmosMeshes.burstEnvelope(0.5f)
        val end = CosmosMeshes.burstEnvelope(0.99f)
        assertEquals("nothing drawn at detonation", 0f, start[0], 1e-6f)
        assertTrue("fully drawn mid-loop", grown[0] >= 1f)
        assertEquals("visible mid-loop", 1f, grown[1], 1e-6f)
        assertEquals("gone before the next loop", 0f, end[1], 1e-6f)
        assertTrue("flash is brightest at detonation", start[2] > grown[2])
    }

    @Test
    fun `framing fits the subject in portrait and landscape`() {
        val portrait = CosmosFraming.fitDistance(1f, 1f, aspect = 0.45f)
        val landscape = CosmosFraming.fitDistance(1f, 1f, aspect = 2f)
        assertTrue("a narrow screen pulls the camera back", portrait > landscape)
        // Landscape: height bound, tan(half vfov) = 12 / 28.
        assertEquals(28f / 12f, landscape, 1e-4f)
        CosmosScene.entries.forEach { scene ->
            val pose = CosmosFraming.pose(scene, time = 3f, aspect = 0.45f)
            assertEquals("eye, target and up", 9, pose.size)
            val distance = sqrt(pose[0] * pose[0] + pose[1] * pose[1] + pose[2] * pose[2])
            assertTrue("$scene camera is outside the subject", distance > 2f)
        }
    }
}
