package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream

/**
 * The end of a `.rrd` file: an index of its chunks, one manifest per store. Viewers that scan the
 * file do not need it, but `rerun rrd verify` refuses a file without one, and tools that jump to a
 * chunk (a catalog, a remote viewer) read nothing else. Rerun **0.38.1**, crate `re_log_encoding`.
 *
 * Bytes, after the last message:
 *
 * 1. An `End` message — the usual 16-byte header (kind `0`, payload length), then a
 *    `rerun.log_msg.v1alpha1.RrdFooter`: `repeated RrdManifest manifests = 1`, the stores in file
 *    order (the default layout's, then the recording's).
 * 2. A 32-byte `StreamFooter`: the payload's offset in the file and its length (two `u64`), its
 *    checksum (`u32`, xxHash32 seeded with [CRC_SEED]), `RRF2`, `FOOT`, and the number of such
 *    entries (`u32`, always `1`). All little-endian. A reader finds the footer from the file's end.
 *
 * An `RrdManifest`:
 *
 * - `store_id = 1` — the store's `StoreId`, as in its messages.
 * - `sorbet_schema = 2` — `Schema { arrow_schema = 1 }`: an Arrow IPC stream holding only a schema,
 *   the union of every chunk's columns ([Manifest.sorbetFields]). Written with the fields sorted
 *   by name, which Rerun does not require but makes these bytes the ones the next field hashes.
 * - `sorbet_schema_sha256 = 3` — the SHA-256 of that schema message, fields sorted by name, in
 *   arrow-rs's own encoding ([RerunArrowSchemaMessage]).
 * - `data = 4` — `DataframePart { encoder_version = 1, payload = 2, compression = 3,
 *   uncompressed_size = 4 }`: an Arrow IPC stream of one record batch, a row per chunk in file
 *   order ([Manifest.dataframe]), stored uncompressed (`compression = 1`) like the chunks.
 *
 * Nothing before the footer points at it, so a file cut short is still a valid stream: what was
 * written reads by full scan, as a file without footer always did.
 *
 * Checked against `rerun-cli` 0.38.1: `rerun rrd verify` accepts the export, and `rerun rrd filter
 * export.rrd -o again.rrd` — which keeps the chunks and writes Rerun's own footer — yields the
 * same `sorbet_schema_sha256` and the same manifest rows, byte offsets and sizes aside (Rerun
 * compresses what it writes).
 */
internal class RerunRrdFooter {
    private val manifests = ArrayList<Manifest>()

    /** The index of one store's chunks. [storeId] is the store's encoded `StoreId`. */
    fun manifest(storeId: ByteArray): Manifest = Manifest(storeId).also { manifests += it }

    /** Closes the stream: the `End` message carrying every manifest, then the `StreamFooter`. */
    fun appendTo(out: LittleEndian) {
        val footer = RerunProtobufWriter()
        for (manifest in manifests) if (manifest.chunkCount > 0) footer.bytes(1, manifest.encoded())
        val payload = footer.bytes
        out.long(KIND_END)
        out.long(payload.size.toLong())
        val start = out.size
        out.bytes(payload)
        out.long(start.toLong())
        out.long(payload.size.toLong())
        out.int(RerunXxHash32.hash(payload, CRC_SEED))
        out.bytes(FOURCC)
        out.bytes(IDENTIFIER)
        out.int(1)
    }

    /**
     * One component of one archetype, as Rerun identifies it. Ordered as Rerun's
     * `ComponentDescriptor` is — archetype, component, component type — which is the order of the
     * manifest's columns.
     */
    private data class Descriptor(
        val archetype: String,
        val component: String,
        val componentType: String,
    ) : Comparable<Descriptor> {
        override fun compareTo(other: Descriptor): Int = ORDER.compare(this, other)

        /** Field metadata of a manifest column about this component on [index] (a timeline, or static). */
        fun metadata(index: String) = mapOf(
            "rerun:archetype" to archetype,
            "rerun:component" to component,
            "rerun:component_type" to componentType,
            "rerun:index" to index,
        )

        private companion object {
            val ORDER: Comparator<Descriptor> = compareBy<Descriptor, String>(RerunUtf8Order) { it.archetype }
                .thenBy(RerunUtf8Order) { it.component }
                .thenBy(RerunUtf8Order) { it.componentType }
        }
    }

