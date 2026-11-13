package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugLogWriter
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.DepthFrame
import io.github.sceneview.demo.demos.internal.PoseContinuity
import io.github.sceneview.demo.demos.internal.PoseStep
import io.github.sceneview.demo.demos.internal.RecordedDepthFrame
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunReplayAssets
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.ScanDepthHeader
import io.github.sceneview.demo.demos.internal.ScanDepthRecorder
import io.github.sceneview.demo.demos.internal.ScanDepthRecording
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ScanDevice
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The depth recording of a scan (`capture-depth.bin`) and its replay: a synthetic room scanned
 * through the recorder, then replayed, gives the cloud the live fusion gave — pose guard included.
 */
class ScanDepthReplayTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a frame round-trips exactly, unsigned depth, black and missing colours included`() {
        val w = 7
        val h = 5
        val random = Random(3)
        val depth = ShortArray(w * h) { (random.nextInt(65_536) - 32_768).toShort() }
        depth[0] = 0
        depth[1] = 0xFFFF.toShort() // 65.535 m: unsigned millimetres
        val confidence = ByteArray(w * h) { random.nextInt(256).toByte() }
        val colors = IntArray(w * h) { if (it % 3 == 0) 0 else 0xFF000000.toInt() or random.nextInt(0x1000000) }
        colors[2] = 0xFF000000.toInt() // black is a colour, 0 is none
        val frame = DepthFrame(w, h, depth, confidence, colors, 5.5f, 6.5f, 3.25f, 2.75f, DebugPose(1f, 2f, 3f, 0f, 0.6f, 0f, 0.8f))
        val recorded = RecordedDepthFrame(
            nanos = 123_456_789_012L, depthNanos = 123_400_000_000L, step = PoseStep.Held, minConfidence = 140,
            frame = frame, display = DebugPose(-1f, 0.5f, 2f, 0.1f, 0.2f, 0.3f, 0.927f), floorPlaneY = null, photo = "photos/frame-0007.jpg",
        )
        val file = folder.newFile("one.bin")
        file.writeBytes(ScanDepthFormat.header(HEADER) + ScanDepthFormat.encode(recorded))

        val back = ScanDepthFormat.read(file) { header, frames -> header to frames.toList() }!!
        assertEquals(HEADER, back.first)
        val r = back.second.single()
        assertEquals(recorded.nanos, r.nanos)
        assertEquals(recorded.depthNanos, r.depthNanos)
        assertEquals(PoseStep.Held, r.step)
        assertTrue(r.rejected)
        assertEquals(140, r.minConfidence)
        assertNull(r.floorPlaneY)
        assertEquals("photos/frame-0007.jpg", r.photo)
        assertEquals(recorded.display, r.display)
        assertEquals(frame.pose, r.frame.pose)
        assertEquals(listOf(5.5f, 6.5f, 3.25f, 2.75f), listOf(r.frame.fx, r.frame.fy, r.frame.cx, r.frame.cy))
        assertArrayEquals(depth, r.frame.depthMm)
        assertArrayEquals(confidence, r.frame.confidence)
        assertArrayEquals(colors, r.frame.colors)
    }

    @Test
    fun `a recording cut mid-frame reads up to the last whole frame`() {
        val recording = record(room(frames = 4)).recording
        val bytes = recording.file.readBytes()
        val cut = folder.newFile("cut.bin").apply { writeBytes(bytes.copyOf(bytes.size - 10)) }
        assertEquals(3, ScanDepthFormat.read(cut) { _, frames -> frames.count() })
        assertNull(ScanDepthFormat.read(folder.newFile("not.bin").apply { writeBytes(ByteArray(40)) }) { _, f -> f.count() })
    }

    @Test
    fun `a synthetic room recorded then replayed gives the cloud the live fusion gave`() {
        val scan = record(room(frames = 24))
        assertEquals(24, scan.recording.entries.size)
        assertEquals(0, scan.recording.dropped)
        assertTrue("the live fusion built a room", scan.live.count > 1_000)

        val replayed = ScanDepthReplay.replay(scan.recording.file, guard = null)!!
        assertEquals(24, replayed.fused)
        assertSameCloud(scan.live, replayed.cloud)
        // The offsets the log indexes are the records'.
        val bytes = scan.recording.file.readBytes()
        for (entry in scan.recording.entries) {
            val length = java.nio.ByteBuffer.wrap(bytes, entry.offset.toInt(), 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            assertEquals(entry.length, length + 4)
        }
        println("ScanDepthReplay: ${bytes.size / scan.recording.entries.size} bytes per ${W}x$H synthetic frame")
    }

    @Test
    fun `the pose guard replays from the log, held frames recorded and left out as on the phone`() {
        // Frames 10 to 13 slipped 1 m in a tenth of a second, then came back.
        val frames = room(frames = 24, sweep = Math.PI / 2) { i -> if (i in 10..13) 1f else 0f }
        val scan = record(frames, guard = PoseContinuity())
        assertTrue("the guard held frames", scan.held > 0)
        assertEquals(24, scan.recording.entries.size)

        val replayed = ScanDepthReplay.replay(scan.recording.file, scan.log, PoseContinuity())!!
        assertEquals(scan.held, replayed.held)
        assertEquals(0, replayed.guardChanged)
        assertSameCloud(scan.live, replayed.cloud)
        // Without the log, the phone's verdicts: the same cloud.
        assertSameCloud(scan.live, ScanDepthReplay.replay(scan.recording.file, guard = null)!!.cloud)
        // A guard that lets everything through fuses the held frames too: a different cloud.
        val loose = ScanDepthReplay.replay(scan.recording.file, scan.log, PoseContinuity(maxMetresPerSecond = 1e9f, maxDegreesPerSecond = 1e9f))!!
        assertEquals(0, loose.held)
        assertEquals(scan.held, loose.guardChanged)
        assertFalse(loose.cloud.positions.contentEquals(scan.live.positions))
    }

    @Test
    fun `a session keeps its depth beside the three files, which open as before`() {
        val scan = record(room(frames = 6), guard = PoseContinuity())
        val pack = bundled()
        val store = RerunSessionStore(folder.newFolder("sessions"))
        val withDepth = RerunCapturePack(
            pack.manifest, ArDebugLogWriter.writeBytes(parseArDebugLog(pack.log), scan.recording.logLines), pack.media,
            depth = scan.recording.file,
        )
        // The new lines are skipped by the log's reader: the same events.
        assertEquals(parseArDebugLog(pack.log).size, parseArDebugLog(withDepth.log).size)
        val session = store.save(withDepth, "Room", RerunSessionSource.Recorded)
        val dir = store.directoryOf(session.id)
        assertTrue(File(dir, ScanDepthFormat.FILE).isFile)
        assertFalse("moved, not copied", scan.recording.file.exists())
        assertNotNull(store.capture(session.id)?.open())
        assertNotNull(ScanDepthReplay.replaySession(dir))
        // An old session has none: no replay.
        val old = store.save(pack, "Old", RerunSessionSource.Recorded)
        assertNull(ScanDepthReplay.replaySession(store.directoryOf(old.id)))
    }

    @Test
    fun `a scan built and saved with its depth replays, in the corpus report, to the cloud it saved`() {
        val scan = record(room(frames = 12), guard = PoseContinuity())
        val events = parseArDebugLog(scan.log)
        val device = ScanDevice("android", "test", ScanDevice.TIER_DEPTH, ScanDevice.SOURCE_RAW_DEPTH)
        val pack = runBlocking {
            RerunCaptureBuilder.build(events, ReplayLens.Default, emptyList(), device, scan.live, bakePlanes = false, depth = scan.recording)
        }
        // The builder's log carries the recording's lines after the events, which its reader skips.
        val log = String(pack.log)
        assertEquals(12, log.lines().count { it.contains("\"type\":\"depth_frame\"") })
        assertEquals(events.size, parseArDebugLog(pack.log).size)
        val store = RerunSessionStore(folder.newFolder("built"))
        val dir = store.directoryOf(store.save(pack, "Room", RerunSessionSource.Recorded).id)

        val out = folder.newFolder("report")
        val report = ScanDepthCorpusTest.measure(dir, out)
        assertEquals(scan.live.count, report["replay_points"]!!.toString().toInt())
        assertEquals("true", report["identical"].toString())
        assertEquals("1.0", report["replay_within_2cm_of_device"].toString())
        for (name in listOf("replay.svpc", "top.png", "doll.png")) assertTrue(name, File(out, name).length() > 0)
    }

    @Test
    fun `a dropped recording deletes its file`() {
        val file = File(folder.newFolder("tmp"), "depth.bin")
        val recorder = ScanDepthRecorder(file, HEADER)
        val frame = room(frames = 1).single()
        assertTrue(recorder.record(RecordedDepthFrame(1L, 1L, PoseStep.Continuous, 128, frame.depth, frame.pose, 0f, null)))
        recorder.discard()
        Thread.sleep(200)
        assertFalse(file.exists())
        assertFalse(recorder.record(RecordedDepthFrame(2L, 2L, PoseStep.Continuous, 128, frame.depth, frame.pose, 0f, null)))
    }

    private class Synthetic(val nanos: Long, val pose: DebugPose, val depth: DepthFrame)

    private class Recorded(val live: DenseCloud, val recording: ScanDepthRecording, val log: ByteArray, val held: Int)

    /** Fuses [frames] as [ScanCapture] does — each recorded, the guard's held ones only recorded. */
    private fun record(frames: List<Synthetic>, guard: PoseContinuity? = null): Recorded {
        val map = ScanDenseMap(maxPoints = HEADER.maxPoints, maxLevel = HEADER.maxLevel)
        val recorder = ScanDepthRecorder(File(folder.newFolder(), "depth.bin"), HEADER, maxPending = Int.MAX_VALUE)
        val events = ArrayList<ArDebugEvent>()
        var held = 0
        for (f in frames) {
            val step = guard?.step(f.nanos, f.pose) ?: PoseStep.Continuous
            if (step != PoseStep.Continuous) recorder.noteGuard(f.nanos, f.pose, step)
            if (!step.integrates) {
                recorder.record(RecordedDepthFrame(f.nanos, f.nanos, step, CONFIDENCE, f.depth, f.pose, map.floorPlaneY, null))
                held++
                continue
            }
            events += ArDebugEvent.CameraPose(f.nanos, f.pose)
            map.floorPlaneY = 0f
            assertTrue(recorder.record(RecordedDepthFrame(f.nanos, f.nanos - 5, step, CONFIDENCE, f.depth, f.pose, 0f, null)))
            map.fuse(f.depth, f.nanos, HEADER.farM)
        }
        val recording = checkNotNull(recorder.finish())
        return Recorded(map.savedCloud(), recording, ArDebugLogWriter.writeBytes(events, recording.logLines), held)
    }

    private fun assertSameCloud(expected: DenseCloud, actual: DenseCloud) {
        assertEquals(expected.count, actual.count)
        assertArrayEquals(expected.positions, actual.positions, 0f)
        assertArrayEquals(expected.colors, actual.colors)
        assertArrayEquals(expected.normals, actual.normals, 0f)
    }

    /**
     * A 3 × 2.5 × 3 m room with a 60 cm cube on its floor, seen turning on the spot at 1.4 m, a
     * frame every tenth of a second, over [sweep] radians; [offsetX] shifts a frame's pose sideways (a slipped pose).
     */
    private fun room(frames: Int, sweep: Double = 2.0 * Math.PI, offsetX: (Int) -> Float = { 0f }): List<Synthetic> =
        (0 until frames).map { i ->
        val yaw = sweep * i / frames
        val pose = DebugPose(offsetX(i), EYE_Y, 0f, 0f, sin(yaw / 2).toFloat(), 0f, cos(yaw / 2).toFloat())
        Synthetic(START_NANOS + i * FRAME_NANOS, pose, depthOf(pose))
    }

    private fun depthOf(pose: DebugPose): DepthFrame {
        val depth = ShortArray(W * H)
        val colors = IntArray(W * H)
        for (py in 0 until H) {
            for (px in 0 until W) {
                val lx = (px - W / 2f) / FOCAL
                val ly = -(py - H / 2f) / FOCAL
                val len = sqrt(lx * lx + ly * ly + 1f)
                val d = pose.rotate(lx / len, ly / len, -1f / len)
                val o = floatArrayOf(pose.x, pose.y, pose.z)
                val v = floatArrayOf(d.x, d.y, d.z)
                // Inside the room: the nearest wall the ray leaves through.
                var best = Float.MAX_VALUE
                var face = 0
                for (a in 0 until 3) {
                    if (abs(v[a]) < 1e-6f) continue
                    val t = ((if (v[a] > 0) ROOM_MAX[a] else ROOM_MIN[a]) - o[a]) / v[a]
                    if (t > 0 && t < best) {
                        best = t
                        face = a * 2 + if (v[a] > 0) 1 else 0
                    }
                }
                cube(o, v)?.let { t -> if (t < best) { best = t; face = 6 } }
                val mm = (best / len * MM).roundToInt()
                if (mm !in 1..Short.MAX_VALUE) continue
                depth[py * W + px] = mm.toShort()
                colors[py * W + px] = PALETTE[face]
            }
        }
        val confidence = ByteArray(W * H) { 0xFF.toByte() }
        return DepthFrame(W, H, depth, confidence, colors, FOCAL, FOCAL, W / 2f, H / 2f, pose)
    }

    private fun cube(o: FloatArray, v: FloatArray): Float? {
        var lo = 1e-4f
        var hi = Float.MAX_VALUE
        for (a in 0 until 3) {
            if (abs(v[a]) < 1e-9f) {
                if (o[a] < CUBE_MIN[a] || o[a] > CUBE_MAX[a]) return null
                continue
            }
            val t0 = (CUBE_MIN[a] - o[a]) / v[a]
            val t1 = (CUBE_MAX[a] - o[a]) / v[a]
            lo = maxOf(lo, min(t0, t1))
            hi = min(hi, maxOf(t0, t1))
            if (lo > hi) return null
        }
        return lo
    }

    private fun bundled(): RerunCapturePack {
        val dir = listOf(
            File("src/main/assets/${RerunReplayAssets.DIR}"),
            File("samples/android-demo/src/main/assets/${RerunReplayAssets.DIR}"),
        ).first { it.isDirectory }
        return RerunCapturePack(
            manifest = File(dir, "showcase-manifest.json").readBytes(),
            log = File(dir, "showcase-session.jsonl").readBytes(),
            media = File(dir, "showcase-media.bin").readBytes(),
        )
    }

    private companion object {
        val HEADER = ScanDepthHeader(maxPoints = 200_000, maxLevel = DenseFusion.MAX_LEVEL, farM = 5f, voxelM = DenseFusion.VOXEL_M)
        const val W = 160
        const val H = 90
        const val FOCAL = 125f
        const val MM = 1000f
        const val EYE_Y = 1.4f
        const val CONFIDENCE = 128
        const val START_NANOS = 1_000_000_000L
        const val FRAME_NANOS = 100_000_000L
        val ROOM_MIN = floatArrayOf(-1.5f, 0f, -1.5f)
        val ROOM_MAX = floatArrayOf(1.5f, 2.5f, 1.5f)
        val CUBE_MIN = floatArrayOf(0.4f, 0f, -1.2f)
        val CUBE_MAX = floatArrayOf(1.0f, 0.6f, -0.6f)
        val PALETTE = intArrayOf(
            0xFFB04030.toInt(), 0xFF30B040.toInt(), 0xFF808080.toInt(), 0xFFE0E0E0.toInt(),
            0xFF3040B0.toInt(), 0xFFB0B030.toInt(), 0xFF202020.toInt(),
        )
    }
}
