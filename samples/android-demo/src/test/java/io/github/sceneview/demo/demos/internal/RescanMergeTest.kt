package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [RescanMerge] (#4736): a lined-up re-scan only adds where the scan has nothing, inside the parts
 * it was for, and the scan's capture is written again around the merged cloud, the rest untouched.
 */
class RescanMergeTest {

    @Test
    fun `a re-scan adds only inside the open part, where the scan is empty`() {
        val merged = RescanMerge.cloud(SCAN, POSE.inverse().moved(FULL), POSE, listOf(HOLE))
        assertTrue("the hole gets points", merged.count > SCAN.count)
        for (i in 0 until SCAN.count * 3) assertEquals(SCAN.positions[i], merged.positions[i], 0f)
        for (i in SCAN.count until merged.count) {
            val x = merged.positions[i * 3]
            val z = merged.positions[i * 3 + 2]
            assertTrue("($x, $z) inside the open part", RescanRegistrar.inside(HOLE, x, 0f, z, RescanRegistrar.OPEN_MARGIN_M))
            assertTrue("($x, $z) away from the scan's points", nearestScan(x, z) > DenseFusion.VOXEL_M)
        }
        val inHole = (SCAN.count until merged.count).count { i ->
            val x = merged.positions[i * 3]
            val z = merged.positions[i * 3 + 2]
            x in HOLE[0]..HOLE[3] && z in HOLE[2]..HOLE[5]
        }
        assertTrue("most of the hole is filled: $inHole", inHole >= HOLE_POINTS * 3 / 5)
    }

    @Test
    fun `nothing open, or nothing re-scanned, leaves the scan as it was`() {
        assertSame(SCAN, RescanMerge.cloud(SCAN, FULL, RescanPose.Identity, emptyList()))
        assertSame(SCAN, RescanMerge.cloud(SCAN, DenseCloud.Empty, RescanPose.Identity, listOf(HOLE)))
        val far = floatArrayOf(5f, -0.1f, 5f, 6f, 0.1f, 6f)
        assertSame(SCAN, RescanMerge.cloud(SCAN, FULL, RescanPose.Identity, listOf(far)))
    }

    @Test
    fun `attributes one side lacks are filled in, one per point`() {
        val bare = DenseCloud(FULL.positions, FULL.colors)
        val merged = RescanMerge.cloud(SCAN, bare, RescanPose.Identity, listOf(HOLE))
        val added = merged.count - SCAN.count
        assertTrue(added > 0)
        val normals = assertNotNull(merged.normals)
        val confidences = assertNotNull(merged.confidences)
        val levels = assertNotNull(merged.levels)
        for (k in 0 until added) {
            val i = SCAN.count + k
            assertEquals(0f, normals[i * 3 + 1], 0f)
            assertEquals(FULL_CONFIDENCE, confidences[i].toInt() and 0xFF)
            assertEquals(0, levels[i].toInt())
        }
        for (i in 0 until SCAN.count) assertEquals(SCAN.confidences!![i], confidences[i])
    }

    @Test
    fun `a coarse scan point keeps the re-scan out of its whole surfel`() {
        val coarse = DenseCloud(
            positions = floatArrayOf(0.21f, 0f, 0.21f),
            colors = intArrayOf(GREY),
            levels = byteArrayOf(2),
        )
        val probe = DenseCloud(floatArrayOf(0.27f, 0f, 0.27f), intArrayOf(GREY))
        assertSame(coarse, RescanMerge.cloud(coarse, probe, RescanPose.Identity, listOf(HOLE)))
    }

    @Test
    fun `the merged capture opens with the new cloud and everything else kept`() {
        val original = capture(SCAN)
        val merged = RescanMerge.cloud(SCAN, POSE.inverse().moved(FULL), POSE, listOf(HOLE))
        val pack = assertNotNull(RescanMerge.pack(original, merged))
        assertArrayEquals(original.log, pack.log)
        val opened = assertNotNull(pack.open())
        val dense = assertNotNull(opened.manifest.dense)
        assertEquals(merged.count, dense.count)
        assertEquals(ReplayDense.PATH, dense.path)
        assertTrue(dense.normals)
        assertArrayEquals(merged.bounds(), dense.bounds, 0f)
        for ((path, bytes) in PHOTOS) assertArrayEquals(path, bytes, opened.bytesOf(path))
        val blob = assertNotNull(opened.bytesOf(ReplayDense.PATH))
        val decoded = assertNotNull(SvpcCodec.decode(blob))
        assertEquals(merged.count, decoded.count)
        for (i in 0 until merged.count * 3) assertEquals(merged.positions[i], decoded.positions[i], CODEC_M)
        val before = assertNotNull(ReplayManifest.parse(String(original.manifest)))
        assertEquals(before.frameCount, opened.manifest.frameCount)
        assertEquals(before.version, opened.manifest.version)
        assertEquals(before.denseMs, opened.manifest.denseMs)
        assertEquals(before.floorY, opened.manifest.floorY)
    }

    @Test
    fun `a capture without a dense cloud is not merged`() {
        val (archive, spans) = ScanArchive.pack(PHOTOS)
        val manifest = ReplayManifest(ReplayLens.Default, FRAME_RATE, 2, 0f, emptyList(), spans, version = 2).toJson()
        assertNull(RescanMerge.pack(RerunCapturePack(manifest.toByteArray(), LOG, archive), SCAN))
        assertNull(RescanMerge.pack(RerunCapturePack("not json".toByteArray(), LOG, archive), SCAN))
    }

    private fun nearestScan(x: Float, z: Float): Float {
        var best = Float.MAX_VALUE
        for (i in 0 until SCAN.count) {
            val d = maxOf(abs(SCAN.positions[i * 3] - x), abs(SCAN.positions[i * 3 + 2] - z))
            if (d < best) best = d
        }
        return best
    }

    private fun <T> assertNotNull(value: T?): T {
        org.junit.Assert.assertNotNull(value)
        return value!!
    }

    companion object {
        private const val GREY = 0x808080
        private const val STEP_M = 0.02f
        private const val FULL_CONFIDENCE = 255
        private const val FRAME_RATE = 30f
        private const val CODEC_M = 0.005f
        private val POSE = RescanPose(Math.toRadians(20.0).toFloat(), 0.7f, 0.1f, -0.4f)

        /** The open part: a 40 cm square of floor the scan missed. */
        private val HOLE = floatArrayOf(0f, -0.05f, 0f, 0.4f, 0.05f, 0.4f)
        private val HOLE_POINTS = (0.4f / STEP_M).toInt().let { it * it }

        /** A 2 × 2 m floor, a point in the middle of each 2 cm voxel. */
        private val FULL = floorGrid { _, _ -> true }

        /** The same floor with [HOLE] missing. */
        private val SCAN = floorGrid { x, z -> !(x in HOLE[0]..HOLE[3] && z in HOLE[2]..HOLE[5]) }

        private val LOG = "{\"type\":\"session\"}\n".toByteArray()
        private val PHOTOS = listOf(
            "frames/0.jpg" to ByteArray(40) { it.toByte() },
            "textures/plane-1.png" to ByteArray(24) { (it * 3).toByte() },
        )

        private fun floorGrid(keep: (Float, Float) -> Boolean): DenseCloud {
            val positions = ArrayList<Float>()
            val steps = (2f / STEP_M).toInt()
            for (i in 0 until steps) for (j in 0 until steps) {
                val x = -1f + (i + 0.5f) * STEP_M
                val z = -1f + (j + 0.5f) * STEP_M
                if (!keep(x, z)) continue
                positions += x
                positions += 0f
                positions += z
            }
            val count = positions.size / 3
            return DenseCloud(
                positions = positions.toFloatArray(),
                colors = IntArray(count) { GREY },
                normals = FloatArray(count * 3) { if (it % 3 == 1) 1f else 0f },
                confidences = ByteArray(count) { 200.toByte() },
                levels = ByteArray(count),
            )
        }

        /** [cloud] as a recorded capture holds it: its dense cloud beside two photos. */
        fun capture(cloud: DenseCloud): RerunCapturePack {
            val (archive, spans) = ScanArchive.pack(PHOTOS + (ReplayDense.PATH to SvpcCodec.encode(cloud)))
            val manifest = ReplayManifest(
                lens = ReplayLens.Default,
                frameRate = FRAME_RATE,
                frameCount = 2,
                floorY = 0f,
                textures = emptyList(),
                media = spans,
                version = 2,
                dense = ReplayDense(ReplayDense.PATH, cloud.count, DenseFusion.VOXEL_M, true, cloud.bounds()),
                denseMs = 1234L,
            ).toJson()
            return RerunCapturePack(manifest.toByteArray(), LOG, archive)
        }
    }
}
