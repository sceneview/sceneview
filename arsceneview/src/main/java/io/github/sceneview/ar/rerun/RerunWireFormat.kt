package io.github.sceneview.ar.rerun

import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.PointCloud
import com.google.ar.core.Pose

/**
 * JSON-lines wire format shared between the Kotlin and Swift Rerun bridges.
 *
 * Every event is one JSON object on a single line, terminated by `\n`, and
 * consumed by a Python sidecar that re-logs it via the `rerun-sdk` into the
 * Rerun viewer. The same wire format is emitted on Android (ARCore) and iOS
 * (ARKit) so a single sidecar script can handle both platforms.
 *
 * Format:
 * ```
 * {"t": 123456789, "type": "camera_pose", "entity": "world/camera", "translation": [x,y,z], "quaternion": [x,y,z,w]}
 * {"t": 123456789, "type": "plane", "entity": "world/planes/<id>", "polygon": [[x,y,z], ...], "kind": "horizontal"}
 * {"t": 123456789, "type": "point_cloud", "entity": "world/points", "positions": [[x,y,z], ...], "confidences": [f, ...]}
 * {"t": 123456789, "type": "anchor", "entity": "world/anchors/<id>", "translation": [x,y,z], "quaternion": [x,y,z,w]}
 * {"t": 123456789, "type": "hit_result", "entity": "world/hits/<id>", "translation": [x,y,z], "distance": f}
 * ```
 *
 * This module is pure: no I/O, no threading, no Android framework. It's a
 * `String`-in-`String`-out serializer that can be unit-tested on plain JVM.
 * All the network plumbing lives in [RerunBridge].
 *
 * JSON is hand-rolled via [StringBuilder] so the runtime has zero new
 * dependencies — `kotlinx.serialization` is not on the `arsceneview`
 * classpath and pulling it in for a debug-only logger would bloat every
 * SceneView app by ~120 kB.
 */
internal object RerunWireFormat {

    /** Camera pose event — one per frame. Entity path defaults to `world/camera`. */
    fun cameraPose(
        timestampNanos: Long,
        pose: Pose,
        entity: String = "world/camera",
    ): String = cameraPoseJson(
        timestampNanos = timestampNanos,
        tx = pose.tx(), ty = pose.ty(), tz = pose.tz(),
        qx = pose.qx(), qy = pose.qy(), qz = pose.qz(), qw = pose.qw(),
        entity = entity,
    )

    /**
     * Testable overload that takes primitives instead of a [Pose].
     *
     * ARCore's [Pose] class is native and final, so mocking it in a pure
     * JVM unit test requires mockito-inline or equivalent. This function
     * lets the golden-JSON tests assert exact output without touching
     * ARCore at all.
     */
    internal fun cameraPoseJson(
        timestampNanos: Long,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
        entity: String = "world/camera",
    ): String = buildString(192) {
        append('{')
        appendCommonHeader(timestampNanos, "camera_pose", entity)
        append(",\"translation\":[")
        appendFloat(tx); append(','); appendFloat(ty); append(','); appendFloat(tz)
        append("],\"quaternion\":[")
        appendFloat(qx); append(','); appendFloat(qy); append(','); appendFloat(qz); append(','); appendFloat(qw)
        append("]}\n")
    }

    /** Testable overload for anchors — takes the pose as primitives. */
    internal fun anchorJson(
        timestampNanos: Long,
        id: Int,
        tx: Float, ty: Float, tz: Float,
        qx: Float, qy: Float, qz: Float, qw: Float,
    ): String = buildString(192) {
        append('{')
        appendCommonHeader(timestampNanos, "anchor", "world/anchors/$id")
        append(",\"translation\":[")
        appendFloat(tx); append(','); appendFloat(ty); append(','); appendFloat(tz)
        append("],\"quaternion\":[")
        appendFloat(qx); append(','); appendFloat(qy); append(','); appendFloat(qz); append(','); appendFloat(qw)
        append("]}\n")
    }

