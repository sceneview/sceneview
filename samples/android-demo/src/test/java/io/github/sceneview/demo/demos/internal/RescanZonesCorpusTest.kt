package io.github.sceneview.demo.demos.internal

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The parts to scan again on real scans (#4736): every `.svscan` and `.rrd` under
 * `SCENEVIEW_SCAN_CORPUS`, read with the demo's own readers, its zones written to
 * `SCENEVIEW_SCAN_OUT` — `<scan file>-rescan.txt`, the zones in words, and `<scan file>-rescan.png`,
 * the cloud seen from above with each zone framed — plus `rescan-summary.txt`.
 *
 * Skipped when either variable is unset: scans stay off the repository. Run with
 * `SCENEVIEW_SCAN_CORPUS=<dir> SCENEVIEW_SCAN_OUT=<dir> ./gradlew :samples:android-demo:testDebugUnitTest
 * --tests '*RescanZonesCorpusTest' --rerun`.
 */
class RescanZonesCorpusTest {
    @Test
    fun `zones of every scan in the corpus`() {
        val corpus = System.getenv(CORPUS)?.takeIf { it.isNotBlank() }?.let(::File)
        val out = System.getenv(OUT)?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("$CORPUS and $OUT not both set", corpus != null && out != null)
        val scans = corpus!!.walkTopDown()
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in EXTENSIONS }
            .sortedBy { it.path }
            .toList()
        assumeTrue("no .svscan or .rrd under $corpus", scans.isNotEmpty())
        out!!.mkdirs()
        val summary = StringBuilder()
        for (scan in scans) {
            val cloud = runCatching { cloudOf(scan) }.getOrNull()
            if (cloud == null) {
                summary.appendLine("${scan.name}: no dense cloud to read")
                continue
            }
            val started = System.nanoTime()
            val ground = ScanGround.of(cloud.positions, cloud.normals, cloud.count)
            val zones = RescanSources.zones(cloud, ground)
            val ms = (System.nanoTime() - started) / NS_PER_MS
            val text = buildString {
                appendLine("${scan.name}: ${cloud.count} points, normals ${cloud.normals != null}, ${zones.size} zones in $ms ms")
                appendLine("  picture: from above, ceiling off, x right, z down; amber missing wall, red floor hole, blue sparse")
                zones.forEach { appendLine("  $it") }
            }
            File(out, "${scan.name}-rescan.txt").writeText(text)
            File(out, "${scan.name}-rescan.png").writeBytes(picture(cloud, zones))
            summary.append(text)
        }
        File(out, "rescan-summary.txt").writeText(summary.toString())
        println(summary)
    }

    /**
     * The cloud from above, x to the right and z down, each zone framed in its kind's colour.
     * java.awt is not on an Android module's test classpath: the raster is plain RGB, written as a
     * PNG by [png].
     */
    private fun picture(cloud: DenseCloud, zones: List<RescanZone>): ByteArray {
        val b = cloud.bounds()
        val minX = min(b[0], zones.minOfOrNull { it.box[0] } ?: b[0])
        val minZ = min(b[2], zones.minOfOrNull { it.box[2] } ?: b[2])
        val maxX = max(b[3], zones.maxOfOrNull { it.box[3] } ?: b[3])
        val maxZ = max(b[5], zones.maxOfOrNull { it.box[5] } ?: b[5])
        val scale = min(PX_PER_M, MAX_PX / max(maxX - minX, maxZ - minZ).coerceAtLeast(1f))
        val width = ceil((maxX - minX) * scale).toInt() + 2 * MARGIN
        val height = ceil((maxZ - minZ) * scale).toInt() + 2 * MARGIN
        val rgb = IntArray(width * height) { WHITE }
        fun px(x: Float) = (MARGIN + ((x - minX) * scale).toInt()).coerceIn(0, width - 1)
        fun py(z: Float) = (MARGIN + ((z - minZ) * scale).toInt()).coerceIn(0, height - 1)
        val p = cloud.positions
        val span = (b[4] - b[1]).coerceAtLeast(MIN_SPAN_M)
        // As seen from above with the ceiling off: low points first and light, high ones over them
        // and dark, so the floor reads where it was seen and a hole stays white.
        val order = (0 until cloud.count).filter { p[it * 3 + 1] - b[1] < CEILING_SHARE * span }
            .sortedBy { p[it * 3 + 1] }
        for (i in order) {
            val t = ((p[i * 3 + 1] - b[1]) / span).coerceIn(0f, 1f)
            val shade = (LIGHT - (LIGHT - DARK) * t).toInt()
            rgb[py(p[i * 3 + 2]) * width + px(p[i * 3])] = (shade shl 16) or (shade shl 8) or shade
        }
        for (zone in zones) {
            val colour = colourOf(zone.kind)
            val x0 = px(zone.box[0])
            val x1 = px(zone.box[3])
            val y0 = py(zone.box[2])
            val y1 = py(zone.box[5])
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val edge = x - x0 < STROKE_PX || x1 - x < STROKE_PX || y - y0 < STROKE_PX || y1 - y < STROKE_PX
                    if (edge) rgb[y * width + x] = colour
                }
            }
        }
        return png(width, height, rgb)
    }

    /** A plain 8-bit RGB PNG of [rgb], rows top to bottom, no filtering. */
    private fun png(width: Int, height: Int, rgb: IntArray): ByteArray {
        val rows = ByteArrayOutputStream()
        for (y in 0 until height) {
            rows.write(0)
            for (x in 0 until width) {
                val c = rgb[y * width + x]
                rows.write(c shr 16 and BYTE)
                rows.write(c shr 8 and BYTE)
                rows.write(c and BYTE)
            }
        }
        val packed = ByteArrayOutputStream()
        DeflaterOutputStream(packed).use { it.write(rows.toByteArray()) }
        val out = ByteArrayOutputStream()
        out.write(PNG_SIGNATURE)
        val header = ByteBuffer.allocate(IHDR_BYTES).putInt(width).putInt(height)
            .put(BIT_DEPTH).put(COLOUR_RGB).put(0).put(0).put(0).array()
        chunk(out, "IHDR", header)
        chunk(out, "IDAT", packed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val crc = CRC32().apply {
            update(type.toByteArray())
            update(data)
        }
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(data.size).array())
        out.write(type.toByteArray())
        out.write(data)
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(crc.value.toInt()).array())
    }

    private fun colourOf(kind: RescanKind) = when (kind) {
        RescanKind.MissingWall -> AMBER
        RescanKind.FloorHole -> RED
        RescanKind.LowDensity -> BLUE
    }

    companion object {
        const val CORPUS = "SCENEVIEW_SCAN_CORPUS"
        private const val OUT = "SCENEVIEW_SCAN_OUT"
        val EXTENSIONS = setOf("svscan", "rrd")

        /** [scan]'s dense cloud, `null` for a scan without one (a v1 or sparse scan). */
        fun cloudOf(scan: File): DenseCloud? {
            val bytes = scan.readBytes()
            val pack = when (RerunFileKind.of(scan.name, bytes.copyOf(min(bytes.size, HEAD_BYTES)))) {
                RerunFileKind.Scan -> RerunScanFile.read(bytes)
                RerunFileKind.Rrd -> RerunRrdReader.capturePack(bytes, scan.name, FakeRerunImageCodec)
                null -> null
            } ?: return null
            val opened = pack.open() ?: return null
            val dense = opened.manifest.dense ?: return null
            return opened.bytesOf(dense.path)?.let { SvpcCodec.decode(it) }?.takeIf { it.count > 0 }
        }

        private const val HEAD_BYTES = 64
        private const val NS_PER_MS = 1_000_000L
        private const val PX_PER_M = 120f
        private const val MAX_PX = 1600f
        private const val MARGIN = 24
        private const val STROKE_PX = 3
        private const val MIN_SPAN_M = 0.01f
        private const val CEILING_SHARE = 0.9f
        private const val LIGHT = 215f
        private const val DARK = 40f
        private const val BYTE = 0xFF
        private const val IHDR_BYTES = 13
        private const val BIT_DEPTH: Byte = 8
        private const val COLOUR_RGB: Byte = 2
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private const val WHITE = 0xFFFFFF

        /** The `warning` amber the result frames zones with. */
        private const val AMBER = 0xF59E0B
        private const val RED = 0xDC2626
        private const val BLUE = 0x2563EB
    }
}
