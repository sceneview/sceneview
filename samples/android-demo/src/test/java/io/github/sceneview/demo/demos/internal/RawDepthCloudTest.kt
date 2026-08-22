package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM unit tests for [RawDepthCloud]. ARCore's `Image` is not mockable on the JVM, so
 * the cloud builder is fed a plain [ByteBuffer] shaped like ARCore's raw-depth +
 * confidence image pair.
 */
class RawDepthCloudTest {

    /**
     * Build a 4×2 raw-depth + confidence buffer pair, with every pixel having a
     * known depth and confidence. Returns `(depth, confidence)`.
     */
    private fun buildPair(
        depthMm: IntArray,
        confidence: IntArray,
        width: Int,
        height: Int,
    ): Pair<ByteBuffer, ByteBuffer> {
        check(depthMm.size == width * height)
        check(confidence.size == width * height)
        val depthBuf = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in depthMm.indices) {
            depthBuf.putShort(i * 2, depthMm[i].toShort())
        }
        val confBuf = ByteBuffer.allocate(width * height)
        for (i in confidence.indices) {
            confBuf.put(i, confidence[i].toByte())
        }
        return depthBuf to confBuf
    }

    @Test
    fun `buildCloud at stride 1 returns one point per qualifying pixel`() {
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(300, 1_000, 2_000, 5_000),
            confidence = intArrayOf(200, 200, 200, 200),
            width = 4,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 1,
            stride = 1,
            confidenceThreshold = 0,
        )

        assertEquals(4, RawDepthCloud.pointCount(packed))
        // First point: x=0, y=0, depth=300, color=falseColor(0) (red).
        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(0, RawDepthCloud.y(packed, 0))
        assertEquals(300, RawDepthCloud.depthMm(packed, 0))
        assertEquals(DepthVisualization.falseColorArgb(0f), RawDepthCloud.argb(packed, 0))
        // Last point: x=3, y=0, depth=5000, color=falseColor(1) (blue).
        assertEquals(3, RawDepthCloud.x(packed, 3))
        assertEquals(DepthVisualization.falseColorArgb(1f), RawDepthCloud.argb(packed, 3))
    }

    @Test
    fun `buildCloud drops pixels below confidence threshold`() {
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(1_000, 1_000, 1_000, 1_000),
            // Confidence 50, 100, 150, 200. With threshold 128, only the last two survive.
            confidence = intArrayOf(50, 100, 150, 200),
            width = 4,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 1,
            stride = 1,
            confidenceThreshold = 128,
        )

        assertEquals(2, RawDepthCloud.pointCount(packed))
        assertEquals(2, RawDepthCloud.x(packed, 0))
        assertEquals(3, RawDepthCloud.x(packed, 1))
    }

    @Test
    fun `buildCloud drops zero-depth (no-data) pixels`() {
        val (depth, conf) = buildPair(
            // Pixel 1 has depth=0 ("no data") even though confidence is high.
            depthMm = intArrayOf(1_000, 0, 2_000, 0),
            confidence = intArrayOf(255, 255, 255, 255),
            width = 4,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 1,
            stride = 1,
            confidenceThreshold = 0,
        )

        assertEquals(2, RawDepthCloud.pointCount(packed))
        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(2, RawDepthCloud.x(packed, 1))
    }

    @Test
    fun `buildCloud at stride 2 sub-samples the image`() {
        // 4x2 image, stride 2 → 2x1 = 2 sampled positions: (0,0), (2,0).
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(
                300, 400, 500, 600,
                700, 800, 900, 1_000,
            ),
            confidence = IntArray(8) { 200 },
            width = 4,
            height = 2,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 2,
            stride = 2,
            confidenceThreshold = 0,
        )

        assertEquals(2, RawDepthCloud.pointCount(packed))
        // Sampled positions: (0,0) → depth 300, (2,0) → depth 500.
        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(0, RawDepthCloud.y(packed, 0))
        assertEquals(300, RawDepthCloud.depthMm(packed, 0))
        assertEquals(2, RawDepthCloud.x(packed, 1))
        assertEquals(0, RawDepthCloud.y(packed, 1))
        assertEquals(500, RawDepthCloud.depthMm(packed, 1))
    }

    @Test
    fun `buildCloud respects maxPoints cap`() {
        // 10 qualifying pixels; cap at 3 → 3 points emitted, in row-major order.
        val (depth, conf) = buildPair(
            depthMm = IntArray(10) { 1_000 + it * 100 },
            confidence = IntArray(10) { 200 },
            width = 10,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 20,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 10,
            width = 10,
            height = 1,
            stride = 1,
            confidenceThreshold = 0,
            maxPoints = 3,
        )

        assertEquals(3, RawDepthCloud.pointCount(packed))
        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(1, RawDepthCloud.x(packed, 1))
        assertEquals(2, RawDepthCloud.x(packed, 2))
    }

    @Test
    fun `buildCloud returns empty when threshold filters every pixel`() {
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(1_000, 1_000, 1_000, 1_000),
            confidence = intArrayOf(10, 20, 30, 40),
            width = 4,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 1,
            stride = 1,
            confidenceThreshold = 255,
        )

        assertEquals(0, RawDepthCloud.pointCount(packed))
        assertEquals(0, packed.size)
    }

    @Test
    fun `buildCloud clamps confidence threshold to 0_255`() {
        // Negative threshold should clamp to 0 (keep everything).
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(1_000, 1_000),
            confidence = intArrayOf(1, 1),
            width = 2,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 4,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 2,
            width = 2,
            height = 1,
            stride = 1,
            confidenceThreshold = -42,
        )

        assertEquals(2, RawDepthCloud.pointCount(packed))
    }

    @Test
    fun `buildCloud respects row-stride padding for depth and confidence independently`() {
        // 1×2 image. Depth rowStride = 4 (pixel + 2 bytes padding). Confidence rowStride = 3
        // (pixel + 2 bytes padding). The reader must read at byte offsets {0, 4} for depth
        // and {0, 3} for confidence, not interpret the padding as valid data.
        val width = 1
        val height = 2
        val depthRowStride = 4
        val confRowStride = 3
        val depthBuf = ByteBuffer.allocate(depthRowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        depthBuf.putShort(0, 300.toShort())   // row 0
        depthBuf.putShort(4, 5_000.toShort()) // row 1
        val confBuf = ByteBuffer.allocate(confRowStride * height)
        confBuf.put(0, 200.toByte()) // row 0
        confBuf.put(3, 200.toByte()) // row 1
        // Bytes at 1,2,4,5 (conf) and 2,3,6,7 (depth) are padding 0x00.

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depthBuf,
            depthRowStrideBytes = depthRowStride,
            confidenceBytes = confBuf,
            confidenceRowStrideBytes = confRowStride,
            width = width,
            height = height,
            stride = 1,
            confidenceThreshold = 0,
        )

        assertEquals(2, RawDepthCloud.pointCount(packed))
        assertEquals(300, RawDepthCloud.depthMm(packed, 0))
        assertEquals(5_000, RawDepthCloud.depthMm(packed, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCloud rejects zero-sized image`() {
        val empty = ByteBuffer.allocate(0)
        RawDepthCloud.buildCloud(empty, 0, empty, 0, 0, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCloud rejects stride less than 1`() {
        val empty = ByteBuffer.allocate(8)
        RawDepthCloud.buildCloud(empty, 8, empty, 4, 4, 1, stride = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCloud rejects negative maxPoints`() {
        val empty = ByteBuffer.allocate(8)
        RawDepthCloud.buildCloud(empty, 8, empty, 4, 4, 1, maxPoints = -1)
    }

    @Test
    fun `buildCloud packs four ints per point in declared order`() {
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(1_500),
            confidence = intArrayOf(180),
            width = 1,
            height = 1,
        )
        val packed = RawDepthCloud.buildCloud(depth, 2, conf, 1, 1, 1, stride = 1, confidenceThreshold = 0)
        assertEquals(RawDepthCloud.STRIDE_INTS, packed.size)
        // Layout: [x, y, depthMm, argb]
        assertEquals(0, packed[0])
        assertEquals(0, packed[1])
        assertEquals(1_500, packed[2])
        val expectedColor = DepthVisualization.falseColorArgb(
            DepthVisualization.normalize(1_500)!!
        )
        assertEquals(expectedColor, packed[3])
    }

    @Test
    fun `pointCount returns zero for an empty packed array`() {
        assertEquals(0, RawDepthCloud.pointCount(IntArray(0)))
    }

    @Test
    fun `buildCloud with rotationDegrees 0 matches the unrotated coordinates`() {
        val (depth, conf) = buildPair(
            depthMm = intArrayOf(300, 1_000, 2_000, 5_000),
            confidence = intArrayOf(200, 200, 200, 200),
            width = 4,
            height = 1,
        )

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = 8,
            confidenceBytes = conf,
            confidenceRowStrideBytes = 4,
            width = 4,
            height = 1,
            stride = 1,
            confidenceThreshold = 0,
            rotationDegrees = 0,
        )

        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(0, RawDepthCloud.y(packed, 0))
        assertEquals(3, RawDepthCloud.x(packed, 3))
        assertEquals(0, RawDepthCloud.y(packed, 3))
    }

    @Test
    fun `buildCloud rotates points 90 degrees clockwise (portrait sensor fix, #3271)`() {
        // 4x2 source image. A single qualifying pixel at (3, 0) — the top-right corner of
        // the sensor-frame image — must land in the top-left corner of the 90°-rotated
        // (2x4) output frame, exactly mirroring DepthVisualization.depthBufferToArgb's
        // pixel remap for the same rotation.
        val width = 4
        val height = 2
        val depthMm = IntArray(width * height)
        val confidence = IntArray(width * height) { 200 }
        depthMm[0 * width + 3] = 1_000 // (x=3, y=0)
        val (depth, conf) = buildPair(depthMm, confidence, width, height)

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = width * 2,
            confidenceBytes = conf,
            confidenceRowStrideBytes = width,
            width = width,
            height = height,
            stride = 1,
            confidenceThreshold = 0,
            rotationDegrees = 90,
        )

        assertEquals(1, RawDepthCloud.pointCount(packed))
        // outX = height - 1 - y = 2 - 1 - 0 = 1; outY = x = 3.
        assertEquals(1, RawDepthCloud.x(packed, 0))
        assertEquals(3, RawDepthCloud.y(packed, 0))
    }

    @Test
    fun `buildCloud rotates points 180 degrees`() {
        val width = 4
        val height = 2
        val depthMm = IntArray(width * height)
        val confidence = IntArray(width * height) { 200 }
        depthMm[0] = 1_000 // (x=0, y=0)
        val (depth, conf) = buildPair(depthMm, confidence, width, height)

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = width * 2,
            confidenceBytes = conf,
            confidenceRowStrideBytes = width,
            width = width,
            height = height,
            stride = 1,
            confidenceThreshold = 0,
            rotationDegrees = 180,
        )

        assertEquals(1, RawDepthCloud.pointCount(packed))
        assertEquals(width - 1, RawDepthCloud.x(packed, 0))
        assertEquals(height - 1, RawDepthCloud.y(packed, 0))
    }

    @Test
    fun `buildCloud rotates points 270 degrees`() {
        val width = 4
        val height = 2
        val depthMm = IntArray(width * height)
        val confidence = IntArray(width * height) { 200 }
        depthMm[0 * width + 3] = 1_000 // (x=3, y=0)
        val (depth, conf) = buildPair(depthMm, confidence, width, height)

        val packed = RawDepthCloud.buildCloud(
            depthBytes = depth,
            depthRowStrideBytes = width * 2,
            confidenceBytes = conf,
            confidenceRowStrideBytes = width,
            width = width,
            height = height,
            stride = 1,
            confidenceThreshold = 0,
            rotationDegrees = 270,
        )

        assertEquals(1, RawDepthCloud.pointCount(packed))
        // outX = y = 0; outY = width - 1 - x = 4 - 1 - 3 = 0.
        assertEquals(0, RawDepthCloud.x(packed, 0))
        assertEquals(0, RawDepthCloud.y(packed, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCloud rejects a rotation that is not a multiple of 90`() {
        val empty = ByteBuffer.allocate(8)
        RawDepthCloud.buildCloud(empty, 8, empty, 4, 4, 1, rotationDegrees = 45)
    }

    @Test
    fun `defaults are within sane bounds`() {
        assertTrue(RawDepthCloud.DEFAULT_STRIDE >= 1)
        assertTrue(RawDepthCloud.DEFAULT_MAX_POINTS > 0)
        assertTrue(RawDepthCloud.DEFAULT_CONFIDENCE_THRESHOLD in 0..255)
    }
}
