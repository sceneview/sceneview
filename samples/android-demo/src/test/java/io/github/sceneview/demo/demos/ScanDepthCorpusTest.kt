package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.ScanDepthFormat
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.demos.internal.model.CleanModelCorpus
import io.github.sceneview.demo.demos.internal.model.JvmImages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * The dense fusion of every session of a corpus replayed from its recorded raw depth
 * (`capture-depth.bin`, [ScanDepthReplay]), next to the cloud the phone saved:
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/sessions SCENEVIEW_SCAN_OUT=/path/to/out \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*ScanDepthCorpusTest*'
 * ```
 *
 * Skipped when either variable is unset, or when no session of the corpus recorded its depth
 * (every scan before the recording): each such session is named, and left alone. Writes, per
 * session with depth, `replay.svpc` (the re-fused cloud), `top.png` (from above), `doll.png`
 * (a dollhouse corner) and `replay.json`: the replayed and the saved cloud's counts and bounds, and
 * the share of each within 2 cm of the other.
 */
class ScanDepthCorpusTest {
    @Test
    fun `every session with recorded depth replays its fusion`() {
        val corpus = System.getenv(CORPUS)?.let(::File)
        val out = System.getenv(OUT)?.let(::File)
        assumeTrue("$CORPUS and $OUT not set", corpus != null && out != null)
        val sessions = checkNotNull(corpus).walkTopDown()
            .filter { it.isDirectory && CleanModelCorpus.isScan(it) }
            .sortedBy { it.path }
            .toList()
        assumeTrue("no session folder in $corpus", sessions.isNotEmpty())
        var replayed = 0
        for (session in sessions) {
            if (!File(session, ScanDepthFormat.FILE).isFile) {
                println("ScanDepthCorpus ${session.name}: skipped, no recorded depth (${ScanDepthFormat.FILE} absent)")
                continue
            }
            val dir = File(checkNotNull(out), session.name).apply { mkdirs() }
            val stats = runCatching { measure(session, dir) }.getOrElse { failure ->
                buildJsonObject {
                    put("session", session.name)
                    put("error", "${failure::class.simpleName}: ${failure.message}")
                }
            }
            File(dir, "replay.json").writeText(stats.toString())
            println("ScanDepthCorpus ${session.name}: $stats")
            replayed++
        }
        assumeTrue("no session of $corpus has recorded depth: nothing to replay", replayed > 0)
    }

    internal companion object Report {
        /** [session]'s replay written into [out]: the files above, and its `replay.json` figures. */
        fun measure(session: File, out: File): JsonObject {
            val started = System.nanoTime()
            val result = checkNotNull(ScanDepthReplay.replaySession(session)) { "unreadable ${ScanDepthFormat.FILE}" }
            val replayMs = (System.nanoTime() - started) / NANOS_PER_MS
            val cloud = result.cloud
            val blob = SvpcCodec.encode(cloud)
            File(out, "replay.svpc").writeBytes(blob)
            render(cloud, null)?.let { File(out, "top.png").writeBytes(it) }
            render(cloud, DOLL_AZIMUTH_DEG)?.let { File(out, "doll.png").writeBytes(it) }
            val capture = CleanModelCorpus.read(session).open()
            val device = capture?.let(CleanModelCorpus::denseCloud)
            val deviceBlob = capture?.manifest?.dense?.let { capture.bytesOf(it.path) }
            return buildJsonObject {
                put("session", session.name)
                put("depth_bytes", File(session, ScanDepthFormat.FILE).length())
                put("frames", result.frames)
                put("fused", result.fused)
                put("held", result.held)
                put("guard_changed", result.guardChanged)
                put("replay_ms", replayMs)
                put("replay_points", cloud.count)
                putJsonArray("replay_bounds") { cloud.bounds().forEach { add(JsonPrimitive(it)) } }
                if (device == null) {
                    put("device_points", 0)
                } else {
                    put("device_points", device.count)
                    putJsonArray("device_bounds") { device.bounds().forEach { add(JsonPrimitive(it)) } }
                    put("replay_within_2cm_of_device", shareWithin(cloud, device, NEAR_M))
                    put("device_within_2cm_of_replay", shareWithin(device, cloud, NEAR_M))
                    // Byte for byte the `dense/points.bin` the phone saved (SVPC keeps millimetres).
                    put("identical", deviceBlob?.contentEquals(blob) == true)
                }
            }
        }

        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        const val OUT = "SCENEVIEW_SCAN_OUT"
        const val NANOS_PER_MS = 1_000_000L
        const val NEAR_M = 0.02f
        const val IMAGE_PX = 1000
        const val MARGIN_PX = 40
        const val DOLL_AZIMUTH_DEG = 35.0
        const val DOLL_ELEVATION_DEG = 45.0
        const val BACKGROUND = 0xFFF4F4F2.toInt()

        /** The share of [a]'s points with a point of [b] within [radius]. */
        fun shareWithin(a: DenseCloud, b: DenseCloud, radius: Float): Double {
            if (a.count == 0) return 0.0
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(x: Int, y: Int, z: Int) = (x.toLong() and 0x1FFFFF shl 42) or (y.toLong() and 0x1FFFFF shl 21) or (z.toLong() and 0x1FFFFF)
            fun cell(v: Float) = floor(v / radius).toInt()
            val p = b.positions
            for (i in 0 until b.count) grid.getOrPut(key(cell(p[i * 3]), cell(p[i * 3 + 1]), cell(p[i * 3 + 2]))) { ArrayList() } += i
            val q = a.positions
            val r2 = radius * radius
            var near = 0
            for (i in 0 until a.count) {
                val x = q[i * 3]
                val y = q[i * 3 + 1]
                val z = q[i * 3 + 2]
                val cx = cell(x)
                val cy = cell(y)
                val cz = cell(z)
                var found = false
                loop@ for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    for (j in grid[key(cx + dx, cy + dy, cz + dz)] ?: continue) {
                        val ex = p[j * 3] - x
                        val ey = p[j * 3 + 1] - y
                        val ez = p[j * 3 + 2] - z
                        if (ex * ex + ey * ey + ez * ez <= r2) {
                            found = true
                            break@loop
                        }
                    }
                }
                if (found) near++
            }
            return near.toDouble() / a.count
        }

        /**
         * [cloud] drawn as coloured points, nearest on top: from above (x right, z down) for a `null`
         * [azimuthDeg], else from a dollhouse corner that far round and [DOLL_ELEVATION_DEG] up.
         */
        fun render(cloud: DenseCloud, azimuthDeg: Double?): ByteArray? {
            if (cloud.count == 0) return null
            val n = cloud.count
            val u = FloatArray(n)
            val v = FloatArray(n)
            val depth = FloatArray(n)
            val a = Math.toRadians(azimuthDeg ?: 0.0)
            val e = Math.toRadians(DOLL_ELEVATION_DEG)
            // The view's right, up and towards-the-eye axes.
            val right = floatArrayOf(cos(a).toFloat(), 0f, -sin(a).toFloat())
            val back = floatArrayOf((sin(a) * cos(e)).toFloat(), sin(e).toFloat(), (cos(a) * cos(e)).toFloat())
            val up = floatArrayOf(
                back[1] * right[2] - back[2] * right[1],
                back[2] * right[0] - back[0] * right[2],
                back[0] * right[1] - back[1] * right[0],
            )
            val p = cloud.positions
            for (i in 0 until n) {
                val x = p[i * 3]
                val y = p[i * 3 + 1]
                val z = p[i * 3 + 2]
                if (azimuthDeg == null) {
                    u[i] = x
                    v[i] = z
                    depth[i] = y
                } else {
                    u[i] = x * right[0] + y * right[1] + z * right[2]
                    v[i] = -(x * up[0] + y * up[1] + z * up[2])
                    depth[i] = x * back[0] + y * back[1] + z * back[2]
                }
            }
            val u0 = u.min()
            val v0 = v.min()
            val span = max(u.max() - u0, v.max() - v0).coerceAtLeast(0.1f)
            val scale = (IMAGE_PX - 2 * MARGIN_PX) / span
            val argb = IntArray(IMAGE_PX * IMAGE_PX) { BACKGROUND }
            val z = FloatArray(IMAGE_PX * IMAGE_PX) { Float.NEGATIVE_INFINITY }
            for (i in 0 until n) {
                val px = (MARGIN_PX + (u[i] - u0) * scale).toInt()
                val py = (MARGIN_PX + (v[i] - v0) * scale).toInt()
                for (oy in 0..1) for (ox in 0..1) {
                    val x = px + ox
                    val y = py + oy
                    if (x !in 0 until IMAGE_PX || y !in 0 until IMAGE_PX) continue
                    val at = y * IMAGE_PX + x
                    if (depth[i] <= z[at]) continue
                    z[at] = depth[i]
                    argb[at] = cloud.colors[i].takeIf { it != 0 } ?: 0xFF808080.toInt()
                }
            }
            return JvmImages.png(argb, IMAGE_PX, IMAGE_PX)
        }
    }
}