    /** Testable overload for hit results. */
    internal fun hitResultJson(
        timestampNanos: Long,
        id: Int,
        tx: Float, ty: Float, tz: Float,
        distance: Float,
    ): String = buildString(160) {
        append('{')
        appendCommonHeader(timestampNanos, "hit_result", "world/hits/$id")
        append(",\"translation\":[")
        appendFloat(tx); append(','); appendFloat(ty); append(','); appendFloat(tz)
        append("],\"distance\":")
        appendFloat(distance)
        append("}\n")
    }

    /**
     * Testable overload for point clouds — takes the raw `[x,y,z,confidence]`
     * packed FloatArray that ARCore's `PointCloud.points` buffer yields.
     */
    internal fun pointCloudJson(
        timestampNanos: Long,
        packed: FloatArray,
        entity: String = "world/points",
    ): String {
        val n = packed.size / 4
        return buildString(64 + n * 48) {
            append('{')
            appendCommonHeader(timestampNanos, "point_cloud", entity)
            append(",\"positions\":[")
            for (i in 0 until n) {
                if (i > 0) append(',')
                append('[')
                appendFloat(packed[i * 4]); append(',')
                appendFloat(packed[i * 4 + 1]); append(',')
                appendFloat(packed[i * 4 + 2])
                append(']')
            }
            append("],\"confidences\":[")
            for (i in 0 until n) {
                if (i > 0) append(',')
                appendFloat(packed[i * 4 + 3])
            }
            append("]}\n")
        }
    }

    /**
     * Testable overload for planes — takes the already-world-space polygon
     * and plane kind so tests don't need a real ARCore Plane instance.
     */
    internal fun planeJson(
        timestampNanos: Long,
        id: Int,
        kind: String,
        worldPolygon: List<FloatArray>,
    ): String = buildString(256 + worldPolygon.size * 48) {
        append('{')
        appendCommonHeader(timestampNanos, "plane", "world/planes/$id")
        append(",\"kind\":\"")
        append(kind)
        append("\",\"polygon\":[")
        for (idx in worldPolygon.indices) {
            if (idx > 0) append(',')
            val p = worldPolygon[idx]
            append('[')
            appendFloat(p[0]); append(','); appendFloat(p[1]); append(','); appendFloat(p[2])
            append(']')
        }
        append("]}\n")
    }

    /**
     * Plane event — one per detected ARCore [Plane].
     *
     * The plane polygon is emitted as world-space 3D points (ARCore's local
     * 2D polygon is lifted into world coordinates via the plane's center pose
     * so the Python sidecar can draw a closed LineStrip3D directly).
     */
    fun plane(
        timestampNanos: Long,
        plane: Plane,
    ): String {
        // Stable id: ARCore Plane identity is object identity; use System.identityHashCode
        // so the Python sidecar can consistently attach the same entity path across frames.
        val id = System.identityHashCode(plane)
        // Keep a defensive fallback (silenced) so a future ARCore Plane.Type
        // addition doesn't crash the bridge — it just shows up as "unknown".
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        val kind = when (plane.type) {
            Plane.Type.HORIZONTAL_UPWARD_FACING -> "horizontal_upward"
            Plane.Type.HORIZONTAL_DOWNWARD_FACING -> "horizontal_downward"
            Plane.Type.VERTICAL -> "vertical"
            else -> "unknown"
        }

        // Lift the local 2D polygon [x0,z0, x1,z1, ...] into world-space 3D points.
        val localPoly = plane.polygon
        val centerPose = plane.centerPose
        val worldPoints = FloatArray(3)
        val worldPoly = ArrayList<FloatArray>(localPoly.remaining() / 2)
        val copy = FloatArray(localPoly.remaining())
        localPoly.get(copy)
        var i = 0
        while (i < copy.size - 1) {
            val local = floatArrayOf(copy[i], 0f, copy[i + 1])
            centerPose.transformPoint(local, 0, worldPoints, 0)
            worldPoly.add(worldPoints.copyOf())
            i += 2
        }

        return planeJson(timestampNanos, id, kind, worldPoly)
    }

