package io.github.sceneview.ar.rerun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-JSON tests for [RerunWireFormat].
 *
 * These tests target the `*Json` testable overloads directly so they don't
 * need real ARCore instances (Pose / Plane / PointCloud are final native
 * classes that can't be mocked without mockito-inline). The production
 * overloads — `cameraPose(Pose)`, `plane(Plane)`, etc. — are thin
 * pass-throughs, so any bug in serialization will be caught here.
 */
class RerunWireFormatTest {

    @Test
    fun `cameraPose emits canonical JSON line`() {
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 123456789L,
            tx = 0.1f, ty = 1.7f, tz = -0.2f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        )
        val expected =
            "{\"t\":123456789,\"type\":\"camera_pose\",\"entity\":\"world/camera\"," +
                "\"translation\":[0.1,1.7,-0.2],\"quaternion\":[0.0,0.0,0.0,1.0]}\n"
        assertEquals(expected, line)
    }

    @Test
    fun `cameraPose terminates with newline`() {
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 0L,
            tx = 0f, ty = 0f, tz = 0f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        )
        assertTrue("line must end with \\n", line.endsWith("\n"))
    }

    @Test
    fun `cameraPose honours custom entity path`() {
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 1L,
            tx = 0f, ty = 0f, tz = 0f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            entity = "world/robot/head",
        )
        assertTrue(line.contains("\"entity\":\"world/robot/head\""))
    }

    @Test
    fun `cameraPose escapes quote in entity path`() {
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 1L,
            tx = 0f, ty = 0f, tz = 0f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
            entity = "world/bad\"path",
        )
        assertTrue(line.contains("world/bad\\\"path"))
    }

    @Test
    fun `non-finite floats are clamped to 0 so the line stays parseable`() {
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 1L,
            tx = Float.NaN, ty = Float.POSITIVE_INFINITY, tz = Float.NEGATIVE_INFINITY,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        )
        assertFalse("NaN must not leak into JSON", line.contains("NaN"))
        assertFalse("Infinity must not leak into JSON", line.contains("Infinity"))
        assertTrue(line.contains("\"translation\":[0,0,0]"))
    }

    @Test
    fun `anchor emits entity path with id`() {
        val line = RerunWireFormat.anchorJson(
            timestampNanos = 1000L,
            id = 42,
            tx = 1f, ty = 2f, tz = 3f,
            qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        )
        assertTrue(line.contains("\"entity\":\"world/anchors/42\""))
        assertTrue(line.contains("\"translation\":[1.0,2.0,3.0]"))
        assertTrue(line.contains("\"quaternion\":[0.0,0.0,0.0,1.0]"))
    }

    @Test
    fun `hit_result carries distance`() {
        val line = RerunWireFormat.hitResultJson(
            timestampNanos = 5L,
            id = 7,
            tx = 0f, ty = 0f, tz = -1f,
            distance = 1.5f,
        )
        assertTrue(line.contains("\"entity\":\"world/hits/7\""))
        assertTrue(line.contains("\"distance\":1.5"))
    }

    @Test
    fun `pointCloud splits packed buffer into positions and confidences`() {
        // Two points: (1,2,3) conf 0.9 and (4,5,6) conf 0.8
        val packed = floatArrayOf(
            1f, 2f, 3f, 0.9f,
            4f, 5f, 6f, 0.8f,
        )
        val line = RerunWireFormat.pointCloudJson(1L, packed)
        assertTrue(
            "positions should be split into two 3-tuples",
            line.contains("\"positions\":[[1.0,2.0,3.0],[4.0,5.0,6.0]]"),
        )
        assertTrue(
            "confidences should be extracted from the packed buffer",
            line.contains("\"confidences\":[0.9,0.8]"),
        )
    }

    @Test
    fun `pointCloud handles empty buffer`() {
        val line = RerunWireFormat.pointCloudJson(1L, FloatArray(0))
        assertTrue(line.contains("\"positions\":[]"))
        assertTrue(line.contains("\"confidences\":[]"))
    }

    @Test
    fun `plane emits world-space polygon in JSON order`() {
        val poly = listOf(
            floatArrayOf(0f, 0f, 0f),
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(1f, 0f, 1f),
            floatArrayOf(0f, 0f, 1f),
        )
        val line = RerunWireFormat.planeJson(
            timestampNanos = 1L,
            id = 99,
            kind = "horizontal_upward",
            worldPolygon = poly,
        )
        assertTrue(line.contains("\"entity\":\"world/planes/99\""))
        assertTrue(line.contains("\"kind\":\"horizontal_upward\""))
        assertTrue(
            line.contains("\"polygon\":[[0.0,0.0,0.0],[1.0,0.0,0.0],[1.0,0.0,1.0],[0.0,0.0,1.0]]"),
        )
    }

    @Test
    fun `plane handles empty polygon`() {
        val line = RerunWireFormat.planeJson(
            timestampNanos = 1L,
            id = 0,
            kind = "vertical",
            worldPolygon = emptyList(),
        )
        assertTrue(line.contains("\"polygon\":[]"))
    }

    // ── Tier-S event types: camera trail + scalar ─────────────────────────

    @Test
    fun `cameraTrail emits canonical JSON line with default entity`() {
        val line = RerunWireFormat.cameraTrailJson(
            timestampNanos = 99L,
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f),
        )
        val expected =
            "{\"t\":99,\"type\":\"camera_trail\",\"entity\":\"world/camera/trail\"," +
                "\"positions\":[[0.0,0.0,0.0],[1.0,0.0,0.0],[1.0,1.0,0.0]]}\n"
        assertEquals(expected, line)
    }

    @Test
    fun `cameraTrail with empty positions emits empty list`() {
        val line = RerunWireFormat.cameraTrailJson(
            timestampNanos = 1L,
            positions = floatArrayOf(),
        )
        assertTrue(line.contains("\"positions\":[]"))
    }

    @Test
    fun `cameraTrail truncates trailing partial point (positions length not multiple of 3)`() {
        // Defensively: a 4-element buffer = 1 full point + leftover.
        val line = RerunWireFormat.cameraTrailJson(
            timestampNanos = 1L,
            positions = floatArrayOf(1f, 2f, 3f, 4f),
        )
        assertTrue(line.contains("[1.0,2.0,3.0]"))
        assertFalse(line.contains("4.0"))
    }

    @Test
    fun `scalar emits canonical JSON line`() {
        val line = RerunWireFormat.scalarJson(
            timestampNanos = 200L,
            value = 0.95f,
            entity = "world/camera/tracking_quality",
        )
        val expected =
            "{\"t\":200,\"type\":\"scalar\",\"entity\":\"world/camera/tracking_quality\"," +
                "\"value\":0.95}\n"
        assertEquals(expected, line)
    }

    @Test
    fun `scalar handles non-finite values by clamping to 0`() {
        val line = RerunWireFormat.scalarJson(
            timestampNanos = 1L,
            value = Float.NaN,
            entity = "world/metric",
        )
        assertTrue(line.contains("\"value\":0"))
    }

    // ── Control protocol ──────────────────────────────────────────────────

    @Test
    fun `controlSaveNow emits canonical control line`() {
        val line = RerunWireFormat.controlSaveNow()
        assertEquals("{\"type\":\"control\",\"cmd\":\"save_now\"}\n", line)
    }

    @Test
    fun `parseControlAck recognises a saved ack`() {
        val ack = RerunWireFormat.parseControlAck(
            "{\"type\":\"control\",\"ack\":\"saved\"," +
                "\"path\":\"/home/dev/.sceneview/recordings/2026-05-06.rrd\"," +
                "\"events\":1234," +
                "\"viewerUrl\":\"https://sceneview.github.io/rerun/?url=https%3A%2F%2Fexample.com%2Fa.rrd\"}",
        )!!
        assertTrue(ack.success)
        assertEquals("/home/dev/.sceneview/recordings/2026-05-06.rrd", ack.path)
        assertEquals(1234, ack.events)
        assertEquals(
            "https://sceneview.github.io/rerun/?url=https%3A%2F%2Fexample.com%2Fa.rrd",
            ack.viewerUrl,
        )
        assertEquals(null, ack.reason)
    }

    @Test
    fun `parseControlAck accepts a local save without viewerUrl`() {
        val ack = RerunWireFormat.parseControlAck(
            """{"type":"control","ack":"saved","path":"/tmp/a.rrd","events":42}""",
        )!!
        assertTrue(ack.success)
        assertEquals("/tmp/a.rrd", ack.path)
        assertEquals(42, ack.events)
        assertEquals(null, ack.viewerUrl)
        assertEquals(null, ack.reason)
    }

    @Test
    fun `parseControlAck recognises a save_unsupported ack`() {
        val ack = RerunWireFormat.parseControlAck(
            "{\"type\":\"control\",\"ack\":\"save_unsupported\"," +
                "\"reason\":\"sidecar started in live mode; relaunch with --save\"}",
        )!!
        assertFalse(ack.success)
        assertEquals(null, ack.path)
        assertEquals("sidecar started in live mode; relaunch with --save", ack.reason)
    }

    @Test
    fun `parseControlAck returns null for non-control lines`() {
        // Event lines (camera_pose etc.) are not control acks — must be ignored.
        val line = RerunWireFormat.cameraPoseJson(
            timestampNanos = 1L,
            tx = 0f, ty = 0f, tz = 0f, qx = 0f, qy = 0f, qz = 0f, qw = 1f,
        )
        assertEquals(null, RerunWireFormat.parseControlAck(line))
        assertEquals(null, RerunWireFormat.parseControlAck("{\"type\":\"control\"}"))  // no ack field
        assertEquals(null, RerunWireFormat.parseControlAck("{}"))
        assertEquals(null, RerunWireFormat.parseControlAck("not json"))
    }

    @Test
    fun `parseControlAck handles escaped characters in path`() {
        val ack = RerunWireFormat.parseControlAck(
            "{\"type\":\"control\",\"ack\":\"saved\"," +
                "\"path\":\"C:\\\\Users\\\\d\\u00e9v\\\\rec.rrd\"}",
        )!!
        assertTrue(ack.success)
        assertEquals("C:\\Users\\dév\\rec.rrd", ack.path)
    }

    // ── Newline invariant ─────────────────────────────────────────────────

    @Test
    fun `every serialized line contains exactly one newline, at the end`() {
        val lines = listOf(
            RerunWireFormat.cameraPoseJson(1L, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
            RerunWireFormat.anchorJson(1L, 1, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
            RerunWireFormat.hitResultJson(1L, 1, 0f, 0f, 0f, 1f),
            RerunWireFormat.pointCloudJson(1L, floatArrayOf(0f, 0f, 0f, 0f)),
            RerunWireFormat.planeJson(1L, 1, "horizontal_upward", emptyList()),
        )
        for (line in lines) {
            assertEquals(
                "line must have exactly one \\n (at end): $line",
                1, line.count { it == '\n' },
            )
            assertTrue(line.endsWith("\n"))
        }
    }
}
