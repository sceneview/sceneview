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
    fun `sharp photos preserve portrait and landscape framing without upscaling sources`() {
        assertEquals(720 to 960, ScanPhotoPolicy.targetSize(true, portrait, 1920, 1440))
        assertEquals(960 to 720, ScanPhotoPolicy.targetSize(true, ReplayLens(0.8f, 0.6f), 1920, 1440))
        assertEquals(480 to 640, ScanPhotoPolicy.targetSize(true, portrait, 640, 480))
        assertEquals(240 to 320, ScanPhotoPolicy.targetSize(false, portrait, 1920, 1440))
    }

    @Test
    fun `budget is 8760 KiB and ordinary photos keep room above the old average`() {
        assertEquals(8760 * 1024, ScanPhotoPolicy.PHOTO_BUDGET_BYTES)
        // The old pipeline averaged about 16.7 KB a photo (5 MB over 300): well under the new limit.
        assertTrue(ScanPhotoPolicy.REPLAY_BYTES >= 24 * 1024)
    }

    @Test
    fun `every prefix of 300 photos fits the scan budget even for noisy encodings`() {
        val picker = ScanSharpPicker()
        var bytes = 0
        repeat(KeyframeGate.MAX_PHOTOS) {
            val sharp = picker.nextIsSharp(calm = true)
            picker.accept(sharp)
            val size = ScanPhotoPolicy.targetSize(sharp, portrait, 1920, 1440)
            val encoded = ScanPhotoPolicy.encode(sharp, size) { w, h, quality -> ByteArray(w * h * quality / 100) }!!
            assertTrue(encoded.bytes.size <= ScanPhotoPolicy.byteLimit(sharp))
            bytes += encoded.bytes.size
            assertTrue(bytes <= ScanPhotoPolicy.PHOTO_BUDGET_BYTES)
        }
        assertEquals(ScanPhotoPolicy.MAX_SHARP, picker.sharpCount)
    }

    @Test
    fun `encoding reduces quality before resolution and rejects an impossible payload`() {
        val attempts = ArrayList<Triple<Int, Int, Int>>()
        val result = ScanPhotoPolicy.encode(true, 720 to 960) { w, h, quality ->
            attempts += Triple(w, h, quality)
            ByteArray(if (quality == 75) 100 else ScanPhotoPolicy.SHARP_BYTES + 1)
        }!!
        assertEquals(listOf(Triple(720, 960, 85), Triple(720, 960, 75)), attempts)
        assertEquals(720, result.width)
        assertNull(ScanPhotoPolicy.encode(true, 1 to 1) { _, _, _ -> ByteArray(ScanPhotoPolicy.SHARP_BYTES + 1) })
        assertNull(ScanPhotoPolicy.encode(true, 720 to 960) { _, _, _ -> null })
    }

    @Test
    fun `encoding steps resolution down by a quarter`() {
        val sizes = ArrayList<Pair<Int, Int>>()
        ScanPhotoPolicy.encode(true, 720 to 960) { w, h, _ ->
            if (sizes.lastOrNull() != w to h) sizes += w to h
            ByteArray(if (w <= 540) 1 else ScanPhotoPolicy.SHARP_BYTES + 1)
        }
        assertEquals(listOf(720 to 960, 540 to 720), sizes)
    }

    @Test
    fun `a typical ordinary photo is stored as before`() {
        // About 20 KB at 240x320 quality 85: above the old 16 KiB limit, inside the new one.
        val attempts = ArrayList<Int>()
        val encoded = ScanPhotoPolicy.encode(false, 240 to 320) { _, _, quality ->
            attempts += quality
            ByteArray(20 * 1024)
        }!!
        assertEquals(listOf(85), attempts)
        assertEquals(240 to 320, encoded.width to encoded.height)
    }

    @Test
    fun `thumbnails are as large for a sharp photo as for an ordinary one`() {
        assertEquals(2, ScanPhotoPolicy.thumbnailSample(240, 320))
        assertEquals(2, ScanPhotoPolicy.thumbnailSample(320, 240))
        assertEquals(4, ScanPhotoPolicy.thumbnailSample(720, 960))
        assertEquals(4, ScanPhotoPolicy.thumbnailSample(540, 960))
        assertEquals(120 to 160, ScanPhotoPolicy.thumbnailSize(240, 320))
        assertEquals(120 to 160, ScanPhotoPolicy.thumbnailSize(720, 960))
        assertEquals(160 to 120, ScanPhotoPolicy.thumbnailSize(960, 720))
        assertEquals(90 to 160, ScanPhotoPolicy.thumbnailSize(540, 960))
        assertEquals(100 to 100, ScanPhotoPolicy.thumbnailSize(100, 100))
    }

    @Test
    fun `a session card never decodes a sharp photo whole`() {
        assertEquals(1, ScanPhotoPolicy.previewSample(240, 320))
        assertEquals(2, ScanPhotoPolicy.previewSample(720, 960))
        assertEquals(2, ScanPhotoPolicy.previewSample(540, 960))
        assertEquals(2, ScanPhotoPolicy.previewSample(480, 640))
    }

    @Test
    fun `fast translation rotation and tracking gaps are not calm`() {
        val motion = ScanPhotoMotion()
        val start = DebugPose(0f, 0f, 0f)
        assertFalse(motion.isCalm(1_000_000_000, start))
        assertTrue(motion.isCalm(1_033_333_333, start))
        val moved = DebugPose(0.2f, 0f, 0f)
        assertFalse(motion.isCalm(1_066_666_666, moved))
        assertTrue(motion.isCalm(1_100_000_000, moved))
        val halfTurn = Math.toRadians(10.0)
        val turned = moved.copy(qy = sin(halfTurn).toFloat(), qw = cos(halfTurn).toFloat())
        assertFalse(motion.isCalm(1_133_333_333, turned))
        assertTrue(motion.isCalm(1_166_666_666, turned))
        assertFalse(motion.isCalm(2_000_000_000, turned))
        assertTrue(motion.isCalm(2_033_333_333, turned))
        assertFalse(motion.isCalm(2_033_333_333, turned))
    }

    @Test
    fun `motion never touches the cadence of the gate`() {
        // The gate decides which poses earn a photo; calm only decides whether it is a sharp one.
        val motion = ScanPhotoMotion()
        val gate = KeyframeGate()
        val start = DebugPose(0f, 0f, 0f)
        motion.isCalm(1_000_000_000, start)
        gate.accept(start)
        val fast = DebugPose(0.2f, 0f, 0f)
        assertFalse(motion.isCalm(1_033_333_333, fast))
        assertTrue(gate.wants(fast))
    }

    @Test
    fun `the first calm photo is sharp and a fast start waits for a calm one`() {
        val picker = ScanSharpPicker()
        assertFalse(picker.nextIsSharp(calm = false))
        picker.accept(false)
        assertFalse(picker.nextIsSharp(calm = false))
        picker.accept(false)
        assertTrue(picker.nextIsSharp(calm = true))
    }

    @Test
    fun `a sharp photo comes after twenty photos and only when calm, ordinary ones never wait`() {
        val picker = ScanSharpPicker()
        assertTrue(picker.nextIsSharp(true))
        picker.accept(true)
        repeat(ScanPhotoPolicy.SHARP_INTERVAL - 1) {
            assertFalse(picker.nextIsSharp(true))
            picker.accept(false)
        }
        assertFalse(picker.nextIsSharp(true))
        picker.accept(false)
        // Twenty photos passed: due, but the camera is moving. No cap: it waits as long as it moves.
        repeat(100) {
            assertFalse(picker.nextIsSharp(false))
            picker.accept(false)
        }
        assertTrue(picker.nextIsSharp(true))
        picker.accept(true)
        assertEquals(2, picker.sharpCount)
        assertFalse(picker.nextIsSharp(true))
    }

    @Test
    fun `a short scan gets a second sharp photo and never more than fifteen`() {
        val picker = ScanSharpPicker()
        var kinds = ""
        repeat(25) {
            val sharp = picker.nextIsSharp(true)
            picker.accept(sharp)
            kinds += if (sharp) "S" else "."
        }
        assertEquals("S" + ".".repeat(20) + "S...", kinds)
        repeat(1000) { picker.accept(picker.nextIsSharp(true)) }
        assertEquals(ScanPhotoPolicy.MAX_SHARP, picker.sharpCount)
    }

    @Test
    fun `a scan that is never calm still gets one sharp photo and only one`() {
        val picker = ScanSharpPicker()
        repeat(ScanPhotoPolicy.SHARP_GUARANTEE_AFTER) {
            assertFalse(picker.nextIsSharp(false))
            picker.accept(false)
        }
        assertTrue(picker.nextIsSharp(false))
        picker.accept(true)
        repeat(200) {
            assertFalse(picker.nextIsSharp(false))
            picker.accept(false)
        }
        assertEquals(1, picker.sharpCount)
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