    /**
     * Point cloud event — one per frame.
     *
     * ARCore's [PointCloud.points] is a packed `[x, y, z, confidence]` float
     * buffer. We split it into `positions[[x,y,z], ...]` and
     * `confidences[f, ...]` for easier consumption in Python.
     */
    fun pointCloud(
        timestampNanos: Long,
        cloud: PointCloud,
        entity: String = "world/points",
    ): String {
        val buf = cloud.points
        // Defensive copy so we don't mutate ARCore's read-position.
        val data = FloatArray(buf.remaining())
        buf.asReadOnlyBuffer().get(data)
        return pointCloudJson(timestampNanos, data, entity)
    }

    /** Anchor event — one per user-placed anchor. */
    fun anchor(
        timestampNanos: Long,
        anchor: Anchor,
    ): String {
        val id = System.identityHashCode(anchor)
        val p = anchor.pose
        return anchorJson(
            timestampNanos, id,
            p.tx(), p.ty(), p.tz(),
            p.qx(), p.qy(), p.qz(), p.qw(),
        )
    }

    /** Hit result event — raycast intersection. */
    fun hitResult(
        timestampNanos: Long,
        hit: HitResult,
    ): String {
        val id = System.identityHashCode(hit)
        val p = hit.hitPose
        return hitResultJson(
            timestampNanos, id,
            p.tx(), p.ty(), p.tz(),
            hit.distance,
        )
    }

    // ── Tier-S event types: camera trail + tracking-quality scalar ─────────

    /**
     * Camera trail event — one polyline through every accumulated camera
     * position so far. The Python sidecar maps this to `rr.LineStrips3D`,
     * giving Rerun viewers a 3D trace of how the operator moved their phone
     * during the session.
     *
     * `positions` is a flat `[x0, y0, z0, x1, y1, z1, …]` buffer; the
     * caller is responsible for keeping it bounded (e.g. ring-buffer the
     * last N samples) so the JSON line doesn't blow up over long sessions.
     *
     * Default entity path is `world/camera/trail` so it sits under the
     * existing camera entity in the Rerun blueprint.
     */
    fun cameraTrailJson(
        timestampNanos: Long,
        positions: FloatArray,
        entity: String = "world/camera/trail",
    ): String {
        val n = positions.size / 3
        val sb = StringBuilder(64 + n * 28)
        sb.append('{')
        sb.appendCommonHeader(timestampNanos, "camera_trail", entity)
        sb.append(",\"positions\":[")
        for (i in 0 until n) {
            if (i > 0) sb.append(',')
            sb.append('[')
            sb.appendFloat(positions[i * 3]); sb.append(',')
            sb.appendFloat(positions[i * 3 + 1]); sb.append(',')
            sb.appendFloat(positions[i * 3 + 2])
            sb.append(']')
        }
        sb.append("]}\n")
        return sb.toString()
    }

    /**
     * Generic scalar timeseries event. The Python sidecar maps this to
     * `rr.Scalars` (Rerun ≥ 0.31) so the value appears as a graph in the
     * viewer's timeline panel. Use it for things like ARCore tracking
     * quality (0.0 … 1.0), feature-point count, or any per-frame health
     * metric you want to scrub against the 3D scene.
     *
     * The `entity` is also the legend label in the Rerun viewer — pass a
     * descriptive path like `world/camera/tracking_quality`.
     */
    fun scalarJson(
        timestampNanos: Long,
        value: Float,
        entity: String,
    ): String {
        val sb = StringBuilder(96)
        sb.append('{')
        sb.appendCommonHeader(timestampNanos, "scalar", entity)
        sb.append(",\"value\":")
        sb.appendFloat(value)
        sb.append("}\n")
        return sb.toString()
    }

    // ── Control protocol ──────────────────────────────────────────────────
    //
    // In addition to event lines (camera_pose / plane / point_cloud / …) the
    // Kotlin bridge can send "control" lines to ask the Python sidecar to
    // perform an action. The sidecar acknowledges by writing a single JSON
    // line back on the same socket.
    //
    // Wire format:
    //   client -> sidecar : {"type":"control","cmd":"save_now"}
    //   sidecar -> client : {"type":"control","ack":"saved","path":"…","viewerUrl":"…","events":N}
    //                       {"type":"control","ack":"save_unsupported","reason":"…"}
    //
    // The control protocol is intentionally minimal — adding a new command is
    // a paired change in this file and `samples/.../tools/rerun-bridge.py`.

