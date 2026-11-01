package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.CORPUS
import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.OUT
import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.denseOf
import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.open
import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.scanFiles
import io.github.sceneview.demo.demos.ScanLiveRealScanTest.Companion.syntheticScan
import io.github.sceneview.demo.demos.internal.RerunGlbMesh
import io.github.sceneview.demo.demos.internal.RerunMesh
import io.github.sceneview.demo.demos.internal.RevealWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The result screen's reveal ([ModelReveal]) over real scans: each scan of
 * `SCENEVIEW_SCAN_CORPUS` (a `.svscan` or `.rrd` file, or a directory of them) is meshed as the
 * result screen meshes it, its wire built ([RevealWire]), and the reveal drawn as a storyboard —
 * one PNG a scan, [FRAMES] moments side by side, software-rasterised on the JVM — with a report
 * ([REPORT]) of the figures, into `SCENEVIEW_SCAN_OUT`. Skipped when either is unset.
 *
 * ```
 * SCENEVIEW_SCAN_CORPUS=/path/to/scans SCENEVIEW_SCAN_OUT=/tmp/scan-reveal \
 *   ./gradlew :samples:android-demo:testDebugUnitTest --tests '*ModelRevealRealScanTest*'
 * ```
 *
 * The storyboard is a flat-shaded sketch of what the phone draws (no PBR, no grid), at the same
 * moments: enough to see the wire stand, the model rise into it and the wire go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelRevealRealScanTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the reveal over the scans of SCENEVIEW_SCAN_CORPUS`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS names no scan file or directory", corpus != null && corpus.exists())
        assumeTrue("$OUT names no output directory", out != null)
        val scans = scanFiles(corpus!!)
        assertTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        assertTrue("no scan of $corpus gave a model", run(scans, out!!).isNotEmpty())
    }

    @Test
    fun `a synthetic recording runs through to a report and a storyboard`() {
        val scan = folder.newFile("walkthrough.svscan").apply { writeBytes(syntheticScan()) }
        val out = folder.newFolder("out")
        val result = run(listOf(scan), out).single()
        assertTrue("${result.triangles} triangles", result.triangles > 0)
        assertTrue("${result.edges} edges", result.edges > 0)
        assertTrue(File(out, REPORT).readText().contains("walkthrough.svscan"))
        val png = File(out, "walkthrough-reveal.png").readBytes()
        assertEquals(PNG_SIGNATURE.toList(), png.copyOf(PNG_SIGNATURE.size).toList())
        val size = ByteBuffer.wrap(png, IHDR_SIZE_AT, 8)
        assertEquals(BOARD_W, size.int)
        assertEquals(FRAME_H, size.int)
        // The first moment shows only the wire, in the accent; the last only the model.
        assertTrue("wire drawn first", result.accentPixels.first() > 0)
        assertEquals("wire gone last", 0, result.accentPixels.last())
        assertTrue("model drawn last", result.solidPixels.last() > result.solidPixels.first())
    }

    private class Result(
        val name: String,
        val triangles: Int,
        val edges: Int,
        val accentPixels: List<Int>,
        val solidPixels: List<Int>,
    )

    private companion object {
        const val REPORT = "scan-reveal.md"
        const val FRAME_W = 360
        const val FRAME_H = 300
        const val GAP_PX = 6
        val BOARD_W = FRAMES_COUNT * FRAME_W + (FRAMES_COUNT - 1) * GAP_PX
        const val FRAMES_COUNT = 5
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        const val IHDR_SIZE_AT = 16

        /** The storyboard's moments, milliseconds into the reveal. */
        val FRAMES = floatArrayOf(250f, 700f, 1300f, 1900f, ModelReveal.TOTAL_MS)

        /** The demo's dark accent (`SceneViewTokens.ArOverlay.accentProgress`) and backdrop. */
        const val ACCENT = 0xA4C1FF
        const val BACKDROP = 0x101418
        const val GAP = 0x000000

        const val FOV_DEG = 45.0
        const val YAW_DEG = 35.0
        const val PITCH_DEG = 28.0
        const val NANOS_PER_MS = 1e6

        fun run(scans: List<File>, out: File): List<Result> {
            out.mkdirs()
            val report = StringBuilder("# 3D Scan result reveal over real scans\n\n")
            report.append("The wire sweeps up for ${ModelReveal.WIRE_SWEEP_MS.toInt()} ms, the model rises from ")
            report.append("${ModelReveal.SOLID_START_MS.toInt()} ms, the wire is gone at ${ModelReveal.TOTAL_MS.toInt()} ms. ")
            report.append("Storyboard moments: ${FRAMES.joinToString { "${it.toInt()} ms" }}. Host times.\n")
            val results = scans.mapNotNull { file ->
                val dense = open(file)?.let(::denseOf)
                val built = dense?.let { buildScanModel(RerunModelSource.Surfels(it.first)) {} }
                if (built == null) {
                    report.append("\n## ${file.name}\n\nNo dense cloud, or no model from it: skipped.\n")
                    return@mapNotNull null
                }
                val mesh = RerunGlbMesh.read(built.glb)
                if (mesh == null) {
                    report.append("\n## ${file.name}\n\nThe model's `.glb` did not read back: skipped.\n")
                    return@mapNotNull null
                }
                val started = System.nanoTime()
                val wire = if (mesh.triangleCount > ModelReveal.MAX_TRIANGLES) {
                    null
                } else {
                    RevealWire.of(mesh.positions, mesh.vertexCount, mesh.indices)
                }
                val wireMs = (System.nanoTime() - started) / NANOS_PER_MS
                val stem = file.nameWithoutExtension
                val board = storyboard(mesh, wire)
                File(out, "$stem-reveal.png").writeBytes(png(board.pixels, BOARD_W))
                report.append("\n## ${file.name}\n\n")
                report.append("- Model: ${built.kind}, ${mesh.triangleCount} triangles, ${mesh.vertexCount} vertices\n")
                if (wire == null) {
                    report.append("- Wire: none (over ${ModelReveal.MAX_TRIANGLES} triangles): the model shows whole\n")
                } else {
                    val capped = if (wire.edgeCount >= RevealWire.MAX_EDGES) ", capped" else ""
                    report.append("- Wire: ${wire.edgeCount} edges$capped, built in ${"%.1f".format(wireMs)} ms, ")
                    report.append("${wire.indices.size * 4 / 1024} KiB of indices\n")
                    report.append("- Height swept: ${"%.2f".format(wire.maxY - wire.minY)} m\n")
                }
                report.append("- Storyboard: `$stem-reveal.png`\n")
                Result(file.name, mesh.triangleCount, wire?.edgeCount ?: 0, board.accent, board.solid)
            }
            File(out, REPORT).writeText(report.toString())
            return results
        }

        class Board(val pixels: IntArray, val accent: List<Int>, val solid: List<Int>)

        fun storyboard(mesh: RerunMesh, wire: RevealWire?): Board {
            check(FRAMES.size == FRAMES_COUNT)
            val image = IntArray(BOARD_W * FRAME_H) { GAP }
            val view = View.framing(mesh.positions, mesh.vertexCount)
            val accent = ArrayList<Int>()
            val solid = ArrayList<Int>()
            FRAMES.forEachIndexed { i, ms ->
                val frame = Frame(view)
                frame.draw(mesh, wire, ms)
                frame.blit(image, BOARD_W, i * (FRAME_W + GAP_PX))
                accent += frame.accentPixels
                solid += frame.solidPixels
            }
            return Board(image, accent, solid)
        }

        /** [pixels], [width] wide, as an 8-bit RGB PNG (no AWT on the unit-test classpath). */
        fun png(pixels: IntArray, width: Int): ByteArray {
            val height = pixels.size / width
            val rows = ByteArrayOutputStream()
            DeflaterOutputStream(rows).use { deflate ->
                for (y in 0 until height) {
                    deflate.write(0) // no filter
                    for (x in 0 until width) {
                        val c = pixels[y * width + x]
                        deflate.write(c shr 16 and 0xFF)
                        deflate.write(c shr 8 and 0xFF)
                        deflate.write(c and 0xFF)
                    }
                }
            }
            val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put(8).put(2).put(0).put(0).put(0).array() // 8 bits, RGB
            val file = ByteArrayOutputStream()
            file.write(PNG_SIGNATURE)
            DataOutputStream(file).apply {
                chunk("IHDR", header)
                chunk("IDAT", rows.toByteArray())
                chunk("IEND", ByteArray(0))
            }
            return file.toByteArray()
        }

        fun DataOutputStream.chunk(type: String, data: ByteArray) {
            val name = type.toByteArray()
            writeInt(data.size)
            write(name)
            write(data)
            writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
    }

    /** A camera orbiting the model's bounds, a little above, as the result's opening shot frames it. */
    private class View(val cx: Float, val cy: Float, val cz: Float, val distance: Float) {
        private val yaw = Math.toRadians(YAW_DEG)
        private val pitch = Math.toRadians(PITCH_DEG)

        // Camera basis: forward points from the eye to the centre.
        val fx = (-sin(yaw) * cos(pitch)).toFloat()
        val fy = (-sin(pitch)).toFloat()
        val fz = (-cos(yaw) * cos(pitch)).toFloat()
        val rx = cos(yaw).toFloat()
        val rz = (-sin(yaw)).toFloat()
        val ux = (-sin(yaw) * sin(pitch)).toFloat()
        val uy = cos(pitch).toFloat()
        val uz = (-cos(yaw) * sin(pitch)).toFloat()
        val focal = (FRAME_H / 2.0 / tan(Math.toRadians(FOV_DEG / 2))).toFloat()

        /** Screen x, y and depth of a world point, into [out]; `false` behind the eye. */
        fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
            val vx = x - cx
            val vy = y - cy
            val vz = z - cz
            val depth = vx * fx + vy * fy + vz * fz + distance
            if (depth <= NEAR) return false
            val sx = vx * rx + vz * rz
            val sy = vx * ux + vy * uy + vz * uz
            out[0] = FRAME_W / 2f + sx * focal / depth
            out[1] = FRAME_H / 2f - sy * focal / depth
            out[2] = depth
            return true
        }

        companion object {
            const val NEAR = 0.01f

            fun framing(positions: FloatArray, vertexCount: Int): View {
                val min = FloatArray(3) { Float.MAX_VALUE }
                val max = FloatArray(3) { -Float.MAX_VALUE }
                for (v in 0 until vertexCount) for (a in 0 until 3) {
                    min[a] = min(min[a], positions[v * 3 + a])
                    max[a] = max(max[a], positions[v * 3 + a])
                }
                val dx = max[0] - min[0]
                val dy = max[1] - min[1]
                val dz = max[2] - min[2]
                val radius = max(sqrt(dx * dx + dy * dy + dz * dz) / 2f, 0.1f)
                val distance = (radius / sin(Math.toRadians(FOV_DEG / 2))).toFloat() * 1.05f
                return View((min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2, distance)
            }
        }
    }

    /** One moment of the reveal, z-buffered. */
    private class Frame(private val view: View) {
        private val color = IntArray(FRAME_W * FRAME_H) { BACKDROP }
        private val depth = FloatArray(FRAME_W * FRAME_H) { Float.MAX_VALUE }
        private val isSolid = BooleanArray(FRAME_W * FRAME_H)
        private val isWire = BooleanArray(FRAME_W * FRAME_H)
        val accentPixels: Int get() = isWire.count { it }
        val solidPixels: Int get() = isSolid.count { it }

        fun draw(mesh: RerunMesh, wire: RevealWire?, ms: Float) {
            val rise = if (wire == null) 1f else ModelReveal.solidRise(ms)
            val floor = wire?.minY ?: 0f
            if (rise > 0f) solid(mesh, floor, max(rise, MIN_RISE))
            if (wire != null) {
                val alpha = ModelReveal.wireAlpha(ms)
                val sweep = ModelReveal.wireSweep(ms)
                if (alpha > 0f && sweep > 0f) lines(mesh.positions, wire, wire.indicesBelow(sweep), alpha)
            }
        }

        fun blit(image: IntArray, width: Int, left: Int) {
            for (y in 0 until FRAME_H) for (x in 0 until FRAME_W) image[y * width + left + x] = color[y * FRAME_W + x]
        }

        private fun solid(mesh: RerunMesh, floor: Float, height: Float) {
            val p = mesh.positions
            val a = FloatArray(3)
            val b = FloatArray(3)
            val c = FloatArray(3)
            for (t in 0 until mesh.triangleCount) {
                val i0 = mesh.indices[t * 3]
                val i1 = mesh.indices[t * 3 + 1]
                val i2 = mesh.indices[t * 3 + 2]
                val y0 = floor + (p[i0 * 3 + 1] - floor) * height
                val y1 = floor + (p[i1 * 3 + 1] - floor) * height
                val y2 = floor + (p[i2 * 3 + 1] - floor) * height
                if (!view.project(p[i0 * 3], y0, p[i0 * 3 + 2], a)) continue
                if (!view.project(p[i1 * 3], y1, p[i1 * 3 + 2], b)) continue
                if (!view.project(p[i2 * 3], y2, p[i2 * 3 + 2], c)) continue
                // Flat shading from the squashed triangle's own normal, both sides lit.
                val ex = p[i1 * 3] - p[i0 * 3]
                val ey = y1 - y0
                val ez = p[i1 * 3 + 2] - p[i0 * 3 + 2]
                val gx = p[i2 * 3] - p[i0 * 3]
                val gy = y2 - y0
                val gz = p[i2 * 3 + 2] - p[i0 * 3 + 2]
                val nx = ey * gz - ez * gy
                val ny = ez * gx - ex * gz
                val nz = ex * gy - ey * gx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                val light = if (len == 0f) 0.5f else abs(nx * LX + ny * LY + nz * LZ) / len
                val shade = AMBIENT + (1f - AMBIENT) * light
                val rgb = average(mesh.colors[i0], mesh.colors[i1], mesh.colors[i2], shade)
                triangle(a, b, c, rgb)
            }
        }

        private fun triangle(a: FloatArray, b: FloatArray, c: FloatArray, rgb: Int) {
            val area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
            if (abs(area) < 1e-6f) return
            val x0 = max(0, min(a[0], min(b[0], c[0])).toInt())
            val x1 = min(FRAME_W - 1, max(a[0], max(b[0], c[0])).toInt() + 1)
            val y0 = max(0, min(a[1], min(b[1], c[1])).toInt())
            val y1 = min(FRAME_H - 1, max(a[1], max(b[1], c[1])).toInt() + 1)
            for (y in y0..y1) for (x in x0..x1) {
                val px = x + 0.5f
                val py = y + 0.5f
                val w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area
                val w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area
                val w2 = 1f - w0 - w1
                if (w0 < 0f || w1 < 0f || w2 < 0f) continue
                val z = w0 * a[2] + w1 * b[2] + w2 * c[2]
                val at = y * FRAME_W + x
                if (z >= depth[at]) continue
                depth[at] = z
                color[at] = rgb
                isSolid[at] = true
            }
        }

        private fun lines(positions: FloatArray, wire: RevealWire, count: Int, alpha: Float) {
            val a = FloatArray(3)
            val b = FloatArray(3)
            var i = 0
            while (i < count) {
                val s = wire.indices[i]
                val e = wire.indices[i + 1]
                i += 2
                if (!view.project(positions[s * 3], positions[s * 3 + 1], positions[s * 3 + 2], a)) continue
                if (!view.project(positions[e * 3], positions[e * 3 + 1], positions[e * 3 + 2], b)) continue
                val steps = max(1, max(abs(b[0] - a[0]), abs(b[1] - a[1])).roundToInt())
                for (k in 0..steps) {
                    val f = k.toFloat() / steps
                    val x = (a[0] + (b[0] - a[0]) * f).toInt()
                    val y = (a[1] + (b[1] - a[1]) * f).toInt()
                    if (x !in 0 until FRAME_W || y !in 0 until FRAME_H) continue
                    val z = a[2] + (b[2] - a[2]) * f
                    val at = y * FRAME_W + x
                    // The wire lies on the model's own surface: a little bias keeps it over it.
                    if (z > depth[at] * (1f + DEPTH_BIAS)) continue
                    color[at] = blend(color[at], ACCENT, alpha)
                    isWire[at] = true
                }
            }
        }

        companion object {
            const val MIN_RISE = 0.002f
            const val AMBIENT = 0.35f
            const val DEPTH_BIAS = 0.01f

            // A light from above and to the camera's left, normalised.
            const val LX = -0.40f
            const val LY = 0.82f
            const val LZ = 0.41f

            fun average(c0: Int, c1: Int, c2: Int, shade: Float): Int {
                fun ch(shift: Int) = ((((c0 shr shift) and 0xFF) + ((c1 shr shift) and 0xFF) + ((c2 shr shift) and 0xFF)) / 3f * shade)
                    .roundToInt().coerceIn(0, 255)
                return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }

            fun blend(under: Int, over: Int, alpha: Float): Int {
                fun ch(shift: Int): Int {
                    val u = (under shr shift) and 0xFF
                    val o = (over shr shift) and 0xFF
                    return (u + (o - u) * alpha).roundToInt().coerceIn(0, 255)
                }
                return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
    }
}
