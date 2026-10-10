package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rerun v2's dense cloud, tier B: the SVPC codec of `dense/points.bin`, the raw-depth
 * back-projection and the 2 cm voxel fusion — pure Kotlin, no ARCore, no Filament.
 */
class DenseCloudTest {
    // ── SVPC codec ────────────────────────────────────────────────────────────

    @Test
    fun `an SVPC blob is a 32-byte header then twelve bytes a point`() {
        val cloud = randomCloud(1_000)
        val blob = SvpcCodec.encode(cloud)
        assertEquals(32 + 12 * 1_000, blob.size)
        assertArrayEquals("SVPC".toByteArray(), blob.copyOf(4))
        val header = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        header.int
        assertEquals(1, header.int) // version
        assertEquals(1_000, header.int) // count
        assertEquals(SvpcCodec.FLAG_NORMALS or SvpcCodec.FLAG_CONFIDENCE, header.int)
        header.position(28)
        assertEquals(0.001f, header.float) // a room-sized cloud: millimetres
    }

    @Test
    fun `an SVPC blob round-trips positions to the millimetre, colours and confidences exactly`() {
        val cloud = randomCloud(5_000)
        val back = SvpcCodec.decode(SvpcCodec.encode(cloud))!!
        assertEquals(cloud.count, back.count)
        for (i in 0 until cloud.positions.size) {
            assertEquals(cloud.positions[i], back.positions[i], 0.0005f + 1e-5f)
        }
        assertArrayEquals(cloud.colors, back.colors)
        assertArrayEquals(cloud.confidences, back.confidences)
        val normals = back.normals!!
        for (i in 0 until cloud.count) {
            val dot = (0 until 3).sumOf { cloud.normals!![i * 3 + it].toDouble() * normals[i * 3 + it] }
            assertTrue("normal $i off by more than ~2.5°: dot $dot", dot > 0.999)
            val length = sqrt((0 until 3).sumOf { normals[i * 3 + it].toDouble() * normals[i * 3 + it] })
            assertEquals(1.0, length, 1e-5)
        }
    }

