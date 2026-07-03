package io.github.sceneview.ar.collaborative

/**
 * JSON-lines wire format for the collaborative (multiplayer) AR layer.
 *
 * Every collaborative event is one JSON object on a single line, terminated
 * by `\n`. A [CollaborativeTransport] relays these lines verbatim between
 * peers; [CollaborativeSession] produces and consumes them.
 *
 * ### Why JSON-lines (and not protobuf / kotlinx.serialization)
 *
 * This mirrors [io.github.sceneview.ar.rerun.RerunWireFormat]: a `String`-in /
 * `String`-out, hand-rolled serializer with **zero new runtime dependencies**.
 * `kotlinx.serialization` is not on the `arsceneview` classpath and pulling it
 * in for a small message bus would bloat every SceneView app. JSON-lines is
 * also trivially framable on a byte stream (split on `\n`) and human-readable
 * on the wire, which makes a loopback / fake transport easy to debug.
 *
 * ### Message types
 *
 * ```
 * {"type":"hello",  "peer":"<id>", "name":"<display>"}
 * {"type":"anchor", "peer":"<id>", "id":"<cloudAnchorId>", "name":"<key>"}
 * {"type":"pose",   "peer":"<id>", "t":<epochMs>, "translation":[x,y,z], "quaternion":[x,y,z,w]}
 * {"type":"node",   "peer":"<id>", "node":"<key>", "model":"<key>",
 *                   "translation":[x,y,z], "quaternion":[x,y,z,w], "scale":[x,y,z]}
 * {"type":"bye",    "peer":"<id>"}
 * ```
 *
 * - `hello` / `bye` — a peer joining / leaving. `hello` carries an optional
 *   human-readable display name.
 * - `anchor` — the shared coordinate-frame anchor. The host broadcasts the
 *   ARCore Cloud Anchor ID it obtained from [io.github.sceneview.ar.node.CloudAnchorNode.host]
 *   so every other peer can resolve the *same* anchor and agree on a frame.
 * - `pose` — a participant's live camera pose, expressed **in the shared
 *   anchor's local space** so it is directly comparable across devices.
 * - `node` — a placed object's transform, again in shared-anchor space. The
 *   `model` field is an app-defined key telling the receiver which asset to
 *   instantiate. Conflict policy is last-writer-wins per `node` key.
 *
 * All coordinates are in the shared anchor's local space. The translation /
 * quaternion / scale ordering matches ARCore's `Pose` (`[x,y,z]` translation,
 * `[x,y,z,w]` quaternion) and SceneView's `Scale` (`[x,y,z]`).
 *
 * This object is pure: no I/O, no threading, no Android framework — it can be
 * unit-tested on plain JVM.
 */
public object CollaborativeWireFormat {

    /** Discriminator values for the `"type"` field. */
    public const val TYPE_HELLO: String = "hello"
    public const val TYPE_ANCHOR: String = "anchor"
    public const val TYPE_POSE: String = "pose"
    public const val TYPE_NODE: String = "node"
    public const val TYPE_BYE: String = "bye"

    // ── Encoders ──────────────────────────────────────────────────────────

    /** Encodes a `hello` message — a peer announcing itself to the session. */
    public fun hello(peerId: String, displayName: String): String = buildString(96) {
        append('{')
        appendField("type", TYPE_HELLO)
        append(',')
        appendField("peer", peerId)
        append(',')
        appendField("name", displayName)
        append("}\n")
    }

    /** Encodes a `bye` message — a peer leaving the session. */
    public fun bye(peerId: String): String = buildString(48) {
        append('{')
        appendField("type", TYPE_BYE)
        append(',')
        appendField("peer", peerId)
        append("}\n")
    }

    /**
     * Encodes an `anchor` message carrying the shared ARCore Cloud Anchor ID.
     *
     * @param peerId        the host that hosted the anchor.
     * @param cloudAnchorId the ARCore-issued Cloud Anchor ID.
     * @param name          an app-meaningful name for the anchor (registry key).
     */
    public fun anchor(peerId: String, cloudAnchorId: String, name: String): String =
        buildString(160) {
            append('{')
            appendField("type", TYPE_ANCHOR)
            append(',')
            appendField("peer", peerId)
            append(',')
            appendField("id", cloudAnchorId)
            append(',')
            appendField("name", name)
            append("}\n")
        }