    /** "Save the current recording and reply with the resulting path + URL." */
    fun controlSaveNow(): String =
        """{"type":"control","cmd":"save_now"}""" + "\n"

    /**
     * Parsed acknowledgment from the sidecar after a `save_now` command.
     *
     * @param success  `true` iff the sidecar wrote a file successfully.
     * @param path     local path of the .rrd on the sidecar host (dev machine), or `null` on failure.
     * @param viewerUrl URL into the SceneView Rerun viewer that already encodes the
     *                  recording's location (often a `file://` link the user must
     *                  re-host before sharing, on an https host that allows
     *                  cross-origin requests — useful as a copy-paste hint).
     * @param events   number of events that made it into the file.
     * @param reason   human-readable error reason on failure (e.g. live-mode sidecar).
     */
    data class ControlAck(
        val success: Boolean,
        val path: String?,
        val viewerUrl: String?,
        val events: Int,
        val reason: String?,
    )

    /**
     * Best-effort parser for one JSON-lines string emitted by the sidecar.
     *
     * Returns `null` if the line is not a control acknowledgment we recognise
     * — callers should ignore it and keep reading. Hand-parsed (no JSON lib)
     * to keep the runtime dependency-free, matching the rest of this file.
     */
    fun parseControlAck(line: String): ControlAck? {
        // Cheap structural sniff first — avoids regex on every event line.
        if (!line.contains("\"type\":\"control\"")) return null
        if (!line.contains("\"ack\"")) return null

        val ack = extractStringField(line, "ack") ?: return null
        return when (ack) {
            "saved" -> ControlAck(
                success = true,
                path = extractStringField(line, "path"),
                viewerUrl = extractStringField(line, "viewerUrl"),
                events = extractIntField(line, "events") ?: 0,
                reason = null,
            )
            else -> ControlAck(
                success = false,
                path = null,
                viewerUrl = null,
                events = 0,
                reason = extractStringField(line, "reason") ?: ack,
            )
        }
    }

    private fun extractStringField(line: String, name: String): String? {
        val key = "\"$name\":\""
        val start = line.indexOf(key).takeIf { it >= 0 } ?: return null
        val from = start + key.length
        val sb = StringBuilder()
        var i = from
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                when (val next = line[i + 1]) {
                    '"', '\\', '/' -> sb.append(next)
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 5 >= line.length) return null
                        sb.append(line.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    }
                    else -> sb.append(next)
                }
                i += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            i++
        }
        return null
    }

    private fun extractIntField(line: String, name: String): Int? {
        val key = "\"$name\":"
        val start = line.indexOf(key).takeIf { it >= 0 } ?: return null
        var i = start + key.length
        // Skip whitespace
        while (i < line.length && line[i].isWhitespace()) i++
        val numStart = i
        if (i < line.length && (line[i] == '-' || line[i] == '+')) i++
        while (i < line.length && line[i].isDigit()) i++
        if (numStart == i) return null
        return line.substring(numStart, i).toIntOrNull()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun StringBuilder.appendCommonHeader(t: Long, type: String, entity: String) {
        append("\"t\":")
        append(t)
        append(",\"type\":\"")
        append(type)
        append("\",\"entity\":\"")
        appendEscaped(entity)
        append('"')
    }

    /**
     * Writes a float as plain JSON number. Non-finite values (NaN/Infinity)
     * would break the JSON line; we emit `0` instead so the line stays parseable.
     * The Python sidecar treats tiny outliers as noise so clamping is safe.
     */
    private fun StringBuilder.appendFloat(f: Float) {
        if (!f.isFinite()) {
            append('0')
            return
        }
        append(f)
    }

    /**
     * Minimal JSON string escaper. Entity paths are developer-supplied so
     * they may legitimately contain `/` — but never a `"` or control char
     * under our conventions. Still, we escape defensively to avoid a single
     * rogue id corrupting the wire format.
     */
    private fun StringBuilder.appendEscaped(s: String) {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
    }
}
