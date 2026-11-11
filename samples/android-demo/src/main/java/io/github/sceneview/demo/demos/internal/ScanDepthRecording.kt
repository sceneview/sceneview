package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * A depth scan's raw depth, kept (the depth recording): every depth frame the scan handed to its
 * dense fusion — ARCore's DEPTH16 millimetres, its confidence, the colour each kept pixel took from
 * the camera image, the depth lens and the camera pose — and every one the pose guard held back, so
 * a change to the fusion or to the guard is judged on the JVM against a real walk instead of a new
 * one. Written beside the session's three files as `capture-depth.bin`; readers that do not know it
 * never open it. Pure Kotlin and java.io, round-trip tested on the JVM.
 *
 * File layout, all little-endian:
 * - header: `"SVDR"`, u16 version (1), u16 header bytes after this field, then i32 maxPoints,
 *   i32 maxLevel, f32 farM, f32 voxelM — the fusion's own settings on that phone.
 * - then one record per frame, until the end of the file (a cut-short tail is ignored):
 *   u32 record bytes after this field, and [ScanDepthFormat.encode]'s fields.
 */

/** The fusion's settings on the phone that recorded: what a replay runs with to give the same cloud. */
data class ScanDepthHeader(
    val maxPoints: Int,
    val maxLevel: Int,
    val farM: Float,
    val voxelM: Float,
)

/**
 * One depth frame of a scan, as the device had it when it fused it — or held it back.
 *
 * [nanos] is the ARCore frame's timestamp (the clock of `camera_pose`, `t` in the log), [depthNanos]
 * the depth image's own. [frame] is exactly what the fusion consumed ([DepthFrame]: the pose is the
 * camera's sensor pose, `Frame.camera.pose`, world from camera, ARCore's axes). [display] is the
 * display-oriented pose the pose guard judged. [minConfidence] is the confidence threshold the
 * colours were read at; [floorPlaneY] ARCore's floor height then, `null` before one; [photo] the
 * path of the photo the same frame took, if it took one.
 */
class RecordedDepthFrame(
    val nanos: Long,
    val depthNanos: Long,
    val step: PoseStep,
    val minConfidence: Int,
    val frame: DepthFrame,
    val display: DebugPose,
    val floorPlaneY: Float?,
    val photo: String?,
) {
    /** Whether the pose guard held this frame back on the device: it was not fused. */
    val rejected: Boolean get() = !step.integrates
}

/** Where one recorded frame sits in the file, for the log's `depth_frame` event. */
class ScanDepthIndexEntry(
    val index: Int,
    val nanos: Long,
    val depthNanos: Long,
    val offset: Long,
    val length: Int,
    val width: Int,
    val height: Int,
    val step: PoseStep,
    val photo: String?,
)

/** The recording [ScanDepthRecorder.finish] closed: its file, its index, and what it cost. */
class ScanDepthRecording(
    val file: File,
    val entries: List<ScanDepthIndexEntry>,
    /** The log lines that go with it: `depth_frame` per frame and `pose_guard` per guarded pose. */
    val logLines: List<String>,
    val bytes: Long,
    /** Frames let go because the writer was behind ([ScanDepthRecorder.MAX_PENDING]): never fused-and-lost silently. */
    val dropped: Int,
)

/** The byte layout of the depth recording. */
object ScanDepthFormat {
    /** The session file beside `capture-manifest.json`. */
    const val FILE = "capture-depth.bin"

    /** `"SVDR"` read as a little-endian u32. */
    const val MAGIC = 0x52445653
    const val VERSION = 1
    private const val HEADER_BODY = 16

    /** Flag bits of a record. */
    const val FLAG_REJECTED = 1
    const val FLAG_FLOOR = 2
    const val FLAG_PHOTO = 4

    /** No record is larger: a garbled length ends the read instead of allocating it. */
    private const val MAX_RECORD = 64 shl 20

    fun header(header: ScanDepthHeader): ByteArray = le(4 + 2 + 2 + HEADER_BODY)
        .putInt(MAGIC).putShort(VERSION.toShort()).putShort(HEADER_BODY.toShort())
        .putInt(header.maxPoints).putInt(header.maxLevel).putFloat(header.farM).putFloat(header.voxelM)
        .array()

