package io.github.sceneview.demo.demos.internal.room

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.FakeRerunImageCodec
import io.github.sceneview.demo.demos.internal.OpenedCapture
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunRrdReader
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.RoomMeasure
import io.github.sceneview.demo.demos.internal.RoomSurvey
import io.github.sceneview.demo.demos.internal.RoomSurveyor
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.demos.internal.roomPlanesOf
import io.github.sceneview.demo.demos.stageFloorY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** One scan of the corpus, read: the figures before (the old measures) and after (the engine). */
class CorpusResult(
    val name: String,
    val points: Int,
    val structure: RoomStructure?,
    val beforeMeasureArea: Float?,
    val beforeSurvey: RoomSurvey?,
    val afterSurvey: RoomSurvey?,
    val fullMs: Double?,
    val liveMs: Double?,
    val error: String?,
)

/**
 * Runs the room engine over a folder of recordings (`*.svscan`, `*.rrd`, or a kept session's
 * directory as the app stores it — `capture-manifest.json` and its two siblings, named after the
 * session), read with the app's own readers, and writes for each scan `<name>.json` (its figures,
 * before and after) and `<name>.png` (the plan from above), plus `summary.json` for the whole folder.
 */
object RoomCorpus {
    private const val LIVE_WARMUP = 30
    private const val LIVE_RUNS = 11
    private const val FULL_RUNS = 3
    private const val IMAGE_PX = 1200
    private const val MARGIN_PX = 170
    private val json = Json { prettyPrint = true }

    fun run(corpus: File, out: File): List<CorpusResult> {
        out.mkdirs()
        val files = corpus.walkTopDown()
            .filter { isScanFile(it) || isSessionDirectory(it) }
            .sortedBy { it.path }
            .toList()
        val results = files.map { file ->
            val result = runCatching { read(file) }.getOrElse { CorpusResult(file.nameWithoutExtension, 0, null, null, null, null, null, null, it.toString()) }
            File(out, "${result.name}.json").writeText(json.encodeToString(JsonElement.serializer(), jsonOf(result)))
            result.structure?.let { File(out, "${result.name}.png").writeBytes(plan(it).png()) }
            println(line(result))
            result
        }
        val summary = JsonArray(results.map { jsonOf(it, walls = false) })
        File(out, "summary.json").writeText(json.encodeToString(JsonElement.serializer(), summary))
        return results
    }

    private fun isScanFile(file: File) = file.isFile && (file.extension == "svscan" || file.extension == "rrd")

    private fun isSessionDirectory(file: File) = file.isDirectory && File(file, RerunCapturePack.MANIFEST).isFile

    private fun open(file: File): OpenedCapture? {
        // A kept session: read the way the app reads its own sessions back.
        if (file.isDirectory) return RerunSessionStore(file.absoluteFile.parentFile).capture(file.name)?.open()
        val data = file.readBytes()
        return when (file.extension) {
            "rrd" -> RerunRrdReader.recording(data, FakeRerunImageCodec).pack.open()
            else -> RerunScanFile.read(data)?.open()
        }
    }

    private fun read(file: File): CorpusResult {
        val name = file.nameWithoutExtension
        val opened = open(file) ?: return CorpusResult(name, 0, null, null, null, null, null, null, "unreadable")
        val trace: ArDebugTrace = opened.trace
        val whole = trace.frameAt(trace.duration)
        val beforeMeasure = RoomMeasure.of(whole.planes, stageFloorY(whole))?.area
        val cloud: DenseCloud = opened.manifest.dense
            ?.let { opened.bytesOf(it.path) }
            ?.let { SvpcCodec.decode(it) }
            ?.takeIf { it.count > 0 }
            ?: return CorpusResult(name, 0, null, beforeMeasure, null, null, null, null, "no dense cloud")
        val scan = RoomScan(cloud.positions, cloud.normals, whole.trail, roomPlanesOf(whole.planes))
        val legacy = runCatching { LegacyRoomSurveyor.survey(RoomScan(cloud.positions, cloud.normals, whole.trail)) }.getOrNull()
        var structure = RoomStructure.read(scan)
        val full = (0 until FULL_RUNS).map {
            val start = System.nanoTime()
            structure = RoomStructure.read(scan)
            (System.nanoTime() - start) / 1e6
        }.sorted()[FULL_RUNS / 2]
        repeat(LIVE_WARMUP) { RoomStructure.live(scan) }
        val live = (0 until LIVE_RUNS).map {
            val start = System.nanoTime()
            RoomStructure.live(scan)
            (System.nanoTime() - start) / 1e6
        }.sorted()[LIVE_RUNS / 2]
        return CorpusResult(name, cloud.count, structure, beforeMeasure, legacy, RoomSurveyor.of(structure), full, live, null)
    }