    /**
     * Encodes a `pose` message — a participant's camera pose in shared-anchor
     * local space.
     *
     * @param peerId       the participant whose pose this is.
     * @param epochMs      wall-clock capture time, used for staleness checks.
     * @param translation  `[x,y,z]` translation in shared-anchor space.
     * @param quaternion   `[x,y,z,w]` rotation in shared-anchor space.
     */
    public fun pose(
        peerId: String,
        epochMs: Long,
        translation: FloatArray,
        quaternion: FloatArray,
    ): String {
        require(translation.size == 3) { "translation must have 3 components" }
        require(quaternion.size == 4) { "quaternion must have 4 components" }
        return buildString(160) {
            append('{')
            appendField("type", TYPE_POSE)
            append(',')
            appendField("peer", peerId)
            append(",\"t\":")
            append(epochMs)
            append(',')
            appendVec("translation", translation)
            append(',')
            appendVec("quaternion", quaternion)
            append("}\n")
        }
    }

    /**
     * Encodes a `node` message — a placed object's transform in shared-anchor
     * local space.
     *
     * @param peerId       the peer that placed / moved the node.
     * @param nodeKey      app-defined unique key for the placed node.
     * @param modelKey     app-defined key for the model asset to instantiate.
     * @param translation  `[x,y,z]` translation in shared-anchor space.
     * @param quaternion   `[x,y,z,w]` rotation in shared-anchor space.
     * @param scale        `[x,y,z]` scale.
     */
    public fun node(
        peerId: String,
        nodeKey: String,
        modelKey: String,
        translation: FloatArray,
        quaternion: FloatArray,
        scale: FloatArray,
    ): String {
        require(translation.size == 3) { "translation must have 3 components" }
        require(quaternion.size == 4) { "quaternion must have 4 components" }
        require(scale.size == 3) { "scale must have 3 components" }
        return buildString(224) {
            append('{')
            appendField("type", TYPE_NODE)
            append(',')
            appendField("peer", peerId)
            append(',')
            appendField("node", nodeKey)
            append(',')
            appendField("model", modelKey)
            append(',')
            appendVec("translation", translation)
            append(',')
            appendVec("quaternion", quaternion)
            append(',')
            appendVec("scale", scale)
            append("}\n")
        }
    }

    // ── Decoder ───────────────────────────────────────────────────────────

