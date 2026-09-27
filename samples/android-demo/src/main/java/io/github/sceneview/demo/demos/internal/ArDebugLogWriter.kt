package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * A recorded scan written back as the bundled replay ships it: its log in the Rerun bridge's wire
 * format, and its manifest — so a scan reopened from the phone's storage goes through exactly the
 * code the sample does and looks exactly like it. Pure Kotlin, round-trip tested on the JVM.
 */

/** Writes events back in the wire format [parseArDebugEvent] reads. */
object ArDebugLogWriter {
    /** One JSON line for [event], or `null` for one that has nothing finite to say. */
    fun line(event: ArDebugEvent): String? = when (event) {
        is ArDebugEvent.CameraPose -> pose(event.nanos, "camera_pose", "world/camera", event.pose)
        is ArDebugEvent.Anchor -> pose(event.nanos, "anchor", "world/anchors/${event.id}", event.pose)
        is ArDebugEvent.Points -> points(event)
        is ArDebugEvent.Plane -> buildJsonObject {
            put("t", event.nanos)
            put("type", "plane")
            put("entity", "world/planes/${event.id}")
            put("kind", event.kind.wireName)
            put("polygon", triples(event.polygon))
        }.toString()
        is ArDebugEvent.Image -> buildJsonObject {
            put("t", event.nanos)
            put("type", "image")
            put("entity", "world/camera/image")
            put("path", event.path)
        }.toString()
    }

    /** The whole log, one event per line. */
    fun write(events: List<ArDebugEvent>): String = buildString {
        for (event in events) {
            val line = line(event) ?: continue
            append(line).append('\n')
        }
    }

    private fun pose(nanos: Long, type: String, entity: String, pose: DebugPose): String? {
        val values = floatArrayOf(pose.x, pose.y, pose.z, pose.qx, pose.qy, pose.qz, pose.qw)
        if (values.any { !it.isFinite() }) return null
        return buildJsonObject {
            put("t", nanos)
            put("type", type)
            put("entity", entity)
            putJsonArray("translation") { for (i in 0 until 3) add(JsonPrimitive(values[i])) }
            putJsonArray("quaternion") { for (i in 3 until 7) add(JsonPrimitive(values[i])) }
        }.toString()
    }

    // An empty observation is written too: the replay's live points follow the latest one.
    private fun points(event: ArDebugEvent.Points): String {
        val count = event.positions.size / 3
        return buildJsonObject {
            put("t", event.nanos)
            put("type", "point_cloud")
            put("entity", "world/points")
            put("positions", triples(event.positions))
            event.confidences?.let { c -> putJsonArray("confidences") { c.forEach { add(JsonPrimitive(it)) } } }
            event.colors?.takeIf { it.size == count }?.let { colors ->
                putJsonArray("colors") { colors.forEach { add(rgb(it)) } }
            }
        }.toString()
    }

    /** `0xFFRRGGBB` → `[r, g, b]`; `0` (no colour) → `[-1, -1, -1]`, which reads back as none. */
    private fun rgb(color: Int): JsonArray = buildJsonArray {
        if (color == 0) {
            repeat(3) { add(JsonPrimitive(-1)) }
        } else {
            add(JsonPrimitive(color shr 16 and 0xFF))
            add(JsonPrimitive(color shr 8 and 0xFF))
            add(JsonPrimitive(color and 0xFF))
        }
    }

    /** Flat `[x,y,z, …]` → `[[x,y,z], …]`, skipping any vertex that is not finite. */
    private fun triples(flat: FloatArray): JsonArray = buildJsonArray {
        for (i in 0 until flat.size / 3) {
            val x = flat[i * 3]
            val y = flat[i * 3 + 1]
            val z = flat[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            add(buildJsonArray { add(JsonPrimitive(x)); add(JsonPrimitive(y)); add(JsonPrimitive(z)) })
        }
    }
}

/** The manifest [ReplayManifest.parse] reads, written back. */
fun ReplayManifest.toJson(): String = buildJsonObject {
    // The lens as intrinsics of a unit focal length: `width / 2 / fx` gives it back exactly.
    putJsonObject("intrinsics") {
        put("width", lens.halfWidthPerDepth * 2f)
        put("height", lens.halfHeightPerDepth * 2f)
        put("fx", 1f)
        put("fy", 1f)
    }
    put("frameRate", frameRate)
    put("frames", frameCount)
    floorY?.let { put("floorY", it) }
    putJsonArray("textures") {
        for (texture in textures) {
            add(
                buildJsonObject {
                    put("plane", texture.planeId)
                    put("path", texture.path)
                    put("origin", vec(texture.origin))
                    put("u", vec(texture.u))
                    put("v", vec(texture.v))
                },
            )
        }
    }
    putJsonArray("media") {
        for ((path, span) in media) {
            add(
                buildJsonObject {
                    put("path", path)
                    put("offset", span.offset)
                    put("length", span.length)
                },
            )
        }
    }
}.toString()

private fun vec(v: Vec3): JsonArray = buildJsonArray {
    add(JsonPrimitive(v.x))
    add(JsonPrimitive(v.y))
    add(JsonPrimitive(v.z))
}
