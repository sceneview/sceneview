package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Reads a session log written in the Rerun bridge's wire format — the JSON lines
 * `RerunWireFormat` streams to the Python sidecar — into an [ArDebugTrace]. The QA fixture
 * (`assets/rerun/sample-session.jsonl`) is such a log, so the fixture exercises the very format a
 * real session streams, not a private one invented for the test.
 */

/** One parsed wire-format event, stamped with the log's own `t` in nanoseconds. */
sealed class ArDebugEvent(val nanos: Long) {
    class CameraPose(nanos: Long, val pose: DebugPose) : ArDebugEvent(nanos)
    class Points(
        nanos: Long,
        val positions: FloatArray,
        val confidences: FloatArray?,
        /** Per-point colour, `0xFFRRGGBB`, when the log carries one (a replay's photo colours). */
        val colors: IntArray? = null,
    ) : ArDebugEvent(nanos)
    class Plane(nanos: Long, val id: Int, val kind: DebugPlaneKind, val polygon: FloatArray) : ArDebugEvent(nanos)
    class Anchor(nanos: Long, val id: Int, val pose: DebugPose) : ArDebugEvent(nanos)

    /** The camera image at this instant, as a path relative to the log (`frames/012.webp`). */
    class Image(nanos: Long, val path: String) : ArDebugEvent(nanos)

    /**
     * A `.svscan` v2 raw-depth keyframe fused: [added] surfels created, [kept] depth samples
     * merged, [total] surfels in the dense map since the start (`depth_stats`).
     */
    class DepthStats(nanos: Long, val added: Int, val kept: Int, val total: Int) : ArDebugEvent(nanos)

