package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

class ScanPhotoPolicyTest {
    private val portrait = ReplayLens(0.6f, 0.8f)

    @Test
    fun `anchors preserve portrait and landscape framing without upscaling sources`() {
        assertEquals(720 to 960, ScanPhotoPolicy.targetSize(0, portrait, 1920, 1440))
        assertEquals(960 to 720, ScanPhotoPolicy.targetSize(20, ReplayLens(0.8f, 0.6f), 1920, 1440))
        assertEquals(480 to 640, ScanPhotoPolicy.targetSize(0, portrait, 640, 480))
        assertEquals(240 to 320, ScanPhotoPolicy.targetSize(1, portrait, 1920, 1440))
    }

    @Test
    fun `every prefix of 300 photos fits the scan budget even for noisy encodings`() {
        var bytes = 0
        var anchors = 0
        repeat(KeyframeGate.MAX_PHOTOS) { index ->
            if (ScanPhotoPolicy.isAnchor(index)) anchors++
            val encoded = ScanPhotoPolicy.encode(index, ScanPhotoPolicy.targetSize(index, portrait, 1920, 1440)) {
                    w, h, quality -> ByteArray(w * h * quality / 100)
            }!!
            assertTrue(encoded.bytes.size <= ScanPhotoPolicy.byteLimit(index))
            bytes += encoded.bytes.size
            assertTrue(bytes <= ScanPhotoPolicy.PHOTO_BUDGET_BYTES)
        }
        assertEquals(15, anchors)
        assertEquals(ScanPhotoPolicy.PHOTO_BUDGET_BYTES,
            (0 until KeyframeGate.MAX_PHOTOS).sumOf { ScanPhotoPolicy.byteLimit(it) })
    }

    @Test
    fun `encoding reduces quality before resolution and rejects an impossible payload`() {
        val attempts = ArrayList<Triple<Int, Int, Int>>()
        val result = ScanPhotoPolicy.encode(0, 720 to 960) { w, h, quality ->
            attempts += Triple(w, h, quality)
            ByteArray(if (quality == 75) 100 else ScanPhotoPolicy.ANCHOR_BYTES + 1)
        }!!
        assertEquals(listOf(Triple(720, 960, 85), Triple(720, 960, 75)), attempts)
        assertEquals(720, result.width)
        assertNull(ScanPhotoPolicy.encode(0, 1 to 1) { _, _, _ -> ByteArray(ScanPhotoPolicy.ANCHOR_BYTES + 1) })
        assertNull(ScanPhotoPolicy.encode(0, 720 to 960) { _, _, _ -> null })
    }

    @Test
    fun `thumbnail decoding retains old dimensions and bounds anchors`() {
        assertEquals(2, ScanPhotoPolicy.thumbnailSample(240, 320))
        assertEquals(2, ScanPhotoPolicy.thumbnailSample(320, 240))
        assertEquals(8, ScanPhotoPolicy.thumbnailSample(720, 960))
        assertEquals(8, ScanPhotoPolicy.thumbnailSample(960, 720))
    }

    @Test
    fun `fast translation rotation and tracking gaps defer without consuming cadence`() {
        val motion = ScanPhotoMotion()
        val gate = KeyframeGate()
        val start = DebugPose(0f, 0f, 0f)
        assertFalse(motion.isSteady(1_000_000_000, start))
        assertTrue(motion.isSteady(1_033_333_333, start))
        gate.accept(start)
        val moved = DebugPose(0.2f, 0f, 0f)
        assertFalse(motion.isSteady(1_066_666_666, moved))
        assertTrue(gate.wants(moved))
        assertEquals(1, gate.count)
        assertTrue(motion.isSteady(1_100_000_000, moved))
        val halfTurn = Math.toRadians(10.0)
        val turned = moved.copy(qy = sin(halfTurn).toFloat(), qw = cos(halfTurn).toFloat())
        assertFalse(motion.isSteady(1_133_333_333, turned))
        assertTrue(motion.isSteady(1_166_666_666, turned))
        assertFalse(motion.isSteady(2_000_000_000, turned))
        assertTrue(motion.isSteady(2_033_333_333, turned))
        assertFalse(motion.isSteady(2_033_333_333, turned))
    }

    @Test
    fun `a view that never settles still gets a photo`() {
        // A fast sweep: 1 m/s, a frame every 33 ms. Deferred for MAX_DEFER_NS, then let through.
        val motion = ScanPhotoMotion()
        var allowed = 0L
        var first: Long? = null
        for (i in 0 until 200) {
            val nanos = 1_000_000_000L + i * 33_333_333L
            if (motion.isSteady(nanos, DebugPose(i * 0.033f, 0f, 0f))) {
                allowed++
                if (first == null) first = nanos - 1_000_000_000L
            }
        }
        val limit = ScanPhotoPolicy.MAX_DEFER_NS
        assertTrue("first allowed after $first", first!! in limit..limit + 100_000_000L)
        assertTrue(allowed >= 3)

        // A slow frame rate (one pose every 400 ms, past the pose-gap limit) is not starved either.
        val slow = ScanPhotoMotion()
        val granted = (0 until 20).count { i ->
            slow.isSteady(1_000_000_000L + i * 400_000_000L, DebugPose(i * 0.01f, 0f, 0f))
        }
        assertTrue(granted >= 3)
    }