    /**
     * [f] as one record, its u32 length first. After the length:
     * i64 nanos, i64 depthNanos, u8 flags ([FLAG_REJECTED], [FLAG_FLOOR], [FLAG_PHOTO]),
     * u8 pose-guard step ([PoseStep] ordinal), u16 minConfidence, u16 width, u16 height,
     * f32 fx fy cx cy (the depth lens, pixels), f32 × 7 sensor pose (tx ty tz qx qy qz qw),
     * f32 × 7 display pose, f32 floorPlaneY (NaN without), u16 photo path bytes + UTF-8 path,
     * then three zlib (deflate) blocks, each u32 length + bytes:
     * depth — i16 per pixel, row-major, each the difference from the pixel to its left (0 before
     * the first of a row), wrapping, millimetres; confidence — u8 per pixel; colours — a bit per
     * pixel (LSB first) set where the pixel has a colour, then the R, G and B planes of those
     * pixels only, in order (a colour is `0xFFRRGGBB`, none is 0).
     */
    fun encode(f: RecordedDepthFrame, deflater: Deflater = Deflater(Deflater.BEST_SPEED)): ByteArray {
        val frame = f.frame
        val w = frame.width
        val h = frame.height
        val n = w * h
        val photo = f.photo?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val deltas = le(n * 2)
        for (y in 0 until h) {
            var previous = 0
            for (x in 0 until w) {
                val d = frame.depthMm[y * w + x].toInt()
                deltas.putShort((d - previous).toShort())
                previous = d
            }
        }
        val confidence = frame.confidence?.copyOf(n) ?: ByteArray(n)
        val mask = ByteArray((n + 7) / 8)
        var present = 0
        for (i in 0 until n) {
            if (frame.colors[i] != 0) {
                mask[i shr 3] = (mask[i shr 3].toInt() or (1 shl (i and 7))).toByte()
                present++
            }
        }
        val colors = ByteArray(mask.size + present * 3)
        System.arraycopy(mask, 0, colors, 0, mask.size)
        var k = 0
        for (i in 0 until n) {
            val c = frame.colors[i]
            if (c == 0) continue
            colors[mask.size + k] = (c shr 16).toByte()
            colors[mask.size + present + k] = (c shr 8).toByte()
            colors[mask.size + 2 * present + k] = c.toByte()
            k++
        }
        val blocks = listOf(deltas.array(), confidence, colors).map { deflate(it, deflater) }
        var flags = 0
        if (f.rejected) flags = flags or FLAG_REJECTED
        if (f.floorPlaneY != null) flags = flags or FLAG_FLOOR
        if (f.photo != null) flags = flags or FLAG_PHOTO
        val fixed = 8 + 8 + 1 + 1 + 2 + 2 + 2 + 4 * 4 + 7 * 4 * 2 + 4 + 2 + photo.size
        val body = fixed + blocks.sumOf { 4 + it.size }
        val out = le(4 + body).putInt(body)
            .putLong(f.nanos).putLong(f.depthNanos)
            .put(flags.toByte()).put(f.step.ordinal.toByte())
            .putShort(f.minConfidence.toShort()).putShort(w.toShort()).putShort(h.toShort())
            .putFloat(frame.fx).putFloat(frame.fy).putFloat(frame.cx).putFloat(frame.cy)
        putPose(out, frame.pose)
        putPose(out, f.display)
        out.putFloat(f.floorPlaneY ?: Float.NaN)
        out.putShort(photo.size.toShort()).put(photo)
        for (block in blocks) out.putInt(block.size).put(block)
        return out.array()
    }