    /** Applies this event to [trace]. */
    fun applyTo(trace: ArDebugTrace) {
        when (this) {
            is CameraPose -> trace.addPose(nanos, pose)
            is Points -> trace.addPoints(nanos, positions, confidences, colors)
            is Plane -> trace.addPlane(nanos, id, kind, polygon)
            is Anchor -> trace.addAnchor(nanos, id, pose)
            is Image -> trace.addImage(nanos, path)
            is DepthStats -> trace.addDepthStats(nanos, added, kept, total)
        }
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses one wire-format line. Returns `null` for anything the view does not draw (`hit_result`,
 * `scalar`, `control`, `camera_trail` — the trail is rebuilt from the poses) and for malformed
 * lines, which are skipped rather than failing the whole log: a log cut mid-line by a crash must
 * still open.
 */
@Suppress("ReturnCount") // one early return per malformed-line case
fun parseArDebugEvent(line: String): ArDebugEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed[0] != '{') return null
    val obj = runCatching { lenientJson.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
    val nanos = (obj["t"] as? JsonPrimitive)?.longOrNull ?: return null
    val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
    val entity = (obj["entity"] as? JsonPrimitive)?.content
    return when (type) {
        "camera_pose" -> obj.pose()?.let { ArDebugEvent.CameraPose(nanos, it) }
        "anchor" -> obj.pose()?.let { ArDebugEvent.Anchor(nanos, entityId(entity) ?: 0, it) }
        "point_cloud" -> {
            val positions = flatten(obj["positions"]) ?: return null
            val confidences = (obj["confidences"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull }
                ?.toFloatArray()
                ?.takeIf { it.size == positions.size / 3 }
            val colors = (obj["colors"] as? JsonArray)
                ?.mapNotNull { rgb -> floats(rgb)?.takeIf { it.size == 3 }?.let(::colorOf) }
                ?.toIntArray()
                ?.takeIf { it.size == positions.size / 3 }
            ArDebugEvent.Points(nanos, positions, confidences, colors)
        }
        "plane" -> {
            val polygon = flatten(obj["polygon"]) ?: FloatArray(0)
            val kind = DebugPlaneKind.ofWire((obj["kind"] as? JsonPrimitive)?.content)
            ArDebugEvent.Plane(nanos, entityId(entity) ?: 0, kind, polygon)
        }
        "image" -> (obj["path"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?.let { ArDebugEvent.Image(nanos, it) }
        "depth_stats" -> {
            fun count(key: String) = (obj[key] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 }
            val total = count("total") ?: return null
            ArDebugEvent.DepthStats(nanos, count("new") ?: 0, count("kept") ?: 0, total)
        }
        else -> null
    }
}

/**
 * A point's colour: `[r, g, b]` packed, or `0` — no colour — for a negative channel, which is how
 * a saved scan marks the points its photos did not show ([ArDebugLogWriter]).
 */
private fun colorOf(rgb: FloatArray): Int = if (rgb.any { it < 0f }) 0 else packRgb(rgb)

/** `[r, g, b]` in 0..255 → `0xFFRRGGBB`, each channel clamped. */
internal fun packRgb(rgb: FloatArray): Int {
    fun channel(v: Float) = v.toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
}

/** Parses a whole log, in order. */
fun parseArDebugLog(lines: Sequence<String>): List<ArDebugEvent> = lines.mapNotNull(::parseArDebugEvent).toList()

/** `world/planes/42` → 42. Non-numeric ids (a hand-written log) hash to a stable int. */
private fun entityId(entity: String?): Int? {
    val last = entity?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return null
    return last.toIntOrNull() ?: last.hashCode()
}

private fun JsonObject.pose(): DebugPose? {
    val t = floats(this["translation"])?.takeIf { it.size == 3 } ?: return null
    val q = floats(this["quaternion"])?.takeIf { it.size == 4 } ?: floatArrayOf(0f, 0f, 0f, 1f)
    return DebugPose(t[0], t[1], t[2], q[0], q[1], q[2], q[3])
}

private fun floats(element: JsonElement?): FloatArray? {
    val array = element as? JsonArray ?: return null
    val out = FloatArray(array.size)
    for (i in array.indices) out[i] = (array[i] as? JsonPrimitive)?.floatOrNull ?: return null
    return out
}

/** `[[x,y,z], …]` → `[x,y,z, …]`; entries that are not three numbers are dropped. */
private fun flatten(element: JsonElement?): FloatArray? {
    val array = element as? JsonArray ?: return null
    val out = FloatArray(array.size * 3)
    var n = 0
    for (entry in array) {
        val xyz = floats(entry)?.takeIf { it.size == 3 } ?: continue
        out[n++] = xyz[0]
        out[n++] = xyz[1]
        out[n++] = xyz[2]
    }
    return out.copyOf(n)
}

/**
 * Feeds a parsed log into a trace **as time passes**, the way a live session fills it — the QA
 * path that shows the trail growing on an emulator that cannot run ARCore (#2754). Loops: past
 * the end it clears the trace and starts again after [loopPauseSeconds].
 *
 * QA only. The demo never shows recorded data as if it were live to a user.
 */
class ArDebugLogPlayer(
    private val events: List<ArDebugEvent>,
    private val speed: Float = 1f,
    private val loopPauseSeconds: Float = 2.5f,
) {
    private val firstNanos = events.firstOrNull()?.nanos ?: 0L
    private val durationSeconds = events.lastOrNull()?.let { (it.nanos - firstNanos) / 1e9f } ?: 0f
    private var next = 0
    private var clock = 0f

    /** The trace being filled; a fresh one per loop, so observers can key on its identity. */
    var trace: ArDebugTrace = ArDebugTrace()
        private set

    /** Advances the feed by [deltaSeconds]; returns `true` when a new loop replaced [trace]. */
    fun advance(deltaSeconds: Float): Boolean {
        if (events.isEmpty()) return false
        clock += deltaSeconds.coerceIn(0f, 0.25f) * speed
        if (clock > durationSeconds + loopPauseSeconds) {
            trace = ArDebugTrace()
            next = 0
            clock = 0f
            return true
        }
        val until = firstNanos + (clock * 1e9).toLong()
        while (next < events.size && events[next].nanos <= until) {
            events[next++].applyTo(trace)
        }
        return false
    }
}

/** A trace holding the whole log at once — the fixture as a finished take, for scrubbing. */
fun ArDebugTrace.Companion.of(events: List<ArDebugEvent>): ArDebugTrace =
    ArDebugTrace().also { trace -> events.forEach { it.applyTo(trace) } }
