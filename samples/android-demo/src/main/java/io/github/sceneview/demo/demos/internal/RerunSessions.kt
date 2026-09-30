package io.github.sceneview.demo.demos.internal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.zip.CRC32

/*
 * "Your sessions", on disk — the same layout as the iOS demo (#4068), so a session moves between
 * the two apps as a file: every recording, and every scan file opened, is one directory holding
 * the recorder's three files, `session.json` and `thumbnail.jpg`. A stored session replays through
 * exactly the code the bundled sample does. Pure Kotlin and java.io, tested on the JVM.
 */

/**
 * A capture as the recorder writes it: its manifest, its log in the Rerun bridge's wire format,
 * and its photos packed in one archive the manifest indexes. Byte for byte what is on disk.
 */
class RerunCapturePack(val manifest: ByteArray, val log: ByteArray, val media: ByteArray) {
    override fun equals(other: Any?): Boolean = other is RerunCapturePack &&
        manifest.contentEquals(other.manifest) && log.contentEquals(other.log) && media.contentEquals(other.media)

    override fun hashCode(): Int =
        (manifest.contentHashCode() * HASH_PRIME + log.contentHashCode()) * HASH_PRIME + media.contentHashCode()

    /** The three files, named as the recorder names them, in the order they are written. */
    fun files(): List<Pair<String, ByteArray>> = listOf(MANIFEST to manifest, LOG to log, MEDIA to media)

    /**
     * The capture opened: its trace, its manifest, and what the sessions list shows of it. `null`
     * when the manifest does not parse.
     */
    fun open(): OpenedCapture? {
        val parsed = ReplayManifest.parse(String(manifest)) ?: return null
        val events = parseArDebugLog(String(log).lineSequence())
        val trace = ArDebugTrace.of(events).apply { keyframeSpacing = ReplayGeometry.KEYFRAME_SPACING_M }
        return OpenedCapture(trace, parsed, media)
    }

    companion object {
        const val MANIFEST = "capture-manifest.json"
        const val LOG = "capture-session.jsonl"
        const val MEDIA = "capture-media.bin"
        private const val HASH_PRIME = 31

        /** The three files from [files] (name → bytes); `null` when one is missing. */
        fun of(files: Map<String, ByteArray>): RerunCapturePack? = RerunCapturePack(
            manifest = files[MANIFEST] ?: return null,
            log = files[LOG] ?: return null,
            media = files[MEDIA] ?: return null,
        )
    }
}

/** A capture read back: its [trace], its [manifest], and the [media] archive the manifest indexes. */
class OpenedCapture(val trace: ArDebugTrace, val manifest: ReplayManifest, val media: ByteArray) {
    /** Photo [path]'s bytes as the archive holds them; `null` when the manifest does not index it. */
    fun bytesOf(path: String): ByteArray? {
        val span = manifest.media[path] ?: return null
        if (span.offset < 0 || span.length <= 0 || span.offset + span.length > media.size) return null
        return media.copyOfRange(span.offset, span.offset + span.length)
    }

    /** The first photo, as recorded: the session's thumbnail. `null` for a capture without one. */
    fun firstPhoto(): ByteArray? = if (trace.imageCount == 0) null else bytesOf(trace.imagePath(0))
}

/** Where a kept session came from. The wire names are the iOS demo's. */
enum class RerunSessionSource(val wireName: String, val label: String) {
    /** Recorded on this phone with "Record your room". */
    Recorded("recorded", "Recorded"),

    /** Opened from a SceneView scan file (`.svscan`). */
    Scan("scan", "Scan file"),

    /** Opened from a Rerun recording (`.rrd`) this app exported. */
    Rrd("rrd", ".rrd file"),
    ;

