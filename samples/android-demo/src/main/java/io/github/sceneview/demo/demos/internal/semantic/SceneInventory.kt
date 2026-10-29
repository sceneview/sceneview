package io.github.sceneview.demo.demos.internal.semantic

import io.github.sceneview.demo.demos.internal.SurveyRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * The recognised parts of a room in the forms the rest of the demo takes them: rows and lines for
 * the room's report, and JSON for the corpus run and for whatever reads a scan's inventory next.
 */
object SceneInventory {
    private const val SEPARATOR = " · "
    private const val PERCENT = 100

    /**
     * One row per recognised part, for the room report's table ([SurveyRow]): its name — [name]
     * says it in the app's language —, and its size in metres with how sure the scan is.
     */
    fun rows(items: List<RecognizedItem>, name: (SceneLabel) -> String): List<SurveyRow> = items.map { item ->
        val (length, depth, height) = item.box.sizes()
        val size = if (item.label.isOpening) "$length × $height m" else "$length × $depth × $height m"
        SurveyRow(name(item.label), size + SEPARATOR + percent(item.confidence), muted = item.confidence < SURE)
    }

    /** The inventory as one plain line for the report's text: `Recognised: sofa, 2 chairs, door`. */
    fun line(items: List<RecognizedItem>, heading: String = "Recognised", name: (SceneLabel) -> String = { it.wireName }): String? {
        if (items.isEmpty()) return null
        val counts = items.groupingBy { it.label }.eachCount()
        return heading + ": " + counts.entries.joinToString(", ") { (label, count) ->
            if (count == 1) name(label) else "$count × ${name(label)}"
        }
    }

    /** The parts, one English line each: `sofa 1.40 × 0.85 × 0.90 m, 82 %, 4 views`. */
    fun lines(items: List<RecognizedItem>): List<String> = items.map { item ->
        val (length, depth, height) = item.box.sizes()
        "${item.label.wireName} $length × $depth × $height m, ${percent(item.confidence)}, " +
            if (item.views == 0) "from the walls" else "${item.views} view${if (item.views == 1) "" else "s"}"
    }

    /** The inventory as JSON: one object per part — label, box, confidence, views. */
    fun json(items: List<RecognizedItem>): JsonArray = buildJsonArray {
        items.forEach { add(json(it)) }
    }

    fun json(item: RecognizedItem): JsonObject = buildJsonObject {
        put("label", item.label.wireName)
        put("confidence", round(item.confidence))
        put("views", item.views)
        put(
            "box",
            buildJsonObject {
                put("center", numbers(item.box.centerX, item.box.baseY + item.box.height / 2, item.box.centerZ))
                put("baseY", round(item.box.baseY))
                put("yaw", round(item.box.yaw))
                put("size", numbers(item.box.length, item.box.depth, item.box.height))
                put("corners", buildJsonArray { item.box.corners().forEach { add(numbers(it.x, it.y, it.z)) } })
            },
        )
        put("surfels", item.surfels.size)
    }

    /** A whole run: the parts, and per keyframe what the detector saw and how long it took. */
    fun report(recognition: SceneRecognition, scan: String): JsonObject = buildJsonObject {
        put("scan", scan)
        put("detector", recognition.detector)
        put("totalMs", recognition.totalMillis)
        put("items", json(recognition.items))
        put(
            "frames",
            buildJsonArray {
                recognition.frames.forEach { frame ->
                    add(
                        buildJsonObject {
                            put("index", frame.keyframe.index)
                            put("path", frame.keyframe.path)
                            put("width", frame.width)
                            put("height", frame.height)
                            put("decodeMs", frame.decodeMillis)
                            put("detectMs", frame.detectMillis)
                            put("liftMs", frame.liftMillis)
                            put(
                                "detections",
                                buildJsonArray {
                                    frame.detections.forEach { d ->
                                        add(
                                            buildJsonObject {
                                                put("name", d.name)
                                                put("score", round(d.score))
                                                put("rect", numbers(d.left, d.top, d.right, d.bottom))
                                                put("lifted", frame.lifted.any { it.score == d.score && it.label == d.label })
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }

    private fun percent(confidence: Float) = "${(confidence * PERCENT).toInt()} %"

    private fun numbers(vararg values: Float) = JsonArray(values.map { JsonPrimitive(round(it)) })

    private fun round(value: Float): Double = String.format(Locale.US, "%.3f", value).toDouble()

    /** Under this, a row of the report is written muted: a guess, not a finding. */
    private const val SURE = 0.5f
}