    @Test
    fun `encoding is deterministic, and a wider cloud than 65 m trades millimetres for range`() {
        val cloud = randomCloud(200)
        assertArrayEquals(SvpcCodec.encode(cloud), SvpcCodec.encode(cloud))

        val wide = DenseCloud(floatArrayOf(-50f, 0f, 0f, 50f, 1f, 2f), intArrayOf(0xFFFF0000.toInt(), 0))
        val blob = SvpcCodec.encode(wide)
        val scale = ByteBuffer.wrap(blob, 28, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(50f / 32_767, scale, 1e-7f)
        val back = SvpcCodec.decode(blob)!!
        for (i in 0 until 6) assertEquals(wide.positions[i], back.positions[i], scale)
        // No normals, no confidence: nine bytes a point, and none read back.
        assertEquals(32 + 2 * 9, blob.size)
        assertNull(back.normals)
        assertNull(back.confidences)
        // SVPC has no "no colour": a point never coloured reads back black, so fusion colours all.
        assertEquals(0xFF000000.toInt(), back.colors[1])
    }

    @Test
    fun `a blob that is not SVPC v1, or is cut short, does not decode`() {
        val blob = SvpcCodec.encode(randomCloud(10))
        assertNotNull(SvpcCodec.decode(blob))
        assertNull(SvpcCodec.decode(blob.copyOf().also { it[0] = 'X'.code.toByte() }))
        assertNull(SvpcCodec.decode(blob.copyOf().also { it[4] = 2 }))
        assertNull(SvpcCodec.decode(blob.copyOf(blob.size - 1)))
        assertNull(SvpcCodec.decode(blob.copyOf(20)))
        assertNull(SvpcCodec.decode(ByteArray(0)))
        // Decoded in place, from inside a larger archive.
        val archive = ByteArray(7) + blob + ByteArray(5)
        assertEquals(10, SvpcCodec.decode(archive, 7, blob.size)!!.count)
    }

    @Test
    fun `an empty cloud is a header only`() {
        val blob = SvpcCodec.encode(DenseCloud.Empty)
        assertEquals(32, blob.size)
        assertEquals(0, SvpcCodec.decode(blob)!!.count)
    }

    // ── Back-projection ───────────────────────────────────────────────────────

    @Test
    fun `a wall two metres ahead back-projects through the lens, facing the camera`() {
        val frame = frame(depthMm = { _, _ -> 2_000 })
        val samples = DepthBackProjection.project(frame)
        assertEquals(W * H, samples.count)
        // Pixel (0, 0): x = (0 - cx) d / fx, y = -(0 - cy) d / fy, z = -d.
        assertEquals(-2f, samples.positions[0], 1e-5f)
        assertEquals(1.5f, samples.positions[1], 1e-5f)
        assertEquals(-2f, samples.positions[2], 1e-5f)
        for (i in 0 until samples.count) {
            assertEquals(-2f, samples.positions[i * 3 + 2], 1e-5f)
            assertEquals(0f, samples.normals[i * 3], 1e-5f)
            assertEquals(0f, samples.normals[i * 3 + 1], 1e-5f)
            assertEquals(1f, samples.normals[i * 3 + 2], 1e-5f)
            assertEquals(COLOR, samples.colors[i])
        }
    }

    @Test
    fun `the camera pose moves the points and turns the normals into world space`() {
        // Half a turn about Y, one metre up: the wall is now behind the start, facing it.
        val pose = DebugPose(0f, 1f, 0f, 0f, 1f, 0f, 0f)
        val samples = DepthBackProjection.project(frame(depthMm = { _, _ -> 2_000 }, pose = pose))
        val centre = (CY.toInt() * W + CX.toInt()) * 3
        assertEquals(0f, samples.positions[centre], 1e-5f)
        assertEquals(1f, samples.positions[centre + 1], 1e-5f)
        assertEquals(2f, samples.positions[centre + 2], 1e-5f)
        assertEquals(-1f, samples.normals[centre + 2], 1e-5f)
    }

    @Test
    fun `low confidence, out-of-range depth and a flying pixel across a depth jump are dropped`() {
        val frame = frame(
            depthMm = { x, y ->
                when {
                    x == 0 && y == 0 -> 0 // no depth
                    x == 1 && y == 0 -> 6_000 // past 5 m
                    x == 4 && y == 3 -> 3_000 // alone a metre behind its neighbours
                    else -> 2_000
                }
            },
            confidence = { x, y -> if (x == 7 && y == 5) 127 else 128 },
        )
        val samples = DepthBackProjection.project(frame)
        assertEquals(W * H - 4, samples.count)
        for (i in 0 until samples.count) assertEquals(-2f, samples.positions[i * 3 + 2], 1e-5f)
        // Without a confidence image every depth counts.
        assertEquals(W * H, DepthBackProjection.project(frame(depthMm = { _, _ -> 2_000 }, confidence = null)).count)
    }

    // ── Fusion ────────────────────────────────────────────────────────────────

    @Test
    fun `samples in one 2 cm voxel fuse into one surfel, averaged`() {
        val fusion = DenseFusion()
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val positions = floatArrayOf(0.001f, 0.001f, 0.001f, 0.011f, 0.009f, 0.005f)
        val stats = fusion.add(samples(positions, intArrayOf(red, blue)))
        assertEquals(DenseFuseStats(added = 1, kept = 2, total = 1, points = 0), stats)
        val cloud = fusion.cloud()
        assertEquals(1, cloud.count)
        assertEquals(0.006f, cloud.positions[0], 1e-6f)
        assertEquals(0.005f, cloud.positions[1], 1e-6f)
        assertEquals(0.003f, cloud.positions[2], 1e-6f)
        assertEquals(0xFF80_0080.toInt(), cloud.colors[0])

        // The next voxel over is another surfel; seen again, nothing new.
        assertEquals(1, fusion.add(samples(floatArrayOf(0.021f, 0f, 0f), intArrayOf(red))).added)
        assertEquals(0, fusion.add(samples(floatArrayOf(0.022f, 0.001f, 0f), intArrayOf(red))).added)
        assertEquals(2, fusion.count)
    }

    /** [n] voxels in a row along x, [first] being the first one's rank. */
    private fun row(first: Int, n: Int) = samples(
        FloatArray(n * 3) { if (it % 3 == 0) (first + it / 3) * 0.1f else 0f },
        IntArray(n) { COLOR },
    )

    @Test
    fun `a scan counts the points it will save, and no others`() {
        // The Pixel 4a's scan: 32 k "points" on the HUD, 5.6 k in the session it saved. The HUD
        // counted every voxel held; the file keeps those two depth frames saw.
        val fusion = DenseFusion()
        val once = fusion.add(row(0, 10))
        assertEquals(10, once.added)
        assertEquals(10, once.total)
        assertEquals("seen by one frame: not a point yet", 0, once.points)
        // The next frame sees four of them again, and six new ones.
        val twice = fusion.add(row(6, 10))
        assertEquals(16, twice.total)
        assertEquals(4, twice.points)
        assertEquals(4, fusion.points)
        assertEquals(fusion.points, fusion.cloud(minViews = DenseFusion.MIN_VIEWS).count)
        // Seen a third time, a point is still one point.
        assertEquals(4, fusion.add(row(6, 4)).points)
    }

    @Test
    fun `the limit counts the points a scan saves`() {
        val capped = DenseFusion(maxPoints = 3)
        // Ten voxels seen once are no point: the limit is not reached, all are held.
        assertEquals(DenseFuseStats(added = 10, kept = 10, total = 10, points = 0), capped.add(row(0, 10)))
        // Seen again, the first three fill the scan; the seven others stay what they were.
        val full = capped.add(row(0, 10))
        assertEquals(3, full.points)
        assertEquals(3, capped.cloud(minViews = DenseFusion.MIN_VIEWS).count)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0.1f, 0f, 0f), capped.cloud(limit = 2).positions, 1e-6f)
        // Full: nothing new is taken, and no later view makes a fourth point.
        val after = capped.add(row(0, 20))
        assertEquals(0, after.added)
        assertEquals(3, after.points)
        assertEquals(3, capped.cloud(minViews = DenseFusion.MIN_VIEWS).count)
    }

    @Test
    fun `voxels seen once make room when the map is full, points never do`() {
        val fusion = DenseFusion(maxPoints = 100, maxVoxels = 20)
        // Eight points, then twelve voxels one frame alone sees: the map holds all it can.
        fusion.add(row(0, 8))
        fusion.add(row(0, 8))
        fusion.add(row(100, 12))
        assertEquals(20, fusion.count)
        // A frame later there is no room, and nothing is old enough to go.
        assertEquals(0, fusion.add(row(200, 5)).added)
        // Long after, the twelve are noise: they go, and the new voxels are taken in.
        repeat(DenseFusion.STALE_VIEWS) { fusion.add(row(0, 8)) }
        val later = fusion.add(row(200, 5))
        assertEquals(5, later.added)
        assertEquals(13, later.total)
        assertEquals(8, later.points)
        // The points are untouched, in their order, and still found where they were.
        val cloud = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
        assertEquals(8, cloud.count)
        for (i in 0 until 8) assertEquals(i * 0.1f, cloud.positions[i * 3], 1e-6f)
        assertEquals(0, fusion.add(row(0, 8)).added)
        // And the newcomers become points as any voxel does.
        assertEquals(13, fusion.add(row(200, 5)).points)
    }

    @Test
    fun `the map survives rehashing`() {

        val big = DenseFusion()
        val n = 50_000
        val grid = FloatArray(n * 3) { i ->
            when (i % 3) {
                0 -> (i / 3 % 250) * 0.02f + 0.01f
                1 -> (i / 3 / 250) * 0.02f + 0.01f
                else -> -1f
            }
        }
        assertEquals(n, big.add(samples(grid, IntArray(n) { COLOR })).added)
        assertEquals(0, big.add(samples(grid, IntArray(n) { COLOR })).added)
        assertEquals(n, big.count)
    }

    @Test
    fun `back-projected frames fuse into a cloud that round-trips through SVPC`() {
        val fusion = DenseFusion()
        fusion.add(DepthBackProjection.project(frame(depthMm = { _, _ -> 2_000 })))
        fusion.add(DepthBackProjection.project(frame(depthMm = { _, _ -> 2_000 }, pose = DebugPose(0.005f, 0f, 0f))))
        val cloud = fusion.cloud()
        assertTrue(cloud.count in 1..W * H)
        val back = SvpcCodec.decode(SvpcCodec.encode(cloud))!!
        assertEquals(cloud.count, back.count)
        assertTrue(back.confidences!!.all { (it.toInt() and 0xFF) >= DepthBackProjection.MIN_CONFIDENCE })
    }

    // ── Replay surfels ────────────────────────────────────────────────────────

    @Test
    fun `each surfel is a quad in the plane of its normal, coloured by its own texel`() {
        val cloud = DenseCloud(
            floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f),
            intArrayOf(COLOR, 0),
            normals = floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f),
        )
        val mesh = DenseSurfels.mesh(cloud, 0.02f)
        assertEquals(8, mesh.vertexCount)
        assertEquals(12, mesh.indexCount)
        // The first quad lies in z = 0, its corners 0.013 m out: 1.3 voxels wide.
        for (v in 0 until 4) {
            assertEquals(0f, mesh.positions[v * 3 + 2], 1e-6f)
            assertEquals(0.013f, abs(mesh.positions[v * 3]), 1e-6f)
            assertEquals(DenseSurfels.uvOf(0).first, mesh.uvs[v * 2], 0f)
        }
        // The second, floor-like, lies in y = 1.
        for (v in 4 until 8) assertEquals(1f, mesh.positions[v * 3 + 1], 1e-6f)
        val atlas = DenseSurfels.atlas(cloud, fallback = 0xFF102030.toInt())
        assertEquals(DenseSurfels.ATLAS_SIZE * DenseSurfels.ATLAS_SIZE * 4, atlas.size)
        assertEquals(listOf(0x40, 0x80, 0xC0, 0xFF), (0 until 4).map { atlas[it].toInt() and 0xFF })
        assertEquals(listOf(0x10, 0x20, 0x30, 0xFF), (4 until 8).map { atlas[it].toInt() and 0xFF })
    }

    private companion object {
        const val W = 8
        const val H = 6
        const val CX = 4f
        const val CY = 3f
        const val F = 4f
        val COLOR = 0xFF4080C0.toInt()

        fun frame(
            depthMm: (Int, Int) -> Int,
            confidence: ((Int, Int) -> Int)? = { _, _ -> 255 },
            pose: DebugPose = DebugPose(0f, 0f, 0f),
        ) = DepthFrame(
            width = W,
            height = H,
            depthMm = ShortArray(W * H) { depthMm(it % W, it / W).toShort() },
            confidence = confidence?.let { c -> ByteArray(W * H) { c(it % W, it / W).toByte() } },
            colors = IntArray(W * H) { COLOR },
            fx = F,
            fy = F,
            cx = CX,
            cy = CY,
            pose = pose,
        )

        fun samples(positions: FloatArray, colors: IntArray) = DenseSamples(
            count = colors.size,
            positions = positions,
            normals = FloatArray(positions.size) { if (it % 3 == 2) 1f else 0f },
            colors = colors,
            confidences = ByteArray(colors.size) { 200.toByte() },
        )

        fun randomCloud(n: Int, seed: Int = 7): DenseCloud {
            val random = Random(seed)
            val normals = FloatArray(n * 3)
            for (i in 0 until n) {
                val x = random.nextFloat() * 2 - 1
                val y = random.nextFloat() * 2 - 1
                val z = random.nextFloat() * 2 - 1
                val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-3f)
                normals[i * 3] = x / l
                normals[i * 3 + 1] = y / l
                normals[i * 3 + 2] = z / l
            }
            return DenseCloud(
                positions = FloatArray(n * 3) { random.nextFloat() * 10f - 5f },
                colors = IntArray(n) { (0xFF shl 24) or random.nextInt(0x1000000) },
                normals = normals,
                confidences = ByteArray(n) { random.nextInt(128, 256).toByte() },
            )
        }
    }
}