    private fun line(r: CorpusResult): String {
        val s = r.structure
        return "RoomCorpus ${r.name}: " + (
            r.error ?: ("area ${s?.area?.value} (before: measure ${r.beforeMeasureArea}, survey ${r.beforeSurvey?.area?.value}), " +
                "height ${s?.ceilingHeight?.value}, ${s?.walls?.size} walls (${s?.walls?.count { it.deduced }} deduced), " +
                "traced ${s?.reading?.traced}${s?.reading?.miss?.let { " (missed: $it)" } ?: ""}, " +
                "closed ${s?.reading?.closed}, ${s?.reading?.distinctWalls} distinct walls, " +
                "${s?.openings?.size} openings, full ${"%.1f".format(r.fullMs)} ms, live ${"%.2f".format(r.liveMs)} ms, ${r.points} points")
            )
    }

    private fun figure(f: RoomFigure?): JsonElement = f?.let {
        buildJsonObject {
            put("value", it.value)
            put("plusMinus", it.plusMinus)
            put("estimate", it.estimate)
        }
    } ?: JsonNull

    private fun num(v: Number?): JsonElement = v?.let { JsonPrimitive(it) } ?: JsonNull

    private fun surveyJson(s: RoomSurvey?): JsonElement = s?.let {
        buildJsonObject {
            put("area", figure(it.area))
            put("perimeter", figure(it.perimeter))
            put("length", figure(it.length))
            put("width", figure(it.width))
            put("ceilingHeight", figure(it.ceilingHeight))
            put("walls", it.walls.size)
            put("gaps", JsonArray(it.gaps.map { g -> JsonPrimitive(g.name) }))
        }
    } ?: JsonNull