    /**
     * All the footer keeps of one chunk once its message is written — not the chunk, whose points
     * and photos are free to go: where it lies in the file, how many rows, the time it spans, and
     * which components it carries.
     */
    private class Row(
        val entityPath: String,
        val chunkId: RerunTuid,
        /** `null` for a static chunk. */
        val timeline: RerunTimeline?,
        val rowCount: Int,
        val byteOffset: Long,
        val byteSize: Long,
        val uncompressedSize: Long,
        val timeStart: Long,
        val timeEnd: Long,
        val components: List<Descriptor>,
    ) {
        /** The chunk's time range on [on], `null` when it has no row there. */
        fun start(on: RerunTimeline): Long? = if (timeline == on && rowCount > 0) timeStart else null

        fun end(on: RerunTimeline): Long? = if (timeline == on && rowCount > 0) timeEnd else null
    }

    /** One data column of the store's schema: a component on an entity, across the chunks holding it. */
    private class DataColumn(
        val entityPath: String,
        val descriptor: Descriptor,
        val item: RerunArrowField,
        /** Some chunk logs it as static data. */
        var isStatic: Boolean,
        /** No chunk holds a single instance of it. */
        var isEmpty: Boolean,
    )

    /** The chunk index of one store, filled as its chunks are written. */
    class Manifest internal constructor(private val storeId: ByteArray) {
        private val rows = ArrayList<Row>()
        private val descriptors = HashMap<Descriptor, Descriptor>()
        private val dataColumns = LinkedHashMap<String, DataColumn>()

        val chunkCount: Int get() = rows.size

        /**
         * Records [chunk], written with id [chunkId] as an `ArrowMsg` whose protobuf payload — the
         * message without its 16-byte header — is [byteSize] bytes at [byteOffset] in the file, and
         * whose Arrow IPC stream is [uncompressedSize] bytes.
         */
        fun add(chunk: RerunChunk, chunkId: RerunTuid, byteOffset: Long, byteSize: Long, uncompressedSize: Long) {
            val isStatic = chunk.times == null
            val components = chunk.components.map { column ->
                val described = Descriptor(column.archetypeName, column.fieldName, column.componentTypeName)
                val descriptor = descriptors.getOrPut(described) { described }
                val isEmpty = column.array.children.single().length == 0
                val known = dataColumns.getOrPut("${columnPrefix(chunk.entityPath)}:${column.fieldName}") {
                    DataColumn(chunk.entityPath, descriptor, column.item, isStatic, isEmpty)
                }
                known.isStatic = known.isStatic || isStatic
                known.isEmpty = known.isEmpty && isEmpty
                descriptor
            }
            rows += Row(
                entityPath = chunk.entityPath,
                chunkId = chunkId,
                timeline = if (isStatic) null else chunk.timeline,
                rowCount = chunk.rowCount,
                byteOffset = byteOffset,
                byteSize = byteSize,
                uncompressedSize = uncompressedSize,
                timeStart = chunk.times?.minOrNull() ?: 0L,
                timeEnd = chunk.times?.maxOrNull() ?: 0L,
                components = components,
            )
        }

        /** The `RrdManifest` message. */
        fun encoded(): ByteArray {
            val fields = sorbetFields()
            val data = dataframe()
            val manifest = RerunProtobufWriter()
            manifest.bytes(1, storeId)
            manifest.message(2) { bytes(1, RerunArrowSchemaMessage.stream(fields)) }
            manifest.bytes(3, RerunArrowSchemaMessage.sha256(fields))
            manifest.message(4) {
                uint64(1, ENCODER_VERSION_V0)
                bytes(2, data)
                uint64(3, COMPRESSION_NONE)
                uint64(4, data.size.toLong())
            }
            return manifest.bytes
        }

        /** The timelines the store's chunks are indexed on, by name. */
        private fun timelines(): List<RerunTimeline> =
            rows.mapNotNull { it.timeline }.distinct().sortedWith(compareBy(RerunUtf8Order) { it.timelineName })