    companion object {
        fun of(wireName: String?): RerunSessionSource? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * A session kept on the phone: what the list shows without opening it. `session.json`, with the
 * iOS demo's keys — [createdAt] is whole seconds since the epoch, written as ISO 8601.
 */
data class RerunStoredSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val source: RerunSessionSource,
    /** Seconds from the first event to the last. */
    val duration: Float,
    val pathMetres: Float,
    val points: Int,
    val planes: Int,
    val photos: Int,
) {
    /** Pretty-printed with sorted keys, like the iOS encoder. */
    fun toJson(): String = pretty.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("createdAt", Instant.ofEpochSecond(createdAt).toString())
            put("duration", duration.finiteOrZero())
            put("id", id)
            put("pathMetres", pathMetres.finiteOrZero())
            put("photos", photos)
            put("planes", planes)
            put("points", points)
            put("source", source.wireName)
            put("title", title)
        },
    )

    companion object {
        private val pretty = Json { prettyPrint = true }
        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        /** `null` when [text] is not a whole `session.json`: a session never lists half-read. */
        @Suppress("ReturnCount") // one early return per required key
        fun parse(text: String): RerunStoredSession? {
            val root = runCatching { lenient.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun string(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun number(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }
            val id = string("id")?.takeIf { it.isNotBlank() && '/' !in it } ?: return null
            val createdAt = string("createdAt")?.let(::epochSecondOf) ?: return null
            return RerunStoredSession(
                id = id,
                title = string("title") ?: return null,
                createdAt = createdAt,
                source = RerunSessionSource.of(string("source")) ?: return null,
                duration = number("duration")?.floatOrNull ?: return null,
                pathMetres = number("pathMetres")?.floatOrNull ?: return null,
                points = number("points")?.intOrNull ?: return null,
                planes = number("planes")?.intOrNull ?: return null,
                photos = number("photos")?.intOrNull ?: return null,
            )
        }

        /** ISO 8601 with an offset (`2026-09-28T12:32:05Z`), in epoch seconds; `null` otherwise. */
        fun epochSecondOf(iso: String): Long? = runCatching { OffsetDateTime.parse(iso).toEpochSecond() }.getOrNull()

        /** "Room · Sep 28, 2:32 PM": the title a fresh recording gets, [date] formatted by the caller. */
        fun recordingTitle(date: String): String = "Room · $date"

        /**
         * What the sessions list keeps of [capture]: its figures read off its last frame. `null`
         * when the capture holds no camera pose, point or plane — nothing to replay.
         */
        fun of(
            capture: OpenedCapture,
            title: String,
            source: RerunSessionSource,
            createdAt: Long,
            id: String = UUID.randomUUID().toString().uppercase(),
        ): RerunStoredSession? {
            val trace = capture.trace
            if (trace.isEmpty) return null
            val last = trace.frameAt(trace.duration)
            return RerunStoredSession(
                id = id,
                title = title,
                createdAt = createdAt,
                source = source,
                duration = trace.duration,
                pathMetres = ArDebugStats.pathLength(last.trail),
                // A `.svscan` v2's dense cloud is the scan's points: the scan HUD counted it live
                // and the replay draws it in place of ARCore's sparse feature points.
                points = trace.pointCountAt(trace.duration, capture.manifest.dense?.count ?: 0),
                planes = last.planes.size,
                photos = trace.imageCount,
            )
        }

        private fun Float.finiteOrZero() = if (isFinite()) this else 0f
    }
}

/** Why a file did not become a session. */
sealed class RerunImportFailure(message: String) : Exception(message) {
    /** Neither a `.svscan` nor a `.rrd`. */
    class Unsupported(file: String) : RerunImportFailure(file)

    /** The right kind of file, but its content does not read. */
    class Unreadable(file: String) : RerunImportFailure(file)

    /** It read, and holds no camera path, point or surface: nothing to replay. */
    class Empty(file: String) : RerunImportFailure(file)

    /** A Rerun recording saved with compression, which this app does not decompress. */
    class RrdCompressed(file: String) : RerunImportFailure(file)

    /** A Rerun recording from a Rerun version newer than the one this app reads. */
    class RrdNewerVersion(file: String) : RerunImportFailure(file)
}

/**
 * The sessions directory: `<root>/<id>/` with the capture's three files, `session.json` and, when
 * the capture has photos, `thumbnail.jpg` — its first photo, as recorded. Blocking IO: call it off
 * the main thread.
 */
class RerunSessionStore(val root: File) {
    fun directoryOf(id: String): File = File(root, id)

    /**
     * Every readable session, newest first. A directory without a readable `session.json` — a save
     * cut short — is skipped, not shown broken.
     */
    fun list(): List<RerunStoredSession> = root.listFiles { file -> file.isDirectory }.orEmpty()
        .mapNotNull { dir ->
            runCatching { RerunStoredSession.parse(File(dir, INFO).readText()) }.getOrNull()
                ?.takeIf { it.id == dir.name }
                ?.countingDense(dir)
        }
        .sortedByDescending { it.createdAt }

    /**
     * A dense scan kept before its `session.json` counted the dense cloud (it counted ARCore's
     * sparse map, capped at 12k, while the scan HUD showed the cloud): its points read again from
     * its manifest, so the list says what the scan and its replay say.
     */
    private fun RerunStoredSession.countingDense(dir: File): RerunStoredSession {
        val dense = runCatching {
            ReplayManifest.parse(File(dir, RerunCapturePack.MANIFEST).readText())?.dense?.count
        }.getOrNull()
        return if (dense != null && dense > 0 && dense != points) copy(points = dense) else this
    }

    /**
     * Keeps [capture] as a new session. The capture files are written before `session.json`, so a
     * session only lists once it is whole.
     *
     * @throws RerunImportFailure.Unreadable when its manifest does not parse.
     * @throws RerunImportFailure.Empty when it holds nothing to replay.
     */
    fun save(
        capture: RerunCapturePack,
        title: String,
        source: RerunSessionSource,
        nowMillis: Long = System.currentTimeMillis(),
    ): RerunStoredSession {
        val opened = capture.open() ?: throw RerunImportFailure.Unreadable(title)
        // Whole seconds: `session.json` stores ISO 8601, so what `save` returns equals what `list` reads.
        val session = RerunStoredSession.of(opened, title, source, createdAt = nowMillis / MILLIS)
            ?: throw RerunImportFailure.Empty(title)
        val dir = directoryOf(session.id)
        dir.mkdirs()
        for ((name, bytes) in capture.files()) writeAtomically(File(dir, name), bytes)
        opened.firstPhoto()?.let { runCatching { writeAtomically(File(dir, THUMBNAIL), it) } }
        writeAtomically(File(dir, INFO), session.toJson().toByteArray())
        return session
    }

    /** Session [id]'s capture; `null` when one of its three files is gone. */
    fun capture(id: String): RerunCapturePack? {
        val dir = directoryOf(id)
        return runCatching {
            RerunCapturePack(
                manifest = File(dir, RerunCapturePack.MANIFEST).readBytes(),
                log = File(dir, RerunCapturePack.LOG).readBytes(),
                media = File(dir, RerunCapturePack.MEDIA).readBytes(),
            )
        }.getOrNull()
    }

    /** Session [id]'s thumbnail file, `null` when it has none. */
    fun thumbnail(id: String): File? = File(directoryOf(id), THUMBNAIL).takeIf { it.isFile }

    fun delete(id: String): Boolean {
        val dir = directoryOf(id)
        return !dir.exists() || dir.deleteRecursively()
    }

    /**
     * Reads a file the user opened — a scan file, named [fileName] — and keeps it as a new session
     * titled after the file. [readRrd] turns a `.rrd`'s bytes into a capture, or throws.
     *
     * @throws RerunImportFailure
     */
    @Suppress("ThrowsCount") // one throw per way a file fails, each renamed after the file
    fun import(
        fileName: String,
        bytes: ByteArray,
        readRrd: (ByteArray) -> RerunCapturePack,
        nowMillis: Long = System.currentTimeMillis(),
    ): RerunStoredSession {
        val kind = RerunFileKind.of(fileName, bytes) ?: throw RerunImportFailure.Unsupported(fileName)
        val capture = when (kind) {
            RerunFileKind.Scan -> RerunScanFile.read(bytes) ?: throw RerunImportFailure.Unreadable(fileName)
            RerunFileKind.Rrd -> try {
                readRrd(bytes)
            } catch (failure: RerunImportFailure) {
                throw failure
            } catch (other: Exception) {
                throw RerunImportFailure.Unreadable(fileName).apply { initCause(other) }
            }
        }
        val title = fileName.substringBeforeLast('.').trim().ifEmpty { "Opened session" }
        val source = if (kind == RerunFileKind.Scan) RerunSessionSource.Scan else RerunSessionSource.Rrd
        return try {
            save(capture, title, source, nowMillis)
        } catch (failure: RerunImportFailure.Empty) {
            throw RerunImportFailure.Empty(fileName).apply { initCause(failure) }
        } catch (failure: RerunImportFailure.Unreadable) {
            throw RerunImportFailure.Unreadable(fileName).apply { initCause(failure) }
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val partial = File(target.parentFile, target.name + PARTIAL)
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) {
            partial.delete()
            error("Could not write ${target.name}")
        }
    }

    companion object {
        const val INFO = "session.json"
        const val THUMBNAIL = "thumbnail.jpg"
        private const val PARTIAL = ".partial"
        private const val MILLIS = 1000L
    }
}

/** The two files the demo opens, told apart by name, or by their first bytes for a nameless one. */
enum class RerunFileKind {
    Scan,
    Rrd,
    ;

    companion object {
        fun of(fileName: String?, head: ByteArray): RerunFileKind? {
            when (fileName?.substringAfterLast('.', "")?.lowercase()) {
                RerunScanFile.EXTENSION -> return Scan
                "rrd" -> return Rrd
            }
            return when {
                RerunScanFile.looksLikeOne(head) -> Scan
                startsWith(head, RRD_MAGIC) -> Rrd
                else -> null
            }
        }

        /** Every Rerun `.rrd` stream opens with `RRF` and a version digit. */
        private val RRD_MAGIC = "RRF".toByteArray()

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }
    }
}

