package io.github.sceneview.demo.demos.internal

/**
 * Reads a Rerun `.rrd` recording written by [RerunRrdWriter] — this app's or the iOS demo's —
 * back into the app, so a space exported from the replay opens in the replay again. Pure Kotlin,
 * like the writer: no Rerun SDK, no protobuf, Arrow or FlatBuffers library. The iOS demo's
 * `RerunRRDReader`.
 *
 * The output is a native capture ([RerunCapturePack]) — the manifest, the JSON-lines session and
 * the media archive the recorder produces — so it replays exactly like a capture made on the
 * phone. What comes back, entity by entity:
 *
 * - `world/camera` — every pose at its time (`camera_pose`), the lens (`Pinhole`, as the
 *   manifest's `intrinsics`) and each photo at its time (`image`; the bytes go into the media
 *   archive and the manifest indexes them, as the recorder does). The writer adds one pose row per
 *   photo so Rerun shows the camera where the photo was taken; those rows are recognised and
 *   dropped ([pathPoses]), which gives back the path the writer was given.
 * - `world/points` — the coloured map; `world/points/live` — what the camera saw over time, one
 *   `point_cloud` per row at its time, coloured from the map.
 * - `world/planes/<id>` — the polygon from the outline (without its closing point), the kind from
 *   the outline's colour, and the plane photo when the `Mesh3D` carries one: the texels become a
 *   PNG in the media archive and the texture frame (`origin`, `u`, `v`) is fitted back from the
 *   vertices and their texture coordinates. A plane whose texture does not map back cleanly comes
 *   back untextured rather than wrongly textured.
 * - `world/anchors/<id>` — each anchor's pose.
 *
 * Planes and anchors are static, emitted at the start of the session. The points replay as they
 * were seen when the file has `world/points/live` (this app's exports since #4080): the map grows
 * and the live points come back, as in the original capture. A file without it (an older export,
 * another writer) only has the final map, shown whole from the first frame — nothing pretends
 * the map grew when that history is not in the file.
 *
 * Anything malformed — another magic, a compressed stream, a truncated message, an offset out of
 * range, a component in an unexpected Arrow layout — throws a [Failure].
 */
object RerunRrdReader {
    /** Why an `.rrd` does not read. */
    sealed class Failure(message: String) : Exception(message) {
        /** The data does not start with `RRF2`: not an `.rrd`, or one from before Rerun's protobuf framing. */
        class NotAnRrd : Failure("not an .rrd")

        /** An `.rrd` of another major Rerun version than the writer's. */
        class UnsupportedVersion(val major: Int, val minor: Int, val patch: Int) :
            Failure("Rerun $major.$minor.$patch")

        /** An LZ4-compressed stream or chunk. The writer never compresses. */
        class Compressed : Failure("compressed")

        /** A message serializer other than protobuf. */
        class UnsupportedSerializer(val serializer: Int) : Failure("serializer $serializer")

        /** The data ends inside a message, or an offset points past its end. */
        class Truncated : Failure("truncated")

        /** Bytes that are not what the format says they are. */
        class Malformed(val what: String) : Failure(what)

        /** A component the replay needs, in an Arrow layout the writer never emits. */
        class UnexpectedLayout(val what: String) : Failure(what)

        /** A valid `.rrd` with nothing to replay: no camera pose, map point or plane. */
        class NothingToReplay : Failure("nothing to replay")
    }

    /** A decoded recording: its name (`RecordingInfo`, `null` when it has none) and the capture. */
    class Recording(val title: String?, val pack: RerunCapturePack)

    /** One camera pose on the `time` timeline, in nanoseconds. */
    data class PoseRow(val time: Long, val position: Vec3, val orientation: RerunExportScene.Quat)

    /** The capture and the recording's name. [codec] turns plane texels back into a PNG. */
    fun recording(data: ByteArray, codec: RerunImageCodec): Recording =
        RerunRrdContents(RrdStream.chunks(data)).recording(codec)

    /**
     * The capture [data] describes, for the session store's import: a [Failure] becomes the
     * [RerunImportFailure] the user is shown, named after [fileName].
     *
     * @throws RerunImportFailure
     */
    fun capturePack(data: ByteArray, fileName: String, codec: RerunImageCodec): RerunCapturePack = try {
        recording(data, codec).pack
    } catch (failure: Failure) {
        throw importFailure(failure, fileName).apply { initCause(failure) }
    }

    /** What the sessions list says for [failure]. */
    fun importFailure(failure: Failure, fileName: String): RerunImportFailure = when (failure) {
        is Failure.Compressed -> RerunImportFailure.RrdCompressed(fileName)
        is Failure.UnsupportedVersion -> RerunImportFailure.RrdNewerVersion(fileName)
        is Failure.NothingToReplay -> RerunImportFailure.Empty(fileName)
        else -> RerunImportFailure.Unreadable(fileName)
    }