        /**
         * The store's schema, as Rerun's `SchemaBuilder` derives it from the chunks: the row id, one
         * index column per timeline, and one data column per component of each entity — named after
         * both, where a chunk's own column only names the component. Sorted by name.
         */
        internal fun sorbetFields(): List<RerunArrowField> {
            val fields = arrayListOf(
                RerunArrowField(
                    name = "rerun.controls.RowId",
                    type = RerunArrowType.FixedSizeBinary(RerunTuid.SIZE),
                    nullable = false,
                    metadata = mapOf(
                        "ARROW:extension:metadata" to """{"namespace":"row"}""",
                        "ARROW:extension:name" to "rerun.datatypes.TUID",
                        "rerun:kind" to "control",
                    ),
                ),
            )
            for (timeline in timelines()) {
                fields += RerunArrowField(
                    name = timeline.timelineName,
                    type = timeline.type,
                    nullable = true,
                    metadata = mapOf("rerun:index_name" to timeline.timelineName, "rerun:kind" to "index"),
                )
            }
            for ((name, column) in dataColumns) {
                val metadata = hashMapOf(
                    "rerun:archetype" to column.descriptor.archetype,
                    "rerun:component" to column.descriptor.component,
                    "rerun:component_type" to column.descriptor.componentType,
                    "rerun:entity_path" to column.entityPath,
                    "rerun:kind" to "data",
                )
                if (column.isStatic) metadata["rerun:is_static"] = "true"
                if (column.isEmpty) metadata["rerun:is_semantically_empty"] = "true"
                fields += RerunArrowField(name, RerunArrowType.ListOf(column.item), nullable = true, metadata)
            }
            return fields.sortedWith(compareBy(RerunUtf8Order) { it.name })
        }

        /**
         * The manifest's record batch, an Arrow IPC stream with a row per chunk, as Rerun's
         * `RrdManifestBuilder` lays it out:
         *
         * - `chunk_entity_path` (utf8), `chunk_id` (16 bytes), `chunk_is_static` (bool), then
         *   `chunk_num_rows`, `chunk_byte_offset`, `chunk_byte_size` and
         *   `chunk_byte_size_uncompressed` (uint64): the offset and the size are those of the
         *   `ArrowMsg` payload, without its message header; the last one is its IPC stream's size.
         * - `<Component>:has_static_data` (bool) for every component of the store: whether the
         *   chunk logs it as static data.
         * - `<timeline>:start` and `<timeline>:end` (the timeline's type, null off that timeline):
         *   the chunk's time range.
         * - `<timeline>:<Component>:start`, `:end` and `:num_rows` (uint64, `0` elsewhere) for every
         *   component logged on that timeline: the range and the row count of that component in
         *   the chunk. The writer's lists are never null, so they are the chunk's own.
         *
         * Components go in [Descriptor] order, timelines by name.
         */
        internal fun dataframe(): ByteArray {
            val fields = ArrayList<RerunArrowField>()
            val arrays = ArrayList<RerunArrowArray>()
            fun column(name: String, type: RerunArrowType, array: RerunArrowArray) {
                fields += RerunArrowField(name, type, nullable = false)
                arrays += array
            }
            fun counts(name: String, value: (Row) -> Long) =
                column(name, RerunArrowType.UInt64, RerunArrowArray.int64s(LongArray(rows.size) { value(rows[it]) }))

            val ids = ByteArrayOutputStream(rows.size * RerunTuid.SIZE)
            rows.forEach { ids.write(it.chunkId.bigEndianBytes()) }
            column("chunk_entity_path", RerunArrowType.Utf8, RerunArrowArray.utf8(rows.map { it.entityPath }))
            column(
                "chunk_id",
                RerunArrowType.FixedSizeBinary(RerunTuid.SIZE),
                RerunArrowArray.fixedSizeBinary(ids.toByteArray(), RerunTuid.SIZE),
            )
            column("chunk_is_static", RerunArrowType.Bool, RerunArrowArray.bools(rows.map { it.timeline == null }))
            counts("chunk_num_rows") { it.rowCount.toLong() }
            counts("chunk_byte_offset") { it.byteOffset }
            counts("chunk_byte_size") { it.byteSize }
            counts("chunk_byte_size_uncompressed") { it.uncompressedSize }

            for (descriptor in descriptors.keys.sorted()) {
                fields += RerunArrowField(
                    "${descriptor.component}:has_static_data",
                    RerunArrowType.Bool,
                    nullable = false,
                    descriptor.metadata(INDEX_STATIC),
                )
                arrays += RerunArrowArray.bools(rows.map { it.timeline == null && descriptor in it.components })
            }
            val timelines = timelines()
            for (timeline in timelines) {
                val metadata = mapOf("rerun:index" to timeline.timelineName)
                fields += RerunArrowField("${timeline.timelineName}:start", timeline.type, nullable = true, metadata)
                arrays += RerunArrowArray.nullableInt64s(rows.map { it.start(timeline) })
                fields += RerunArrowField("${timeline.timelineName}:end", timeline.type, nullable = true, metadata)
                arrays += RerunArrowArray.nullableInt64s(rows.map { it.end(timeline) })
            }
            for (timeline in timelines) temporalColumns(timeline, fields, arrays)
            return RerunArrowIpc.stream(fields, arrays, rows.size, metadata = emptyMap())
        }