/**
 * The app's own recording format as one file: a stored (uncompressed) zip of the recorder's three
 * files, under their exact names — the layout the recorder writes to disk, so any zip tool can
 * unpack it and any SceneView app can replay it. Written exactly as the iOS demo writes it, each
 * file's data on a 64-byte boundary, so the same capture is the same file on both.
 */
object RerunScanFile {
    const val EXTENSION = "svscan"

    /** A file name from a session title: path separators and colons replaced, never empty. */
    fun fileName(title: String): String {
        val cleaned = title.map { if (it in "/\\:") '-' else it }.joinToString("").trim()
        return cleaned.ifEmpty { "scan" } + "." + EXTENSION
    }

    fun write(capture: RerunCapturePack): ByteArray = StoredZip.write(capture.files())

    /** The capture in [bytes]; `null` when they are not a scan file or one of its files is missing. */
    fun read(bytes: ByteArray): RerunCapturePack? =
        StoredZip.read(bytes)?.let { files -> RerunCapturePack.of(files.toMap()) }

    /** A zip whose first entry is the manifest: how both apps write a scan file. */
    fun looksLikeOne(head: ByteArray): Boolean {
        if (head.size < ZIP_HEADER || StoredZip.u32(head, 0) != StoredZip.LOCAL) return false
        val nameLength = StoredZip.u16(head, NAME_LENGTH_AT)
        if (head.size < ZIP_HEADER + nameLength) return false
        return String(head, ZIP_HEADER, nameLength) == RerunCapturePack.MANIFEST
    }