    /**
     * Parses one JSON-lines string into a [CollaborativeMessage].
     *
     * Returns `null` for blank lines or for any line we don't recognise —
     * callers should silently ignore those and keep reading, so a future
     * message-type addition never crashes an older peer. Hand-parsed (no JSON
     * library) to keep the runtime dependency-free, matching [hello] etc.
     */
    public fun parse(line: String): CollaborativeMessage? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val type = stringField(trimmed, "type") ?: return null
        val peer = stringField(trimmed, "peer") ?: return null
        return when (type) {
            TYPE_HELLO -> CollaborativeMessage.Hello(
                peerId = peer,
                displayName = stringField(trimmed, "name") ?: peer,
            )
            TYPE_BYE -> CollaborativeMessage.Bye(peerId = peer)
            TYPE_ANCHOR -> parseAnchor(trimmed, peer)
            TYPE_POSE -> parsePose(trimmed, peer)
            TYPE_NODE -> parseNode(trimmed, peer)
            else -> null
        }
    }

    private fun parseAnchor(line: String, peer: String): CollaborativeMessage? {
        val id = stringField(line, "id") ?: return null
        return CollaborativeMessage.SharedAnchor(
            peerId = peer,
            cloudAnchorId = id,
            name = stringField(line, "name") ?: id,
        )
    }

    private fun parsePose(line: String, peer: String): CollaborativeMessage? {
        val t = floatVec(line, "translation") ?: return null
        val q = floatVec(line, "quaternion") ?: return null
        if (t.size != 3 || q.size != 4) return null
        return CollaborativeMessage.ParticipantPose(
            peerId = peer,
            epochMs = longField(line, "t") ?: 0L,
            translation = t,
            quaternion = q,
        )
    }

    private fun parseNode(line: String, peer: String): CollaborativeMessage? {
        val nodeKey = stringField(line, "node")
        val modelKey = stringField(line, "model")
        val transform = parseTransform(line) ?: return null
        if (nodeKey == null || modelKey == null) return null
        return CollaborativeMessage.NodeState(
            peerId = peer,
            nodeKey = nodeKey,
            modelKey = modelKey,
            translation = transform.translation,
            quaternion = transform.quaternion,
            scale = transform.scale,
        )
    }

    /** A `translation`/`quaternion`/`scale` triple parsed off one line. */
    private class Transform(
        val translation: FloatArray,
        val quaternion: FloatArray,
        val scale: FloatArray,
    )

    /** Parses + size-validates the translation/quaternion/scale triple. */
    private fun parseTransform(line: String): Transform? {
        val t = floatVec(line, "translation") ?: return null
        val q = floatVec(line, "quaternion") ?: return null
        val s = floatVec(line, "scale") ?: return null
        val sizesValid = t.size == 3 && q.size == 4 && s.size == 3
        return if (sizesValid) Transform(t, q, s) else null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun StringBuilder.appendField(name: String, value: String) {
        append('"')
        append(name)
        append("\":\"")
        appendEscaped(value)
        append('"')
    }

    private fun StringBuilder.appendVec(name: String, vec: FloatArray) {
        append('"')
        append(name)
        append("\":[")
        for (i in vec.indices) {
            if (i > 0) append(',')
            appendFloat(vec[i])
        }
        append(']')
    }

    /**
     * Writes a float as a plain JSON number. Non-finite values (NaN / Infinity)
     * would break the JSON line; we emit `0` instead so the line stays
     * parseable rather than silently corrupting the whole stream.
     */
    private fun StringBuilder.appendFloat(f: Float) {
        if (!f.isFinite()) append('0') else append(f)
    }

    /** Minimal JSON string escaper — matches `RerunWireFormat`. */
    private fun StringBuilder.appendEscaped(s: String) {
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.code < 0x20 -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
    }

    /** Extracts a quoted string field by name, or `null` if absent. */
    private fun stringField(line: String, name: String): String? {
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

    /** Extracts an integer (`long`) field by name, or `null` if absent. */
    private fun longField(line: String, name: String): Long? {
        val key = "\"$name\":"
        val start = line.indexOf(key).takeIf { it >= 0 } ?: return null
        var i = start + key.length
        while (i < line.length && line[i].isWhitespace()) i++
        val numStart = i
        if (i < line.length && (line[i] == '-' || line[i] == '+')) i++
        while (i < line.length && line[i].isDigit()) i++
        if (numStart == i) return null
        return line.substring(numStart, i).toLongOrNull()
    }

    /**
     * Longest legitimate vector in the protocol — a quaternion has 4
     * components. Anything longer is malformed (or malicious) and is rejected
     * before any per-component parsing.
     */
    private const val MAX_VEC_COMPONENTS = 4

    /**
     * Generous character budget for a [MAX_VEC_COMPONENTS]-float vector body
     * (sign + digits + exponent + separators). A body longer than this cannot
     * be a valid vector, so we bail **before** substring/split — an untrusted
     * peer must not be able to force a large transient allocation with one
     * oversized `[...]` field (#2569).
     */
    private const val MAX_VEC_BODY_LENGTH = 128

    /** Extracts a `[a,b,c]` float-array field by name, or `null` if absent. */
    private fun floatVec(line: String, name: String): FloatArray? {
        val key = "\"$name\":["
        val start = line.indexOf(key).takeIf { it >= 0 } ?: return null
        val from = start + key.length
        val end = line.indexOf(']', from).takeIf { it >= 0 } ?: return null
        // Early-bail on an oversized body BEFORE allocating the substring —
        // no legitimate vector (max 4 floats) comes close to this length.
        if (end - from > MAX_VEC_BODY_LENGTH) return null
        return parseVecBody(line.substring(from, end).trim())
    }

    /** Parses a trimmed `a,b,c` vector body, or `null` on any malformed part. */
    private fun parseVecBody(body: String): FloatArray? {
        if (body.isEmpty()) return FloatArray(0)
        val parts = body.split(',')
        // Early-bail before allocating/parsing: nothing in the protocol
        // carries more than 4 components (quaternion).
        if (parts.size > MAX_VEC_COMPONENTS) return null
        val out = FloatArray(parts.size)
        for (i in parts.indices) {
            out[i] = parts[i].trim().toFloatOrNull() ?: return null
        }
        return out
    }
}
