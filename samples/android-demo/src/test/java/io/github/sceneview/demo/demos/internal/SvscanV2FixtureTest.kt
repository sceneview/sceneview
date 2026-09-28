package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `.svscan` v2 fixtures shared with the iOS demo (`samples/ios-demo/SceneViewDemoTests/
 * RerunDenseCloudTests.swift` reads these very files): the same literal clouds must encode
 * to the same SVPC bytes on both platforms, and a v2 scan written by one must open on the other.
 * The clouds below are repeated verbatim in the Swift test — change both or neither.
 */
class SvscanV2FixtureTest {
    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/rerun/svscan-v2/$name")) { "missing fixture $name" }
            .use { it.readBytes() }

    private val cloud = DenseCloud(
        positions = floatArrayOf(
            0f, 0f, 0f,
            1.25f, -0.5f, 2f,
            -0.123f, 0.456f, -0.789f,
            0.0105f, 1.5f, -2.25f,
            -1f, -1f, 1f,
        ),
        colors = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0, 0xFF808080.toInt()),
        normals = floatArrayOf(
            0f, 1f, 0f,
            0f, 0f, -1f,
            0.6f, 0f, 0.8f,
            -0.267261f, 0.534522f, -0.801784f,
            0.57735f, -0.57735f, 0.57735f,
        ),
        confidences = byteArrayOf(255.toByte(), 128.toByte(), 200.toByte(), 131.toByte(), 255.toByte()),
    )

    /** Wider than ±32.767 m: the scale leaves the millimetre for `half extent / 32767`. */
    private val wide = DenseCloud(
        positions = floatArrayOf(-50f, 0f, 0f, 50f, 1f, -3f, 12.345f, -2.5f, 7.5f),
        colors = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt(), 0),
    )

    @Test
    fun `the shared cloud encodes to the fixture's bytes`() {
        assertArrayEquals(fixture("points.bin"), SvpcCodec.encode(cloud))
    }

    @Test
    fun `a cloud past 32 metres encodes to the fixture's bytes, scale and all`() {
        val bytes = fixture("points-wide.bin")
        assertArrayEquals(bytes, SvpcCodec.encode(wide))
        val back = SvpcCodec.decode(bytes)!!
        for (i in 0 until wide.count * 3) assertEquals(wide.positions[i], back.positions[i], 50f / 32_767f)
    }

    @Test
    fun `the fixture decodes to the shared cloud, within the codec's precision`() {
        val back = SvpcCodec.decode(fixture("points.bin"))!!
        assertEquals(cloud.count, back.count)
        for (i in 0 until cloud.count * 3) assertEquals(cloud.positions[i], back.positions[i], 0.0005f)
        // A missing colour (0) is written black, and reads back opaque black.
        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt()),
            back.colors,
        )
        assertArrayEquals(cloud.confidences, back.confidences)
        for (i in 0 until cloud.count * 3) assertEquals(cloud.normals!![i], back.normals!![i], 0.02f)
    }

    @Test
    fun `the shared scan file is the stored zip of its three payloads`() {
        val pack = RerunCapturePack(
            manifest = fixture("dense-v2-manifest.json"),
            log = fixture("dense-v2-log.jsonl"),
            media = fixture("points.bin"),
        )
        assertArrayEquals(fixture("dense-v2.svscan"), RerunScanFile.write(pack))
    }

    @Test
    fun `the shared scan file opens with its device, dense cloud and dense timeline`() {
        val pack = RerunScanFile.read(fixture("dense-v2.svscan"))!!
        val opened = pack.open()!!
        val manifest = opened.manifest
        assertEquals(2, manifest.version)
        assertEquals(ScanDevice("android", "Pixel 8 Pro", "depth", "arcore_raw_depth"), manifest.device)
        val dense = manifest.dense!!
        assertEquals(ReplayDense.PATH, dense.path)
        assertEquals(5, dense.count)
        assertEquals(0.02f, dense.voxelM)
        assertEquals(true, dense.normals)
        assertArrayEquals(floatArrayOf(-1f, -1f, -2.25f, 1.25f, 1.5f, 2f), dense.bounds, 0f)
        assertEquals(42L, manifest.denseMs)
        val back = SvpcCodec.decode(opened.bytesOf(dense.path)!!)!!
        assertEquals(dense.count, back.count)
        assertEquals(0, opened.trace.denseCountAt(0f))
        assertEquals(3, opened.trace.denseCountAt(0.3f))
        assertEquals(5, opened.trace.denseCountAt(0.6f))
    }
}