    private const val ZIP_HEADER = 30
    private const val NAME_LENGTH_AT = 26
}

/**
 * A stored (method 0) zip, the way the iOS demo's USDZ archive writes one: fixed 1980-01-01 time,
 * each file's data padded onto a 64-byte boundary with one `0x1986` extra field — so the same
 * files make the same bytes on both platforms. [read] refuses a compressed entry.
 */
internal object StoredZip {
    const val LOCAL = 0x04034B50L
    private const val CENTRAL = 0x02014B50L
    private const val END = 0x06054B50L
    private const val VERSION = 20
    private const val DOS_DATE = 0x21
    private const val ALIGN = 64
    private const val PAD_ID = 0x1986
    private const val LOCAL_HEADER = 30
    private const val CENTRAL_HEADER = 46
    private const val END_RECORD = 22
    private const val MAX_COMMENT = 65_535

    fun write(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = LittleEndianBuffer()
        val central = LittleEndianBuffer()
        for ((path, data) in files) {
            val name = path.toByteArray()
            val crc = CRC32().apply { update(data) }.value
            val offset = out.size
            var pad = (ALIGN - (out.size + LOCAL_HEADER + name.size) % ALIGN) % ALIGN
            if (pad in 1..3) pad += ALIGN
            val extra = LittleEndianBuffer()
            if (pad > 0) {
                extra.u16(PAD_ID)
                extra.u16(pad - 4)
                extra.bytes(ByteArray(pad - 4))
            }
            out.u32(LOCAL)
            out.u16(VERSION)
            out.u16(0) // flags
            out.u16(0) // method: stored
            out.u16(0) // time
            out.u16(DOS_DATE)
            out.u32(crc)
            out.u32(data.size.toLong())
            out.u32(data.size.toLong())
            out.u16(name.size)
            out.u16(extra.size)
            out.bytes(name)
            out.bytes(extra.toByteArray())
            out.bytes(data)

            central.u32(CENTRAL)
            central.u16(VERSION) // version made by
            central.u16(VERSION)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u16(DOS_DATE)
            central.u32(crc)
            central.u32(data.size.toLong())
            central.u32(data.size.toLong())
            central.u16(name.size)
            central.u16(0) // extra
            central.u16(0) // comment
            central.u16(0) // disk
            central.u16(0) // internal attributes
            central.u32(0) // external attributes
            central.u32(offset.toLong())
            central.bytes(name)
        }
        val centralOffset = out.size
        out.bytes(central.toByteArray())
        out.u32(END)
        out.u16(0)
        out.u16(0)
        out.u16(files.size)
        out.u16(files.size)
        out.u32(central.size.toLong())
        out.u32(centralOffset.toLong())
        out.u16(0)
        return out.toByteArray()
    }

