package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RerunSessionTest {

    // ── The log, written back ─────────────────────────────────────────────────

    @Test
    fun `a trace's journal, written and read back, rebuilds the same trace`() {
        val events = File(bundledDir(), "showcase-session.jsonl").useLines { parseArDebugLog(it) }
        val source = ArDebugTrace().apply { journal = ArrayList() }
        events.forEach { it.applyTo(source) }

        val text = ArDebugLogWriter.write(source.journal!!)
        val back = ArDebugTrace.of(parseArDebugLog(text.lineSequence()))

        assertEquals(source.duration, back.duration, 0f)
        assertEquals(source.imageCount, back.imageCount)
        for (time in listOf(0f, source.duration / 3f, source.duration / 2f, source.duration)) {
            assertSameFrame(source.frameAt(time), back.frameAt(time))
        }
    }

    @Test
    fun `point colours survive the round trip, a point without one included`() {
        val red = 0xFFFF0000.toInt()
        val teal = 0xFF00A0B0.toInt()
        val points = ArDebugEvent.Points(
            nanos = 5L,
            positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 2f, 0f, 0f),
            confidences = null,
            colors = intArrayOf(red, 0, teal),
        )
        val line = ArDebugLogWriter.line(points)!!
        val back = parseArDebugEvent(line) as ArDebugEvent.Points

        assertArrayEquals(points.positions, back.positions, 0f)
        assertArrayEquals(intArrayOf(red, 0, teal), back.colors)
    }

    @Test
    fun `every event kind reads back as itself`() {
        val pose = DebugPose(0.1f, -1.25f, 3.3333333f, 0.1f, 0.2f, 0.3f, 0.927362f)
        val polygon = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
        val events = listOf(
            ArDebugEvent.CameraPose(1L, pose),
            ArDebugEvent.Anchor(2L, 7, pose),
            ArDebugEvent.Plane(3L, 42, DebugPlaneKind.Wall, polygon),
            ArDebugEvent.Image(4L, "scan/frame-0001.jpg"),
            ArDebugEvent.Points(5L, FloatArray(0), null),
        )
        val back = parseArDebugLog(ArDebugLogWriter.write(events).lineSequence())

        assertEquals(events.size, back.size)
        assertEquals(pose, (back[0] as ArDebugEvent.CameraPose).pose)
        assertEquals(7, (back[1] as ArDebugEvent.Anchor).id)
        assertEquals(pose, (back[1] as ArDebugEvent.Anchor).pose)
        val plane = back[2] as ArDebugEvent.Plane
        assertEquals(42, plane.id)
        assertEquals(DebugPlaneKind.Wall, plane.kind)
        assertArrayEquals(polygon, plane.polygon, 0f)
        assertEquals("scan/frame-0001.jpg", (back[3] as ArDebugEvent.Image).path)
        assertEquals(0, (back[4] as ArDebugEvent.Points).positions.size)
        assertTrue(back.zip(events).all { (a, b) -> a.nanos == b.nanos })
    }

    @Test
    fun `a pose that is not finite is not written`() {
        val broken = ArDebugEvent.CameraPose(1L, DebugPose(Float.NaN, 0f, 0f, 0f, 0f, 0f, 1f))
        assertNull(ArDebugLogWriter.line(broken))
    }

    // ── The manifest, written back ────────────────────────────────────────────

    @Test
    fun `a manifest written back parses to the same lens, textures and media`() {
        val manifest = ReplayManifest(
            lens = ReplayLens(0.3746f, 0.49951f),
            frameRate = 3.7f,
            frameCount = 64,
            floorY = -1.42f,
            textures = listOf(
                ReplayPlaneTexture(
                    3,
                    "planes/plane-3.jpg",
                    Vec3(-1f, -1.42f, -2f),
                    Vec3(2.5f, 0f, 0f),
                    Vec3(0f, 0f, 3f),
                ),
            ),
            media = linkedMapOf(
                "scan/frame-0001.jpg" to MediaSpan(0, 1500),
                "planes/plane-3.jpg" to MediaSpan(1500, 9000),
            ),
        )
        val back = ReplayManifest.parse(manifest.toJson())!!

        assertEquals(manifest.lens, back.lens)
        assertEquals(manifest.frameRate, back.frameRate, 0f)
        assertEquals(manifest.frameCount, back.frameCount)
        assertEquals(manifest.floorY!!, back.floorY!!, 0f)
        assertEquals(manifest.media, back.media)
        val texture = back.textureFor(3)!!
        assertEquals("planes/plane-3.jpg", texture.path)
        assertEquals(Vec3(-1f, -1.42f, -2f), texture.origin)
        assertEquals(Vec3(2.5f, 0f, 0f), texture.u)
        assertEquals(Vec3(0f, 0f, 3f), texture.v)
    }

    private fun assertSameFrame(a: ArDebugFrame, b: ArDebugFrame) {
        assertArrayEquals(a.trail, b.trail, 0f)
        assertEquals(a.camera, b.camera)
        assertArrayEquals(a.mapPoints, b.mapPoints, 0f)
        assertArrayEquals(a.livePoints, b.livePoints, 0f)
        assertEquals(a.mapPointColors?.toList(), b.mapPointColors?.toList())
        assertEquals(a.planes.map { it.id to it.kind }, b.planes.map { it.id to it.kind })
        a.planes.zip(b.planes).forEach { (p, q) -> assertArrayEquals(p.polygon, q.polygon, 0f) }
        assertEquals(a.anchors, b.anchors)
        assertEquals(a.keyframes, b.keyframes)
        assertEquals(a.image, b.image)
        assertNotNull(b)
    }

    private fun bundledDir(): File = listOf(
        File("src/main/assets/${RerunReplayAssets.DIR}"),
        File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
    ).first { it.isDirectory }
}