    @Test
    fun `encoding steps resolution down by a quarter`() {
        val sizes = ArrayList<Pair<Int, Int>>()
        ScanPhotoPolicy.encode(0, 720 to 960) { w, h, _ ->
            if (sizes.lastOrNull() != w to h) sizes += w to h
            ByteArray(if (w <= 540) 1 else ScanPhotoPolicy.ANCHOR_BYTES + 1)
        }
        assertEquals(listOf(720 to 960, 540 to 720), sizes)
    }

    @Test
    fun `old v1 photos and mixed sizes survive scan and rrd readers`() {
        val old = capture(listOf(240 to 320))
        val oldOpened = RerunScanFile.read(RerunScanFile.write(old))!!.open()!!
        assertEquals(1, oldOpened.manifest.version)
        assertEquals(1, oldOpened.trace.imageCount)
        assertArrayEquals(old.media, oldOpened.media)
        assertEquals(240 to 320, dimensions(oldOpened.firstPhoto()!!))
        for (sizes in listOf(listOf(240 to 320), listOf(720 to 960, 240 to 320, 720 to 960))) {
            val pack = capture(sizes)
            val opened = RerunScanFile.read(RerunScanFile.write(pack))!!.open()!!
            val scene = RerunExportAdapter.scene(pack, "Room")!!
            val chunks = RerunRrdWriter.chunks(scene, jpegCodec)
                .filter { chunk -> chunk.components.any { it.archetype == "Pinhole" } }
            if (sizes.size == 1) assertNull(chunks.single().times)
            else assertEquals(
                listOf(listOf(0L), listOf(1_000_000_000L), listOf(2_000_000_000L)),
                chunks.map { it.times },
            )
            chunks.forEachIndexed { i, chunk ->
                var array = chunk.components.single { it.name == "resolution" }.array
                while (array.children.isNotEmpty()) array = array.children.single()
                val values = ByteBuffer.wrap(array.buffers.last()).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(sizes[i].first.toFloat(), values.float, 0f)
                assertEquals(sizes[i].second.toFloat(), values.float, 0f)
            }
            val back = RerunRrdReader.recording(RerunRrdWriter.write(scene, jpegCodec), jpegCodec).pack.open()!!
            assertEquals(sizes.size, back.trace.imageCount)
            for (i in sizes.indices) {
                val original = opened.bytesOf(opened.trace.imagePath(i))!!
                val replayed = back.bytesOf(back.trace.imagePath(i))!!
                assertArrayEquals(original, replayed)
                assertEquals(sizes[i], dimensions(replayed))
                assertEquals(opened.trace.imageTime(i), back.trace.imageTime(i), 1e-5f)
            }
        }
    }

    // The unit-test classpath has no image decoder: a fake "JPEG" is its size, then filler.
    private fun fakeJpeg(w: Int, h: Int): ByteArray =
        ByteBuffer.allocate(8 + 64).putInt(w).putInt(h).array()

    private fun dimensions(bytes: ByteArray): Pair<Int, Int> =
        ByteBuffer.wrap(bytes).let { it.getInt() to it.getInt() }

    private val jpegCodec = object : RerunImageCodec by FakeRerunImageCodec {
        override fun photo(encoded: ByteArray): RerunImageCodec.Photo {
            val (w, h) = dimensions(encoded)
            return RerunImageCodec.Photo(encoded, RerunImageCodec.JPEG, w, h)
        }
    }

    private fun capture(sizes: List<Pair<Int, Int>>): RerunCapturePack {
        val photos = sizes.mapIndexed { i, (w, h) ->
            ScanArchive.photoPath(i) to fakeJpeg(w, h)
        }
        val (archive, spans) = ScanArchive.pack(photos)
        val events = sizes.flatMapIndexed { i, _ ->
            val nanos = 1_000_000_000L * (i + 1)
            listOf(ArDebugEvent.CameraPose(nanos, DebugPose(i * 0.2f, 0f, 0f)),
                ArDebugEvent.Image(nanos, photos[i].first))
        }
        val manifest = ReplayManifest(lens = portrait, frameRate = 1f, frameCount = sizes.size,
            floorY = null, textures = emptyList(), media = spans)
        return RerunCapturePack(manifest.toJson().toByteArray(), ArDebugLogWriter.write(events).toByteArray(), archive)
    }
}
