package io.github.sceneview.ar.arcore

import io.github.sceneview.ar.node.computePointCloudAabb
import io.github.sceneview.ar.node.pointCloudNextPowerOfTwo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Pins the pure point-cloud packing + confidence-filter math behind [PointCloudNode] (#1773).
 *
 * The ARCore [com.google.ar.core.PointCloud] plumbing cannot run in a JVM unit test, so
 * [buildPointCloudPositions] is `internal` and verified here in isolation — a stride or offset
 * error would otherwise only surface on a tracking-capable device.
 */
class PointCloudGeometryTest {

    /** Wraps `[x, y, z, confidence]` quadruples into a tightly-packed direct [FloatBuffer]. */
    private fun pointBuffer(vararg floats: Float): FloatBuffer {
        val bytes = ByteBuffer.allocateDirect((floats.size * 4).coerceAtLeast(4))
            .order(ByteOrder.nativeOrder())
        val view = bytes.asFloatBuffer()
        floats.forEach { view.put(it) }
        view.rewind()
        return view
    }

    @Test
    fun `empty buffer yields empty positions`() {
        val positions = buildPointCloudPositions(pointBuffer(), confidenceThreshold = 0f)
        assertEquals(0, positions.size)
    }

    @Test
    fun `zero threshold keeps every point and drops the confidence channel`() {
        val buffer = pointBuffer(
            1f, 2f, 3f, 0.1f,
            4f, 5f, 6f, 0.9f,
        )
        val positions = buildPointCloudPositions(buffer, confidenceThreshold = 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), positions, 0f)
    }

    @Test
    fun `points below the threshold are filtered out`() {
        val buffer = pointBuffer(
            1f, 1f, 1f, 0.1f, // dropped
            2f, 2f, 2f, 0.5f, // kept (>= 0.5)
            3f, 3f, 3f, 0.49f, // dropped
            4f, 4f, 4f, 1.0f, // kept
        )
        val positions = buildPointCloudPositions(buffer, confidenceThreshold = 0.5f)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f, 4f, 4f, 4f), positions, 0f)
    }

    @Test
    fun `threshold above every confidence yields empty positions`() {
        val buffer = pointBuffer(1f, 1f, 1f, 0.8f, 2f, 2f, 2f, 0.9f)
        val positions = buildPointCloudPositions(buffer, confidenceThreshold = 1.0f)
        assertEquals(0, positions.size)
    }

    @Test
    fun `caller buffer position is not mutated`() {
        val buffer = pointBuffer(1f, 2f, 3f, 1f)
        val positionBefore = buffer.position()
        buildPointCloudPositions(buffer, confidenceThreshold = 0f)
        assertEquals(positionBefore, buffer.position())
    }

    // ── computePointCloudAabb ─────────────────────────────────────────────────────────────────

    @Test
    fun `empty positions yield a non-empty degenerate AABB`() {
        val box = computePointCloudAabb(FloatArray(0))
        // Filament rejects an empty AABB — every half-extent must be strictly positive.
        assert(box.halfExtent.all { it > 0f }) { "half-extents must be > 0, got ${box.halfExtent.toList()}" }
    }

    @Test
    fun `AABB spans the point cloud bounds`() {
        val positions = floatArrayOf(
            -1f, -2f, -3f,
            4f, 5f, 6f,
        )
        val box = computePointCloudAabb(positions)
        assertEquals(1.5f, box.center[0], 1e-5f)
        assertEquals(1.5f, box.center[1], 1e-5f)
        assertEquals(1.5f, box.center[2], 1e-5f)
        assertEquals(2.5f, box.halfExtent[0], 1e-5f)
        assertEquals(3.5f, box.halfExtent[1], 1e-5f)
        assertEquals(4.5f, box.halfExtent[2], 1e-5f)
    }

    @Test
    fun `coincident points produce a clamped non-empty AABB`() {
        // Two identical points collapse every extent to 0 — Filament would SIGABRT without the clamp.
        val box = computePointCloudAabb(floatArrayOf(2f, 2f, 2f, 2f, 2f, 2f))
        assert(box.halfExtent.all { it > 0f }) { "half-extents must be > 0, got ${box.halfExtent.toList()}" }
    }

    // ── pointCloudNextPowerOfTwo ────────────────────────────────────────────────────────────────────────

    @Test
    fun `pointCloudNextPowerOfTwo rounds up`() {
        assertEquals(1, pointCloudNextPowerOfTwo(0))
        assertEquals(1, pointCloudNextPowerOfTwo(1))
        assertEquals(256, pointCloudNextPowerOfTwo(256))
        assertEquals(512, pointCloudNextPowerOfTwo(257))
        assertEquals(1024, pointCloudNextPowerOfTwo(1000))
    }
}