    /** The header of a recording, `null` when [input] does not start with one. Reads only the header. */
    fun readHeader(input: InputStream): ScanDepthHeader? {
        val data = DataInputStream(input)
        val head = runCatching { ByteArray(8).also { data.readFully(it) } }.getOrNull() ?: return null
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) return null
        val version = b.short.toInt()
        val size = b.short.toInt() and 0xFFFF
        if (version != VERSION || size < HEADER_BODY) return null
        val body = runCatching { ByteArray(size).also { data.readFully(it) } }.getOrNull() ?: return null
        val h = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        return ScanDepthHeader(h.int, h.int, h.float, h.float)
    }

    /** The next record of [input], `null` at the end of the file or at a tail cut short. */
    fun readFrame(input: InputStream): RecordedDepthFrame? {
        val data = DataInputStream(input)
        val lengthBytes = ByteArray(4)
        try {
            data.readFully(lengthBytes)
        } catch (_: EOFException) {
            return null
        }
        val length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (length <= 0 || length > MAX_RECORD) return null
        val body = ByteArray(length)
        try {
            data.readFully(body)
        } catch (_: EOFException) {
            return null
        }
        return runCatching { decode(body) }.getOrNull()
    }

    /** Whether [file] opens as a depth recording: its header reads. */
    fun readable(file: File): Boolean =
        runCatching { BufferedInputStream(file.inputStream()).use { readHeader(it) != null } }.getOrDefault(false)

    /** Every frame of [file], read one at a time: a long scan's recording never sits whole on the heap. */
    fun <T> read(file: File, use: (ScanDepthHeader, Sequence<RecordedDepthFrame>) -> T): T? =
        BufferedInputStream(file.inputStream(), STREAM_BUFFER).use { input ->
            val header = readHeader(input) ?: return null
            use(header, generateSequence { readFrame(input) })
        }

    private fun decode(body: ByteArray): RecordedDepthFrame {
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val nanos = b.long
        val depthNanos = b.long
        val flags = b.get().toInt() and 0xFF
        val step = PoseStep.entries[b.get().toInt() and 0xFF]
        val minConfidence = b.short.toInt() and 0xFFFF
        val w = b.short.toInt() and 0xFFFF
        val h = b.short.toInt() and 0xFFFF
        val fx = b.float
        val fy = b.float
        val cx = b.float
        val cy = b.float
        val pose = pose(b)
        val display = pose(b)
        val floor = b.float.takeIf { flags and FLAG_FLOOR != 0 }
        val photoBytes = ByteArray(b.short.toInt() and 0xFFFF).also { b.get(it) }
        val photo = if (flags and FLAG_PHOTO != 0) String(photoBytes, Charsets.UTF_8) else null
        val n = w * h
        val deltas = ByteBuffer.wrap(inflate(block(b), n * 2)).order(ByteOrder.LITTLE_ENDIAN)
        val depth = ShortArray(n)
        for (y in 0 until h) {
            var previous = 0
            for (x in 0 until w) {
                previous = (previous + deltas.short) and 0xFFFF
                depth[y * w + x] = previous.toShort()
            }
        }
        val confidence = inflate(block(b), n)
        val colorBytes = inflate(block(b), -1)
        val maskBytes = (n + 7) / 8
        val present = (colorBytes.size - maskBytes) / 3
        val colors = IntArray(n)
        var k = 0
        for (i in 0 until n) {
            if ((colorBytes[i shr 3].toInt() shr (i and 7)) and 1 == 0) continue
            val r = colorBytes[maskBytes + k].toInt() and 0xFF
            val g = colorBytes[maskBytes + present + k].toInt() and 0xFF
            val bl = colorBytes[maskBytes + 2 * present + k].toInt() and 0xFF
            colors[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            k++
        }
        val frame = DepthFrame(w, h, depth, confidence, colors, fx, fy, cx, cy, pose)
        return RecordedDepthFrame(nanos, depthNanos, step, minConfidence, frame, display, floor, photo)
    }

    /** The `depth_frame` event of [entry], in the log's wire format. */
    fun depthFrameLine(entry: ScanDepthIndexEntry): String = buildJsonObject {
        put("t", entry.nanos)
        put("type", DEPTH_FRAME)
        put("entity", "world/camera/depth")
        put("index", entry.index)
        put("file", FILE)
        put("offset", entry.offset)
        put("length", entry.length)
        put("depth_t", entry.depthNanos)
        put("width", entry.width)
        put("height", entry.height)
        put("step", entry.step.name)
        put("rejected", !entry.step.integrates)
        entry.photo?.let { put("photo", it) }
    }.toString()

    /**
     * The `pose_guard` event: a frame the pose guard did not read as continuous, its display pose
     * and its verdict. With the log's `camera_pose` events (the frames it integrated) they are every
     * pose the guard was handed, so the guard replays on its own.
     */
    fun poseGuardLine(nanos: Long, pose: DebugPose, step: PoseStep): String? {
        val values = floatArrayOf(pose.x, pose.y, pose.z, pose.qx, pose.qy, pose.qz, pose.qw)
        if (values.any { !it.isFinite() }) return null
        return buildJsonObject {
            put("t", nanos)
            put("type", POSE_GUARD)
            put("entity", "world/camera")
            put("step", step.name)
            putJsonArray("translation") { for (i in 0 until 3) add(JsonPrimitive(values[i])) }
            putJsonArray("quaternion") { for (i in 3 until 7) add(JsonPrimitive(values[i])) }
        }.toString()
    }

    /** A `pose_guard` line read back: its time, display pose and verdict; `null` for any other line. */
    fun parsePoseGuard(obj: JsonObject): Triple<Long, DebugPose, PoseStep>? {
        if ((obj["type"] as? JsonPrimitive)?.content != POSE_GUARD) return null
        val t = (obj["t"] as? JsonPrimitive)?.longOrNull ?: return null
        val step = PoseStep.entries.firstOrNull { it.name == (obj["step"] as? JsonPrimitive)?.content } ?: return null
        fun floats(key: String) = (obj[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
        val p = floats("translation")?.takeIf { it.size == 3 } ?: return null
        val q = floats("quaternion")?.takeIf { it.size == 4 } ?: return null
        return Triple(t, DebugPose(p[0], p[1], p[2], q[0], q[1], q[2], q[3]), step)
    }

    const val DEPTH_FRAME = "depth_frame"
    const val POSE_GUARD = "pose_guard"
    private const val STREAM_BUFFER = 1 shl 16

    private fun block(b: ByteBuffer): ByteArray = ByteArray(b.int).also { b.get(it) }

    private fun deflate(bytes: ByteArray, deflater: Deflater): ByteArray {
        deflater.reset()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 2 + 64)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** [bytes] inflated; [expected] bytes exactly, or any size for `-1`. */
    private fun inflate(bytes: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(if (expected > 0) expected else bytes.size * 3)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IOException("truncated block")
                out.write(buffer, 0, n)
            }
            val result = out.toByteArray()
            if (expected >= 0 && result.size != expected) throw IOException("block of ${result.size} bytes, $expected expected")
            return result
        } finally {
            inflater.end()
        }
    }

    private fun putPose(b: ByteBuffer, p: DebugPose) {
        b.putFloat(p.x).putFloat(p.y).putFloat(p.z).putFloat(p.qx).putFloat(p.qy).putFloat(p.qz).putFloat(p.qw)
    }

    private fun pose(b: ByteBuffer) = DebugPose(b.float, b.float, b.float, b.float, b.float, b.float, b.float)

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

/**
 * Streams a scan's depth frames to [file] as [ScanDepthFormat] lays them out, on its own thread:
 * [record] only queues the frame (its arrays are shared, never copied — the fusion only reads
 * them), at most [MAX_PENDING] waiting, so the scan's heap stays bounded and the render thread
 * never touches the disk. A write that fails stops the recording; the scan goes on without it.
 *
 * [record] and [noteGuard] from one thread (the scan's main thread); [finish] once, off it.
 */
class ScanDepthRecorder(
    val file: File,
    private val header: ScanDepthHeader,
    private val sweepOlderThanMillis: Long = SWEEP_AFTER_MS,
    private val maxPending: Int = MAX_PENDING,
) {
    // One thread, in order, that ends when idle: a scan dropped without [finish] leaks none.
    private val writer: ExecutorService = ThreadPoolExecutor(
        1, 1, IDLE_S, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { task -> Thread(task, "scan-depth-writer").apply { isDaemon = true } }.apply { allowCoreThreadTimeOut(true) }
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val deflater = Deflater(Deflater.BEST_SPEED)
    private var out: OutputStream? = null
    private var offset = 0L

    @Volatile
    private var failed = false
    private val entries = ArrayList<ScanDepthIndexEntry>()
    private val guardLines = ArrayList<Pair<Long, String>>()
    @Volatile
    private var finished: ScanDepthRecording? = null
    private var count = 0

    /** Why the recording stopped, if it did: a device log's line. */
    @Volatile
    var lastError: Throwable? = null
        private set

    init {
        writer.execute {
            try {
                file.parentFile?.let { dir ->
                    dir.mkdirs()
                    // What a scan never saved left behind (discarded, or the app killed): gone after a while.
                    val now = System.currentTimeMillis()
                    dir.listFiles().orEmpty()
                        .filter { it != file && it.isFile && now - it.lastModified() > sweepOlderThanMillis }
                        .forEach { it.delete() }
                }
                val bytes = ScanDepthFormat.header(header)
                out = BufferedOutputStream(FileOutputStream(file), WRITE_BUFFER).apply { write(bytes) }
                offset = bytes.size.toLong()
            } catch (e: IOException) {
                fail(e)
            }
        }
    }

    /** Queues [frame]; `false` when it is let go — the writer behind, failed, or finished. */
    fun record(frame: RecordedDepthFrame): Boolean {
        if (failed || finished != null || writer.isShutdown) return false
        if (pending.incrementAndGet() > maxPending) {
            pending.decrementAndGet()
            dropped.incrementAndGet()
            return false
        }
        val index = count++
        val queued = runCatching { writer.execute { write(index, frame) } }.isSuccess
        if (!queued) pending.decrementAndGet()
        return queued
    }

    @Suppress("TooGenericExceptionCaught") // a frame that does not encode stops the recording, never the scan
    private fun write(index: Int, frame: RecordedDepthFrame) {
        try {
            val stream = out ?: return
            if (failed) return
            val bytes = ScanDepthFormat.encode(frame, deflater)
            stream.write(bytes)
            val f = frame.frame
            synchronized(entries) {
                entries += ScanDepthIndexEntry(
                    index, frame.nanos, frame.depthNanos, offset, bytes.size, f.width, f.height, frame.step, frame.photo,
                )
            }
            offset += bytes.size
        } catch (e: Exception) {
            fail(e)
        } finally {
            pending.decrementAndGet()
        }
    }

    /** Notes a pose the guard did not read as [PoseStep.Continuous]: the log's `pose_guard` event. */
    fun noteGuard(nanos: Long, display: DebugPose, step: PoseStep) {
        if (finished != null) return
        val line = ScanDepthFormat.poseGuardLine(nanos, display, step) ?: return
        synchronized(guardLines) { guardLines += nanos to line }
    }

    /**
     * Writes what is queued, closes the file and returns the recording; `null` when nothing was
     * recorded or the file failed — and then the file is deleted. Blocking: off the main thread.
     * Idempotent.
     */
    @Synchronized
    fun finish(): ScanDepthRecording? {
        finished?.let { return it }
        runCatching {
            writer.execute {
                runCatching { out?.flush() }.onFailure { fail(it) }
                runCatching { out?.close() }
                out = null
            }
        }
        writer.shutdown()
        if (!writer.awaitTermination(FINISH_TIMEOUT_S, TimeUnit.SECONDS)) failed = true
        deflater.end()
        val frames = synchronized(entries) { entries.toList() }
        if (failed || frames.isEmpty() || !file.isFile) {
            file.delete()
            return null
        }
        val lines = (synchronized(guardLines) { guardLines.toList() } + frames.map { it.nanos to ScanDepthFormat.depthFrameLine(it) })
            .sortedBy { it.first }
            .map { it.second }
        return ScanDepthRecording(file, frames, lines, file.length(), dropped.get()).also { finished = it }
    }

    /**
     * Lets the recording go, for a scan dropped unsaved: the file is closed and, unless [finish]
     * already handed it on, deleted. Never blocks. Idempotent.
     */
    fun discard() {
        if (writer.isShutdown) return
        runCatching {
            writer.execute {
                runCatching { out?.close() }
                out = null
                if (finished == null) file.delete()
            }
        }
        writer.shutdown()
    }

    private fun fail(error: Throwable) {
        failed = true
        lastError = error
        runCatching { out?.close() }
        out = null
    }

    companion object {
        /** Frames queued at most: about 1 MB of depth at ARCore's 160 × 90, and under a second of scan. */
        const val MAX_PENDING = 8

        /** A temp recording no scan saved is swept this long after it was last written. */
        const val SWEEP_AFTER_MS = 30L * 60 * 1000

        private const val WRITE_BUFFER = 1 shl 16
        private const val FINISH_TIMEOUT_S = 30L
        private const val IDLE_S = 5L
    }
}