        /** The three columns of each component logged on [timeline]. */
        private fun temporalColumns(
            timeline: RerunTimeline,
            fields: MutableList<RerunArrowField>,
            arrays: MutableList<RerunArrowArray>,
        ) {
            val logged = rows.filter { it.timeline == timeline }.flatMap { it.components }.distinct().sorted()
            for (descriptor in logged) {
                val name = "${timeline.timelineName}:${descriptor.component}"
                val metadata = descriptor.metadata(timeline.timelineName)
                fun holds(row: Row) = row.timeline == timeline && descriptor in row.components
                fields += RerunArrowField("$name:start", timeline.type, nullable = true, metadata)
                arrays += RerunArrowArray.nullableInt64s(rows.map { if (holds(it)) it.start(timeline) else null })
                fields += RerunArrowField("$name:end", timeline.type, nullable = true, metadata)
                arrays += RerunArrowArray.nullableInt64s(rows.map { if (holds(it)) it.end(timeline) else null })
                fields += RerunArrowField("$name:num_rows", RerunArrowType.UInt64, nullable = true, metadata)
                arrays += RerunArrowArray.int64s(
                    LongArray(rows.size) { if (holds(rows[it])) rows[it].rowCount.toLong() else 0L },
                )
            }
        }

        /**
         * What a data column's name starts with: the entity path, or `property` for the recording's
         * properties (`/__properties`, where `RecordingInfo` lives), which Rerun shows that way.
         */
        private fun columnPrefix(entityPath: String): String = when {
            entityPath == PROPERTIES -> "property"
            entityPath.startsWith("$PROPERTIES/") -> "property:" + entityPath.removePrefix("$PROPERTIES/")
            else -> entityPath
        }
    }

    internal companion object {
        /** The `StreamFooter`'s size: one 20-byte entry, `RRF2`, `FOOT` and the entry count. */
        const val STREAM_FOOTER_SIZE = 32

        /** The seed of the footer's xxHash32 checksum: "RERUN" read as a base-26 number. */
        const val CRC_SEED = 7850921

        private const val KIND_END = 0L
        private const val ENCODER_VERSION_V0 = 1L
        private const val COMPRESSION_NONE = 1L
        private const val INDEX_STATIC = "rerun:static"
        private const val PROPERTIES = "/__properties"
        private val FOURCC = "RRF2".encodeToByteArray()
        private val IDENTIFIER = "FOOT".encodeToByteArray()
    }
}

/** xxHash32 (https://xxhash.com), the checksum of a `.rrd` footer. */
internal object RerunXxHash32 {
    private const val PRIME_1 = 0x9E3779B1.toInt()
    private const val PRIME_2 = 0x85EBCA77.toInt()
    private const val PRIME_3 = 0xC2B2AE3D.toInt()
    private const val PRIME_4 = 0x27D4EB2F
    private const val PRIME_5 = 0x165667B1
    private const val STRIPE = 16

    fun hash(data: ByteArray, seed: Int): Int {
        var position = 0
        var hash: Int
        if (data.size >= STRIPE) {
            var a = seed + PRIME_1 + PRIME_2
            var b = seed + PRIME_2
            var c = seed
            var d = seed - PRIME_1
            while (position <= data.size - STRIPE) {
                a = round(a, int(data, position))
                b = round(b, int(data, position + 4))
                c = round(c, int(data, position + 8))
                d = round(d, int(data, position + 12))
                position += STRIPE
            }
            hash = a.rotateLeft(1) + b.rotateLeft(7) + c.rotateLeft(12) + d.rotateLeft(18)
        } else {
            hash = seed + PRIME_5
        }
        hash += data.size
        while (position <= data.size - Int.SIZE_BYTES) {
            hash = (hash + int(data, position) * PRIME_3).rotateLeft(17) * PRIME_4
            position += Int.SIZE_BYTES
        }
        while (position < data.size) {
            hash = (hash + (data[position].toInt() and 0xFF) * PRIME_5).rotateLeft(11) * PRIME_1
            position++
        }
        hash = (hash xor (hash ushr 15)) * PRIME_2
        hash = (hash xor (hash ushr 13)) * PRIME_3
        return hash xor (hash ushr 16)
    }

    private fun round(accumulator: Int, input: Int) = (accumulator + input * PRIME_2).rotateLeft(13) * PRIME_1

    private fun int(data: ByteArray, at: Int): Int =
        (data[at].toInt() and 0xFF) or
            ((data[at + 1].toInt() and 0xFF) shl 8) or
            ((data[at + 2].toInt() and 0xFF) shl 16) or
            ((data[at + 3].toInt() and 0xFF) shl 24)
}