    private fun jsonOf(r: CorpusResult, walls: Boolean = true): JsonObject = buildJsonObject {
        put("name", r.name)
        put("points", r.points)
        r.error?.let { put("error", it) }
        put(
            "before",
            buildJsonObject {
                put("roomMeasureArea", num(r.beforeMeasureArea))
                put("roomSurveyor", surveyJson(r.beforeSurvey))
            },
        )
        val s = r.structure
        put(
            "after",
            if (s == null) {
                JsonNull
            } else {
                buildJsonObject {
                    put("area", figure(s.area))
                    put("ceilingHeight", figure(s.ceilingHeight))
                    put("perimeter", figure(s.perimeter))
                    put("volume", figure(s.volume))
                    put("confidence", s.confidence)
                    put("seenWalls", s.seenWalls)
                    put("deducedWalls", s.walls.count { it.deduced })
                    put("gaps", JsonArray(s.gaps.map { JsonPrimitive(it.name) }))
                    put("traced", s.reading?.let { JsonPrimitive(it.traced) } ?: JsonNull)
                    put("traceMiss", s.reading?.miss?.let { JsonPrimitive(it.name) } ?: JsonNull)
                    put("floorArea", num(s.reading?.floorArea))
                    put("tracedArea", num(s.reading?.tracedArea))
                    put("closed", s.reading?.let { JsonPrimitive(it.closed) } ?: JsonNull)
                    put("distinctWalls", num(s.reading?.distinctWalls))
                    if (walls) {
                        put(
                            "walls",
                            buildJsonArray {
                                for (w in s.walls) {
                                    add(
                                        buildJsonObject {
                                            put("a", buildJsonArray { add(JsonPrimitive(w.ax)); add(JsonPrimitive(w.az)) })
                                            put("b", buildJsonArray { add(JsonPrimitive(w.bx)); add(JsonPrimitive(w.bz)) })
                                            put("length", figure(w.length))
                                            put("seen", w.seen)
                                            put("deduced", w.deduced)
                                            put("seenShare", w.seenShare)
                                            put("height", w.height)
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "openings",
                            buildJsonArray {
                                for ((i, w) in s.walls.withIndex()) {
                                    for (o in w.openings) {
                                        add(
                                            buildJsonObject {
                                                put("kind", o.kind.name)
                                                put("wall", i)
                                                put("from", o.from)
                                                put("width", o.width)
                                                put("height", o.height)
                                                put("sill", o.sill)
                                                put("confidence", o.confidence)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    } else {
                        put("walls", s.walls.size)
                        put("openings", s.openings.size)
                    }
                    put("roomSurveyor", surveyJson(r.afterSurvey))
                }
            },
        )
        put(
            "timings",
            buildJsonObject {
                put("fullMs", num(r.fullMs))
                put("liveMs", num(r.liveMs))
            },
        )
    }

    /**
     * The plan from above, x to the right and z down: the floor, seen walls solid black, deduced
     * ones dashed red, doors blue, windows cyan, each wall's length outside it (`*` an estimate),
     * and the area (`A`) and height (`H`) on top.
     */
    fun plan(s: RoomStructure): PlanCanvas {
        val canvas = PlanCanvas(IMAGE_PX, IMAGE_PX)
        val head = "A=" + (s.area?.let { "%.2f m2%s".format(java.util.Locale.ROOT, it.value, if (it.estimate) "*" else "") } ?: "-") +
            "  H=" + (s.ceilingHeight?.let { "%.2f m".format(java.util.Locale.ROOT, it.value) } ?: "-")
        canvas.text(20, 20, head, TEXT_SCALE + 2, INK)
        if (s.walls.isEmpty()) return canvas
        val xs = s.walls.flatMap { listOf(it.ax, it.bx) }
        val zs = s.walls.flatMap { listOf(it.az, it.bz) }
        val x0 = xs.min()
        val z0 = zs.min()
        val span = max(xs.max() - x0, zs.max() - z0).coerceAtLeast(MIN_SPAN_M)
        val scale = (IMAGE_PX - 2 * MARGIN_PX) / span
        // Centred: the shorter side gets the slack.
        val left = MARGIN_PX + (span - (xs.max() - x0)) * scale / 2
        val top = MARGIN_PX + (span - (zs.max() - z0)) * scale / 2
        fun px(x: Float) = left + (x - x0) * scale
        fun pz(z: Float) = top + (z - z0) * scale
        canvas.fillPolygon(
            FloatArray(s.walls.size) { px(s.walls[it].ax) },
            FloatArray(s.walls.size) { pz(s.walls[it].az) },
            FLOOR,
        )
        for (w in s.walls) {
            val deduced = w.deduced || !w.seen
            canvas.line(px(w.ax), pz(w.az), px(w.bx), pz(w.bz), WALL_PX, if (deduced) DEDUCED else INK, if (deduced) DASH_PX else 0f)
        }
        for (w in s.walls) {
            val length = hypot(w.bx - w.ax, w.bz - w.az)
            if (length <= 0f) continue
            val dx = (w.bx - w.ax) / length
            val dz = (w.bz - w.az) / length
            for (o in w.openings) {
                canvas.line(
                    px(w.ax + dx * o.from), pz(w.az + dz * o.from),
                    px(w.ax + dx * (o.from + o.width)), pz(w.az + dz * (o.from + o.width)),
                    OPENING_PX,
                    if (o.kind == OpeningKind.Door) DOOR else WINDOW,
                )
            }
            // The length outside the room: against the wall's normal, which points in.
            val label = "%.2f m%s".format(java.util.Locale.ROOT, w.length.value, if (w.length.estimate) "*" else "")
            val half = canvas.textWidth(label, TEXT_SCALE) / 2f
            val away = LABEL_OFFSET_PX + abs(w.nx) * half + abs(w.nz) * TEXT_SCALE * 2.5f
            val mx = px((w.ax + w.bx) / 2) - w.nx * away
            val mz = pz((w.az + w.bz) / 2) - w.nz * away
            canvas.text((mx - half).toInt(), (mz - 2.5f * TEXT_SCALE).toInt(), label, TEXT_SCALE, LABEL)
        }
        return canvas
    }

    private const val TEXT_SCALE = 4
    private const val MIN_SPAN_M = 0.5f
    private const val WALL_PX = 6f
    private const val DASH_PX = 14f
    private const val OPENING_PX = 12f
    private const val LABEL_OFFSET_PX = 18f
    private const val INK = 0x202020
    private const val FLOOR = 0xEEEEEA
    private const val DEDUCED = 0xC03030
    private const val DOOR = 0x2070D0
    private const val WINDOW = 0x10B0C0
    private const val LABEL = 0x505050
}
