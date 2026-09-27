package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins the pure half of the Rerun demo's Record mode: where a world point lands on ARCore's
 * camera image and which colour it takes there, how a photo is turned upright for the frustum it
 * sits in, which poses earn a photo, how the photos are packed, and the copy the brief promised.
 * The emulator cannot track (#2754), so the last test runs the bundled walk through the same
 * steps a live scan takes and checks what the replay would open on.
 */
class RoomScanTest {

    // ── Colour ────────────────────────────────────────────────────────────────

    @Test
    fun `YUV converts to the colours the camera saw`() {
        assertEquals(0xFF808080.toInt(), ScanColor.yuvToArgb(128, 128, 128))
        assertEquals(0xFFFFFFFF.toInt(), ScanColor.yuvToArgb(255, 128, 128))
        assertEquals(0xFF000000.toInt(), ScanColor.yuvToArgb(0, 128, 128))
        val red = ScanColor.yuvToArgb(76, 85, 255)
        assertTrue("red channel of ${Integer.toHexString(red)}", (red shr 16 and 0xFF) > 250)
        assertTrue("green channel of ${Integer.toHexString(red)}", (red shr 8 and 0xFF) < 5)
        assertTrue("blue channel of ${Integer.toHexString(red)}", (red and 0xFF) < 5)
    }

    @Test
    fun `a pixel reads its chroma from the half-size planes, whatever their stride`() {
        // NV21-like: interleaved chroma, pixel stride 2, padded rows.
        val width = 4
        val height = 2
        val y = ByteBuffer.wrap(ByteArray(8 * height) { 200.toByte() })
        val uv = ByteArray(8) { 128.toByte() }
        uv[2] = 60 // U of the second chroma column
        uv[3] = 200.toByte() // its V
        val frame = YuvFrame(width, height, y, 8, ByteBuffer.wrap(uv, 0, 8), ByteBuffer.wrap(uv, 1, 7).slice(), 8, 2)

        assertEquals(ScanColor.yuvToArgb(200, 128, 128), frame.argb(0, 0))
        assertEquals(ScanColor.yuvToArgb(200, 60, 200), frame.argb(2, 1))
        assertEquals("clamped to the image", frame.argb(3, 1), frame.argb(99, 99))
    }

    // ── Projection ────────────────────────────────────────────────────────────

    @Test
    fun `a point in front of the camera lands where the pinhole puts it`() {
        val sensor = DebugPose(1f, 2f, 3f).turnedAboutY(degrees = 30f)
        val world = sensor.transform(0.2f, -0.1f, -2f)

        val uv = ScanProjection.project(INTRINSICS, sensor, world.x, world.y, world.z)!!

        assertEquals(INTRINSICS.cx + INTRINSICS.fx * 0.1f, uv[0], 1e-2f)
        assertEquals(INTRINSICS.cy + INTRINSICS.fy * 0.05f, uv[1], 1e-2f)
    }

    @Test
    fun `nothing behind the camera or off the image projects`() {
        val sensor = DebugPose(0f, 0f, 0f)
        assertNull(ScanProjection.project(INTRINSICS, sensor, 0f, 0f, 1f))
        assertNull(ScanProjection.project(INTRINSICS, sensor, 50f, 0f, -1f))
    }

    @Test
    fun `a point takes the colour of the image where it lands, and none where it does not`() {
        val image = uniform(INTRINSICS.width, INTRINSICS.height, luma = 90, u = 100, v = 170)
        val sensor = DebugPose(0f, 0f, 0f)
        val points = floatArrayOf(
            0f, 0f, -1f, // centre
            0.1f, 0.05f, -2f, // in view
            0f, 0f, 1f, // behind
            40f, 0f, -1f, // far off to the side
        )

        val colors = ScanProjection.colors(image, INTRINSICS, sensor, points)

        val expected = ScanColor.yuvToArgb(90, 100, 170)
        assertArrayEquals(intArrayOf(expected, expected, 0, 0), colors)
    }

    // ── Upright photos ────────────────────────────────────────────────────────

    @Test
    fun `a portrait screen turns the lens a quarter`() {
        val sensor = DebugPose(0f, 0f, 0f)
        val portrait = sensor.turnedAboutZ(degrees = 90f)

        assertFalse(ScanProjection.isQuarterTurn(sensor, sensor))
        assertTrue(ScanProjection.isQuarterTurn(sensor, portrait))

        val landscape = ScanProjection.displayLens(INTRINSICS, sensor, sensor)
        val upright = ScanProjection.displayLens(INTRINSICS, sensor, portrait)
        assertEquals(INTRINSICS.width / 2f / INTRINSICS.fx, landscape.halfWidthPerDepth, 1e-5f)
        assertEquals(landscape.halfWidthPerDepth, upright.halfHeightPerDepth, 1e-5f)
        assertEquals(landscape.halfHeightPerDepth, upright.halfWidthPerDepth, 1e-5f)
    }

    @Test
    fun `a photo keeps the lens proportions, portrait or landscape`() {
        assertEquals(320 to 240, ScanProjection.photoSize(ReplayLens(0.8f, 0.6f), 320))
        assertEquals(240 to 320, ScanProjection.photoSize(ReplayLens(0.6f, 0.8f), 320))
    }

    @Test
    fun `held as the sensor is, the photo is the image itself`() {
        val intrinsics = ScanIntrinsics(fx = 4f, fy = 4f, cx = 4f, cy = 2f, width = 8, height = 4)
        val image = topBright(intrinsics.width, intrinsics.height)
        val sensor = DebugPose(0f, 0f, 0f)
        val lens = ScanProjection.displayLens(intrinsics, sensor, sensor)

        val out = ScanProjection.uprightImage(image, intrinsics, sensor, sensor, lens, 8, 4)

        assertEquals(BRIGHT, out[0 * 8 + 4])
        assertEquals(DARK, out[3 * 8 + 4])
    }

    @Test
    fun `held in portrait, the photo turns so the top of the room stays up`() {
        // The sensor's top edge is bright. Turned a quarter anticlockwise the screen's right
        // runs up the sensor image, so the bright edge must come out on the photo's right.
        val intrinsics = ScanIntrinsics(fx = 4f, fy = 4f, cx = 4f, cy = 2f, width = 8, height = 4)
        val image = topBright(intrinsics.width, intrinsics.height)
        val sensor = DebugPose(0f, 0f, 0f)
        val display = sensor.turnedAboutZ(degrees = 90f)
        val lens = ScanProjection.displayLens(intrinsics, sensor, display)
        val (width, height) = ScanProjection.photoSize(lens, 8)
        assertEquals(4 to 8, width to height)

        val out = ScanProjection.uprightImage(image, intrinsics, sensor, display, lens, width, height)

        for (row in 1 until height - 1) {
            assertEquals("row $row, right edge", BRIGHT, out[row * width + width - 1])
            assertEquals("row $row, left edge", DARK, out[row * width])
        }
    }

    // ── Which poses earn a photo ──────────────────────────────────────────────

    @Test
    fun `a photo every fifteen centimetres or ten degrees, never two of the same view`() {
        val gate = KeyframeGate()
        val start = DebugPose(0f, 1.4f, 0f)
        assertTrue("the first pose always does", gate.wants(start))
        gate.accept(start)

        assertFalse(gate.wants(DebugPose(0.1f, 1.4f, 0f)))
        assertTrue(gate.wants(DebugPose(0.16f, 1.4f, 0f)))
        assertFalse(gate.wants(start.turnedAboutY(degrees = 9f)))
        assertTrue(gate.wants(start.turnedAboutY(degrees = 11f)))
        assertTrue("wants does not commit", gate.wants(DebugPose(0.16f, 1.4f, 0f)))
        assertEquals(1, gate.count)
    }

    @Test
    fun `the photos stop at the cap so a long scan cannot fill the memory`() {
        val gate = KeyframeGate(maxPhotos = 2)
        gate.accept(DebugPose(0f, 0f, 0f))
        gate.accept(DebugPose(1f, 0f, 0f))

        assertTrue(gate.isFull)
        assertFalse(gate.wants(DebugPose(5f, 0f, 0f)))
    }

    // ── Packing ───────────────────────────────────────────────────────────────

    @Test
    fun `the photos pack back to back, each indexed, and a missing one is skipped`() {
        assertEquals("scan/frame-0001.jpg", ScanArchive.photoPath(0))
        assertEquals("scan/frame-0120.jpg", ScanArchive.photoPath(119))

        val (archive, spans) = ScanArchive.pack(
            listOf(
                "a" to byteArrayOf(1, 2, 3),
                "b" to ByteArray(0),
                "c" to byteArrayOf(4, 5),
            ),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), archive)
        assertEquals(mapOf("a" to MediaSpan(0, 3), "c" to MediaSpan(3, 2)), spans)
    }

    // ── Copy ──────────────────────────────────────────────────────────────────

    @Test
    fun `the copy says the scan stays on the phone`() {
        assertEquals("Everything stays on your phone.", ScanCopy.PRIVACY)
        assertTrue(ScanCopy.IDLE_DETAIL.endsWith(ScanCopy.PRIVACY))
    }

    @Test
    fun `the figures read from a metre away and fit three abreast`() {
        assertEquals("0", ScanCopy.figure(0))
        assertEquals("9,999", ScanCopy.figure(9_999))
        assertEquals("12k", ScanCopy.figure(12_400))
        assertEquals("photo", ScanCopy.label(1, "photo", "photos"))
        assertEquals("photos", ScanCopy.label(0, "photo", "photos"))
        assertEquals("photos", ScanCopy.label(2, "photo", "photos"))
    }

    @Test
    fun `a saved scan's line gives its length, points and photos`() {
        assertEquals("0:48 · 2,521 points · 64 photos", ScanCopy.summary(48.6f, 2_521, 64))
        assertEquals("1:05 · 1 point · 1 photo", ScanCopy.summary(65f, 1, 1))
        assertEquals("0:00 · 0 points · 0 photos", ScanCopy.summary(Float.NaN, 0, 0))
    }

    // ── End to end ────────────────────────────────────────────────────────────

    @Test
    fun `the bundled walk, scanned the way Record scans, opens as a coloured scan with photos`() {
        val events = File(bundledDir(), "showcase-session.jsonl").useLines { parseArDebugLog(it) }
        val image = uniform(INTRINSICS.width, INTRINSICS.height, luma = 150, u = 110, v = 150)
        val trace = ArDebugTrace().apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
        val gate = KeyframeGate()
        var camera: DebugPose? = null

        for (event in events) {
            when (event) {
                is ArDebugEvent.CameraPose -> {
                    camera = event.pose
                    trace.addPose(event.nanos, event.pose)
                    if (gate.wants(event.pose)) {
                        gate.accept(event.pose)
                        trace.addImage(event.nanos, ScanArchive.photoPath(gate.count - 1))
                    }
                }
                is ArDebugEvent.Points -> {
                    val sensor = camera ?: continue
                    val colors = ScanProjection.colors(image, INTRINSICS, sensor, event.positions)
                    trace.addPoints(event.nanos, event.positions, event.confidences, colors)
                }
                is ArDebugEvent.Image -> Unit // a live scan takes its own
                else -> event.applyTo(trace)
            }
        }

        val last = trace.frameAt(trace.duration)
        assertTrue("photos: ${trace.imageCount}", trace.imageCount in 10..KeyframeGate.MAX_PHOTOS)
        assertTrue(last.mapPointCount > 1_000)
        assertTrue(last.planes.isNotEmpty())
        val colors = last.mapPointColors ?: error("the scan's points carry no colour")
        val painted = colors.count { it == ScanColor.yuvToArgb(150, 110, 150) }
        assertTrue("painted $painted of ${colors.size}", painted > colors.size / 2)
        assertTrue(last.keyframes.isNotEmpty())
        assertTrue("every frustum has its photo", last.keyframeImages.all { it != null })

        val photos = (0 until trace.imageCount).map { trace.imagePath(it) to byteArrayOf(1, 2) }
        val (archive, spans) = ScanArchive.pack(photos)
        assertEquals(trace.imageCount * 2, archive.size)
        for (i in 0 until trace.imageCount) assertNotNull(spans[trace.imagePath(i)])
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun DebugPose.turnedAboutY(degrees: Float): DebugPose {
        val half = Math.toRadians(degrees / 2.0)
        return copy(qx = 0f, qy = sin(half).toFloat(), qz = 0f, qw = cos(half).toFloat())
    }

    private fun DebugPose.turnedAboutZ(degrees: Float): DebugPose {
        val half = Math.toRadians(degrees / 2.0)
        return copy(qx = 0f, qy = 0f, qz = sin(half).toFloat(), qw = cos(half).toFloat())
    }

    private fun uniform(width: Int, height: Int, luma: Int, u: Int, v: Int) = YuvFrame(
        width = width,
        height = height,
        y = ByteBuffer.wrap(ByteArray(width * height) { luma.toByte() }),
        yRowStride = width,
        u = ByteBuffer.wrap(ByteArray(width / 2 * (height / 2)) { u.toByte() }),
        v = ByteBuffer.wrap(ByteArray(width / 2 * (height / 2)) { v.toByte() }),
        uvRowStride = width / 2,
        uvPixelStride = 1,
    )

    /** Grey, white across the top half of the image and black across the bottom. */
    private fun topBright(width: Int, height: Int) = YuvFrame(
        width = width,
        height = height,
        y = ByteBuffer.wrap(ByteArray(width * height) { i -> if (i / width < height / 2) 255.toByte() else 0 }),
        yRowStride = width,
        u = ByteBuffer.wrap(ByteArray(width / 2 * (height / 2)) { 128.toByte() }),
        v = ByteBuffer.wrap(ByteArray(width / 2 * (height / 2)) { 128.toByte() }),
        uvRowStride = width / 2,
        uvPixelStride = 1,
    )

    private fun bundledDir(): File = listOf(
        File("src/main/assets/${RerunReplayAssets.DIR}"),
        File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
    ).first { it.isDirectory }

    private companion object {
        /** A 640×480 sensor image, the size ARCore hands the CPU on most phones. */
        val INTRINSICS = ScanIntrinsics(fx = 500f, fy = 500f, cx = 320f, cy = 240f, width = 640, height = 480)
        val BRIGHT = 0xFFFFFFFF.toInt()
        val DARK = 0xFF000000.toInt()
    }
}