    /** The files of a stored zip, in archive order; `null` when it is not one, or compresses. */
    fun read(zip: ByteArray): List<Pair<String, ByteArray>>? = runCatching { readOrThrow(zip) }.getOrNull()

    @Suppress("ThrowsCount") // one throw per malformed field
    private fun readOrThrow(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val end = endRecord(bytes) ?: error("no end record")
        val count = u16(bytes, end + 10)
        var cursor = u32(bytes, end + 16).toInt()
        val found = ArrayList<Triple<Int, String, ByteArray>>()
        repeat(count) {
            check(u32(bytes, cursor) == CENTRAL) { "bad central header" }
            val method = u16(bytes, cursor + 10)
            val size = u32(bytes, cursor + 20).toInt()
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val commentLength = u16(bytes, cursor + 32)
            val local = u32(bytes, cursor + 42).toInt()
            check(cursor + CENTRAL_HEADER + nameLength <= bytes.size) { "truncated name" }
            val path = String(bytes, cursor + CENTRAL_HEADER, nameLength)
            cursor += CENTRAL_HEADER + nameLength + extraLength + commentLength
            if (path.endsWith("/")) return@repeat
            check(method == 0) { "compressed entry $path" }
            check(u32(bytes, local) == LOCAL) { "bad local header" }
            val start = local + LOCAL_HEADER + u16(bytes, local + 26) + u16(bytes, local + 28)
            check(size >= 0 && start + size <= bytes.size) { "truncated data" }
            found += Triple(local, path, bytes.copyOfRange(start, start + size))
        }
        return found.sortedBy { it.first }.map { it.second to it.third }
    }

    private fun endRecord(bytes: ByteArray): Int? {
        var i = bytes.size - END_RECORD
        val floor = maxOf(0, bytes.size - END_RECORD - MAX_COMMENT)
        while (i >= floor) {
            if (u32(bytes, i) == END) return i
            i--
        }
        return null
    }

    fun u16(bytes: ByteArray, at: Int): Int {
        check(at >= 0 && at + 2 <= bytes.size) { "out of bounds" }
        return (bytes[at].toInt() and BYTE) or ((bytes[at + 1].toInt() and BYTE) shl 8)
    }

    fun u32(bytes: ByteArray, at: Int): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private const val BYTE = 0xFF

    private class LittleEndianBuffer {
        private val out = ByteArrayOutputStream()
        val size: Int get() = out.size()

        fun u16(value: Int) {
            out.write(value and BYTE)
            out.write(value shr 8 and BYTE)
        }

        fun u32(value: Long) {
            u16((value and 0xFFFF).toInt())
            u16((value shr 16 and 0xFFFF).toInt())
        }

        fun bytes(value: ByteArray) = out.write(value)

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