    /**
     * The camera path without the writer's keyframe rows.
     *
     * [RerunRrdWriter] logs the path's poses and, merged in, one pose per photo at the photo's
     * time, sorted after the path pose on a tie. Those are the last rows at each photo time; they
     * are dropped when what remains retraces `world/camera_path` exactly. Failing that, the path
     * is matched from its end. When neither fits (a file from another writer), every row is a pose.
     */
    fun pathPoses(rows: List<PoseRow>, path: List<Vec3>, photoTimes: List<Long>): List<PoseRow> {
        if (path.size < 2 || rows.size <= path.size) return rows
        val pending = HashMap<Long, Int>()
        for (time in photoTimes) pending[time] = (pending[time] ?: 0) + 1
        val dropped = HashSet<Int>()
        for (index in rows.indices.reversed()) {
            val count = pending[rows[index].time] ?: 0
            if (count > 0) {
                dropped += index
                pending[rows[index].time] = count - 1
            }
        }
        val kept = rows.filterIndexed { index, _ -> index !in dropped }
        if (kept.size == path.size && kept.zip(path).all { (row, point) -> row.position == point }) return kept

        var next = path.size - 1
        val matched = BooleanArray(rows.size)
        for (index in rows.indices.reversed()) {
            if (next >= 0 && rows[index].position == path[next]) {
                matched[index] = true
                next--
            }
        }
        return if (next < 0) rows.filterIndexed { index, _ -> matched[index] } else rows
    }
}

/** Throws [failure]: one call site per way a file fails, without a `throw` count per function. */
internal fun rrdFail(failure: RerunRrdReader.Failure): Nothing = throw failure

/**
 * The `.rrd` framing: a 12-byte stream header, then messages of a 16-byte header (kind, length)
 * and a protobuf payload.
 */
internal object RrdStream {
    private const val HEADER_SIZE = 12
    private const val MESSAGE_HEADER_SIZE = 16
    private const val KIND_END = 0L
    private const val KIND_ARROW_MSG = 2L
    private const val SERIALIZER_PROTOBUF = 2
    private const val STORE_KIND_BLUEPRINT = 2L

    fun chunks(data: ByteArray): List<RrdChunk> {
        val bytes = RrdBytes(data)
        header(bytes, 0)
        var position = HEADER_SIZE
        val chunks = ArrayList<RrdChunk>()
        while (position < bytes.count) {
            val kind = bytes.u64(position)
            val length = bytes.u64(position + 8)
            position += MESSAGE_HEADER_SIZE
            if (length < 0 || length > bytes.count - position) rrdFail(RerunRrdReader.Failure.Truncated())
            val payload = bytes.slice(position, length.toInt())
            position += length.toInt()
            when (kind) {
                // End of stream. Another stream may follow (concatenated recordings); anything else
                // is the optional footer, an index the reader does not need.
                KIND_END -> {
                    if (position >= bytes.count || runCatching { header(bytes, position) }.isFailure) return chunks
                    position += HEADER_SIZE
                }
                KIND_ARROW_MSG -> chunks += arrowMessage(payload)
                else -> Unit // SetStoreInfo, blueprint activation: nothing the replay draws.
            }
        }
        return chunks
    }

    /**
     * `RRF2`, the Rerun version (major, minor, patch, 0), then the options: compression (0 off,
     * 1 LZ4) and serializer (2 protobuf).
     */
    fun header(bytes: RrdBytes, position: Int) {
        if (bytes.count - position < 4 || !bytes.matches("RRF2", position)) rrdFail(RerunRrdReader.Failure.NotAnRrd())
        if (bytes.count - position < HEADER_SIZE) rrdFail(RerunRrdReader.Failure.Truncated())
        val major = bytes.u8(position + 4)
        if (major != RerunRrdWriter.VERSION_MAJOR) {
            rrdFail(RerunRrdReader.Failure.UnsupportedVersion(major, bytes.u8(position + 5), bytes.u8(position + 6)))
        }
        if (bytes.u8(position + 8) != 0) rrdFail(RerunRrdReader.Failure.Compressed())
        val serializer = bytes.u8(position + 9)
        if (serializer != SERIALIZER_PROTOBUF) rrdFail(RerunRrdReader.Failure.UnsupportedSerializer(serializer))
    }

    /** `rerun.log_msg.v1alpha1.ArrowMsg`: store id (1), compression (2), encoding (4), the IPC payload (5). */
    private fun arrowMessage(payload: RrdBytes): List<RrdChunk> {
        val message = RrdProto(payload)
        if (message.message(1)?.varint(1) == STORE_KIND_BLUEPRINT) return emptyList() // Not the recording.
        when (message.varint(2) ?: 1L) {
            0L, 1L -> Unit // Unspecified, none.
            2L -> rrdFail(RerunRrdReader.Failure.Compressed())
            else -> rrdFail(RerunRrdReader.Failure.Malformed("unknown chunk compression"))
        }
        if ((message.varint(4) ?: 1L) !in 0L..1L) {
            rrdFail(RerunRrdReader.Failure.Malformed("chunk encoding is not Arrow IPC"))
        }
        val ipc = message.bytes(5) ?: rrdFail(RerunRrdReader.Failure.Malformed("chunk without payload"))
        return RrdArrowStream.chunks(ipc)
    }
}
